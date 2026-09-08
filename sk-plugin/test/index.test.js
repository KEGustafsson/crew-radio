// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const plugin = require("../index");
const { ChannelCrypto } = require("../lib/crypto");
const P = require("../lib/packet");

const KEY = "north-star-2026";
const crypto = ChannelCrypto.forChannelKeySync(KEY);

/** A fake network link: records what the plugin sends, lets the test inject packets. */
class FakeLink extends EventEmitter {
  constructor(opts) {
    super();
    this.opts = opts;
    this.sent = [];
    this.closed = false;
    FakeLink.last = this;
  }
  open() {
    if (FakeLink.failOpen) return Promise.reject(new Error("no usable IPv4 interface"));
    return Promise.resolve({ iface: "fake0", address: "10.0.0.2", broadcast: "10.0.0.255" });
  }
  send(buf) { this.sent.push(Buffer.from(buf)); return true; }
  close() { this.closed = true; }
}

/** A fake speech engine: 100 ms of a tone per call, instantly; records what it was asked. */
class FakeTts {
  constructor(opts) { this.opts = opts; this.texts = []; this.stopped = 0; FakeTts.last = this; }
  synthesize(text) {
    this.texts.push(text);
    const pcm = Buffer.alloc(3200);
    for (let i = 0; i < 1600; i++) pcm.writeInt16LE(Math.round(6000 * Math.sin(i / 3)), i * 2);
    return Promise.resolve(pcm);
  }
  stop() { this.stopped++; }
}

/** The subset of the Signal K plugin API the plugin uses, with everything recorded. */
function fakeApp() {
  const app = {
    log: [], errors: [], status: [], deltas: [], props: {}, subs: [], puts: {},
    debug: (m) => app.log.push(m),
    error: (m) => app.errors.push(m),
    setPluginStatus: (m) => app.status.push(m),
    setPluginError: (m) => app.errors.push(`plugin error: ${m}`),
    handleMessage: (id, delta) => app.deltas.push({ id, delta }),
    getSelfPath: (p) => (p === "name" ? "Sirius" : undefined),
    getDataDirPath: () => require("node:os").tmpdir(),
    emitPropertyValue: (name, value) => { app.props[name] = value; },
    registerPutHandler: (context, path, handler) => { app.puts[path] = handler; },
    subscriptionmanager: { subscribe: (sub, unsubs, onErr, cb) => { app.subs.push({ sub, cb }); unsubs.push(() => app.subs.pop()); } },
  };
  return app;
}

const flush = (n = 3) => new Promise((r) => { let i = 0; const step = () => (++i >= n ? r() : setImmediate(step)); setImmediate(step); });
const until = async (cond, ms = 3000) => { const t0 = Date.now(); while (!cond()) { if (Date.now() - t0 > ms) throw new Error("timeout"); await new Promise((r) => setTimeout(r, 5)); } };
const deps = { LanLink: FakeLink, Tts: FakeTts };
const phonePacket = (senderId, seq, payload, codec = P.Codec.HELLO, time) => {
  const h = P.encodeHeader({ senderId, seq, codec, ttl: 4, ...(time !== undefined ? { time } : {}) });
  return Buffer.concat([h, crypto.seal(P.aadOf(h), payload)]);
};

function lastValue(app, path) {
  for (let i = app.deltas.length - 1; i >= 0; i--) {
    for (const v of app.deltas[i].delta.updates[0].values) if (v.path === path) return v.value;
  }
  return undefined;
}

/** A fake Express router capturing the routes the plugin registers; with `access` when asked, as current servers have. */
function fakeRouter(withAccess = false) {
  const routes = {};
  const levels = {};
  const registrar = (level) => ({
    post: (p, h) => { routes[`POST ${p}`] = h; levels[`POST ${p}`] = level; return registrar(level); },
    get: (p, h) => { routes[`GET ${p}`] = h; levels[`GET ${p}`] = level; return registrar(level); },
  });
  const r = { routes, levels, ...registrar("admin (default)") };
  if (withAccess) r.access = (level) => registrar(level);
  return r;
}
function fakeRes() {
  const res = { code: 200, body: null, status(c) { res.code = c; return res; }, json(b) { res.body = b; res.done?.(); return res; } };
  res.finished = new Promise((r) => (res.done = r));
  return res;
}
/** A request with a streamed body, as Express hands one over when nothing parsed it. */
function streamReq(headers, chunks, body) {
  const req = new EventEmitter();
  req.headers = headers;
  req.body = body;
  req.setEncoding = () => {};
  req.destroyed = 0;
  req.destroy = () => { req.destroyed++; };
  setImmediate(() => { for (const c of chunks) req.emit("data", c); req.emit("end"); });
  return req;
}

test("metadata: id, schema with the key required, the voices, password widget, vessel name as the default", () => {
  const app = fakeApp();
  const p = plugin(app);
  assert.equal(p.id, "signalk-crewradio");
  const schema = p.schema();
  assert.deepEqual(schema.required, ["channelKey"]);
  assert.deepEqual(schema.properties.voice.enum, ["slt", "kal16", "rms", "awb"]);
  assert.match(schema.properties.nodeName.description, /Sirius/);
  assert.equal(p.uiSchema().channelKey["ui:widget"], "password");
});

test("without a channel key the plugin waits, says so in its status, and does nothing else", () => {
  const app = fakeApp();
  const p = plugin(app, deps);
  p.start({});
  assert.match(app.status[0], /Waiting for the channel key/);
  assert.deepEqual(app.errors, []);
  assert.equal(FakeLink.last, undefined);
  p.stop();
  p.start(undefined);           // the server may pass nothing at all; restart must be as calm
  assert.deepEqual(app.errors, []);
  p.stop();
});

test("settings are validated: out-of-range values fall back to the defaults and are named", () => {
  const app = fakeApp();
  const cfg = plugin.withDefaults({ port: 80, hops: 0, group: "10.0.0.1", rate: "fast", waitForSilenceMs: -1, bridge: { repeatSec: 9999 }, nodeName: " Sea‮ Wolf " }, app);
  assert.equal(cfg.port, 47474);
  assert.equal(cfg.hops, 4);
  assert.equal(cfg.group, "239.255.42.1");
  assert.equal(cfg.rate, 1);
  assert.equal(cfg.waitForSilenceMs, 2000);
  assert.equal(cfg.bridge.repeatSec, 30);
  assert.equal(cfg.nodeName, "Sea Wolf", "sanitised like a hello");
  assert.equal(cfg.warnings.length, 6);
  assert.ok(cfg.warnings.some((w) => /UDP port 80 is not 1024-65535, using 47474/.test(w)), cfg.warnings.join(" | "));
  const good = plugin.withDefaults({ port: "47475", hops: 16, group: "224.0.0.251", rate: 0.8 }, app);
  assert.deepEqual([good.port, good.hops, good.group, good.rate, good.warnings], [47475, 16, "224.0.0.251", 0.8, []]);
  assert.equal(plugin.withDefaults({ port: NaN }, app).warnings.length, 1);
  assert.ok(plugin.isMulticastV4("239.255.255.255"));
  assert.ok(!plugin.isMulticastV4("240.0.0.1"));
  assert.ok(!plugin.isMulticastV4("239.256.0.1"));
  assert.ok(!plugin.isMulticastV4("ff02::1"));
  const p = plugin(app, deps);
  p.start({ channelKey: KEY, port: 80 });
  assert.ok(app.errors.some((e) => /Settings: .*UDP port 80/.test(e)), app.errors.join(" | "));
  assert.match(app.status.at(-1), /check settings: UDP port 80/);
  p.stop();
});

test("on start it derives the key off the main thread, joins the channel, sends hellos, publishes the roster and reports status; stop tears down", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, deps);
  p.start({ channelKey: KEY, hops: 3 });
  assert.match(app.status[0], /starting/, "start() returns before the key is derived");
  await until(() => FakeLink.last && FakeLink.last.sent.length > 0);
  const link = FakeLink.last;
  assert.deepEqual(link.opts, { group: "239.255.42.1", port: 47474, iface: "auto" });
  const h = P.parseHeader(link.sent[0]);
  assert.equal(h.codec, P.Codec.HELLO);
  assert.equal(h.ttl, 3);
  assert.ok(Math.abs(h.time - Date.now() / 1000) < 5);
  const hello = P.decodeHello(crypto.open(P.aadOf(link.sent[0]), link.sent[0].subarray(P.HEADER)));
  assert.deepEqual(hello, { name: "Sirius", transports: 1, ttl: 3, versionCode: 0 });
  assert.equal(lastValue(app, "communication.crewradio.online"), 0);
  assert.match(app.status.at(-1), /0 online/);
  assert.match(app.status.at(-1), /voice slt/);

  link.emit("packet", phonePacket(42, 0, P.encodeHello({ name: "Anna", transports: 3, ttl: 4, versionCode: 134 })), { address: "10.0.0.7" });
  await flush();
  assert.equal(lastValue(app, "communication.crewradio.online"), 1);
  assert.deepEqual(lastValue(app, "communication.crewradio.nodes"), [{ name: "Anna", hops: 0, transports: 3, talking: false, versionCode: 134 }]);
  assert.match(app.status.at(-1), /1 online/);

  p.stop();
  assert.equal(link.closed, true);
  assert.equal(FakeTts.last.stopped, 1, "the speech worker is ended");
  assert.equal(app.status.at(-1), "Stopped");
  assert.equal(lastValue(app, "communication.crewradio.online"), 0);
  assert.equal(app.props["signalk-crewradio.api"], null, "the in-process api is withdrawn");
});

test("say() through the in-process api: chime, speech, tail as paced frames on the channel; the speaking path follows; long texts are cut", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, deps);
  p.start({ channelKey: KEY, waitForSilenceMs: 0, nodeName: "Boat" });
  await until(() => FakeLink.last);
  const api = app.props["signalk-crewradio.api"];
  assert.equal(api.version, 1);
  const r = await api.say({ text: "Depth 2.5 m" });
  assert.equal(r.ok, true);
  assert.equal(r.queued, 0);
  assert.equal(r.priority, "normal");
  assert.equal(r.truncated, false);
  assert.deepEqual(FakeTts.last.texts, ["Depth 2.5 m"]);
  await until(() => lastValue(app, "communication.crewradio.speaking") === false && FakeLink.last.sent.length > 30, 5000);
  const frames = FakeLink.last.sent.map((b) => P.parseHeader(b)).filter((h) => h.codec === P.Codec.PCM);
  // chime (~460 ms) + 100 ms tone + 150 ms tail, in 20 ms frames
  assert.ok(frames.length >= 33 && frames.length <= 38, `${frames.length} frames`);
  assert.deepEqual(frames.map((h) => h.seq), frames.map((_, i) => i));
  assert.ok(app.status.some((s) => /announcing/.test(s)));
  await assert.rejects(api.say({ text: "" }), /text is required/);
  const long = await api.say({ text: "x".repeat(501) });
  assert.equal(long.truncated, true, "cut to 500, not refused: an alarm's text is still an alarm");
  assert.equal(FakeTts.last.texts.at(-1).length, 500);
  p.stop();
  await assert.rejects(api.say({ text: "late" }), /not running/);
});

test("say() through PUT and REST, plain text or {text, priority}; urgent goes first; routes carry their access level", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, deps);
  p.start({ channelKey: KEY, waitForSilenceMs: 0 });
  await until(() => FakeLink.last);
  const put = app.puts["communication.crewradio.say"];
  assert.equal(typeof put, "function");
  const results = [];
  const pending = put("vessels.self", "communication.crewradio.say", "Hello crew", (r) => results.push(r));
  assert.equal(pending.state, "PENDING");
  await until(() => results.length === 1);
  assert.equal(results[0].statusCode, 200);
  assert.equal(JSON.parse(results[0].message).ok, true);
  put("vessels.self", "communication.crewradio.say", { text: "" }, (r) => results.push(r));
  await until(() => results.length === 2);
  assert.equal(results[1].statusCode, 400);

  const router = fakeRouter(true);
  p.registerWithRouter(router);
  assert.deepEqual(router.levels, { "GET /status": "readonly", "GET /say": "readonly", "POST /say": "readwrite" });
  const res1 = fakeRes();
  router.routes["POST /say"]({ headers: { "content-type": "application/json" }, body: { text: "Man overboard", priority: "urgent" } }, res1);
  await res1.finished;
  assert.equal(res1.code, 200);
  assert.equal(res1.body.priority, "urgent");
  assert.equal(res1.body.queued, 0, "urgent: ahead of what waits");
  const res2 = fakeRes();
  router.routes["POST /say"](streamReq({ "content-type": "text/plain; charset=utf-8" }, ["Plain ", "text body"], {}), res2);
  await res2.finished;
  assert.equal(res2.code, 200);
  assert.ok(FakeTts.last.texts.includes("Plain text body"));
  const res3 = fakeRes();
  router.routes["GET /say"]({}, res3);
  assert.deepEqual(res3.body.voices, ["slt", "kal16", "rms", "awb"]);
  assert.equal(res3.body.voice, "slt");
  assert.equal(res3.body.maxText, 500);
  const older = fakeRouter(false);
  p.registerWithRouter(older);
  assert.equal(older.levels["POST /say"], "admin (default)", "a server without router.access keeps its default protection");
  p.stop();
});

test("POST /say body handling: JSON by content type, an unparsed {} read from the stream, 415 for other types, 413 over the limit, 400 for bad JSON", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, deps);
  p.start({ channelKey: KEY, waitForSilenceMs: 0 });
  await until(() => FakeLink.last);
  const router = fakeRouter(true);
  p.registerWithRouter(router);
  const post = router.routes["POST /say"];
  // Express 4 left {} for a JSON body it did not parse: the stream is read and parsed here
  let res = fakeRes();
  post(streamReq({ "content-type": "application/json" }, ['{"text": "From the ', 'stream"}'], {}), res);
  await res.finished;
  assert.equal(res.code, 200);
  assert.ok(FakeTts.last.texts.includes("From the stream"));
  // a real, empty {} (the stream consumed) is a request without a text
  res = fakeRes();
  const consumed = streamReq({ "content-type": "application/json" }, [], {}); consumed.readableEnded = true;
  post(consumed, res);
  await res.finished;
  assert.equal(res.code, 400);
  assert.match(res.body.error, /text is required/);
  // req.is, when Express provides it, decides
  res = fakeRes();
  post({ is: (t) => (t === "text/*" ? "text/plain" : false), body: "Through req.is", headers: {} }, res);
  await res.finished;
  assert.equal(res.code, 200);
  assert.ok(FakeTts.last.texts.includes("Through req.is"));
  // a form post, or no content type, is not a body the plugin reads
  res = fakeRes();
  post({ headers: { "content-type": "application/x-www-form-urlencoded" }, body: {} }, res);
  assert.equal(res.code, 415);
  res = fakeRes();
  post({ is: () => false, body: {} }, res);
  assert.equal(res.code, 415);
  // over the limit: 413 answered, the stream left alone
  res = fakeRes();
  const big = streamReq({ "content-type": "text/plain" }, ["y".repeat(6000), "y".repeat(6000), "tail"], {});
  post(big, res);
  await res.finished;
  assert.equal(res.code, 413);
  assert.match(res.body.error, /over 10000/);
  assert.equal(FakeTts.last.texts.some((t) => t.length > 500), false, "nothing of it was spoken");
  res = fakeRes();
  post({ headers: { "content-type": "text/plain" }, body: Buffer.alloc(10_001, 0x79) }, res);
  assert.equal(res.code, 413, "a parsed buffer over the limit too");
  // bad JSON
  res = fakeRes();
  post(streamReq({ "content-type": "application/json" }, ["{not json"], {}), res);
  await res.finished;
  assert.equal(res.code, 400);
  assert.match(res.body.error, /not JSON/);
  // a parsed text body, as express.text() would leave it, and a parsed Buffer
  res = fakeRes();
  post({ headers: { "content-type": "text/plain" }, body: "Parsed text" }, res);
  await res.finished;
  assert.equal(res.code, 200);
  res = fakeRes();
  post({ headers: { "content-type": "application/json" }, body: Buffer.from('{"text":"Raw buffer"}') }, res);
  await res.finished;
  assert.equal(res.code, 200);
  assert.ok(FakeTts.last.texts.includes("Raw buffer"));
  p.stop();
});

test("each door has its own rate budget, and the queue's room is checked before any speech is made", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, deps);
  p.start({ channelKey: KEY, waitForSilenceMs: 0, chime: false });
  await until(() => FakeLink.last);
  const api = app.props["signalk-crewradio.api"];
  // urgent: one plays, five wait, the seventh is refused before synthesis
  for (let i = 0; i < 6; i++) assert.equal((await api.say({ text: `urgent ${i}`, priority: "urgent" })).ok, true);
  assert.equal(FakeTts.last.texts.length, 6);
  await assert.rejects(api.say({ text: "one too many", priority: "urgent" }), /queue full \(5 urgent waiting\)/);
  assert.equal(FakeTts.last.texts.length, 6, "nothing was synthesised for it");
  // the api door has 10 a minute: 7 spent, three left, then the limit; the PUT door is untouched
  for (let i = 0; i < 3; i++) assert.equal((await api.say({ text: `normal ${i}` })).ok, true);
  await assert.rejects(api.say({ text: "eleventh" }), /over the rate limit \(10 a minute for api\)/);
  const results = [];
  app.puts["communication.crewradio.say"]("vessels.self", "communication.crewradio.say", "Through the PUT door", (r) => results.push(r));
  await until(() => results.length === 1);
  assert.equal(results[0].statusCode, 200);
  assert.equal(plugin.RATE_PER_MINUTE, 10);
  assert.equal(plugin.BRIDGE_RATE_PER_MINUTE, 30);
  p.stop();
});

test("concurrent says do not spend speech on announcements the queue will refuse", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, deps);
  p.start({ channelKey: KEY, waitForSilenceMs: 0, chime: false });
  await until(() => FakeLink.last);
  const api = app.props["signalk-crewradio.api"];
  // Eight at once, over the five urgent slots. Each checks the room before it makes its speech,
  // so without a reservation all eight would synthesise and only the losers would find out.
  const settled = await Promise.allSettled(
    Array.from({ length: 8 }, (_, i) => api.say({ text: `urgent ${i}`, priority: "urgent" })),
  );
  const ok = settled.filter((r) => r.status === "fulfilled");
  const refused = settled.filter((r) => r.status === "rejected");
  assert.equal(ok.length + refused.length, 8);
  assert.ok(ok.length >= 5, `at least the five slots were taken, got ${ok.length}`);
  assert.ok(refused.every((r) => /queue full/.test(r.reason.message)), "the rest were refused as full");
  assert.equal(FakeTts.last.texts.length, ok.length, "nothing was synthesised for a refused one");
  p.stop();
});

test("a failed synthesis gives its slot back, so a run of them does not shrink the queue", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, deps);
  p.start({ channelKey: KEY, waitForSilenceMs: 0, chime: false });
  await until(() => FakeLink.last);
  const api = app.props["signalk-crewradio.api"];
  const tts = FakeTts.last;
  const good = tts.synthesize.bind(tts);
  tts.synthesize = (text) => { tts.texts.push(text); return Promise.reject(new Error("flite fell over")); };
  for (let i = 0; i < 6; i++) await assert.rejects(api.say({ text: `broken ${i}`, priority: "urgent" }), /flite fell over/);
  tts.synthesize = good;
  // Six reservations were taken and none returned would leave no urgent room at all.
  assert.equal((await api.say({ text: "after the failures", priority: "urgent" })).ok, true);
  p.stop();
});

test("the notification bridge speaks through say(): urgent for an emergency, nothing below the threshold", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, deps);
  p.start({ channelKey: KEY, waitForSilenceMs: 0, bridge: { repeatSec: 0 } });
  await until(() => FakeLink.last);
  assert.equal(app.subs.length, 1);
  assert.equal(app.subs[0].sub.subscribe[0].path, "notifications.*");
  app.subs[0].cb({ updates: [{ values: [{ path: "notifications.mob", value: { state: "emergency", method: ["sound", "visual"], message: "Man overboard" } }] }] });
  await until(() => FakeTts.last.texts.length === 1);
  assert.deepEqual(FakeTts.last.texts, ["Emergency, mob: Man overboard"]);
  await until(() => app.log.some((m) => /say \(bridge, urgent/.test(m)));
  app.subs[0].cb({ updates: [{ values: [{ path: "notifications.navigation.depth", value: { state: "warn", method: ["sound"], message: "shallow" } }] }] });
  await flush();
  assert.equal(FakeTts.last.texts.length, 1, "warn is below the default alarm threshold");
  p.stop();
  assert.equal(app.subs.length, 0, "unsubscribed");
});

test("a network link that cannot open is reported once, retried, and its recovery logged; say() still queues", async () => {
  FakeLink.last = undefined;
  FakeLink.failOpen = true;
  const app = fakeApp();
  const p = plugin(app, deps);
  try {
    p.start({ channelKey: KEY });
    await until(() => app.errors.length > 0);
    assert.match(app.errors.at(-1), /no usable IPv4 interface/);
    assert.match(app.status.at(-1), /network link down/);
    const r = await app.props["signalk-crewradio.api"].say({ text: "hello" });
    assert.equal(r.ok, true, "queued; it plays once the link is back");
    assert.match(app.status.at(-1), /network link down · 1 waiting/, "the announcement is held at the head of the queue");
    const firstLink = FakeLink.last;
    await until(() => FakeLink.last !== firstLink, 2500);        // the retry after 1 s failed too
    await flush();
    assert.equal(app.errors.length, 1, "the same failure again is not logged again");
    FakeLink.failOpen = false;                       // the next retry (2 s backoff) succeeds
    const audioOn = (link) => link.sent.filter((b) => P.parseHeader(b).codec === P.Codec.PCM);
    // frames leave every 20 ms: wait for a handful, not just the first, so a slow runner passes too
    await until(() => FakeLink.last && !FakeLink.last.closed && audioOn(FakeLink.last).length >= 5, 6000);
    assert.equal(FakeLink.last.closed, false, "the queued announcement went out as PCM frames on the reconnected link");
    assert.ok(app.log.includes("Network link recovered"));
  } finally {
    FakeLink.failOpen = false;
    p.stop();
  }
});

test("a link that dies after start reports it in the status at once", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, deps);
  p.start({ channelKey: KEY });
  await until(() => FakeLink.last && FakeLink.last.sent.length > 0);
  const link = FakeLink.last;
  link.emit("error", new Error("network is unreachable"));
  assert.match(app.errors.at(-1), /network is unreachable/);
  assert.match(app.status.at(-1), /network link down/);
  assert.equal(link.closed, true);
  p.stop();
});

test("an unknown voice in the settings falls back to the default, and a failing engine is a plugin error", () => {
  const app = fakeApp();
  const p = plugin(app, deps);
  p.start({ channelKey: KEY, voice: "nope" });
  assert.equal(app.errors.length, 0);
  assert.equal(FakeTts.last.opts.voice, "slt");
  p.stop();
  class BrokenTts { constructor() { throw new Error("no wasm here"); } }
  const app2 = fakeApp();
  const p2 = plugin(app2, { LanLink: FakeLink, Tts: BrokenTts });
  p2.start({ channelKey: KEY });
  assert.match(app2.errors[0], /Speech: no wasm here/);
  p2.stop();
});

test("a stop() before the key is derived leaves nothing behind", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, deps);
  p.start({ channelKey: "a-key-nobody-derived-yet-" + Date.now() });
  p.stop();
  await new Promise((r) => setTimeout(r, 400));
  assert.equal(FakeLink.last, undefined, "no link was opened for a stopped plugin");
  assert.equal(app.status.at(-1), "Stopped");
});

test("GET /status carries what the web page shows, stale packets included, and the page is shipped", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, deps);
  const router = fakeRouter();
  p.registerWithRouter(router);
  let res = fakeRes();
  router.routes["GET /status"]({}, res);
  assert.equal(res.body.running, false, "before start");
  p.start({ channelKey: KEY, nodeName: "Sirius" });
  await until(() => FakeLink.last && FakeLink.last.sent.length > 0);
  const hello = P.encodeHello({ name: "Skipper", transports: P.Transports.LAN | P.Transports.BT, ttl: 4, versionCode: 140 });
  FakeLink.last.emit("packet", phonePacket(42, 0, hello), { address: "10.0.0.7", port: 47474 });
  FakeLink.last.emit("packet", phonePacket(43, 0, hello, P.Codec.HELLO, Math.floor(Date.now() / 1000) - 3600), { address: "10.0.0.8", port: 47474 });
  await flush();
  assert.match(app.errors.at(-1), /Clock: 1 packets more than 60 s off/);
  res = fakeRes();
  router.routes["GET /status"]({}, res);
  const s = res.body;
  assert.equal(s.running, true);
  assert.equal(s.name, "Sirius");
  assert.deepEqual(s.link, { iface: "fake0", address: "10.0.0.2" });
  assert.equal(s.voice, "slt");
  assert.deepEqual(s.roster.map((n) => [n.name, n.transports, n.hops, n.versionCode]), [["Skipper", "LAN+BT", 0, 140]]);
  assert.equal(s.stats.rx, 1);
  assert.equal(s.stats.stale, 1);
  assert.equal(s.queued, 0);
  assert.deepEqual(s.limits, { maxText: 500, perMinute: 10, queue: 20, urgent: 5 });
  assert.deepEqual(s.warnings, []);
  p.stop();
  const page = require("node:fs").readFileSync(require("node:path").join(__dirname, "..", "public", "index.html"), "utf8");
  assert.ok(page.includes('"/plugins/signalk-crewradio"') && page.includes('"/status"') && page.includes('"/say"'), "the page talks to the plugin routes");
  assert.ok(require("../package.json").keywords.includes("signalk-webapp"), "served at /signalk-crewradio/");
});
