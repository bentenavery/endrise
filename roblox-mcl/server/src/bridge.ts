import http from "node:http";
import { randomUUID } from "node:crypto";
import {
  ChatMessage,
  CommandOutcome,
  LogEntry,
  PlaceInfo,
  PollRequest,
  RECONNECTED_MESSAGE,
  ResultRequest,
  StudioStatus,
  WireCommand,
} from "./types.js";

const POLL_HOLD_MS = 20_000;
// §1: plugin counts as connected if any poll arrived within the last 35 seconds.
const CONNECTED_WINDOW_MS = 35_000;
const CHAT_CAP = 200;
const MAX_BODY_BYTES = 8 * 1024 * 1024;

interface PendingCommand {
  id: string;
  tool: string;
  args: Record<string, unknown>;
  dispatched: boolean;
  resolve: (outcome: CommandOutcome) => void;
  reject: (err: Error) => void;
  timer: NodeJS.Timeout;
}

// Only accepting polls park — paused polls are answered immediately in handlePoll.
interface ParkedPoll {
  res: http.ServerResponse;
  timer: NodeJS.Timeout;
}

interface ChatWaiter {
  fire: () => void;
  timer: NodeJS.Timeout;
}

function sendJson(
  res: http.ServerResponse,
  status: number,
  body: unknown,
  headers: Record<string, string> = {},
): void {
  if (res.writableEnded || res.destroyed) return;
  res.writeHead(status, { "Content-Type": "application/json", ...headers });
  res.end(JSON.stringify(body));
}

function parsePlace(value: unknown): PlaceInfo | null {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return null;
  const p = value as Record<string, unknown>;
  if (typeof p.name !== "string") return null;
  if (typeof p.placeId !== "number" || !Number.isFinite(p.placeId)) return null;
  if (typeof p.gameId !== "number" || !Number.isFinite(p.gameId)) return null;
  return { name: p.name, placeId: p.placeId, gameId: p.gameId };
}

// Returns null when `session` is invalid (400 at the caller). A malformed `place`
// parses to null so the bridge keeps its previous value (§1).
function parsePollRequest(body: Record<string, unknown>): PollRequest | null {
  if (typeof body.session !== "string" || body.session === "") return null;
  return {
    session: body.session,
    version: typeof body.version === "string" ? body.version : null,
    accepting: body.accepting !== false,
    place: parsePlace(body.place),
  };
}

function parseResultRequest(body: Record<string, unknown>): ResultRequest {
  return {
    id: typeof body.id === "string" ? body.id : "",
    ok: body.ok === true,
    result: body.result ?? null,
    error: typeof body.error === "string" ? body.error : null,
    logs: Array.isArray(body.logs) ? (body.logs as LogEntry[]) : [],
    durationMs: typeof body.durationMs === "number" ? body.durationMs : 0,
  };
}

export class Bridge {
  private server: http.Server | null = null;
  private pending = new Map<string, PendingCommand>();
  private queue: PendingCommand[] = [];
  private parked: ParkedPoll[] = [];
  private chatQueue: ChatMessage[] = [];
  private chatWaiters: ChatWaiter[] = [];
  private session: string | null = null;
  private place: PlaceInfo | null = null;
  private pluginVersion: string | null = null;
  private lastPollAt: number | null = null;
  private paused = false;
  private stopped = false;

  constructor(
    private readonly port: number,
    readonly serverVersion: string,
  ) {}

  start(): Promise<void> {
    return new Promise((resolve) => {
      this.server = http.createServer((req, res) => {
        try {
          this.route(req, res);
        } catch (err) {
          console.error(`MCL: bridge request error: ${String(err)}`);
          sendJson(res, 500, { ok: false, error: "internal error" });
        }
      });
      this.server.on("error", (err: NodeJS.ErrnoException) => {
        if (err.code === "EADDRINUSE") {
          console.error(
            `MCL: cannot start — port ${this.port} on 127.0.0.1 is already in use. ` +
              `Another MCL server (or other program) is bound to it. ` +
              `Stop it or set MCL_PORT to a free port (and update the port in the Studio MCL dock).`,
          );
        } else {
          console.error(`MCL: bridge failed to start: ${err.message}`);
        }
        process.exit(1);
      });
      this.server.listen(this.port, "127.0.0.1", () => resolve());
    });
  }

  // Idempotent shutdown: flush parked polls, reject pending commands, close the server.
  stop(): void {
    if (this.stopped) return;
    this.stopped = true;
    this.flushParked();
    for (const cmd of [...this.pending.values()]) {
      clearTimeout(cmd.timer);
      this.pending.delete(cmd.id);
      cmd.reject(new Error("MCL server is shutting down"));
    }
    this.queue.length = 0;
    for (const waiter of this.chatWaiters.splice(0)) {
      clearTimeout(waiter.timer);
      waiter.fire();
    }
    this.server?.close();
    this.server = null;
  }

  isConnected(): boolean {
    // §1: a dispatched command still awaiting its result is proof of liveness — a
    // long-running run_code starves the poll loop past the 35s window.
    for (const cmd of this.pending.values()) {
      if (cmd.dispatched) return true;
    }
    return this.lastPollAt !== null && Date.now() - this.lastPollAt < CONNECTED_WINDOW_MS;
  }

  isPaused(): boolean {
    return this.paused;
  }

  studioStatus(): StudioStatus {
    return {
      connected: this.isConnected(),
      paused: this.paused,
      place: this.place,
      pluginVersion: this.pluginVersion,
      lastSeenSecondsAgo:
        this.lastPollAt === null ? null : Math.round((Date.now() - this.lastPollAt) / 1000),
      queuedUserMessages: this.chatQueue.length,
      serverVersion: this.serverVersion,
    };
  }

  execute(tool: string, args: Record<string, unknown>, timeoutMs: number): Promise<CommandOutcome> {
    return new Promise((resolve, reject) => {
      const cmd: PendingCommand = {
        id: randomUUID(),
        tool,
        args,
        dispatched: false,
        resolve,
        reject,
        timer: setTimeout(() => this.expire(cmd, timeoutMs), timeoutMs),
      };
      this.pending.set(cmd.id, cmd);
      this.queue.push(cmd);
      this.wakeParked();
    });
  }

  readChat(waitSeconds: number): Promise<{ messages: ChatMessage[]; studioConnected: boolean }> {
    const drain = () => ({
      messages: this.chatQueue.splice(0, this.chatQueue.length),
      studioConnected: this.isConnected(),
    });
    if (this.chatQueue.length > 0 || waitSeconds <= 0) {
      return Promise.resolve(drain());
    }
    return new Promise((resolve) => {
      const waiter: ChatWaiter = {
        fire: () => resolve(drain()),
        timer: setTimeout(() => {
          const i = this.chatWaiters.indexOf(waiter);
          if (i !== -1) this.chatWaiters.splice(i, 1);
          resolve(drain());
        }, waitSeconds * 1000),
      };
      this.chatWaiters.push(waiter);
    });
  }

  private expire(cmd: PendingCommand, timeoutMs: number): void {
    this.pending.delete(cmd.id);
    const i = this.queue.indexOf(cmd);
    if (i !== -1) this.queue.splice(i, 1);
    cmd.reject(
      new Error(
        `${cmd.tool} timed out after ${timeoutMs / 1000}s waiting for Roblox Studio to respond.`,
      ),
    );
  }

  // A queued command is dispatchable to a paused poll only if it is `say` (§3/§6 pause gate).
  private takeEligible(accepting: boolean): PendingCommand | null {
    const i = this.queue.findIndex((c) => accepting || c.tool === "say");
    if (i === -1) return null;
    const [cmd] = this.queue.splice(i, 1);
    return cmd;
  }

  private respondPoll(res: http.ServerResponse, cmd: PendingCommand | null): void {
    const commands: WireCommand[] = [];
    if (cmd) {
      cmd.dispatched = true;
      commands.push({ id: cmd.id, tool: cmd.tool, args: cmd.args });
    }
    sendJson(res, 200, { ok: true, commands });
  }

  private wakeParked(): void {
    while (this.parked.length > 0) {
      const slot = this.parked[0];
      if (slot.res.destroyed || slot.res.writableEnded) {
        clearTimeout(slot.timer);
        this.parked.shift();
        continue;
      }
      // Parked polls are always accepting (paused polls never park).
      const cmd = this.takeEligible(true);
      if (!cmd) return;
      clearTimeout(slot.timer);
      this.parked.shift();
      this.respondPoll(slot.res, cmd);
    }
  }

  private flushParked(): void {
    for (const slot of this.parked.splice(0)) {
      clearTimeout(slot.timer);
      this.respondPoll(slot.res, null);
    }
  }

  private failDispatched(reason: string): void {
    for (const cmd of [...this.pending.values()]) {
      if (!cmd.dispatched) continue;
      clearTimeout(cmd.timer);
      this.pending.delete(cmd.id);
      cmd.reject(new Error(reason));
    }
  }

  private handlePoll(res: http.ServerResponse, body: Record<string, unknown>): void {
    const poll = parsePollRequest(body);
    if (poll === null) {
      sendJson(res, 400, { ok: false, error: "session must be a string" });
      return;
    }
    if (this.session !== null && poll.session !== this.session) {
      this.failDispatched(RECONNECTED_MESSAGE);
      // §1: flush zombie long-polls left open by the old plugin instance so they
      // cannot steal the next command.
      this.flushParked();
    }
    this.session = poll.session;
    if (poll.version !== null) this.pluginVersion = poll.version;
    if (poll.place !== null) this.place = poll.place;
    this.lastPollAt = Date.now();
    this.paused = !poll.accepting;

    const cmd = this.takeEligible(poll.accepting);
    if (cmd) {
      this.respondPoll(res, cmd);
      return;
    }
    if (!poll.accepting) {
      // Paused: answer immediately so the plugin's pause state round-trips fast (§1).
      this.respondPoll(res, null);
      return;
    }

    const slot: ParkedPoll = {
      res,
      timer: setTimeout(() => {
        const i = this.parked.indexOf(slot);
        if (i === -1) return;
        this.parked.splice(i, 1);
        this.respondPoll(res, null);
      }, POLL_HOLD_MS),
    };
    this.parked.push(slot);
    res.on("close", () => {
      if (res.writableFinished) return;
      clearTimeout(slot.timer);
      const i = this.parked.indexOf(slot);
      if (i !== -1) this.parked.splice(i, 1);
    });
  }

  private handleResult(res: http.ServerResponse, body: Record<string, unknown>): void {
    const result = parseResultRequest(body);
    const cmd = this.pending.get(result.id);
    if (cmd) {
      clearTimeout(cmd.timer);
      this.pending.delete(result.id);
      const i = this.queue.indexOf(cmd);
      if (i !== -1) this.queue.splice(i, 1);
      cmd.resolve({
        ok: result.ok,
        result: result.result,
        error: result.error,
        logs: result.logs,
        durationMs: result.durationMs,
      });
    }
    // Unknown or duplicate id: idempotent success (§1).
    sendJson(res, 200, { ok: true });
  }

  private handleChat(res: http.ServerResponse, body: Record<string, unknown>): void {
    if (typeof body.text !== "string") {
      sendJson(res, 400, { ok: false, error: "text must be a string" });
      return;
    }
    const message: ChatMessage = {
      text: body.text,
      sentAt: typeof body.sentAt === "number" ? body.sentAt : Math.floor(Date.now() / 1000),
    };
    this.chatQueue.push(message);
    while (this.chatQueue.length > CHAT_CAP) this.chatQueue.shift();
    const waiter = this.chatWaiters.shift();
    if (waiter) {
      clearTimeout(waiter.timer);
      waiter.fire();
    }
    sendJson(res, 200, { ok: true });
  }

  private statusBody(): Record<string, unknown> {
    return {
      ok: true,
      pluginConnected: this.isConnected(),
      lastPollAgoMs: this.lastPollAt === null ? null : Date.now() - this.lastPollAt,
      paused: this.paused,
      queuedCommands: this.queue.length,
      queuedUserMessages: this.chatQueue.length,
      place: this.place,
      serverVersion: this.serverVersion,
    };
  }

  private route(req: http.IncomingMessage, res: http.ServerResponse): void {
    const url = (req.url ?? "").split("?")[0];
    if (req.method === "GET" && url === "/mcl/status") {
      sendJson(res, 200, this.statusBody());
      return;
    }
    if (req.method === "POST" && (url === "/mcl/poll" || url === "/mcl/result" || url === "/mcl/chat")) {
      this.readJsonBody(req, res, (body) => {
        try {
          if (url === "/mcl/poll") this.handlePoll(res, body);
          else if (url === "/mcl/result") this.handleResult(res, body);
          else this.handleChat(res, body);
        } catch (err) {
          console.error(`MCL: bridge handler error on ${url}: ${String(err)}`);
          sendJson(res, 500, { ok: false, error: "internal error" });
        }
      });
      return;
    }
    sendJson(res, 404, { ok: false, error: `unknown route: ${req.method} ${url}` });
  }

  private readJsonBody(
    req: http.IncomingMessage,
    res: http.ServerResponse,
    onBody: (body: Record<string, unknown>) => void,
  ): void {
    const chunks: Buffer[] = [];
    let size = 0;
    let overflowed = false;
    req.on("data", (chunk: Buffer) => {
      if (overflowed) return;
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        overflowed = true;
        chunks.length = 0;
        // Respond mid-upload so the client sees a JSON 400, not ECONNRESET; only
        // destroy the socket once the response has flushed (§1).
        const socket = req.socket;
        res.on("finish", () => socket.destroy());
        sendJson(res, 400, { ok: false, error: "body too large" }, { Connection: "close" });
        return;
      }
      chunks.push(chunk);
    });
    req.on("error", (err) => {
      console.error(`MCL: bridge request stream error: ${err.message}`);
    });
    req.on("end", () => {
      if (overflowed) return;
      let parsed: unknown;
      try {
        parsed = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      } catch {
        sendJson(res, 400, { ok: false, error: "malformed JSON" });
        return;
      }
      if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
        sendJson(res, 400, { ok: false, error: "body must be a JSON object" });
        return;
      }
      onBody(parsed as Record<string, unknown>);
    });
  }
}
