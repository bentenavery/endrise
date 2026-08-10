const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export class FakePlugin {
  constructor({ port, session, version = "1.0.0", place, handlers = {}, swallow = null } = {}) {
    if (!port) throw new Error("FakePlugin: port is required");
    this.base = `http://127.0.0.1:${port}`;
    this.session = session ?? `fake-${Math.random().toString(36).slice(2)}`;
    this.version = version;
    this.place = place ?? { name: "TestPlace", placeId: 123, gameId: 456 };
    this.accepting = true;
    this.running = false;
    this.received = [];
    this.said = [];
    this.errors = [];
    this.swallow = swallow;
    this.controller = null;
    this.loopPromise = null;
    this.handlers = {
      run_code: async (args) => ({
        ok: true,
        result: { returns: [`ran:${args.code}`] },
        logs: [{ level: "print", text: `echo:${args.code}` }],
        durationMs: 4.2,
      }),
      say: async (args) => {
        this.said.push(args.message);
        return { ok: true, result: { ok: true }, logs: [], durationMs: 1 };
      },
      set_waypoint: async () => ({ ok: true, result: { ok: true }, logs: [], durationMs: 1 }),
      ...handlers,
    };
  }

  start() {
    if (this.running) return;
    this.running = true;
    this.loopPromise = this.#loop();
  }

  async stop() {
    this.running = false;
    this.controller?.abort();
    await this.loopPromise?.catch(() => {});
  }

  setAccepting(value) {
    this.accepting = value;
    // Abort the in-flight long poll so the new accepting flag reaches the server immediately.
    this.controller?.abort();
  }

  async #loop() {
    while (this.running) {
      this.controller = new AbortController();
      let data;
      try {
        const res = await fetch(`${this.base}/mcl/poll`, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({
            session: this.session,
            version: this.version,
            accepting: this.accepting,
            place: this.place,
          }),
          signal: this.controller.signal,
        });
        data = await res.json();
      } catch {
        if (!this.running) return;
        await sleep(50);
        continue;
      }
      const commands = data?.commands ?? [];
      // §1: the server dispatches at most one command per poll response.
      if (commands.length > 1) {
        this.errors.push(`poll response carried ${commands.length} commands`);
      }
      for (const cmd of commands) {
        await this.#execute(cmd);
      }
      // Paused polls return immediately; back off so the loop does not spin.
      if (!this.accepting) await sleep(25);
    }
  }

  async #execute(cmd) {
    this.received.push(cmd);
    if (this.swallow?.(cmd)) return;
    const handler = this.handlers[cmd.tool];
    let outcome;
    if (!handler) {
      outcome = { ok: false, error: `FakePlugin: no handler for ${cmd.tool}`, logs: [], durationMs: 0 };
    } else {
      try {
        outcome = await handler(cmd.args, cmd);
      } catch (err) {
        outcome = { ok: false, error: String(err), logs: [], durationMs: 0 };
      }
    }
    await this.postResult({
      id: cmd.id,
      ok: outcome.ok !== false,
      result: outcome.result ?? null,
      error: outcome.error ?? null,
      logs: outcome.logs ?? [],
      durationMs: outcome.durationMs ?? 0,
    });
  }

  async postResult(body) {
    return this.#post("/mcl/result", body);
  }

  async postChat(text, sentAt = Math.floor(Date.now() / 1000)) {
    return this.#post("/mcl/chat", { text, sentAt });
  }

  async getStatus() {
    const res = await fetch(`${this.base}/mcl/status`);
    return res.json();
  }

  async #post(path, body) {
    const res = await fetch(`${this.base}${path}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    });
    return res.json();
  }
}
