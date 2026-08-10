import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { Bridge } from "./bridge.js";
import { CommandOutcome, NOT_CONNECTED_MESSAGE, PAUSED_MESSAGE } from "./types.js";

const DEFAULT_TIMEOUT_MS = 30_000;

interface ToolResult {
  [key: string]: unknown;
  content: { type: "text"; text: string }[];
  isError?: boolean;
}

function ok(payload: unknown): ToolResult {
  return { content: [{ type: "text", text: JSON.stringify(payload) }] };
}

function fail(message: string): ToolResult {
  return { content: [{ type: "text", text: message }], isError: true };
}

async function respond(fn: () => Promise<unknown>): Promise<ToolResult> {
  try {
    return ok(await fn());
  } catch (err) {
    return fail(err instanceof Error ? err.message : String(err));
  }
}

const descriptionArg = z
  .string()
  .optional()
  .describe("Short human label shown on the dock activity card and used as the undo-step name.");

export function registerTools(server: McpServer, bridge: Bridge): void {
  async function roundTrip(
    tool: string,
    args: Record<string, unknown>,
    timeoutMs = DEFAULT_TIMEOUT_MS,
  ): Promise<CommandOutcome> {
    if (!bridge.isConnected()) throw new Error(NOT_CONNECTED_MESSAGE);
    if (bridge.isPaused() && tool !== "say") throw new Error(PAUSED_MESSAGE);
    const outcome = await bridge.execute(tool, args, timeoutMs);
    if (!outcome.ok) throw new Error(outcome.error ?? `${tool} failed in Roblox Studio`);
    return outcome;
  }

  server.registerTool(
    "get_studio_status",
    {
      description:
        "Report the server's view of Roblox Studio: connected (plugin polled within 35s), paused (user " +
        "pressed Pause in the MCL dock), place {name, placeId, gameId}, pluginVersion, lastSeenSecondsAgo, " +
        "queuedUserMessages, and serverVersion. Answers instantly from bridge state — never waits on the " +
        "plugin and works while paused or disconnected. Call this first to confirm Studio is reachable.",
      inputSchema: {},
    },
    async () => respond(async () => bridge.studioStatus()),
  );

  server.registerTool(
    "run_code",
    {
      description:
        "Run a Luau chunk inside Roblox Studio (edit mode, plugin-level security) and return " +
        "{returns, logs, durationMs}: the chunk's return values (up to 8, Roblox datatypes encoded as " +
        '{"$type": ..., "value": ...} tags), captured print/warn output, and execution time. Each call ' +
        "is wrapped in a ChangeHistory recording so it forms one undo step. Use this for logic, loops, and " +
        "bulk edits; prefer the structured tools (set_properties, create_instance, ...) for simple changes.",
      inputSchema: {
        code: z.string().describe("Luau source to execute. Its return values come back encoded."),
        description: descriptionArg,
        timeout_seconds: z
          .number()
          .min(1)
          .max(300)
          .default(30)
          .describe("Seconds to wait for Studio to finish before the call fails (1-300)."),
      },
    },
    async (args) =>
      respond(async () => {
        const outcome = await roundTrip("run_code", args, args.timeout_seconds * 1000);
        const result = (outcome.result ?? {}) as { returns?: unknown[] };
        return {
          returns: Array.isArray(result.returns) ? result.returns : [],
          logs: outcome.logs,
          durationMs: outcome.durationMs,
        };
      }),
  );

  server.registerTool(
    "say",
    {
      description:
        "Post an assistant chat bubble in the MCL dock inside Roblox Studio. This is your narration " +
        "channel: call say before and between build stages to tell the user what you are about to do and " +
        "what you just finished, then use read_chat to pick up their replies. Requires Studio to be " +
        "connected, but keeps working while MCL is paused. Returns {ok:true}.",
      inputSchema: {
        message: z.string().describe("Text of the chat bubble shown to the user in the dock."),
      },
    },
    async (args) =>
      respond(async () => {
        await roundTrip("say", args);
        return { ok: true };
      }),
  );

  server.registerTool(
    "read_chat",
    {
      description:
        "Drain messages the user typed into the Studio MCL dock; returns {messages: [{text, sentAt}], " +
        "studioConnected}. Reads the server-side queue — no plugin round-trip, so it works while paused or " +
        "disconnected. Check read_chat between build stages so user instructions and course-corrections are " +
        "picked up promptly; after asking a question via say, call read_chat with wait_seconds to block " +
        "until the user answers or the wait expires.",
      inputSchema: {
        wait_seconds: z
          .number()
          .min(0)
          .max(120)
          .default(0)
          .describe("If no messages are queued, wait up to this many seconds for one to arrive (0-120)."),
      },
    },
    async (args) => respond(async () => bridge.readChat(args.wait_seconds)),
  );

  server.registerTool(
    "get_tree",
    {
      description:
        "Return the instance tree under a path as {name, className, path, children?}, with truncated:true " +
        "on nodes whose children were cut (2000-node cap). class_filter keeps only instances that IsA the " +
        'class or contain a matching descendant. At the "game" root only the common build services ' +
        "(Workspace, Lighting, ReplicatedStorage, ...) are listed unless include_services is true. Use this " +
        "to orient yourself in the place before editing.",
      inputSchema: {
        path: z.string().default("game").describe('Root of the tree, e.g. "game" or "game.Workspace.Model".'),
        max_depth: z.number().int().min(1).max(10).default(2).describe("How many levels below the root to include (1-10)."),
        class_filter: z
          .string()
          .optional()
          .describe('Class name to filter by (IsA match), e.g. "BasePart" or "Script".'),
        include_services: z
          .boolean()
          .default(false)
          .describe("When path is \"game\", list every service instead of just the common build services."),
      },
    },
    async (args) =>
      respond(async () => {
        const outcome = await roundTrip("get_tree", args);
        return outcome.result ?? {};
      }),
  );

  server.registerTool(
    "get_properties",
    {
      description:
        "Read properties of the instance at a path. Returns {path, className, properties: {name: " +
        "encodedValue}, failed?}. Omit `properties` to get a sensible default set for the class (position/" +
        "size/color for parts, pivot for models, etc. — Script.Source is excluded by default and must be " +
        "requested explicitly). Roblox datatypes come back tagged, e.g. {\"$type\":\"Vector3\",\"value\":[1,2,3]}.",
      inputSchema: {
        path: z.string().describe('Instance path, e.g. "game.Workspace.Part" or game.Workspace["My Part"].'),
        properties: z
          .array(z.string())
          .optional()
          .describe("Property names to read. Omit for the class-appropriate default set."),
      },
    },
    async (args) =>
      respond(async () => {
        const outcome = await roundTrip("get_properties", args);
        return outcome.result ?? {};
      }),
  );

  server.registerTool(
    "set_properties",
    {
      description:
        "Set properties on the instance at a path. Values may be plain JSON, tagged datatypes " +
        '({"$type":"Vector3","value":[1,2,3]}), or shorthands: [x,y,z] for Vector3, "Neon" or ' +
        '"Material.Neon" for enums, [r,g,b] floats or "#rrggbb" for Color3. A property named Parent accepts ' +
        "an instance path. Returns {applied, failed}; the call errors only if every property failed. One " +
        "undo step per call.",
      inputSchema: {
        path: z.string().describe("Path of the instance to modify."),
        properties: z
          .record(z.unknown())
          .describe("Map of property name to new value (tagged, shorthand, or plain JSON)."),
        description: descriptionArg,
      },
    },
    async (args) =>
      respond(async () => {
        const outcome = await roundTrip("set_properties", args);
        return outcome.result ?? {};
      }),
  );

  server.registerTool(
    "create_instance",
    {
      description:
        "Create an instance with Instance.new(class_name), apply name and properties, then parent it under " +
        "parent_path (parenting happens last). Returns {path, applied, failed} — path is the new instance's " +
        "canonical path for use in later calls. Property values use the same encoding/shorthands as " +
        "set_properties.",
      inputSchema: {
        class_name: z.string().describe('Roblox class to instantiate, e.g. "Part", "Model", "PointLight".'),
        parent_path: z.string().describe("Path of the parent instance the new instance goes under."),
        name: z.string().optional().describe("Name for the new instance (defaults to the class name)."),
        properties: z
          .record(z.unknown())
          .optional()
          .describe("Initial properties, same value encoding as set_properties."),
        description: descriptionArg,
      },
    },
    async (args) =>
      respond(async () => {
        const outcome = await roundTrip("create_instance", args);
        return outcome.result ?? {};
      }),
  );

  server.registerTool(
    "delete_instances",
    {
      description:
        "Destroy each instance in `paths` via :Destroy(). Returns {deleted, failed}; the call errors only " +
        "if every deletion failed. Deletions are undoable as one step.",
      inputSchema: {
        paths: z.array(z.string()).describe("Instance paths to destroy."),
        description: descriptionArg,
      },
    },
    async (args) =>
      respond(async () => {
        const outcome = await roundTrip("delete_instances", args);
        return outcome.result ?? {};
      }),
  );

  server.registerTool(
    "insert_asset",
    {
      description:
        "Insert a Roblox catalog/library asset by id via InsertService:LoadAsset and reparent its contents " +
        "(usually one model) under parent_path. Returns {paths} of the inserted roots. The asset is not " +
        "moved after insertion — position it yourself with set_properties or run_code if needed.",
      inputSchema: {
        asset_id: z.number().int().describe("Numeric Roblox asset id to load."),
        parent_path: z
          .string()
          .default("game.Workspace")
          .describe("Where to parent the inserted asset."),
        name: z.string().optional().describe("Rename the inserted root (only applied when the asset has exactly one root)."),
        description: descriptionArg,
      },
    },
    async (args) =>
      respond(async () => {
        const outcome = await roundTrip("insert_asset", args);
        return outcome.result ?? {};
      }),
  );

  server.registerTool(
    "find_instances",
    {
      description:
        "Search descendants of a path for instances by case-insensitive Name substring and/or class " +
        "(IsA match). Returns {matches: [{path, name, className}], truncated}. Use this instead of walking " +
        "get_tree when you know roughly what you are looking for.",
      inputSchema: {
        path: z.string().default("game.Workspace").describe("Root to search under."),
        name_contains: z
          .string()
          .optional()
          .describe("Case-insensitive substring the instance Name must contain."),
        class_name: z.string().optional().describe('Class the instance must IsA, e.g. "BasePart".'),
        max_results: z.number().int().min(1).max(200).default(25).describe("Cap on returned matches (1-200)."),
      },
    },
    async (args) =>
      respond(async () => {
        const outcome = await roundTrip("find_instances", args);
        return outcome.result ?? {};
      }),
  );

  server.registerTool(
    "set_waypoint",
    {
      description:
        "Call ChangeHistoryService:SetWaypoint(label) in Studio to mark an undo boundary. Use it to group " +
        "a batch of run_code edits into a single labelled undo step. Returns {ok:true}.",
      inputSchema: {
        label: z.string().describe("Label for the undo waypoint, shown in Studio's history."),
      },
    },
    async (args) =>
      respond(async () => {
        await roundTrip("set_waypoint", args);
        return { ok: true };
      }),
  );
}
