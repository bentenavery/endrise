#!/usr/bin/env node
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { Bridge } from "./bridge.js";
import { registerTools } from "./tools.js";

const SERVER_VERSION = "0.1.0";
const DEFAULT_PORT = 28650;

const rawPort = process.env.MCL_PORT;
// Digits only — Number.parseInt would silently accept garbage like "8080abc".
const port =
  rawPort === undefined || rawPort === ""
    ? DEFAULT_PORT
    : /^\d+$/.test(rawPort)
      ? Number.parseInt(rawPort, 10)
      : NaN;
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  console.error(`MCL: invalid MCL_PORT value ${JSON.stringify(rawPort)} — expected a port number.`);
  process.exit(1);
}

const bridge = new Bridge(port, SERVER_VERSION);
await bridge.start();
console.error(`MCL: bridge listening on http://127.0.0.1:${port}`);

// §6: when the MCP client goes away, release the port — an orphaned server must
// never survive to EADDRINUSE the next launch.
let shuttingDown = false;
function shutdown(reason: string): void {
  if (shuttingDown) return;
  shuttingDown = true;
  console.error(`MCL: shutting down (${reason})`);
  bridge.stop();
  process.exit(0);
}
process.stdin.on("end", () => shutdown("stdin end"));
process.stdin.on("close", () => shutdown("stdin close"));
process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));

const server = new McpServer({ name: "mcl-studio-server", version: SERVER_VERSION });
registerTools(server, bridge);
server.server.onclose = () => shutdown("transport closed");
await server.connect(new StdioServerTransport());
console.error("MCL: MCP server ready on stdio");
