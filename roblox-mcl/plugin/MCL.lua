-- MCL (Model Context Link) Studio plugin.
-- Single-file plugin: long-polls the local MCL bridge server, executes commands,
-- and renders a chat + activity dock. See PROTOCOL.md for the normative spec.

if not plugin then
	return
end

local RunService = game:GetService("RunService")
if not RunService:IsStudio() then
	return
end
if RunService:IsRunning() then
	-- Edit mode only; do nothing in play solo / run mode.
	return
end

local HttpService = game:GetService("HttpService")
local ChangeHistoryService = game:GetService("ChangeHistoryService")
local InsertService = game:GetService("InsertService")

local VERSION = "1.0.0"
local DEFAULT_PORT = 28650
local SESSION = HttpService:GenerateGUID(false)

local MAX_ENCODE_DEPTH = 8
local MAX_ENCODE_NODES = 10000
local MAX_DECODE_DEPTH = 32
local MAX_UI_ITEMS = 300
local TREE_NODE_CAP = 2000

local State = {
	running = true,
	paused = false,
	-- "connecting" | "connected" | "disconnected"
	status = "connecting",
	port = DEFAULT_PORT,
	hintedHttpDisabled = false,
	hintedPermission = false,
}

do
	local ok, saved = pcall(function()
		return plugin:GetSetting("mcl_port")
	end)
	if ok then
		local n = tonumber(saved)
		if n and n >= 1 and n <= 65535 then
			State.port = math.floor(n)
		end
	end
end

-- Module tables (declared up front so later sections can reference each other
-- at call time regardless of definition order).
local Paths = {}
local Codec = {}
local UI = {}
local Executor = {}
local Net = {}

--------------------------------------------------------------------------------
-- Paths: canonical instance paths (spec section 4)
--------------------------------------------------------------------------------

-- Splits "game.Workspace[\"My Part\"].Child" into raw name segments.
local function splitPath(path)
	local segments = {}
	local i = 1
	local n = #path
	local current = ""
	while i <= n do
		local c = string.sub(path, i, i)
		if c == "." then
			if current ~= "" then
				table.insert(segments, current)
				current = ""
			end
			i = i + 1
		elseif c == "[" then
			if current ~= "" then
				table.insert(segments, current)
				current = ""
			end
			local quote = string.sub(path, i + 1, i + 1)
			if quote ~= "\"" and quote ~= "'" then
				return nil, string.format("Malformed path (expected quote after '[' at position %d): %s", i, path)
			end
			i = i + 2
			local buf = {}
			local closed = false
			while i <= n do
				local ch = string.sub(path, i, i)
				if ch == "\\" then
					table.insert(buf, string.sub(path, i + 1, i + 1))
					i = i + 2
				elseif ch == quote then
					closed = true
					i = i + 1
					break
				else
					table.insert(buf, ch)
					i = i + 1
				end
			end
			if not closed or string.sub(path, i, i) ~= "]" then
				return nil, "Malformed path (unclosed bracket segment): " .. path
			end
			i = i + 1
			table.insert(segments, table.concat(buf))
		else
			current = current .. c
			i = i + 1
		end
	end
	if current ~= "" then
		table.insert(segments, current)
	end
	return segments
end

-- Resolves a path string to an Instance. Returns instance, or nil + error text.
function Paths.resolve(path)
	if typeof(path) ~= "string" or path == "" then
		return nil, "Invalid path: expected a non-empty string"
	end
	local segments, splitErr = splitPath(path)
	if not segments then
		return nil, splitErr
	end
	local startIndex = 1
	if segments[1] == "game" or segments[1] == "Game" then
		startIndex = 2
	end
	local current = game
	for i = startIndex, #segments do
		local seg = segments[i]
		local nextInstance = nil
		if i == startIndex and current == game then
			-- First segment under game: FindService avoids creating absent
			-- services as a side effect of read-only tools. It errors for names
			-- that are not service classes, and returns nil for a service class
			-- that exists but has not been instantiated yet -- only in that case
			-- is GetService (which creates it) the right fallback.
			local okFind, service = pcall(game.FindService, game, seg)
			if okFind and typeof(service) == "Instance" then
				nextInstance = service
			elseif okFind and service == nil then
				local okGet, created = pcall(game.GetService, game, seg)
				if okGet and typeof(created) == "Instance" then
					nextInstance = created
				end
			end
		end
		if not nextInstance then
			nextInstance = current:FindFirstChild(seg)
		end
		if not nextInstance then
			return nil, string.format("No instance at path: %s (failed at segment '%s')", path, seg)
		end
		current = nextInstance
	end
	return current
end

local function quoteSegment(name)
	if string.match(name, "^[A-Za-z_][A-Za-z0-9_]*$") then
		return name
	end
	local escaped = string.gsub(name, "\\", "\\\\")
	escaped = string.gsub(escaped, "\"", "\\\"")
	return string.format("[\"%s\"]", escaped)
end

-- Canonical path for an instance, or nil when not a descendant of game.
function Paths.toPath(instance)
	if instance == game then
		return "game"
	end
	local segments = {}
	local cursor = instance
	while cursor ~= nil and cursor ~= game do
		table.insert(segments, 1, cursor.Name)
		cursor = cursor.Parent
	end
	if cursor ~= game then
		return nil
	end
	local out = "game"
	for _, name in ipairs(segments) do
		local quoted = quoteSegment(name)
		if string.sub(quoted, 1, 1) == "[" then
			out = out .. quoted
		else
			out = out .. "." .. quoted
		end
	end
	return out
end

--------------------------------------------------------------------------------
-- Codec: datatype encoding/decoding (spec section 2)
--------------------------------------------------------------------------------

local function encodeNumber(n)
	if n ~= n then
		return "nan"
	end
	if n == math.huge then
		return "inf"
	end
	if n == -math.huge then
		return "-inf"
	end
	return n
end

local function encodeInstance(instance)
	local path = Paths.toPath(instance)
	if path then
		return { ["$type"] = "Instance", value = path }
	end
	local okName, name = pcall(function()
		return instance.Name
	end)
	local okClass, className = pcall(function()
		return instance.ClassName
	end)
	return {
		["$type"] = "Instance",
		value = "<detached>",
		name = okName and name or "?",
		className = okClass and className or "?",
	}
end

local DATATYPE_ENCODERS = {
	Vector3 = function(v)
		return { ["$type"] = "Vector3", value = { encodeNumber(v.X), encodeNumber(v.Y), encodeNumber(v.Z) } }
	end,
	Vector2 = function(v)
		return { ["$type"] = "Vector2", value = { encodeNumber(v.X), encodeNumber(v.Y) } }
	end,
	CFrame = function(v)
		local components = { v:GetComponents() }
		for i = 1, #components do
			components[i] = encodeNumber(components[i])
		end
		return { ["$type"] = "CFrame", value = components }
	end,
	Color3 = function(v)
		return { ["$type"] = "Color3", value = { encodeNumber(v.R), encodeNumber(v.G), encodeNumber(v.B) } }
	end,
	BrickColor = function(v)
		return { ["$type"] = "BrickColor", value = v.Name }
	end,
	UDim = function(v)
		return { ["$type"] = "UDim", value = { encodeNumber(v.Scale), encodeNumber(v.Offset) } }
	end,
	UDim2 = function(v)
		return {
			["$type"] = "UDim2",
			value = {
				{ encodeNumber(v.X.Scale), encodeNumber(v.X.Offset) },
				{ encodeNumber(v.Y.Scale), encodeNumber(v.Y.Offset) },
			},
		}
	end,
	EnumItem = function(v)
		return { ["$type"] = "Enum", value = tostring(v.EnumType) .. "." .. v.Name }
	end,
	NumberRange = function(v)
		return { ["$type"] = "NumberRange", value = { encodeNumber(v.Min), encodeNumber(v.Max) } }
	end,
	NumberSequence = function(v)
		local keypoints = {}
		for _, kp in ipairs(v.Keypoints) do
			table.insert(keypoints, { encodeNumber(kp.Time), encodeNumber(kp.Value), encodeNumber(kp.Envelope) })
		end
		return { ["$type"] = "NumberSequence", value = keypoints }
	end,
	ColorSequence = function(v)
		local keypoints = {}
		for _, kp in ipairs(v.Keypoints) do
			table.insert(keypoints, {
				encodeNumber(kp.Time),
				{ encodeNumber(kp.Value.R), encodeNumber(kp.Value.G), encodeNumber(kp.Value.B) },
			})
		end
		return { ["$type"] = "ColorSequence", value = keypoints }
	end,
	Rect = function(v)
		return {
			["$type"] = "Rect",
			value = { encodeNumber(v.Min.X), encodeNumber(v.Min.Y), encodeNumber(v.Max.X), encodeNumber(v.Max.Y) },
		}
	end,
	Font = function(v)
		return {
			["$type"] = "Font",
			value = { family = v.Family, weight = v.Weight.Name, style = v.Style.Name },
		}
	end,
}

local encodeValue -- forward declaration

local function encodeTable(t, depth, state)
	-- Array when keys are exactly 1..n.
	local isArray = true
	local count = 0
	local maxKey = 0
	for key in pairs(t) do
		count = count + 1
		if typeof(key) == "number" and key >= 1 and key % 1 == 0 then
			if key > maxKey then
				maxKey = key
			end
		else
			isArray = false
		end
	end
	if isArray and count ~= maxKey then
		isArray = false
	end
	if isArray then
		local out = {}
		for i = 1, maxKey do
			out[i] = encodeValue(t[i], depth + 1, state)
			if out[i] == nil then
				out[i] = "<nil>"
			end
		end
		return out
	end
	local out = {}
	for key, value in pairs(t) do
		if typeof(key) == "string" then
			out[key] = encodeValue(value, depth + 1, state)
		end
	end
	for key, value in pairs(t) do
		if typeof(key) ~= "string" then
			local keyText = tostring(key)
			if out[keyText] ~= nil then
				keyText = "$k:" .. keyText
			end
			out[keyText] = encodeValue(value, depth + 1, state)
		end
	end
	return out
end

encodeValue = function(value, depth, state)
	state.nodes = state.nodes + 1
	if state.nodes > MAX_ENCODE_NODES then
		return "<truncated>"
	end
	local valueType = typeof(value)
	if valueType == "nil" then
		return nil
	elseif valueType == "boolean" or valueType == "string" then
		return value
	elseif valueType == "number" then
		return encodeNumber(value)
	elseif valueType == "Instance" then
		return encodeInstance(value)
	elseif valueType == "table" then
		if state.seen[value] then
			return "<cycle>"
		end
		if depth >= MAX_ENCODE_DEPTH then
			return "<truncated>"
		end
		state.seen[value] = true
		local encoded = encodeTable(value, depth, state)
		state.seen[value] = nil
		return encoded
	end
	local encoder = DATATYPE_ENCODERS[valueType]
	if encoder then
		local ok, encoded = pcall(encoder, value)
		if ok then
			return encoded
		end
	end
	return "<" .. valueType .. ">"
end

-- Encodes any Luau value into the JSON-safe tagged representation.
function Codec.encode(value)
	local state = { nodes = 0, seen = {} }
	return encodeValue(value, 0, state)
end

local function resolveEnumString(text, currentEnumItem)
	-- Accepts "Enum.Material.Neon", "Material.Neon", or bare "Neon" (bare form
	-- requires currentEnumItem to supply the enum type).
	local enumName, itemName = string.match(text, "([%w_]+)%.([%w_]+)$")
	if enumName and enumName ~= "Enum" then
		local okType, enumType = pcall(function()
			return Enum[enumName]
		end)
		if not okType then
			error("Unknown enum type: " .. enumName, 0)
		end
		local okItem, item = pcall(function()
			return enumType[itemName]
		end)
		if not okItem then
			error("Unknown enum item: " .. text, 0)
		end
		return item
	end
	local bare = string.match(text, "^[%w_]+$")
	if bare and currentEnumItem then
		local okItem, item = pcall(function()
			return currentEnumItem.EnumType[bare]
		end)
		if okItem then
			return item
		end
	end
	error("Cannot resolve enum value: " .. tostring(text), 0)
end

local decodeValue -- forward declaration

local function decodeTagged(tag, v)
	if tag == "Vector3" then
		return Vector3.new(v[1], v[2], v[3])
	elseif tag == "Vector2" then
		return Vector2.new(v[1], v[2])
	elseif tag == "CFrame" then
		return CFrame.new(table.unpack(v))
	elseif tag == "Color3" then
		return Color3.new(v[1], v[2], v[3])
	elseif tag == "BrickColor" then
		return BrickColor.new(v)
	elseif tag == "UDim" then
		return UDim.new(v[1], v[2])
	elseif tag == "UDim2" then
		return UDim2.new(v[1][1], v[1][2], v[2][1], v[2][2])
	elseif tag == "Enum" then
		return resolveEnumString(v, nil)
	elseif tag == "Instance" then
		local instance, err = Paths.resolve(v)
		if not instance then
			error(err, 0)
		end
		return instance
	elseif tag == "NumberRange" then
		return NumberRange.new(v[1], v[2])
	elseif tag == "NumberSequence" then
		local keypoints = {}
		for _, kp in ipairs(v) do
			table.insert(keypoints, NumberSequenceKeypoint.new(kp[1], kp[2], kp[3] or 0))
		end
		return NumberSequence.new(keypoints)
	elseif tag == "ColorSequence" then
		local keypoints = {}
		for _, kp in ipairs(v) do
			table.insert(keypoints, ColorSequenceKeypoint.new(kp[1], Color3.new(kp[2][1], kp[2][2], kp[2][3])))
		end
		return ColorSequence.new(keypoints)
	elseif tag == "Rect" then
		return Rect.new(v[1], v[2], v[3], v[4])
	elseif tag == "Font" then
		local weight = Enum.FontWeight.Regular
		if typeof(v.weight) == "string" then
			weight = Enum.FontWeight[v.weight]
		end
		local style = Enum.FontStyle.Normal
		if typeof(v.style) == "string" then
			style = Enum.FontStyle[v.style]
		end
		return Font.new(v.family, weight, style)
	end
	error("Unknown $type: " .. tostring(tag), 0)
end

decodeValue = function(value, depth)
	if depth > MAX_DECODE_DEPTH then
		error("decode depth limit exceeded", 0)
	end
	if typeof(value) ~= "table" then
		return value
	end
	local tag = value["$type"]
	if typeof(tag) == "string" then
		return decodeTagged(tag, value.value)
	end
	local out = {}
	for key, entry in pairs(value) do
		out[key] = decodeValue(entry, depth + 1)
	end
	return out
end

-- Decodes a JSON-shaped value (tagged objects anywhere) into Luau values.
-- Errors (with a readable message) on malformed tagged values or when nesting
-- exceeds MAX_DECODE_DEPTH (server-controlled input must not blow the stack).
function Codec.decode(value)
	return decodeValue(value, 0)
end

-- Coerces an untagged shorthand value to targetType (the typeof() of the
-- property's current value). Returns the coerced value or nil when no rule
-- applies; may error for invalid inputs (callers pcall).
local function coerceValue(value, targetType, currentValue)
	local valueType = typeof(value)
	if targetType == "Vector3" and valueType == "table" then
		return Vector3.new(value[1], value[2], value[3])
	elseif targetType == "Vector2" and valueType == "table" then
		return Vector2.new(value[1], value[2])
	elseif targetType == "UDim" and valueType == "table" then
		return UDim.new(value[1], value[2])
	elseif targetType == "UDim2" and valueType == "table" then
		if typeof(value[1]) == "table" then
			return UDim2.new(value[1][1], value[1][2], value[2][1], value[2][2])
		end
		return UDim2.new(value[1], value[2], value[3], value[4])
	elseif targetType == "Color3" then
		if valueType == "string" then
			return Color3.fromHex(value)
		elseif valueType == "table" then
			return Color3.new(value[1], value[2], value[3])
		end
	elseif targetType == "EnumItem" and valueType == "string" then
		return resolveEnumString(value, currentValue)
	elseif targetType == "CFrame" and valueType == "table" then
		if #value == 12 then
			return CFrame.new(table.unpack(value))
		elseif #value == 3 then
			return CFrame.new(value[1], value[2], value[3])
		end
	elseif targetType == "BrickColor" and valueType == "string" then
		return BrickColor.new(value)
	elseif targetType == "number" and valueType == "string" then
		if value == "nan" then
			return 0 / 0
		elseif value == "inf" then
			return math.huge
		elseif value == "-inf" then
			return -math.huge
		end
		return tonumber(value)
	elseif targetType == "Instance" and valueType == "string" then
		local instance, err = Paths.resolve(value)
		if not instance then
			error(err, 0)
		end
		return instance
	end
	return nil
end

-- Sets one property from a raw (JSON-shaped) value, applying tagged decoding
-- then shorthand coercion per spec section 2. Returns true, or false + error.
function Codec.applyProperty(instance, name, rawValue)
	if name == "Parent" and typeof(rawValue) == "string" then
		local target, resolveErr = Paths.resolve(rawValue)
		if not target then
			return false, resolveErr
		end
		local okParent, parentErr = pcall(function()
			instance.Parent = target
		end)
		if okParent then
			return true
		end
		return false, tostring(parentErr)
	end
	local okDecode, decoded = pcall(Codec.decode, rawValue)
	if not okDecode then
		return false, tostring(decoded)
	end
	local okSet, setErr = pcall(function()
		instance[name] = decoded
	end)
	if okSet then
		return true
	end
	-- Direct assignment failed: coerce using the current value's type.
	local okCurrent, currentValue = pcall(function()
		return instance[name]
	end)
	if not okCurrent then
		return false, tostring(setErr)
	end
	local okCoerce, coerced = pcall(coerceValue, decoded, typeof(currentValue), currentValue)
	if not okCoerce or coerced == nil then
		return false, tostring(setErr)
	end
	local okSet2, setErr2 = pcall(function()
		instance[name] = coerced
	end)
	if okSet2 then
		return true
	end
	return false, tostring(setErr2)
end

--------------------------------------------------------------------------------
-- UI: dock widget, chat list, activity cards (spec sections 3 and 7)
--------------------------------------------------------------------------------

local STATUS_GREEN = Color3.fromRGB(76, 194, 98)
local STATUS_YELLOW = Color3.fromRGB(235, 195, 52)
local STATUS_RED = Color3.fromRGB(233, 75, 60)
local ERROR_RED = Color3.fromRGB(237, 96, 84)

local HEADER_HEIGHT = 34
local SETTINGS_HEIGHT = 30
local INPUT_HEIGHT = 42

local function themeColor(styleGuideName, fallback)
	local ok, color = pcall(function()
		return settings().Studio.Theme:GetColor(Enum.StudioStyleGuideColor[styleGuideName])
	end)
	if ok and typeof(color) == "Color3" then
		return color
	end
	return fallback
end

local function buildPalette()
	return {
		bg = themeColor("MainBackground", Color3.fromRGB(46, 46, 46)),
		headerBg = themeColor("Titlebar", Color3.fromRGB(53, 53, 53)),
		inputBg = themeColor("InputFieldBackground", Color3.fromRGB(37, 37, 37)),
		text = themeColor("MainText", Color3.fromRGB(204, 204, 204)),
		subText = themeColor("SubText", Color3.fromRGB(170, 170, 170)),
		dimText = themeColor("DimmedText", Color3.fromRGB(140, 140, 140)),
		border = themeColor("Border", Color3.fromRGB(34, 34, 34)),
		button = themeColor("Button", Color3.fromRGB(60, 60, 60)),
		buttonText = themeColor("ButtonText", Color3.fromRGB(204, 204, 204)),
		cardBg = themeColor("CategoryItem", Color3.fromRGB(56, 56, 56)),
		userBubble = themeColor("DialogMainButton", Color3.fromRGB(0, 116, 189)),
		userBubbleText = themeColor("DialogButtonText", Color3.fromRGB(255, 255, 255)),
		scrollBar = themeColor("ScrollBar", Color3.fromRGB(70, 70, 70)),
	}
end

local palette = buildPalette()

local function make(className, props)
	local instance = Instance.new(className)
	for key, value in pairs(props) do
		if key ~= "Parent" then
			instance[key] = value
		end
	end
	if props.Parent then
		instance.Parent = props.Parent
	end
	return instance
end

local dockInfo = DockWidgetPluginGuiInfo.new(
	Enum.InitialDockState.Right,
	true, -- initially enabled on first install
	false, -- do not override the user's saved enabled state
	360,
	520,
	280,
	320
)
local dock = plugin:CreateDockWidgetPluginGui("MCLDock", dockInfo)
dock.Title = "MCL"
dock.Name = "MCLDock"
dock.ZIndexBehavior = Enum.ZIndexBehavior.Sibling

local root = make("Frame", {
	Name = "Root",
	Size = UDim2.new(1, 0, 1, 0),
	BackgroundColor3 = palette.bg,
	BorderSizePixel = 0,
	Parent = dock,
})

local header = make("Frame", {
	Name = "Header",
	Size = UDim2.new(1, 0, 0, HEADER_HEIGHT),
	BackgroundColor3 = palette.headerBg,
	BorderSizePixel = 0,
	Parent = root,
})

local statusDot = make("Frame", {
	Name = "StatusDot",
	Size = UDim2.new(0, 10, 0, 10),
	Position = UDim2.new(0, 10, 0.5, -5),
	BackgroundColor3 = STATUS_YELLOW,
	BorderSizePixel = 0,
	Parent = header,
})
make("UICorner", { CornerRadius = UDim.new(1, 0), Parent = statusDot })

-- Status word next to the dot: docs and server error messages tell users to
-- look for the word "Connected", so it must exist as text (spec section 7).
local statusWordLabel = make("TextLabel", {
	Name = "StatusWord",
	Position = UDim2.new(0, 26, 0, 0),
	Size = UDim2.new(0, 84, 1, 0),
	BackgroundTransparency = 1,
	Font = Enum.Font.SourceSans,
	TextSize = 13,
	TextColor3 = palette.subText,
	TextXAlignment = Enum.TextXAlignment.Left,
	Text = "Connecting",
	Parent = header,
})

local placeLabel = make("TextLabel", {
	Name = "PlaceName",
	Position = UDim2.new(0, 112, 0, 0),
	Size = UDim2.new(1, -252, 1, 0),
	BackgroundTransparency = 1,
	Font = Enum.Font.SourceSansSemibold,
	TextSize = 15,
	TextColor3 = palette.text,
	TextXAlignment = Enum.TextXAlignment.Left,
	TextTruncate = Enum.TextTruncate.AtEnd,
	Text = game.Name,
	Parent = header,
})

local pauseButton = make("TextButton", {
	Name = "PauseButton",
	Size = UDim2.new(0, 62, 0, 24),
	Position = UDim2.new(1, -134, 0.5, -12),
	BackgroundColor3 = palette.button,
	BorderSizePixel = 0,
	Font = Enum.Font.SourceSans,
	TextSize = 14,
	TextColor3 = palette.buttonText,
	Text = "Pause",
	Parent = header,
})
make("UICorner", { CornerRadius = UDim.new(0, 4), Parent = pauseButton })

local gearButton = make("TextButton", {
	Name = "GearButton",
	Size = UDim2.new(0, 62, 0, 24),
	Position = UDim2.new(1, -68, 0.5, -12),
	BackgroundColor3 = palette.button,
	BorderSizePixel = 0,
	Font = Enum.Font.SourceSans,
	TextSize = 14,
	TextColor3 = palette.buttonText,
	Text = "Port...",
	Parent = header,
})
make("UICorner", { CornerRadius = UDim.new(0, 4), Parent = gearButton })

local settingsRow = make("Frame", {
	Name = "SettingsRow",
	Position = UDim2.new(0, 0, 0, HEADER_HEIGHT),
	Size = UDim2.new(1, 0, 0, SETTINGS_HEIGHT),
	BackgroundColor3 = palette.headerBg,
	BorderSizePixel = 0,
	Visible = false,
	Parent = root,
})

local portLabel = make("TextLabel", {
	Name = "PortLabel",
	Position = UDim2.new(0, 10, 0, 0),
	Size = UDim2.new(0, 34, 1, 0),
	BackgroundTransparency = 1,
	Font = Enum.Font.SourceSans,
	TextSize = 14,
	TextColor3 = palette.subText,
	TextXAlignment = Enum.TextXAlignment.Left,
	Text = "Port",
	Parent = settingsRow,
})

local portBox = make("TextBox", {
	Name = "PortBox",
	Position = UDim2.new(0, 48, 0.5, -11),
	Size = UDim2.new(0, 76, 0, 22),
	BackgroundColor3 = palette.inputBg,
	BorderSizePixel = 0,
	Font = Enum.Font.SourceSans,
	TextSize = 14,
	TextColor3 = palette.text,
	ClearTextOnFocus = false,
	Text = tostring(State.port),
	Parent = settingsRow,
})
make("UICorner", { CornerRadius = UDim.new(0, 4), Parent = portBox })

local portHint = make("TextLabel", {
	Name = "PortHint",
	Position = UDim2.new(0, 132, 0, 0),
	Size = UDim2.new(1, -140, 1, 0),
	BackgroundTransparency = 1,
	Font = Enum.Font.SourceSans,
	TextSize = 13,
	TextColor3 = palette.dimText,
	TextXAlignment = Enum.TextXAlignment.Left,
	TextTruncate = Enum.TextTruncate.AtEnd,
	Text = "Applies on the next poll",
	Parent = settingsRow,
})

local scroller = make("ScrollingFrame", {
	Name = "Messages",
	Position = UDim2.new(0, 0, 0, HEADER_HEIGHT),
	Size = UDim2.new(1, 0, 1, -(HEADER_HEIGHT + INPUT_HEIGHT)),
	BackgroundTransparency = 1,
	BorderSizePixel = 0,
	ScrollBarThickness = 6,
	ScrollBarImageColor3 = palette.scrollBar,
	ScrollingDirection = Enum.ScrollingDirection.Y,
	CanvasSize = UDim2.new(0, 0, 0, 0),
	Parent = root,
})

local listLayout = make("UIListLayout", {
	Padding = UDim.new(0, 6),
	SortOrder = Enum.SortOrder.LayoutOrder,
	Parent = scroller,
})
make("UIPadding", {
	PaddingTop = UDim.new(0, 6),
	PaddingBottom = UDim.new(0, 6),
	PaddingLeft = UDim.new(0, 8),
	PaddingRight = UDim.new(0, 8),
	Parent = scroller,
})

local inputRow = make("Frame", {
	Name = "InputRow",
	Position = UDim2.new(0, 0, 1, -INPUT_HEIGHT),
	Size = UDim2.new(1, 0, 0, INPUT_HEIGHT),
	BackgroundColor3 = palette.headerBg,
	BorderSizePixel = 0,
	Parent = root,
})

local inputBox = make("TextBox", {
	Name = "InputBox",
	Position = UDim2.new(0, 8, 0.5, -13),
	Size = UDim2.new(1, -84, 0, 26),
	BackgroundColor3 = palette.inputBg,
	BorderSizePixel = 0,
	Font = Enum.Font.SourceSans,
	TextSize = 14,
	TextColor3 = palette.text,
	PlaceholderText = "Message the assistant...",
	PlaceholderColor3 = palette.dimText,
	TextXAlignment = Enum.TextXAlignment.Left,
	ClearTextOnFocus = false,
	Text = "",
	Parent = inputRow,
})
make("UICorner", { CornerRadius = UDim.new(0, 4), Parent = inputBox })
make("UIPadding", {
	PaddingLeft = UDim.new(0, 6),
	PaddingRight = UDim.new(0, 6),
	Parent = inputBox,
})

local sendButton = make("TextButton", {
	Name = "SendButton",
	Position = UDim2.new(1, -68, 0.5, -13),
	Size = UDim2.new(0, 60, 0, 26),
	BackgroundColor3 = palette.button,
	BorderSizePixel = 0,
	Font = Enum.Font.SourceSansSemibold,
	TextSize = 14,
	TextColor3 = palette.buttonText,
	Text = "Send",
	Parent = inputRow,
})
make("UICorner", { CornerRadius = UDim.new(0, 4), Parent = sendButton })

local function relayout()
	local top = HEADER_HEIGHT
	if settingsRow.Visible then
		top = top + SETTINGS_HEIGHT
	end
	scroller.Position = UDim2.new(0, 0, 0, top)
	scroller.Size = UDim2.new(1, 0, 1, -(top + INPUT_HEIGHT))
end

-- Message list management -----------------------------------------------------

local items = {} -- FIFO of { frame, restyle }
local layoutOrderCounter = 0
local stickToBottom = true

local function updateCanvas()
	local wasStick = stickToBottom
	local contentY = listLayout.AbsoluteContentSize.Y + 12
	scroller.CanvasSize = UDim2.new(0, 0, 0, contentY)
	if wasStick then
		scroller.CanvasPosition = Vector2.new(0, math.max(0, contentY - scroller.AbsoluteWindowSize.Y))
		stickToBottom = true
	end
end

-- Connected once, not per item.
listLayout:GetPropertyChangedSignal("AbsoluteContentSize"):Connect(updateCanvas)
scroller:GetPropertyChangedSignal("AbsoluteSize"):Connect(updateCanvas)
scroller:GetPropertyChangedSignal("CanvasPosition"):Connect(function()
	local contentY = scroller.CanvasSize.Y.Offset
	local windowY = scroller.AbsoluteWindowSize.Y
	local fromBottom = contentY - (scroller.CanvasPosition.Y + windowY)
	-- Stick unless the user has scrolled up more than one screen.
	stickToBottom = fromBottom <= windowY
end)

local function registerItem(frame, restyle)
	layoutOrderCounter = layoutOrderCounter + 1
	frame.LayoutOrder = layoutOrderCounter
	frame.Parent = scroller
	table.insert(items, { frame = frame, restyle = restyle })
	if #items > MAX_UI_ITEMS then
		local oldest = table.remove(items, 1)
		oldest.frame:Destroy()
	end
end

local function makeBubble(text, isUser)
	local wrapper = make("Frame", {
		Size = UDim2.new(1, 0, 0, 0),
		AutomaticSize = Enum.AutomaticSize.Y,
		BackgroundTransparency = 1,
	})
	local label = make("TextLabel", {
		Size = UDim2.new(1, -48, 0, 0),
		Position = isUser and UDim2.new(0, 48, 0, 0) or UDim2.new(0, 0, 0, 0),
		AutomaticSize = Enum.AutomaticSize.Y,
		BackgroundColor3 = isUser and palette.userBubble or palette.cardBg,
		BorderSizePixel = 0,
		Font = Enum.Font.SourceSans,
		TextSize = 14,
		TextColor3 = isUser and palette.userBubbleText or palette.text,
		TextXAlignment = Enum.TextXAlignment.Left,
		TextYAlignment = Enum.TextYAlignment.Top,
		TextWrapped = true,
		Text = text,
		Parent = wrapper,
	})
	make("UICorner", { CornerRadius = UDim.new(0, 6), Parent = label })
	make("UIPadding", {
		PaddingTop = UDim.new(0, 6),
		PaddingBottom = UDim.new(0, 6),
		PaddingLeft = UDim.new(0, 8),
		PaddingRight = UDim.new(0, 8),
		Parent = label,
	})
	registerItem(wrapper, function(p)
		label.BackgroundColor3 = isUser and p.userBubble or p.cardBg
		label.TextColor3 = isUser and p.userBubbleText or p.text
	end)
end

function UI.addUserBubble(text)
	makeBubble(text, true)
end

function UI.addAssistantBubble(text)
	makeBubble(text, false)
end

function UI.addSystemNote(text)
	local label = make("TextLabel", {
		Size = UDim2.new(1, 0, 0, 0),
		AutomaticSize = Enum.AutomaticSize.Y,
		BackgroundTransparency = 1,
		Font = Enum.Font.SourceSansItalic,
		TextSize = 13,
		TextColor3 = palette.dimText,
		TextXAlignment = Enum.TextXAlignment.Left,
		TextYAlignment = Enum.TextYAlignment.Top,
		TextWrapped = true,
		Text = text,
	})
	registerItem(label, function(p)
		label.TextColor3 = p.dimText
	end)
end

-- Returns a card handle with :finish(ok, durationMs, errorText).
function UI.addActivityCard(toolName, summary)
	local card = make("Frame", {
		Size = UDim2.new(1, 0, 0, 0),
		AutomaticSize = Enum.AutomaticSize.Y,
		BackgroundColor3 = palette.cardBg,
		BorderSizePixel = 0,
	})
	make("UICorner", { CornerRadius = UDim.new(0, 6), Parent = card })
	make("UIPadding", {
		PaddingTop = UDim.new(0, 6),
		PaddingBottom = UDim.new(0, 6),
		PaddingLeft = UDim.new(0, 8),
		PaddingRight = UDim.new(0, 8),
		Parent = card,
	})
	make("UIListLayout", {
		Padding = UDim.new(0, 2),
		SortOrder = Enum.SortOrder.LayoutOrder,
		Parent = card,
	})
	local titleLabel = make("TextLabel", {
		LayoutOrder = 1,
		Size = UDim2.new(1, 0, 0, 0),
		AutomaticSize = Enum.AutomaticSize.Y,
		BackgroundTransparency = 1,
		Font = Enum.Font.SourceSansSemibold,
		TextSize = 14,
		TextColor3 = palette.text,
		TextXAlignment = Enum.TextXAlignment.Left,
		Text = toolName,
		Parent = card,
	})
	local descLabel = make("TextLabel", {
		LayoutOrder = 2,
		Size = UDim2.new(1, 0, 0, 0),
		AutomaticSize = Enum.AutomaticSize.Y,
		BackgroundTransparency = 1,
		Font = Enum.Font.SourceSans,
		TextSize = 13,
		TextColor3 = palette.subText,
		TextXAlignment = Enum.TextXAlignment.Left,
		TextYAlignment = Enum.TextYAlignment.Top,
		TextWrapped = true,
		Text = summary or "",
		Visible = summary ~= nil and summary ~= "",
		Parent = card,
	})
	local statusLabel = make("TextLabel", {
		LayoutOrder = 3,
		Size = UDim2.new(1, 0, 0, 0),
		AutomaticSize = Enum.AutomaticSize.Y,
		BackgroundTransparency = 1,
		Font = Enum.Font.SourceSans,
		TextSize = 13,
		TextColor3 = palette.dimText,
		TextXAlignment = Enum.TextXAlignment.Left,
		Text = "Running",
		Parent = card,
	})
	local errorLabel = make("TextLabel", {
		LayoutOrder = 4,
		Size = UDim2.new(1, 0, 0, 0),
		AutomaticSize = Enum.AutomaticSize.Y,
		BackgroundTransparency = 1,
		Font = Enum.Font.SourceSans,
		TextSize = 13,
		TextColor3 = ERROR_RED,
		TextXAlignment = Enum.TextXAlignment.Left,
		TextYAlignment = Enum.TextYAlignment.Top,
		TextWrapped = true,
		Text = "",
		Visible = false,
		Parent = card,
	})

	local handle = { finished = false }

	registerItem(card, function(p)
		card.BackgroundColor3 = p.cardBg
		titleLabel.TextColor3 = p.text
		descLabel.TextColor3 = p.subText
		if not handle.finished then
			statusLabel.TextColor3 = p.dimText
		end
	end)

	task.spawn(function()
		local frameCount = 0
		while not handle.finished and statusLabel.Parent ~= nil do
			frameCount = frameCount + 1
			statusLabel.Text = "Running" .. string.rep(".", (frameCount % 3) + 1)
			task.wait(0.3)
		end
	end)

	function handle.finish(_, ok, durationMs, errorText)
		handle.finished = true
		if statusLabel.Parent == nil then
			return
		end
		local msText = string.format("%.0f ms", tonumber(durationMs) or 0)
		if ok then
			statusLabel.Text = "Done - " .. msText
			statusLabel.TextColor3 = STATUS_GREEN
		else
			statusLabel.Text = "Failed - " .. msText
			statusLabel.TextColor3 = STATUS_RED
			if errorText and errorText ~= "" then
				errorLabel.Text = errorText
				errorLabel.Visible = true
			end
		end
	end

	return handle
end

function UI.refreshStatus()
	local color = STATUS_RED
	local word = "Disconnected"
	if State.status == "connected" then
		color = STATUS_GREEN
		word = "Connected"
	elseif State.status == "connecting" then
		color = STATUS_YELLOW
		word = "Connecting"
	end
	statusDot.BackgroundColor3 = color
	statusWordLabel.Text = word
	pauseButton.Text = State.paused and "Resume" or "Pause"
	placeLabel.Text = game.Name
end

-- Theme re-skin ---------------------------------------------------------------

local function applyPalette()
	palette = buildPalette()
	root.BackgroundColor3 = palette.bg
	header.BackgroundColor3 = palette.headerBg
	statusWordLabel.TextColor3 = palette.subText
	placeLabel.TextColor3 = palette.text
	pauseButton.BackgroundColor3 = palette.button
	pauseButton.TextColor3 = palette.buttonText
	gearButton.BackgroundColor3 = palette.button
	gearButton.TextColor3 = palette.buttonText
	settingsRow.BackgroundColor3 = palette.headerBg
	portLabel.TextColor3 = palette.subText
	portBox.BackgroundColor3 = palette.inputBg
	portBox.TextColor3 = palette.text
	portHint.TextColor3 = palette.dimText
	scroller.ScrollBarImageColor3 = palette.scrollBar
	inputRow.BackgroundColor3 = palette.headerBg
	inputBox.BackgroundColor3 = palette.inputBg
	inputBox.TextColor3 = palette.text
	inputBox.PlaceholderColor3 = palette.dimText
	sendButton.BackgroundColor3 = palette.button
	sendButton.TextColor3 = palette.buttonText
	for _, item in ipairs(items) do
		if item.restyle then
			pcall(item.restyle, palette)
		end
	end
end

pcall(function()
	settings().Studio.ThemeChanged:Connect(applyPalette)
end)

-- Header/input interactions ---------------------------------------------------

pauseButton.MouseButton1Click:Connect(function()
	State.paused = not State.paused
	UI.refreshStatus()
	if State.paused then
		UI.addSystemNote("MCL paused. Commands are withheld until you press Resume.")
	else
		UI.addSystemNote("MCL resumed.")
	end
end)

gearButton.MouseButton1Click:Connect(function()
	settingsRow.Visible = not settingsRow.Visible
	relayout()
end)

portBox.FocusLost:Connect(function()
	local n = tonumber(portBox.Text)
	if n and n >= 1 and n <= 65535 then
		n = math.floor(n)
		if n ~= State.port then
			State.port = n
			pcall(function()
				plugin:SetSetting("mcl_port", n)
			end)
			UI.addSystemNote(string.format("Port set to %d (takes effect on the next poll).", n))
		end
		portBox.Text = tostring(n)
	else
		portBox.Text = tostring(State.port)
		UI.addSystemNote("Invalid port; keeping " .. tostring(State.port) .. ".")
	end
end)

local function submitChat()
	local text = string.gsub(inputBox.Text, "^%s*(.-)%s*$", "%1")
	if text == "" then
		return
	end
	inputBox.Text = ""
	Net.sendChat(text)
end

inputBox.FocusLost:Connect(function(enterPressed)
	if enterPressed then
		submitChat()
		-- Recapture on the next step; capturing synchronously inside FocusLost
		-- does not reliably take.
		task.defer(function()
			inputBox:CaptureFocus()
		end)
	end
end)

sendButton.MouseButton1Click:Connect(submitChat)

-- Toolbar ---------------------------------------------------------------------

local toolbar = plugin:CreateToolbar("MCL")
local toggleButton = toolbar:CreateButton("MCL", "Toggle the MCL dock", "")
toggleButton.ClickableWhenViewportHidden = true
toggleButton.Click:Connect(function()
	dock.Enabled = not dock.Enabled
end)
dock:GetPropertyChangedSignal("Enabled"):Connect(function()
	toggleButton:SetActive(dock.Enabled)
end)
toggleButton:SetActive(dock.Enabled)

--------------------------------------------------------------------------------
-- Executor: tool dispatch (spec sections 3 and 5)
--------------------------------------------------------------------------------

local Tools = {}

-- set_waypoint is deliberately absent: SetWaypoint is ignored while a
-- recording is in progress, so it must run outside one (spec section 3).
local MUTATING_TOOLS = {
	run_code = true,
	set_properties = true,
	create_instance = true,
	delete_instances = true,
	insert_asset = true,
}

local function pushLog(logs, level, ...)
	local packed = table.pack(...)
	local parts = {}
	for i = 1, packed.n do
		parts[i] = tostring(packed[i])
	end
	table.insert(logs, { level = level, text = table.concat(parts, " ") })
end

Tools.run_code = function(args, logs)
	local code = args.code
	if typeof(code) ~= "string" then
		error("run_code: 'code' must be a string", 0)
	end
	local fn, compileErr = loadstring(code, "MCL.run_code")
	if not fn then
		error("Syntax error: " .. tostring(compileErr), 0)
	end
	local env = setmetatable({
		print = function(...)
			pushLog(logs, "print", ...)
			print(...)
		end,
		warn = function(...)
			pushLog(logs, "warn", ...)
			warn(...)
		end,
	}, { __index = getfenv() })
	setfenv(fn, env)
	-- Runtime errors propagate to the xpcall harness in Executor.execute.
	local packed = table.pack(fn())
	local returns = {}
	for i = 1, math.min(packed.n, 8) do
		local encoded = Codec.encode(packed[i])
		if encoded == nil then
			encoded = "<nil>"
		end
		returns[i] = encoded
	end
	return { returns = returns }
end

Tools.say = function(args)
	local message = args.message
	if typeof(message) ~= "string" then
		error("say: 'message' must be a string", 0)
	end
	UI.addAssistantBubble(message)
	return { ok = true }
end

local BUILDY_SERVICES = {
	"Workspace",
	"Lighting",
	"ReplicatedStorage",
	"ServerStorage",
	"ServerScriptService",
	"StarterGui",
	"StarterPack",
	"StarterPlayer",
	"Teams",
	"SoundService",
	"TextChatService",
}

Tools.get_tree = function(args)
	local rootInstance, resolveErr = Paths.resolve(args.path or "game")
	if not rootInstance then
		error(resolveErr, 0)
	end
	local maxDepth = math.clamp(math.floor(tonumber(args.max_depth) or 2), 1, 10)
	local classFilter = typeof(args.class_filter) == "string" and args.class_filter or nil
	local includeServices = args.include_services == true
	local nodeCount = 0

	local function isaSafe(instance, className)
		local ok, result = pcall(function()
			return instance:IsA(className)
		end)
		return ok and result == true
	end

	local function hasMatchingDescendant(instance)
		local ok, found = pcall(function()
			return instance:FindFirstChildWhichIsA(classFilter, true)
		end)
		return ok and found ~= nil
	end

	local function buildNode(instance, depth)
		nodeCount = nodeCount + 1
		local node = {
			name = instance.Name,
			className = instance.ClassName,
			path = Paths.toPath(instance) or "<detached>",
		}
		if depth < maxDepth then
			local children
			if instance == game and not includeServices then
				children = {}
				for _, serviceName in ipairs(BUILDY_SERVICES) do
					local ok, service = pcall(function()
						return game:GetService(serviceName)
					end)
					if ok and typeof(service) == "Instance" then
						table.insert(children, service)
					end
				end
			else
				children = instance:GetChildren()
			end
			local childNodes = {}
			local cut = false
			for _, child in ipairs(children) do
				if nodeCount >= TREE_NODE_CAP then
					cut = true
					break
				end
				local include = true
				if classFilter then
					include = isaSafe(child, classFilter) or hasMatchingDescendant(child)
				end
				if include then
					table.insert(childNodes, buildNode(child, depth + 1))
				end
			end
			if #childNodes > 0 then
				node.children = childNodes
			end
			if cut then
				node.truncated = true
			end
		end
		return node
	end

	return buildNode(rootInstance, 0)
end

Tools.get_properties = function(args)
	local instance, resolveErr = Paths.resolve(args.path or "")
	if not instance then
		error(resolveErr, 0)
	end
	local properties = {}
	local failed = {}

	local function readInto(name, collectFailure)
		local ok, value = pcall(function()
			return instance[name]
		end)
		if ok then
			local encoded = Codec.encode(value)
			if encoded ~= nil then
				properties[name] = encoded
			end
		elseif collectFailure then
			failed[name] = tostring(value)
		end
	end

	local requested = args.properties
	if typeof(requested) == "table" and #requested > 0 then
		for _, name in ipairs(requested) do
			readInto(tostring(name), true)
		end
	else
		-- Default set per spec section 5; failures are skipped silently.
		readInto("Name", false)
		readInto("ClassName", false)
		readInto("Parent", false)
		if instance:IsA("BasePart") then
			for _, name in ipairs({ "Position", "Size", "Rotation", "Color", "Material", "Anchored", "CanCollide", "Transparency" }) do
				readInto(name, false)
			end
			if instance:IsA("Part") then
				readInto("Shape", false)
			end
		end
		if instance:IsA("Model") then
			readInto("PrimaryPart", false)
			readInto("WorldPivot", false)
		end
		if instance:IsA("LuaSourceContainer") then
			-- Enabled/Disabled state only; Source is intentionally excluded and
			-- must be requested explicitly.
			readInto("Enabled", false)
			readInto("Disabled", false)
		end
		if instance:IsA("GuiObject") then
			for _, name in ipairs({ "Position", "Size", "Visible", "BackgroundColor3", "Text" }) do
				readInto(name, false)
			end
		end
	end

	local result = {
		path = Paths.toPath(instance) or tostring(args.path),
		className = instance.ClassName,
		properties = properties,
	}
	if next(failed) ~= nil then
		result.failed = failed
	end
	return result
end

Tools.set_properties = function(args)
	local instance, resolveErr = Paths.resolve(args.path or "")
	if not instance then
		error(resolveErr, 0)
	end
	if typeof(args.properties) ~= "table" then
		error("set_properties: 'properties' must be an object", 0)
	end
	local applied = {}
	local failed = {}
	for name, rawValue in pairs(args.properties) do
		name = tostring(name)
		local ok, applyErr = Codec.applyProperty(instance, name, rawValue)
		if ok then
			table.insert(applied, name)
		else
			table.insert(failed, { name = name, error = applyErr })
		end
	end
	if #applied == 0 and #failed > 0 then
		local messages = {}
		for _, failure in ipairs(failed) do
			table.insert(messages, failure.name .. ": " .. tostring(failure.error))
		end
		error("All properties failed: " .. table.concat(messages, "; "), 0)
	end
	return { applied = applied, failed = failed }
end

Tools.create_instance = function(args)
	if typeof(args.class_name) ~= "string" then
		error("create_instance: 'class_name' must be a string", 0)
	end
	local parent, resolveErr = Paths.resolve(args.parent_path or "")
	if not parent then
		error(resolveErr, 0)
	end
	local okNew, instance = pcall(Instance.new, args.class_name)
	if not okNew then
		error("Cannot create class '" .. args.class_name .. "': " .. tostring(instance), 0)
	end
	if typeof(args.name) == "string" and args.name ~= "" then
		instance.Name = args.name
	end
	local applied = {}
	local failed = {}
	if typeof(args.properties) == "table" then
		for name, rawValue in pairs(args.properties) do
			name = tostring(name)
			if name ~= "Parent" then
				local ok, applyErr = Codec.applyProperty(instance, name, rawValue)
				if ok then
					table.insert(applied, name)
				else
					table.insert(failed, { name = name, error = applyErr })
				end
			end
		end
	end
	-- Parent last, after properties.
	instance.Parent = parent
	return { path = Paths.toPath(instance) or "<detached>", applied = applied, failed = failed }
end

Tools.delete_instances = function(args)
	if typeof(args.paths) ~= "table" then
		error("delete_instances: 'paths' must be an array of strings", 0)
	end
	local deleted = {}
	local failed = {}
	for _, path in ipairs(args.paths) do
		path = tostring(path)
		local instance, resolveErr = Paths.resolve(path)
		if not instance then
			table.insert(failed, { path = path, error = resolveErr })
		else
			local ok, destroyErr = pcall(function()
				instance:Destroy()
			end)
			if ok then
				table.insert(deleted, path)
			else
				table.insert(failed, { path = path, error = tostring(destroyErr) })
			end
		end
	end
	if #deleted == 0 and #failed > 0 then
		local messages = {}
		for _, failure in ipairs(failed) do
			table.insert(messages, failure.path .. ": " .. tostring(failure.error))
		end
		error("No instances deleted: " .. table.concat(messages, "; "), 0)
	end
	return { deleted = deleted, failed = failed }
end

Tools.insert_asset = function(args)
	local assetId = tonumber(args.asset_id)
	if not assetId then
		error("insert_asset: 'asset_id' must be a number", 0)
	end
	local parent, resolveErr = Paths.resolve(args.parent_path or "game.Workspace")
	if not parent then
		error(resolveErr, 0)
	end
	local okLoad, container = pcall(function()
		return InsertService:LoadAsset(assetId)
	end)
	if not okLoad then
		error("LoadAsset failed: " .. tostring(container), 0)
	end
	local children = container:GetChildren()
	if #children == 1 and typeof(args.name) == "string" and args.name ~= "" then
		children[1].Name = args.name
	end
	local paths = {}
	for _, child in ipairs(children) do
		child.Parent = parent
		table.insert(paths, Paths.toPath(child) or "<detached>")
	end
	container:Destroy()
	return { paths = paths }
end

Tools.find_instances = function(args)
	local rootInstance, resolveErr = Paths.resolve(args.path or "game.Workspace")
	if not rootInstance then
		error(resolveErr, 0)
	end
	local nameContains = nil
	if typeof(args.name_contains) == "string" and args.name_contains ~= "" then
		nameContains = string.lower(args.name_contains)
	end
	local className = typeof(args.class_name) == "string" and args.class_name or nil
	local maxResults = math.clamp(math.floor(tonumber(args.max_results) or 25), 1, 200)
	local matches = {}
	local truncated = false
	for _, descendant in ipairs(rootInstance:GetDescendants()) do
		local include = true
		if nameContains and not string.find(string.lower(descendant.Name), nameContains, 1, true) then
			include = false
		end
		if include and className then
			local ok, isa = pcall(function()
				return descendant:IsA(className)
			end)
			include = ok and isa == true
		end
		if include then
			if #matches >= maxResults then
				truncated = true
				break
			end
			table.insert(matches, {
				path = Paths.toPath(descendant) or "<detached>",
				name = descendant.Name,
				className = descendant.ClassName,
			})
		end
	end
	return { matches = matches, truncated = truncated }
end

Tools.set_waypoint = function(args)
	local label = args.label
	if typeof(label) ~= "string" or label == "" then
		error("set_waypoint: 'label' must be a non-empty string", 0)
	end
	ChangeHistoryService:SetWaypoint(label)
	return { ok = true }
end

-- Runs one command to completion inside a pcall harness with undo recording.
-- Returns: ok, errorText, result, logs, durationMs.
function Executor.execute(command, sharedLogs)
	local toolName = tostring(command.tool)
	local args = typeof(command.args) == "table" and command.args or {}
	-- sharedLogs lets the caller keep a handle on captured output so a timeout
	-- result can still report whatever was printed before the deadline.
	local logs = sharedLogs or {}
	local startedAt = os.clock()
	local handler = Tools[toolName]
	if not handler then
		return false, "Unknown tool: " .. toolName, nil, logs, (os.clock() - startedAt) * 1000
	end

	local recording = nil
	if MUTATING_TOOLS[toolName] then
		local description = "MCL " .. toolName
		if typeof(args.description) == "string" and args.description ~= "" then
			description = "MCL: " .. args.description
		end
		local okBegin, rec = pcall(function()
			return ChangeHistoryService:TryBeginRecording(description)
		end)
		if okBegin then
			-- nil means a recording is already active: execute anyway without one.
			recording = rec
		end
	end

	local ok, resultOrError = xpcall(function()
		return handler(args, logs)
	end, function(err)
		return debug.traceback(tostring(err), 2)
	end)

	if recording ~= nil then
		-- Commit even on error so partial changes remain undoable.
		pcall(function()
			ChangeHistoryService:FinishRecording(recording, Enum.FinishRecordingOperation.Commit)
		end)
	end

	local durationMs = (os.clock() - startedAt) * 1000
	if ok then
		return true, nil, resultOrError, logs, durationMs
	end
	return false, tostring(resultOrError), nil, logs, durationMs
end

-- Compact auto-summary used when the command has no description argument.
function Executor.summarize(toolName, args)
	if typeof(args.description) == "string" and args.description ~= "" then
		return args.description
	end
	if toolName == "run_code" then
		local firstLine = string.match(tostring(args.code or ""), "^[^\r\n]*") or ""
		if #firstLine > 60 then
			firstLine = string.sub(firstLine, 1, 57) .. "..."
		end
		return firstLine
	elseif toolName == "set_properties" or toolName == "get_properties" then
		return tostring(args.path or "")
	elseif toolName == "get_tree" or toolName == "find_instances" then
		return tostring(args.path or (toolName == "get_tree" and "game" or "game.Workspace"))
	elseif toolName == "create_instance" then
		return tostring(args.class_name or "?") .. " in " .. tostring(args.parent_path or "?")
	elseif toolName == "delete_instances" then
		local count = typeof(args.paths) == "table" and #args.paths or 0
		if count == 1 then
			return tostring(args.paths[1])
		end
		return string.format("%d instances", count)
	elseif toolName == "insert_asset" then
		return "asset " .. tostring(args.asset_id)
	elseif toolName == "set_waypoint" then
		return tostring(args.label or "")
	end
	return ""
end

--------------------------------------------------------------------------------
-- Net: HTTP bridge client + poll loop (spec sections 1 and 7)
--------------------------------------------------------------------------------

local POLL_BACKOFFS = { 1, 2, 5 }

-- POST a JSON body. Returns true, decodedResponse or false, errorText.
-- Never raises: all transport work happens inside pcall.
function Net.post(endpoint, bodyTable)
	local okEncode, body = pcall(function()
		return HttpService:JSONEncode(bodyTable)
	end)
	if not okEncode then
		return false, "JSON encode failed: " .. tostring(body)
	end
	local url = string.format("http://127.0.0.1:%d%s", State.port, endpoint)
	local okRequest, response = pcall(function()
		return HttpService:RequestAsync({
			Url = url,
			Method = "POST",
			Headers = { ["Content-Type"] = "application/json" },
			Body = body,
		})
	end)
	if not okRequest then
		return false, tostring(response)
	end
	if not response.Success then
		return false, string.format("HTTP %d: %s", response.StatusCode, tostring(response.Body))
	end
	local okDecode, decoded = pcall(function()
		return HttpService:JSONDecode(response.Body)
	end)
	if not okDecode then
		return false, "Bad JSON in server response"
	end
	return true, decoded
end

-- Show each connectivity hint at most once per plugin session.
local function maybeShowHttpHint(errorText)
	local lowered = string.lower(errorText)
	local mentionsDisabled = string.find(lowered, "not enabled", 1, true)
		or string.find(lowered, "disabled", 1, true)
		or string.find(lowered, "enable via game settings", 1, true)
	local mentionsPermission = string.find(lowered, "permission", 1, true)
		or string.find(lowered, "not allowed", 1, true)
		or string.find(lowered, "denied", 1, true)
		or string.find(lowered, "blocked", 1, true)
	if mentionsDisabled then
		if not State.hintedHttpDisabled then
			State.hintedHttpDisabled = true
			UI.addSystemNote("HTTP requests appear to be disabled. Enable Game Settings > Security > Allow HTTP Requests, then MCL will reconnect automatically.")
		end
	elseif mentionsPermission then
		if not State.hintedPermission then
			State.hintedPermission = true
			UI.addSystemNote("Studio blocked the request. Open Plugin Management and permit this plugin to access 127.0.0.1.")
		end
	end
end

-- Chat posts are serialized through a FIFO queue drained by one long-lived
-- task so /mcl/chat receives messages in the order they were typed. The UI
-- bubble still appears instantly in Net.sendChat.
local chatQueue = {}

task.spawn(function()
	while State.running do
		if #chatQueue == 0 then
			task.wait(0.1)
		else
			local entry = table.remove(chatQueue, 1)
			local ok, errOrBody = Net.post("/mcl/chat", entry)
			if not ok then
				UI.addSystemNote("Could not deliver the message (server unreachable). It will not be retried.")
				if typeof(errOrBody) == "string" then
					maybeShowHttpHint(errOrBody)
				end
			end
		end
	end
end)

function Net.sendChat(text)
	UI.addUserBubble(text)
	table.insert(chatQueue, { text = text, sentAt = os.time() })
end

-- Backoff between result-post attempts; roughly 10 seconds total. Duplicate
-- ids are idempotent server-side, so retrying is always safe.
local RESULT_RETRY_DELAYS = { 1, 1, 2, 3, 3 }

local function postResultWithRetry(payload)
	for attempt = 1, #RESULT_RETRY_DELAYS + 1 do
		local ok = Net.post("/mcl/result", payload)
		if ok then
			return true
		end
		if attempt <= #RESULT_RETRY_DELAYS then
			task.wait(RESULT_RETRY_DELAYS[attempt])
		end
	end
	UI.addSystemNote("Could not deliver a result to the server; the tool call will time out.")
	return false
end

local function handleCommand(command)
	if typeof(command) ~= "table" or command.id == nil then
		return
	end
	local toolName = tostring(command.tool)
	local args = typeof(command.args) == "table" and command.args or {}
	local card = nil
	if toolName ~= "say" then
		-- say renders as an assistant chat bubble instead of an activity card.
		card = UI.addActivityCard(toolName, Executor.summarize(toolName, args))
	end

	-- Run the handler in its own thread and watch it from this (poll loop)
	-- thread with a deadline, so a yielding runaway command cannot wedge the
	-- bridge (spec section 3). Non-yielding code cannot be preempted by Luau;
	-- the timeout covers everything that yields.
	local timeoutSeconds = math.clamp(tonumber(args.timeout_seconds) or 30, 1, 300)
	local exec = { done = false, reported = false }
	local sharedLogs = {}
	task.spawn(function()
		local ok, errorText, result, logs, durationMs = Executor.execute(command, sharedLogs)
		if exec.reported then
			-- The deadline already fired and a timeout result was posted; a
			-- late completion must not post a second result.
			return
		end
		exec.ok = ok
		exec.errorText = errorText
		exec.result = result
		exec.logs = logs
		exec.durationMs = durationMs
		exec.done = true
	end)

	local deadline = os.clock() + timeoutSeconds
	while not exec.done and os.clock() < deadline do
		task.wait(0.05)
	end

	if not exec.done then
		exec.reported = true
		local timeoutMs = timeoutSeconds * 1000
		if card then
			card:finish(false, timeoutMs, "command timed out in Studio")
		end
		postResultWithRetry({
			id = command.id,
			ok = false,
			error = "command timed out in Studio",
			logs = sharedLogs,
			durationMs = timeoutMs,
		})
		return
	end
	exec.reported = true

	local ok = exec.ok
	local errorText = exec.errorText
	local result = exec.result
	local logs = exec.logs
	local durationMs = exec.durationMs

	if card then
		card:finish(ok, durationMs, errorText)
	end

	if ok and toolName == "run_code" and typeof(result) == "table" then
		result.logs = logs
		result.durationMs = durationMs
	end

	local payload = {
		id = command.id,
		ok = ok,
		logs = logs,
		durationMs = durationMs,
	}
	if ok then
		payload.result = result ~= nil and result or {}
	else
		payload.error = errorText
	end

	local okEncode = pcall(function()
		HttpService:JSONEncode(payload)
	end)
	if not okEncode then
		payload = {
			id = command.id,
			ok = false,
			error = "Result could not be serialized to JSON",
			logs = logs,
			durationMs = durationMs,
		}
	end
	postResultWithRetry(payload)
end

local function pollOnce()
	local placeName = game.Name
	-- Keep the header place name current (it can change after a rename or
	-- publish); cheapest here where game.Name is already read each poll.
	if placeLabel.Text ~= placeName then
		placeLabel.Text = placeName
	end
	local body = {
		session = SESSION,
		version = VERSION,
		accepting = not State.paused,
		place = {
			name = placeName,
			placeId = game.PlaceId,
			gameId = game.GameId,
		},
	}
	return Net.post("/mcl/poll", body)
end

local function pollLoop()
	local failCount = 0
	while State.running do
		local pollStartedAt = os.clock()
		local ok, responseOrError = pollOnce()
		if not State.running then
			break
		end
		if ok and typeof(responseOrError) == "table" and responseOrError.ok == true then
			failCount = 0
			if State.status ~= "connected" then
				State.status = "connected"
				UI.refreshStatus()
			end
			local commands = responseOrError.commands
			local handledAny = false
			if typeof(commands) == "table" then
				-- The server dispatches at most one command per response, but
				-- iterate every entry in order (strictly sequentially) so
				-- nothing is silently dropped if a server ever batches.
				for _, command in ipairs(commands) do
					handleCommand(command)
					handledAny = true
				end
			end
			if State.paused then
				-- The server answers paused polls immediately (no long hold);
				-- pace them to at most one per second or we busy-loop through
				-- Studio's HttpService request budget.
				task.wait(1)
			elseif not handledAny and (os.clock() - pollStartedAt) < 0.5 then
				-- General floor: an idle poll should long-poll for ~20s, so a
				-- fast empty return means some quick-answer server path;
				-- pace it rather than hammering the request budget.
				task.wait(0.5)
			end
		else
			failCount = failCount + 1
			if State.status ~= "disconnected" then
				State.status = "disconnected"
				UI.refreshStatus()
			end
			if not ok and typeof(responseOrError) == "string" then
				maybeShowHttpHint(responseOrError)
			end
			task.wait(POLL_BACKOFFS[math.min(failCount, #POLL_BACKOFFS)])
		end
	end
end

--------------------------------------------------------------------------------
-- Startup
--------------------------------------------------------------------------------

UI.refreshStatus()
task.spawn(pollLoop)

plugin.Unloading:Connect(function()
	State.running = false
end)
