# MCL — Model Context Link for Roblox Studio

MCL connects an MCP client (Claude Code, Claude Desktop, or any MCP-compatible agent) to a live Roblox Studio session. It is two pieces: a Node/TypeScript MCP server that exposes structured scene-editing tools over stdio, and a Studio plugin that executes those commands inside the open place and renders a chat + activity dock. The result: you ask Claude to build something, then watch it happen in your viewport — parts appearing, properties changing, scripts landing — with a running feed of what Claude is doing and a chat box to steer it mid-build. See [PROTOCOL.md](PROTOCOL.md) for the full wire spec.

## Architecture

```
┌──────────────┐         ┌─────────────────────┐         ┌──────────────────────┐
│  MCP client  │  stdio  │     MCL server      │  HTTP   │    Studio plugin     │
│ (Claude Code │◄───────►│  (node dist/        │long-poll│     (MCL.lua)        │
│  / Desktop)  │   MCP   │   index.js)         │◄───────►│                      │
└──────────────┘         │                     │127.0.0.1│  ┌────────────────┐  │
                         │  - MCP tools        │  :28650 │  │   DataModel    │  │
                         │  - command queue    │         │  │ (edit the game)│  │
                         │  - chat queue       │         │  └────────────────┘  │
                         └─────────────────────┘         │  ┌────────────────┐  │
                                                         │  │   chat dock    │  │
                                                         │  │ (watch + type) │  │
                                                         │  └────────────────┘  │
                                                         └──────────────────────┘
```

The plugin long-polls the server (`POST /mcl/poll`), executes one command at a time inside Studio, and posts results back. All traffic stays on `127.0.0.1`.

## Quick Start

### A. Build the server

```sh
cd roblox-mcl/server
npm install
npm run build
```

This produces `server/dist/index.js`. The MCP client starts it for you — don't run it by hand.

### B. Register with your MCP client

**Claude Code:**

```sh
claude mcp add mcl -- node /absolute/path/to/roblox-mcl/server/dist/index.js
```

**Claude Desktop** — add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "mcl": {
      "command": "node",
      "args": ["/absolute/path/to/roblox-mcl/server/dist/index.js"]
    }
  }
}
```

Use the real absolute path to `dist/index.js` in both cases.

### C. Install the Studio plugin

Copy `plugin/MCL.lua` into your local Plugins folder:

- **Windows:** `%LOCALAPPDATA%\Roblox\Plugins`
- **macOS:** `~/Documents/Roblox/Plugins`

Or open Studio → **Plugins** tab → **Plugins Folder** button, and drop the file there. Restart Studio (or use the Plugins folder refresh) to load it.

Alternatively, build a `.rbxm` with [Rojo](https://rojo.space): `rojo build plugin/default.project.json -o MCL.rbxm` and put that in the Plugins folder instead.

### D. First run

1. Open a place in Studio and start a conversation in your MCP client (so the server is running).
2. Click the **MCL** toolbar button to open the MCL dock.
3. Check the header shows a green dot and **Connected**. (If not, see Troubleshooting.)
4. Ask Claude something like: *"Build a small obby with five platforms over lava in my open Roblox place."*
5. Watch the dock: Claude narrates what it's doing, and each edit shows up as an activity card while the scene changes live.

## Tool reference

| Tool | What it does |
|---|---|
| `get_studio_status` | Report connection state, pause state, and open place — no Studio round-trip. |
| `run_code` | Run arbitrary Luau in Studio (edit mode, plugin security); returns values and captured `print`/`warn` logs. |
| `say` | Post an assistant chat bubble to the Studio dock — the narration channel. |
| `read_chat` | Drain messages the user typed in the dock; can long-poll up to 120 s for the next one. |
| `get_tree` | Get the instance tree from a path, with depth, class filter, and service listing options. |
| `get_properties` | Read properties of one instance (a sensible default set, or an explicit list). |
| `set_properties` | Set properties on one instance; accepts tagged Roblox datatypes and shorthands. |
| `create_instance` | `Instance.new` with name/properties, parented last; returns the new path. |
| `delete_instances` | `:Destroy()` a list of paths; reports per-path failures. |
| `insert_asset` | Insert a marketplace asset by ID via `InsertService:LoadAsset`. |
| `find_instances` | Search descendants by name substring and/or class (`IsA`). |
| `set_waypoint` | Set a `ChangeHistoryService` waypoint to group edits into one undo boundary. |

Full argument schemas and semantics are in [PROTOCOL.md §5](PROTOCOL.md).

## Live chat while it builds

The dock is a two-way channel, not just a viewer:

- **Type instructions mid-build.** Anything you type in the dock queues on the server; Claude picks it up via `read_chat` between steps. "Make the tower taller", "no, red not orange", "stop adding scripts" — say it as it happens, and Claude course-corrects without restarting.
- **Claude narrates with `say`.** Before and during edits, Claude posts what it's about to do, so the activity feed reads as a running commentary you can interrupt.
- **Pause anytime.** The Pause button in the dock header stops the server from dispatching commands. Chat still flows both ways while paused, so you can discuss before pressing Resume.
- **Every action is one undo step.** Each mutating tool call is wrapped in a `ChangeHistoryService` recording, so Ctrl+Z rolls back exactly one Claude action at a time. (`set_waypoint` lets Claude group a batch of edits into a single step.)

## Troubleshooting

- **Dock says Disconnected** — the server isn't running (start a conversation in your MCP client so it launches the server), or the port doesn't match. Click the gear icon in the dock header and check the port equals the server's (default `28650`, or whatever `MCL_PORT` is set to).
- **"Http requests are not enabled" error** — enable **Game Settings → Security → Allow HTTP Requests** in Studio, then republish/retry.
- **Plugin permission prompt / permission error** — Studio gates plugin HTTP. Go to **Plugin Management** and allow the MCL plugin to access `127.0.0.1`.
- **Server exits with "port busy"** — something else owns 28650. Set `MCL_PORT` in the server's environment (e.g. in the MCP client config) and update the port in the dock's gear menu to match.

## Security notes

- **Localhost only.** The bridge binds `127.0.0.1` — nothing is exposed to your network or the internet.
- **Edit mode only.** The plugin refuses to run during play solo / running sessions; it only edits the open place in edit mode.
- **You can always pause.** The Pause button withholds all commands server-side until you press Resume.
- **Everything is undoable.** Every mutating action commits a `ChangeHistoryService` recording, so any change Claude makes is one Ctrl+Z away — even failed, partially-applied ones.
- `run_code` executes arbitrary Luau with plugin security in your Studio session. That's the point — but treat MCL like giving Claude the keyboard, and keep the dock where you can see it.

## Why not the stock Roblox MCP?

- **Structured scene tools, not just `run_code`.** `get_tree`, `get_properties`, `set_properties`, `create_instance`, `find_instances`, etc. give the model a typed, path-addressed view of the DataModel instead of forcing every read and edit through generated Lua strings. Fewer hallucinated APIs, better results.
- **Live activity feed.** Every command renders a card in the dock — tool, description, spinner, green/red status, duration. You always know what just happened.
- **In-Studio chat.** Talk to Claude from inside Studio while it builds; no alt-tabbing to redirect it.
- **Per-action undo.** Each action is a proper `ChangeHistoryService` step — selective rollback instead of all-or-nothing.
- **Pause.** A hard stop switch in the dock, honored server-side.
