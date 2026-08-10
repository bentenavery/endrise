# MCL Protocol Specification v1

MCL (Model Context Link) connects an MCP client (Claude Code, Claude Desktop, or any
MCP-compatible agent) to Roblox Studio. It has two halves:

- **Server** (`server/`): a Node/TypeScript MCP server speaking stdio to the MCP client,
  plus an HTTP "bridge" bound to `127.0.0.1:<port>` that the Studio plugin talks to.
- **Plugin** (`plugin/`): a Luau Studio plugin that long-polls the bridge, executes
  commands inside Studio, and renders a chat + live activity dock.

Default port: **28650**. Server reads `MCL_PORT` env var; plugin stores the port in a
plugin setting (`mcl_port`) editable from the dock UI. All traffic is localhost-only.

The server is the source of truth for queues. The plugin is stateless across restarts
except for its settings (port, dock visibility handled by Studio).

---

## 1. HTTP bridge (plugin ↔ server)

All bodies are JSON. All responses are JSON with at least `{"ok": true|false}`.
On failure: `{"ok": false, "error": "message"}`. The server must never crash on
malformed input; respond 400 with `ok:false`.

### POST /mcl/poll
Plugin long-poll for commands. The server holds the request up to **20 seconds** and
responds early as soon as at least one command is queued.

Request body:
```json
{
  "session": "<random string chosen by plugin at load, stable for plugin lifetime>",
  "version": "1.0.0",
  "accepting": true,
  "place": { "name": "MyGame", "placeId": 123, "gameId": 456 }
}
```
- `accepting: false` means the user paused MCL in the dock. The server must NOT
  dispatch commands while paused (see §3 pause semantics) and should respond to the
  poll immediately (no long hold needed, but holding is also acceptable).

Response:
```json
{ "ok": true, "commands": [ { "id": "c1", "tool": "run_code", "args": { } } ] }
```
- `commands` may be empty (timeout with nothing queued).
- The server dispatches **at most one command per poll response** (simplifies undo
  recording and result attribution; queued commands go out on subsequent polls).
- If a poll arrives with a new `session` value (Studio restarted / place reopened),
  the server drops any in-flight command state for the old session and fails those
  pending MCP calls with "Studio plugin reconnected".

Connectivity: the server considers the plugin **connected** if any poll arrived within
the last **35 seconds**.

### POST /mcl/result
Plugin reports the outcome of one command.

Request body:
```json
{
  "id": "c1",
  "ok": true,
  "result": {},
  "error": null,
  "logs": [ { "level": "print", "text": "hello" } ],
  "durationMs": 12.5
}
```
- `level` is one of `"print" | "warn" | "error"`.
- On `ok:false`, `error` is a human-readable message (include Luau stack where available).
- Unknown/duplicate `id` → respond `{ok:true}` and ignore (idempotent).

### POST /mcl/chat
User typed a message in the Studio dock.

Request body: `{ "text": "make the tower taller", "sentAt": 1723300000 }`
(`sentAt` is unix seconds from `os.time()`.)

The server queues messages (cap 200, drop oldest) for retrieval via the `read_chat`
MCP tool. Response: `{ok:true}`.

### GET /mcl/status
Debug endpoint: `{ ok, pluginConnected, lastPollAgoMs, paused, queuedCommands,
queuedUserMessages, place, serverVersion }`.

---

## 2. Datatype encoding

JSON can't express Roblox datatypes, so both sides use a tagged encoding. A value is
either a JSON primitive, an array, a plain object (string keys), or a tagged object:

```json
{ "$type": "Vector3", "value": [1, 2, 3] }
```

| $type        | value encoding                                                        |
|--------------|-----------------------------------------------------------------------|
| Vector3      | `[x, y, z]`                                                           |
| Vector2      | `[x, y]`                                                              |
| CFrame       | `[x,y,z, r00,r01,r02, r10,r11,r12, r20,r21,r22]` (CFrame components)  |
| Color3       | `[r, g, b]` floats 0–1                                                |
| BrickColor   | `"Bright red"` (name string)                                          |
| UDim         | `[scale, offset]`                                                     |
| UDim2        | `[[xScale, xOffset], [yScale, yOffset]]`                              |
| Enum         | `"Material.Neon"` (EnumType.Name)                                     |
| Instance     | `"game.Workspace.Part"` (canonical path, see §4)                      |
| NumberRange  | `[min, max]`                                                          |
| NumberSequence | `[[time, value, envelope], ...]`                                    |
| ColorSequence  | `[[time, [r,g,b]], ...]`                                            |
| Rect         | `[minX, minY, maxX, maxY]`                                            |
| Font         | `{ "family": "rbxasset://...", "weight": "Bold", "style": "Normal" }` |

Decoding (server → plugin, e.g. `set_properties`): the plugin must accept tagged
objects anywhere a property value appears. As a convenience it must ALSO accept, for a
property whose reflected value is a Roblox datatype, these untagged shorthands:
- 3-number array for Vector3, 2-number array where Vector2/UDim is expected,
- `"Material.Neon"` or `"Neon"` strings for enum properties,
- `[r,g,b]` (0–1 floats) or `"#rrggbb"` for Color3.
Shorthand resolution: try direct assignment first; on type error, coerce using the
current value's type via `typeof(instance[prop])`.

Encoding (plugin → server, e.g. `run_code` return values, `get_properties`): encode
Roblox datatypes as tagged objects; tables become JSON objects/arrays (array if
contiguous integer keys from 1). Depth limit **8**, total-node limit **10000** per
result; on hitting a limit emit the string `"<truncated>"` in place of deeper content.
Cycles emit `"<cycle>"`. Non-serializable values (functions, threads, userdata with no
mapping) emit `"<TypeName>"` e.g. `"<function>"`. NaN/±inf encode as strings
`"nan"`, `"inf"`, `"-inf"` in place of the number. Mixed-key tables: encode the
string-keyed entries in an object; non-string keys become `tostring(key)` prefixed
with `"$k:"` only if a collision would result — otherwise just `tostring(key)`.

`Instance` values encode as their canonical path (§4). If an instance is not a
descendant of `game`, encode as `{"$type":"Instance","value":"<detached>","name":...,
"className":...}`.

---

## 3. Command execution semantics (plugin side)

- The plugin executes commands **one at a time, in order received**.
- Every mutating tool (`run_code`, `set_properties`, `create_instance`,
  `delete_instances`, `insert_asset`, `set_waypoint`) wraps execution in
  `ChangeHistoryService:TryBeginRecording(desc)` → `FinishRecording(recording,
  Enum.FinishRecordingOperation.Commit)`. If `TryBeginRecording` returns nil
  (recording already active), execute anyway without a new recording. On execution
  error, still `FinishRecording(..., Commit)` so partial changes remain undoable.
- `run_code` executes via `loadstring(code)`; the compiled function's environment is
  replaced (`setfenv`) with a table whose `__index` falls through to the plugin's
  global environment but overrides `print` and `warn` to ALSO capture into the
  command's `logs` (they should still forward to the real output). Runtime errors are
  caught with `xpcall` using `debug.traceback` as the handler.
- **Pause**: dock toggle. While paused the plugin keeps polling with
  `accepting:false` and keeps sending/receiving chat, but the server withholds
  commands. MCP tool calls issued while paused fail fast server-side with
  `"MCL is paused in Studio — press Resume in the MCL dock."` EXCEPT `say`,
  `read_chat`, and `get_studio_status`, which work while paused.
- Every executed command produces an **activity card** in the dock: tool name, the
  `description` argument if present (else a compact auto-summary like
  `set_properties: game.Workspace.Part`), spinner while running, then green/red
  status with duration. Errors render the message in red under the card.

---

## 4. Instance paths

Canonical form: `game.Workspace.Model.Part`. Segments that are not valid Lua
identifiers use bracket-quoted form: `game.Workspace["My Part"]["door#2"]`.

Parser requirements (plugin): accept `game` or `Game` root (also a bare path starting
at a service, e.g. `Workspace.Part`, treated as `game.Workspace.Part`). Split on `.`
outside brackets; inside `["..."]` allow any chars with `\"` and `\\` escapes. First
segment after `game` resolves via `game:GetService(name)` when `name` is a known
service (pcall; fall back to `FindFirstChild`). Subsequent segments resolve via
`FindFirstChild(name)` (never recursive). Resolution failure → error
`"No instance at path: <path> (failed at segment '<seg>')"`.

Generator requirements (plugin): walk `Instance.Parent` up to `game`; emit `game` +
segments, bracket-quoting any name that doesn't match `^[A-Za-z_][A-Za-z0-9_]*$`.
Service segments use the instance's `Name` (which equals the service class name for
standard services).

---

## 5. MCP tools (server)

Tool names and JSON-schema argument names are exact. All tools that round-trip to the
plugin share a default timeout of **30s** (override per-call where noted). If the
plugin is not connected, plugin-round-trip tools fail immediately with:
`"Roblox Studio is not connected. Open Studio with the MCL plugin installed and check
the MCL dock shows 'Connected'."`

Tool results are returned as MCP `content: [{type:"text", text: JSON.stringify(payload)}]`
with `isError: true` on failure and the error message as the text.

### get_studio_status
Args: none. Never round-trips; answers from bridge state.
Returns: `{ connected, paused, place, pluginVersion, lastSeenSecondsAgo,
queuedUserMessages }` (`place` etc. null when never connected).

### run_code
Args: `{ code: string, description?: string, timeout_seconds?: number (1–300, default 30) }`
Runs Luau in Studio (edit mode, plugin security). Returns
`{ returns: [encoded...], logs: [{level, text}], durationMs }` — `returns` holds the
code's return values (up to 8, encoded per §2).

### say
Args: `{ message: string }`. Posts an assistant chat bubble to the Studio dock. This is
the narration channel — the model should call it before/while building so the user can
follow along. Round-trips to the plugin (so it fails if Studio is gone) but works
while paused. Returns `{ ok: true }`.

### read_chat
Args: `{ wait_seconds?: number (0–120, default 0) }`. Drains queued user messages
(typed in the Studio dock) from the SERVER queue — no plugin round-trip. If the queue
is empty and `wait_seconds > 0`, long-polls until a message arrives or the wait
expires. Returns `{ messages: [{text, sentAt}], studioConnected }`.

### get_tree
Args: `{ path?: string = "game", max_depth?: number (1–10, default 2),
class_filter?: string, include_services?: boolean = false }`
Returns the instance tree: `{ name, className, path, children?: [...] }`.
`class_filter` keeps only nodes that are the class/subclass (`IsA`) OR have a matching
descendant. When `path` is `"game"`, only well-known buildy services (Workspace,
Lighting, ReplicatedStorage, ServerStorage, ServerScriptService, StarterGui,
StarterPack, StarterPlayer, Teams, SoundService, TextChatService) are listed unless
`include_services` is true. Node cap 2000 total; set `truncated: true` on nodes whose
children were cut.

### get_properties
Args: `{ path: string, properties?: string[] }`
Returns `{ path, className, properties: { name: encodedValue }, failed?: {name: error} }`.
When `properties` is omitted, return this default set (each read pcall-guarded, skip
failures silently): Name, ClassName, Parent (as path), plus for BaseParts: Position,
Size, Rotation, Color, Material, Anchored, CanCollide, Transparency, Shape (when
Part); for Models: PrimaryPart (as path), plus pivot as `WorldPivot`; for Scripts:
Enabled/Disabled state and `Source` EXCLUDED (too big — must be requested explicitly);
for GuiObjects: Position, Size, Visible, BackgroundColor3, Text (when present).

### set_properties
Args: `{ path: string, properties: object, description?: string }`
Values per §2 decoding (tagged or shorthand). Special-case: a property named
`Parent` accepts an instance path. Returns `{ applied: string[],
failed: [{name, error}] }`. The call succeeds (ok) if at least one property applied;
it is an error only if ALL failed.

### create_instance
Args: `{ class_name: string, parent_path: string, name?: string, properties?: object,
description?: string }`
`Instance.new(class_name)`, apply name/properties (same semantics as set_properties;
failures collected), then parent (parent LAST, after properties, for perf and to
avoid partially-configured pops). Returns `{ path, applied, failed }`.

### delete_instances
Args: `{ paths: string[], description?: string }`
`:Destroy()` each. Returns `{ deleted: string[], failed: [{path, error}] }`. Succeeds
if at least one deleted.

### insert_asset
Args: `{ asset_id: number, parent_path?: string = "game.Workspace", name?: string,
description?: string }`
`InsertService:LoadAsset(asset_id)` — reparent the asset's children (usually one
model) to `parent_path`; if exactly one child, optionally rename to `name` and pivot
it to a clear spot near origin ONLY if it has no meaningful position (skip pivoting —
keep v1 simple: do not move it). Returns `{ paths: string[] }` of inserted roots.

### find_instances
Args: `{ path?: string = "game.Workspace", name_contains?: string, class_name?: string
(IsA match), max_results?: number (1–200, default 25) }`
Case-insensitive substring on Name. Searches descendants. Returns
`{ matches: [{path, name, className}], truncated }`.

### set_waypoint
Args: `{ label: string }`. Calls `ChangeHistoryService:SetWaypoint(label)` in the
plugin. Returns `{ ok: true }`. Use to group a batch of run_code edits into one
undo step boundary.

---

## 6. Server internals (normative)

- MCP: `@modelcontextprotocol/sdk` `McpServer` over stdio. **Nothing may write to
  stdout except the MCP transport** — all logging to stderr.
- Bridge: Node `http` server on `127.0.0.1`, port from `MCL_PORT` (default 28650). If
  the port is busy, exit with a clear stderr message (code 1).
- Command lifecycle: tool call → `{id: randomUUID(), tool, args}` pushed to queue +
  promise stored → dispatched to next poll (one per poll) → resolved by `/mcl/result`
  → timeout rejects and REMOVES the queued command if still undispatched; if already
  dispatched, a late result is ignored.
- `say` bypasses the pause gate; `run_code`/mutating tools respect it.
- Chat queue: FIFO cap 200. `read_chat` drains all pending at once.
- Server version string surfaces in `/mcl/status` and `get_studio_status`.

## 7. Plugin internals (normative)

- Single Script (`MCL.lua`) run as a local plugin. Guard: only run in Studio
  (`RunService:IsStudio()`), and only in edit mode (skip in play solo: if
  `RunService:IsRunning()` do nothing).
- Toolbar "MCL" with one button toggling a `DockWidgetPluginGui`
  (`plugin:CreateDockWidgetPluginGui("MCLDock", info)`), default docked right,
  floating size 360×520.
- Poll loop in `task.spawn`; on HTTP failure show "Disconnected" state and retry with
  backoff 1s → 2s → 5s (cap). Recover instantly on success. Use
  `HttpService:RequestAsync` with `Content-Type: application/json`.
- HTTP failure UX: if the error mentions http being disabled, the dock shows a hint to
  enable Allow HTTP Requests (Game Settings → Security); if it mentions permission,
  point at Plugin Management to grant the plugin permission for 127.0.0.1.
- UI theme: colors from `settings().Studio.Theme:GetColor(...)` with sensible
  fallbacks, re-applied on `ThemeChanged`.
- The dock header shows: status dot (green connected / yellow connecting / red
  disconnected), place name, Pause/Resume button, gear button revealing a port field
  (saved via `plugin:SetSetting("mcl_port", n)`; takes effect on next poll).
- Every chat/user/AI/activity item appends to a ScrollingFrame with auto-scroll
  (unless the user has scrolled up more than one screen — then don't yank).
- Keep the last 300 items; trim oldest.
