import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { FakePlugin } from "./fake-plugin.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SERVER_PATH = path.join(__dirname, "..", "dist", "index.js");
const PORT = 28911;
const SHUTDOWN_PORT = 28912;
const BASE = `http://127.0.0.1:${PORT}`;

const PAUSED_MESSAGE = "MCL is paused in Studio — press Resume in the MCL dock.";
const NOT_CONNECTED_MESSAGE =
  "Roblox Studio is not connected. Open Studio with the MCL plugin installed and check the MCL dock shows 'Connected'.";
const RECONNECTED_MESSAGE = "Studio plugin reconnected";

let passes = 0;
let failures = 0;

function check(name, cond, detail = "") {
  if (cond) {
    passes++;
    console.log(`PASS: ${name}`);
  } else {
    failures++;
    console.log(`FAIL: ${name}${detail ? ` — ${detail}` : ""}`);
  }
}

function parsePayload(result) {
  const text = result.content?.[0]?.text ?? "";
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

async function waitFor(fn, timeoutMs = 5000, everyMs = 50) {
  const start = Date.now();
  for (;;) {
    if (await fn()) return true;
    if (Date.now() - start > timeoutMs) return false;
    await sleep(everyMs);
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// Raw HTTP helpers for tests where the FakePlugin's poll loop is too smart.
async function rawPoll({ session, accepting = true, place, signal } = {}) {
  const res = await fetch(`${BASE}/mcl/poll`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ session, version: "1.0.0", accepting, place }),
    signal,
  });
  return { status: res.status, body: await res.json() };
}

async function rawResult(body) {
  const res = await fetch(`${BASE}/mcl/result`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  return res.json();
}

async function rawStatus() {
  const res = await fetch(`${BASE}/mcl/status`);
  return res.json();
}

async function testShutdown(env) {
  const childEnv = { ...env, MCL_PORT: String(SHUTDOWN_PORT) };
  const child = spawn(process.execPath, [SERVER_PATH], {
    env: childEnv,
    stdio: ["pipe", "pipe", "pipe"],
  });
  child.stdout.resume();
  child.stderr.resume();
  const exited = new Promise((resolve) => child.on("exit", (code) => resolve(code)));
  const statusUrl = `http://127.0.0.1:${SHUTDOWN_PORT}/mcl/status`;
  const up = await waitFor(async () => {
    try {
      return (await fetch(statusUrl)).ok;
    } catch {
      return false;
    }
  });
  check("shutdown: spawned server bridge came up", up);
  child.stdin.end();
  const code = await Promise.race([exited, sleep(3000).then(() => "timeout")]);
  check("shutdown: process exits within 3s of stdin EOF", code !== "timeout", String(code));
  if (code === "timeout") child.kill("SIGKILL");
  check("shutdown: exit code 0", code === 0, String(code));
  const released = await waitFor(async () => {
    try {
      await fetch(statusUrl);
      return false;
    } catch {
      return true;
    }
  }, 2000);
  check("shutdown: port released after exit", released);
}

async function main() {
  const env = Object.fromEntries(
    Object.entries(process.env).filter(([, v]) => v !== undefined),
  );
  env.MCL_PORT = String(PORT);

  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [SERVER_PATH],
    env,
  });
  const client = new Client({ name: "mcl-integration-test", version: "0.0.0" });
  await client.connect(transport);

  const call = async (name, args = {}) => {
    const result = await client.callTool({ name, arguments: args });
    return { result, payload: parsePayload(result), text: result.content?.[0]?.text ?? "" };
  };
  const status = async () => (await call("get_studio_status")).payload;

  const fake = new FakePlugin({ port: PORT });

  try {
    // (1) not connected initially
    let st = await status();
    check("initial get_studio_status connected:false", st.connected === false, JSON.stringify(st));
    check("initial place is null", st.place === null);
    const rcOffline = await call("run_code", { code: "return 1" });
    check(
      "run_code before plugin connects fails with not-connected message",
      rcOffline.result.isError === true && rcOffline.text === NOT_CONNECTED_MESSAGE,
      rcOffline.text,
    );

    // (2) fake plugin connects
    fake.start();
    const becameConnected = await waitFor(async () => (await status()).connected === true);
    check("connected:true once FakePlugin polls", becameConnected);
    st = await status();
    check(
      "place info surfaced",
      st.place?.name === "TestPlace" && st.place?.placeId === 123 && st.place?.gameId === 456,
      JSON.stringify(st.place),
    );
    check("pluginVersion surfaced", st.pluginVersion === "1.0.0", String(st.pluginVersion));
    check(
      "lastSeenSecondsAgo is a number",
      typeof st.lastSeenSecondsAgo === "number",
      String(st.lastSeenSecondsAgo),
    );

    // (3) run_code round-trip
    const rc = await call("run_code", { code: "return 1+1", description: "math" });
    check("run_code succeeds", rc.result.isError !== true, rc.text);
    check(
      "run_code returns round-trip",
      Array.isArray(rc.payload.returns) && rc.payload.returns[0] === "ran:return 1+1",
      JSON.stringify(rc.payload),
    );
    check(
      "run_code logs round-trip",
      rc.payload.logs?.[0]?.level === "print" && rc.payload.logs?.[0]?.text === "echo:return 1+1",
      JSON.stringify(rc.payload.logs),
    );
    check("run_code durationMs present", typeof rc.payload.durationMs === "number");
    const seen = fake.received.find((c) => c.tool === "run_code");
    check(
      "run_code args arrived at plugin",
      seen?.args?.code === "return 1+1" && seen?.args?.description === "math",
      JSON.stringify(seen?.args),
    );

    // (4) say round-trip
    const say1 = await call("say", { message: "starting the tower build" });
    check("say succeeds with {ok:true}", say1.result.isError !== true && say1.payload.ok === true, say1.text);
    check("say message reached plugin", fake.said.includes("starting the tower build"));

    // (5) chat: post, drain, and wait_seconds mid-wait resolution
    await fake.postChat("make the tower taller", 1723300000);
    const chat1 = await call("read_chat", {});
    check(
      "read_chat drains posted message",
      chat1.payload.messages?.length === 1 &&
        chat1.payload.messages[0].text === "make the tower taller" &&
        chat1.payload.messages[0].sentAt === 1723300000,
      JSON.stringify(chat1.payload),
    );
    check("read_chat reports studioConnected:true", chat1.payload.studioConnected === true);
    const chat2 = await call("read_chat", {});
    check("read_chat empty after drain", chat2.payload.messages?.length === 0, JSON.stringify(chat2.payload));

    const waitStart = Date.now();
    const waiting = call("read_chat", { wait_seconds: 10 });
    setTimeout(() => {
      fake.postChat("mid-wait reply").catch(() => {});
    }, 400);
    const chat3 = await waiting;
    const waitElapsed = Date.now() - waitStart;
    check(
      "read_chat wait_seconds resolves on mid-wait message",
      chat3.payload.messages?.length === 1 && chat3.payload.messages[0].text === "mid-wait reply",
      JSON.stringify(chat3.payload),
    );
    check("read_chat wait resolved early (not full wait)", waitElapsed < 5000, `${waitElapsed}ms`);

    // (6) pause gate
    fake.setAccepting(false);
    const pausedSeen = await waitFor(async () => (await status()).paused === true);
    check("paused:true visible after accepting:false", pausedSeen);
    const pauseStart = Date.now();
    const pausedRun = await call("run_code", { code: "return 1" });
    const pauseElapsed = Date.now() - pauseStart;
    check(
      "run_code fails fast while paused with exact message",
      pausedRun.result.isError === true && pausedRun.text === PAUSED_MESSAGE,
      pausedRun.text,
    );
    check("paused run_code failed fast (<1s)", pauseElapsed < 1000, `${pauseElapsed}ms`);
    const pausedSay = await call("say", { message: "still narrating while paused" });
    check(
      "say still works while paused",
      pausedSay.result.isError !== true &&
        pausedSay.payload.ok === true &&
        fake.said.includes("still narrating while paused"),
      pausedSay.text,
    );
    const pausedStatus = await call("get_studio_status");
    check("get_studio_status works while paused", pausedStatus.result.isError !== true && pausedStatus.payload.paused === true);
    fake.setAccepting(true);
    const resumed = await waitFor(async () => (await status()).paused === false);
    check("paused:false after resume", resumed);

    // (7) swallowed command times out cleanly
    fake.swallow = (cmd) => cmd.tool === "run_code";
    const toStart = Date.now();
    const swallowed = await call("run_code", { code: "return 'never'", timeout_seconds: 1 });
    const toElapsed = Date.now() - toStart;
    check(
      "swallowed run_code times out with isError",
      swallowed.result.isError === true && /timed out/i.test(swallowed.text),
      swallowed.text,
    );
    check("timeout fired near 1s", toElapsed >= 900 && toElapsed < 5000, `${toElapsed}ms`);
    fake.swallow = null;
    const after = await call("run_code", { code: "return 2" });
    check(
      "server healthy after timeout",
      after.result.isError !== true && after.payload.returns?.[0] === "ran:return 2",
      after.text,
    );

    // (8) /mcl/status shape (via FakePlugin.getStatus)
    const bridgeStatus = await fake.getStatus();
    check(
      "/mcl/status has the spec shape",
      bridgeStatus.ok === true &&
        bridgeStatus.pluginConnected === true &&
        typeof bridgeStatus.lastPollAgoMs === "number" &&
        bridgeStatus.paused === false &&
        bridgeStatus.queuedCommands === 0 &&
        bridgeStatus.queuedUserMessages === 0 &&
        bridgeStatus.place?.name === "TestPlace" &&
        typeof bridgeStatus.serverVersion === "string",
      JSON.stringify(bridgeStatus),
    );

    // (9) early wakeup: round-trip completes while the plugin is mid-hold
    await sleep(500); // let the fake's next long-poll park
    const holdStart = Date.now();
    const held = await call("run_code", { code: "return 'wake'" });
    const holdElapsed = Date.now() - holdStart;
    check("mid-hold run_code succeeds", held.result.isError !== true, held.text);
    check("mid-hold run_code beats the 20s hold (<5s)", holdElapsed < 5000, `${holdElapsed}ms`);

    // Raw-poll phases: stop the FakePlugin loop but stay inside the 35s connected window.
    const session = fake.session;
    await fake.stop();
    await sleep(100);

    // (10) dispatch-level pause gate: a queued command is withheld from accepting:false polls
    const gated = call("run_code", { code: "return 'gated'" });
    await waitFor(async () => (await rawStatus()).queuedCommands === 1);
    const pausedPoll = await rawPoll({ session, accepting: false });
    check(
      "accepting:false poll returns zero commands",
      pausedPoll.body.ok === true && pausedPoll.body.commands.length === 0,
      JSON.stringify(pausedPoll.body),
    );
    const resumedPoll = await rawPoll({ session, accepting: true });
    check(
      "following accepting:true poll gets the withheld command",
      resumedPoll.body.commands.length === 1 && resumedPoll.body.commands[0].tool === "run_code",
      JSON.stringify(resumedPoll.body),
    );
    await rawResult({
      id: resumedPoll.body.commands[0].id,
      ok: true,
      result: { returns: ["gated-ran"] },
      logs: [],
      durationMs: 1,
    });
    const gatedResult = await gated;
    check(
      "gated run_code resolves after resume",
      gatedResult.result.isError !== true && gatedResult.payload.returns?.[0] === "gated-ran",
      gatedResult.text,
    );

    // (11) one command per poll: two concurrent calls, one command per response
    const rcA = call("run_code", { code: "return 'A'" });
    const rcB = call("run_code", { code: "return 'B'" });
    await waitFor(async () => (await rawStatus()).queuedCommands === 2);
    const poll1 = await rawPoll({ session });
    check("first poll carries exactly one command", poll1.body.commands.length === 1, JSON.stringify(poll1.body));
    const poll2 = await rawPoll({ session });
    check("second poll carries exactly one command", poll2.body.commands.length === 1, JSON.stringify(poll2.body));
    check(
      "the two polls carried distinct commands",
      poll1.body.commands[0]?.id !== poll2.body.commands[0]?.id,
    );
    await rawResult({ id: poll1.body.commands[0].id, ok: true, result: { returns: ["one"] }, logs: [], durationMs: 1 });
    await rawResult({ id: poll2.body.commands[0].id, ok: true, result: { returns: ["two"] }, logs: [], durationMs: 1 });
    const [rcAResult, rcBResult] = await Promise.all([rcA, rcB]);
    check(
      "both concurrent run_code calls resolve",
      rcAResult.result.isError !== true && rcBResult.result.isError !== true,
      `${rcAResult.text} | ${rcBResult.text}`,
    );

    // (12) duplicate /mcl/result id: second post is {ok:true}, tool call sees only the first
    const dup = call("run_code", { code: "return 'dup'" });
    await waitFor(async () => (await rawStatus()).queuedCommands === 1);
    const dupPoll = await rawPoll({ session });
    const dupId = dupPoll.body.commands[0]?.id;
    check("duplicate-id test got its command", typeof dupId === "string", JSON.stringify(dupPoll.body));
    const firstPost = await rawResult({ id: dupId, ok: true, result: { returns: ["first"] }, logs: [], durationMs: 1 });
    const secondPost = await rawResult({ id: dupId, ok: true, result: { returns: ["second"] }, logs: [], durationMs: 1 });
    check("first result post answered {ok:true}", firstPost.ok === true, JSON.stringify(firstPost));
    check("duplicate result post answered {ok:true}", secondPost.ok === true, JSON.stringify(secondPost));
    const dupResult = await dup;
    check(
      "tool call saw only the first result",
      dupResult.result.isError !== true && dupResult.payload.returns?.[0] === "first",
      dupResult.text,
    );

    // (13) session change fails the dispatched command and flushes parked old-session polls
    const doomed = call("run_code", { code: "return 'doomed'" });
    await waitFor(async () => (await rawStatus()).queuedCommands === 1);
    const pollA1 = await rawPoll({ session: "sess-A" });
    check("session-A poll got the command", pollA1.body.commands.length === 1, JSON.stringify(pollA1.body));
    const parkedStart = Date.now();
    const parkedA2 = rawPoll({ session: "sess-A" });
    await sleep(300); // let the second session-A poll park
    const bController = new AbortController();
    const pollB = rawPoll({ session: "sess-B", signal: bController.signal }).catch(() => null);
    const doomedResult = await doomed;
    check(
      "dispatched command fails with reconnect message on session change",
      doomedResult.result.isError === true && doomedResult.text === RECONNECTED_MESSAGE,
      doomedResult.text,
    );
    const flushed = await Promise.race([parkedA2, sleep(3000).then(() => "timeout")]);
    check(
      "parked session-A poll flushed with empty commands",
      flushed !== "timeout" && flushed?.body?.ok === true && flushed?.body?.commands?.length === 0,
      JSON.stringify(flushed?.body ?? flushed),
    );
    check("parked poll flushed promptly, not on the 20s hold", Date.now() - parkedStart < 5000, `${Date.now() - parkedStart}ms`);
    bController.abort();
    await pollB;

    // (14) HTTP robustness
    const badJsonRes = await fetch(`${BASE}/mcl/poll`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "{not json",
    });
    const badJsonBody = await badJsonRes.json();
    check("malformed JSON -> 400 JSON", badJsonRes.status === 400 && badJsonBody.ok === false, JSON.stringify(badJsonBody));
    const missingSession = await rawPoll({ accepting: false });
    check(
      "poll without session -> 400 JSON",
      missingSession.status === 400 && missingSession.body.ok === false,
      JSON.stringify(missingSession.body),
    );
    const badPlace = await rawPoll({ session: "sess-B", accepting: false, place: { name: 5, placeId: "x" } });
    check("malformed place still polls ok", badPlace.status === 200 && badPlace.body.ok === true, JSON.stringify(badPlace.body));
    const placeAfter = (await rawStatus()).place;
    check("malformed place keeps the previous value", placeAfter?.name === "TestPlace", JSON.stringify(placeAfter));
    const notFoundRes = await fetch(`${BASE}/mcl/nope`);
    const notFoundBody = await notFoundRes.json();
    check("unknown route -> 404 JSON", notFoundRes.status === 404 && notFoundBody.ok === false, JSON.stringify(notFoundBody));
    let bigOutcome;
    try {
      const bigRes = await fetch(`${BASE}/mcl/poll`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ session: "sess-B", pad: "x".repeat(9 * 1024 * 1024) }),
      });
      bigOutcome = { status: bigRes.status, body: await bigRes.json() };
    } catch (err) {
      bigOutcome = { error: String(err) };
    }
    check(
      "oversized body -> 400 JSON {ok:false}, not a reset",
      bigOutcome.status === 400 && bigOutcome.body?.ok === false,
      JSON.stringify(bigOutcome),
    );

    // (15) FakePlugin never saw a batched dispatch
    check("FakePlugin never saw >1 command per poll", fake.errors.length === 0, JSON.stringify(fake.errors));
  } finally {
    await fake.stop().catch(() => {});
    await client.close().catch(() => {});
  }

  // (16) stdin EOF shutdown: a directly-spawned server exits and releases its port
  await testShutdown(env);

  console.log(`\n${passes} passed, ${failures} failed`);
  process.exit(failures > 0 ? 1 : 0);
}

main().catch((err) => {
  console.log(`FAIL: integration test crashed — ${err?.stack ?? err}`);
  process.exit(1);
});
