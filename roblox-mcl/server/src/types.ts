// "error" is reserved (§5): runtime errors surface in the failure message, not as log entries.
export type LogLevel = "print" | "warn" | "error";

export interface LogEntry {
  level: LogLevel;
  text: string;
}

export interface PlaceInfo {
  name: string;
  placeId: number;
  gameId: number;
}

// Validated shape of a POST /mcl/poll body. `null` fields mean "absent or malformed":
// the bridge keeps its previous value rather than surfacing garbage (§1).
export interface PollRequest {
  session: string;
  version: string | null;
  accepting: boolean;
  place: PlaceInfo | null;
}

export interface WireCommand {
  id: string;
  tool: string;
  args: Record<string, unknown>;
}

// Validated shape of a POST /mcl/result body (defaults already applied).
export interface ResultRequest {
  id: string;
  ok: boolean;
  result: unknown;
  error: string | null;
  logs: LogEntry[];
  durationMs: number;
}

export interface ChatMessage {
  text: string;
  sentAt: number;
}

export interface CommandOutcome {
  ok: boolean;
  result: unknown;
  error: string | null;
  logs: LogEntry[];
  durationMs: number;
}

export interface StudioStatus {
  connected: boolean;
  paused: boolean;
  place: PlaceInfo | null;
  pluginVersion: string | null;
  lastSeenSecondsAgo: number | null;
  queuedUserMessages: number;
  serverVersion: string;
}

export const PAUSED_MESSAGE =
  "MCL is paused in Studio — press Resume in the MCL dock.";

export const NOT_CONNECTED_MESSAGE =
  "Roblox Studio is not connected. Open Studio with the MCL plugin installed and check the MCL dock shows 'Connected'.";

export const RECONNECTED_MESSAGE = "Studio plugin reconnected";
