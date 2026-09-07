// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * signalk-crewradio: the Signal K server speaks on the Crew Radio channel.
 *
 *  1. A node on the crew's push-to-talk channel over the boat's LAN or WLAN, byte-compatible
 *     with the Android app (lib/packet.js, lib/crypto.js, lib/node.js); the phones relay it
 *     onward over Bluetooth and Wi-Fi Aware.
 *  2. Text to speech inside the plugin (lib/tts.js: Flite in WebAssembly on a worker thread,
 *     English), with a queue where urgent announcements go first (lib/queue.js). Three doors to
 *     say(): PUT communication.crewradio.say, POST /plugins/signalk-crewradio/say, and the
 *     in-process PropertyValue "signalk-crewradio.api"; each door has its own rate budget
 *     (lib/ratelimit.js), the notification bridge a larger one of its own.
 *  3. A notification bridge (lib/bridge.js): Signal K notifications at or above a chosen state
 *     are announced, urgent for emergencies, repeated until they clear.
 *  4. The channel's roster in Signal K: communication.crewradio.* (online, nodes, talking, speaking).
 */

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { ChannelCrypto } = require("./lib/crypto");
const { Transports, sanitiseName, REPLAY_WINDOW_S } = require("./lib/packet");
const { LanLink } = require("./lib/lan");
const { ChannelNode } = require("./lib/node");
const { ReplayGuard } = require("./lib/replay");
const { SourceLimiter } = require("./lib/ratelimit");
const { FliteTts, VOICES, MAX_TEXT } = require("./lib/tts");
const { AnnouncementQueue } = require("./lib/queue");
const { NotificationBridge } = require("./lib/bridge");
const { samplesToBytes, bytesToSamples } = require("./lib/resample");
const tones = require("./lib/tones");
const pkg = require("./package.json");

const API_PROPERTY = "signalk-crewradio.api";
const SAY_PATH = "communication.crewradio.say";
const RATE_PER_MINUTE = 10;          // say() calls a minute for each door
const BRIDGE_RATE_PER_MINUTE = 30;   // the notification bridge: several alarms repeating every 30 s
const MAX_BODY = 10_000;             // characters of a POST /say body

/** @param {object} app the Signal K plugin API; `deps` lets tests inject a fake network link and speech engine */
module.exports = function crewRadioPlugin(app, deps = {}) {
  const Link = deps.LanLink ?? LanLink;
  const Tts = deps.Tts ?? FliteTts;
  const plugin = {
    id: "signalk-crewradio",
    name: "Crew Radio",
    description: pkg.description,
    schema: () => schema(app),
    uiSchema: () => uiSchema(),
  };

  let running = false;
  let generation = 0;                              // start() count: a key derived for an earlier start is dropped
  let cfg = null;
  let crypto = null;                               // the channel's ChannelCrypto once the key is derived
  let link = null;
  let node = null;
  let tts = null;
  let queue = null;
  let limiter = null;
  let bridge = null;
  let guard = null;                                // the replay memory, kept for the plugin's whole life
  let unsubscribes = [];
  let reopenTimer = null;
  let backoffMs = 1000;
  let lastStatus = "";
  let lastLinkError = null;                        // the last link error logged; the same one again is not
  let linkInfo = null;                             // {iface, address, broadcast} while the link is up
  let startedAt = 0;

  plugin.start = function (options) {
    running = true;
    const gen = ++generation;
    startedAt = Date.now();
    cfg = withDefaults(options, app);
    for (const w of cfg.warnings) app.error(`Settings: ${w}`);
    if (!cfg.channelKey) {
      // Not configured yet is not a failure: nothing is started, and the status says what is needed.
      app.setPluginStatus("Waiting for the channel key: set the same key as on the phones (Plugin Config)");
      return;
    }
    try {
      tts = new Tts({ voice: cfg.voice, rate: cfg.rate, tempDir: path.join(dataDir(app), "tts-tmp") });
    } catch (e) {
      app.setPluginError(`Speech: ${e.message}`);
      return;
    }
    queue = new AnnouncementQueue({
      play: (pcm, cancelled) => playOnChannel(pcm, cancelled),
      onCancel: () => node?.cancel(),
      log: (m) => app.debug(m),
    });
    queue.on("started", () => status());
    queue.on("done", () => status());
    limiter = new SourceLimiter({ perMinute: RATE_PER_MINUTE, rates: { bridge: BRIDGE_RATE_PER_MINUTE } });
    guard ??= new ReplayGuard();
    crypto = null;

    const openLink = async () => {
      if (!running || gen !== generation) return;
      const mine = new Link({ group: cfg.group, port: cfg.port, iface: cfg.iface });
      link = mine;
      mine.on("error", (e) => {
        reportLink(e.message);
        scheduleReopen();
        status();
      });
      try {
        const where = await mine.open();
        if (!running || link !== mine) { mine.close(); return; }   // stopped or reopened while the socket was binding
        backoffMs = 1000;
        linkInfo = where;
        if (lastLinkError) { app.debug("Network link recovered"); lastLinkError = null; }
        app.debug(`Network link up on ${where.iface} ${where.address} (group ${cfg.group}:${cfg.port}, broadcast ${where.broadcast})`);
      } catch (e) {
        reportLink(e.message);
        scheduleReopen();
        status();
        return;
      }
      node = new ChannelNode({ name: cfg.nodeName, crypto, link, ttl: cfg.hops, guard });
      node.on("roster", (r) => publishRoster(r));
      node.on("speaking", (on) => { publishSpeaking(on); status(); });
      node.on("stale", (n) => app.error(`Clock: ${n} packets more than ${REPLAY_WINDOW_S} s off (the server's clock or a phone's is wrong)`));
      node.start();
      publishRoster(node.roster());              // the paths exist from the start, even when nobody is there yet
    };
    const scheduleReopen = () => {
      if (!running || reopenTimer) return;
      if (node) { node.stop(); node = null; }
      if (link) { link.close(); link = null; }
      linkInfo = null;
      reopenTimer = setTimeout(() => { reopenTimer = null; openLink(); }, backoffMs);
      backoffMs = Math.min(backoffMs * 2, 15_000);
    };
    /** A link failure is logged once; the same message again (every retry) is not, until the link has been up in between. */
    const reportLink = (msg) => {
      if (msg === lastLinkError) return;
      lastLinkError = msg;
      app.error(`Network link: ${msg}`);
    };

    // The three doors to say(): PUT on a path, REST (registerWithRouter), in-process.
    if (typeof app.registerPutHandler === "function") {
      app.registerPutHandler("vessels.self", SAY_PATH, (context, p, value, callback) => {
        say(typeof value === "string" ? { text: value } : value ?? {}, "put").then(
          (r) => callback({ state: "COMPLETED", statusCode: 200, message: JSON.stringify(r) }),
          (e) => callback({ state: "COMPLETED", statusCode: 400, message: e.message }),
        );
        return { state: "PENDING" };
      }, plugin.id);
    }
    if (typeof app.emitPropertyValue === "function") {
      app.emitPropertyValue(API_PROPERTY, { version: 1, say: (o) => say(o, "api") });
    }

    // Notifications to announcements.
    if (cfg.bridge.enabled) {
      bridge = new NotificationBridge({
        say: (o) => say(o, "bridge"),
        log: (m) => app.debug(m),
        rules: {
          minState: cfg.bridge.minState,
          soundOnly: cfg.bridge.soundOnly,
          repeatSec: cfg.bridge.repeatSec,
          urgentStates: cfg.bridge.urgentStates,
          include: cfg.bridge.include,
          exclude: cfg.bridge.exclude,
          sayPath: cfg.bridge.sayPath,
        },
      });
      bridge.on("announce", (a) => app.debug(`announce ${a.priority}: ${a.path}: ${a.message}`));
      bridge.start();
      app.subscriptionmanager.subscribe(
        { context: "vessels.self", subscribe: [{ path: "notifications.*", policy: "instant" }] },
        unsubscribes,
        (err) => app.error(`notifications subscription: ${err}`),
        (delta) => bridge.onDelta(delta),
      );
    }

    // The packet key takes a while to derive (600 000 rounds of PBKDF2); it runs on the thread
    // pool, and the link opens when it is there. start() itself returns at once.
    ChannelCrypto.forChannelKey(cfg.channelKey).then(
      (c) => { if (running && gen === generation) { crypto = c; openLink(); } },
      (e) => { if (running && gen === generation) app.setPluginError(`Channel key: ${e.message}`); },
    );
    status();
  };

  /**
   * REST: POST /plugins/signalk-crewradio/say with {text, priority} as application/json or a
   * text/plain body (read-write users); GET /say for the voice and queue and GET /status for
   * everything the web page (public/index.html) shows (read-only users). Servers without
   * router.access() keep their default, admin-only, protection.
   */
  plugin.registerWithRouter = function (router) {
    const scoped = (level) => (typeof router.access === "function" ? router.access(level) : router);
    const readonly = scoped("readonly");
    const readwrite = scoped("readwrite");
    readonly.get("/status", (req, res) => res.json(statusNow()));
    readonly.get("/say", (req, res) => res.json({
      voice: cfg?.voice ?? null, voices: VOICES, queued: queue?.size ?? 0, speaking: !!node?.speaking,
      online: node ? node.roster().length : 0, maxText: MAX_TEXT, perMinute: RATE_PER_MINUTE,
    }));
    readwrite.post("/say", (req, res) => {
      const done = (body) => {
        say(typeof body === "string" ? { text: body } : body ?? {}, "rest").then(
          (r) => res.json(r),
          (e) => res.status(400).json({ ok: false, error: e.message }),
        );
      };
      const kind = contentKind(req);
      if (!kind) return res.status(415).json({ ok: false, error: "send application/json {\"text\": ..., \"priority\": ...} or text/plain" });
      const body = req.body;
      if (kind === "json" && body && typeof body === "object" && !Buffer.isBuffer(body)) {
        // Express's JSON parser ran: a parsed object, or {} when it skipped the body (Express 4
        // puts {} there for a body it did not parse; a real, empty {} has the stream consumed).
        if (Object.keys(body).length > 0 || req.readableEnded) return done(body);
      }
      if (kind === "text" && typeof body === "string") return done(body);
      if (Buffer.isBuffer(body) && body.length > 0) return handleRaw(body.toString("utf8"));
      readBody(req, MAX_BODY).then((raw) => {
        if (raw === null) {
          res.status(413).json({ ok: false, error: `body over ${MAX_BODY} characters` });
          if (typeof res.once === "function") res.once("finish", () => req.destroy?.());   // answered first, then cut off
          return;
        }
        handleRaw(raw);
      });
      function handleRaw(raw) {
        if (raw.length > MAX_BODY) return res.status(413).json({ ok: false, error: `body over ${MAX_BODY} characters` });
        if (kind === "text") return done(raw);
        try { done(raw.trim() ? JSON.parse(raw) : {}); } catch (e) { res.status(400).json({ ok: false, error: `not JSON: ${e.message}` }); }
      }
    });
  };

  plugin.stop = function () {
    running = false;
    generation++;
    if (reopenTimer) { clearTimeout(reopenTimer); reopenTimer = null; }
    for (const u of unsubscribes) { try { u(); } catch { /* gone */ } }
    unsubscribes = [];
    if (bridge) { bridge.stop(); bridge = null; }
    if (queue) { queue.stop(); queue = null; }
    if (node) { node.stop(); node = null; }
    if (link) { link.close(); link = null; }
    linkInfo = null;
    lastLinkError = null;
    if (tts && typeof tts.stop === "function") tts.stop();
    tts = null;
    crypto = null;
    limiter = null;
    if (typeof app.emitPropertyValue === "function") app.emitPropertyValue(API_PROPERTY, null);
    publishRoster([]);
    lastStatus = "";                                   // a restart must set its first line even if it reads the same
    app.setPluginStatus("Stopped");
  };

  /**
   * Speaks a text on the channel. Resolves when it is queued: {ok, queued: position, priority,
   * seconds, truncated}. A text over MAX_TEXT characters is cut, not refused (an alarm's text
   * is still an alarm), and `truncated` says so. Rejects for an empty text, when the plugin is
   * not running, when `source` is over its rate budget, or when the queue is full for the
   * priority; the room is checked before any speech is made.
   */
  async function say(opts, source = "api") {
    if (!running || !tts || !queue) throw new Error("signalk-crewradio is not running");
    let text = typeof opts?.text === "string" ? opts.text.trim() : "";
    if (!text) throw new Error("say: text is required");
    let truncated = false;
    if (text.length > MAX_TEXT) { text = text.slice(0, MAX_TEXT); truncated = true; }
    const priority = opts.priority === "urgent" ? "urgent" : "normal";
    if (!limiter.allow(source)) throw new Error(`say: over the rate limit (${limiter.rateOf(source)} a minute for ${source})`);
    if (!queue.hasRoom(priority)) throw new Error(`say: queue full (${priority === "urgent" ? `${queue.maxUrgent} urgent` : queue.max} waiting)`);
    const q = queue;
    const speech = await tts.synthesize(text);
    if (!running || queue !== q) throw new Error("signalk-crewradio is not running");
    const parts = [];
    if (cfg.chime) parts.push(priority === "urgent" ? tones.urgentChime() : tones.chime());
    parts.push(bytesToSamples(speech), tones.silence(150));
    const pcm = samplesToBytes(tones.concat(parts));
    const position = q.enqueue(pcm, priority);
    app.debug(`say (${source}, ${priority}, position ${position}${truncated ? ", truncated" : ""}): ${text}`);
    return { ok: true, queued: position, priority, seconds: Math.round(pcm.length / 32) / 1000, truncated };
  }

  /** How long an announcement waits for the network link to come back before it is dropped. */
  const LINK_WAIT_MS = 60_000;

  async function playOnChannel(pcm, cancelled) {
    // The link reconnects on its own (1 s doubling to 15 s); an announcement made while it is
    // down waits for it at the head of the queue instead of being thrown away.
    const t0 = Date.now();
    while (!node) {
      if (!running || cancelled()) return;
      if (Date.now() - t0 > LINK_WAIT_MS) throw new Error("not on the channel (network link down)");
      await new Promise((r) => setTimeout(r, 100));
    }
    if (cfg.waitForSilenceMs > 0) await node.waitForSilence(300, cfg.waitForSilenceMs);
    if (cancelled()) return;
    await node.speak(pcm);
  }

  /** The plugin's state for the web page: link, roster, queue, voice, counters, limits. */
  function statusNow() {
    const roster = node ? node.roster() : [];
    return {
      running,
      channelKey: !!cfg?.channelKey,
      name: cfg?.nodeName ?? null,
      statusLine: lastStatus,
      warnings: cfg?.warnings ?? [],
      link: linkInfo ? { iface: linkInfo.iface, address: linkInfo.address } : null,
      group: cfg?.group ?? null, port: cfg?.port ?? null, hops: cfg?.hops ?? null,
      voice: cfg?.voice ?? null, voices: VOICES, rate: cfg?.rate ?? null,
      speaking: !!node?.speaking,
      queued: (queue?.size ?? 0) + (queue?.current && !node?.speaking ? 1 : 0),
      roster: roster.map((n) => ({ name: n.name, transports: describeTransports(n.transports), hops: n.hops, versionCode: n.versionCode, talking: n.talking, ageMs: n.ageMs })),
      stats: { ...(node?.stats ?? { rx: 0, tx: 0, rejected: 0, stale: 0, late: 0 }), synthesized: tts?.stats?.synthesized ?? 0, cached: tts?.stats?.cached ?? 0 },
      limits: { maxText: MAX_TEXT, perMinute: RATE_PER_MINUTE, queue: queue?.max ?? null, urgent: queue?.maxUrgent ?? null },
      uptimeSec: running ? Math.round((Date.now() - startedAt) / 1000) : 0,
    };
  }

  function publishRoster(roster) {
    const talking = roster.filter((n) => n.talking).map((n) => n.name);
    app.handleMessage(plugin.id, {
      updates: [{
        values: [
          { path: "communication.crewradio.online", value: roster.length },
          { path: "communication.crewradio.nodes", value: roster.map((n) => ({ name: n.name, hops: n.hops, transports: n.transports, talking: n.talking, versionCode: n.versionCode })) },
          { path: "communication.crewradio.talking", value: talking },
          { path: "communication.crewradio.speaking", value: !!node?.speaking },
        ],
      }],
    });
    status(roster);
  }

  function publishSpeaking(on) {
    app.handleMessage(plugin.id, { updates: [{ values: [{ path: "communication.crewradio.speaking", value: !!on }] }] });
  }

  function status(roster) {
    if (!running) return;
    const r = roster ?? node?.roster() ?? [];
    const parts = [node ? `${r.length} online` : crypto ? "network link down" : "starting"];
    const talking = r.filter((n) => n.talking).map((n) => n.name);
    if (talking.length) parts.push(`talking: ${talking.join(", ")}`);
    if (node?.speaking) parts.push("announcing");
    const waiting = (queue?.size ?? 0) + (queue?.current && !node?.speaking ? 1 : 0);   // held for the link, or for a gap in talk
    if (waiting) parts.push(`${waiting} waiting`);
    parts.push(`voice ${cfg?.voice ?? "-"}`);
    if (cfg?.warnings.length) parts.push(`check settings: ${cfg.warnings.join("; ")}`);
    const line = parts.join(" · ");
    if (line !== lastStatus) { lastStatus = line; app.setPluginStatus(line); }
  }

  return plugin;
};

/**
 * The settings with defaults, validated: a value out of range falls back to the default and is
 * named in `warnings`, so a typo in the port never leaves the plugin silently off the channel.
 */
function withDefaults(o, app) {
  o = o ?? {};
  const b = o.bridge ?? {};
  const warnings = [];
  const number = (v, def, lo, hi, what, integer = false) => {
    if (v === undefined || v === null || v === "") return def;
    const n = Number(v);
    if (!Number.isFinite(n) || n < lo || n > hi || (integer && !Number.isInteger(n))) {
      warnings.push(`${what} ${JSON.stringify(v)} is not ${lo}-${hi}, using ${def}`);
      return def;
    }
    return n;
  };
  let group = String(o.group ?? "239.255.42.1").trim() || "239.255.42.1";
  if (!isMulticastV4(group)) { warnings.push(`multicast group ${JSON.stringify(group)} is not an IPv4 multicast address, using 239.255.42.1`); group = "239.255.42.1"; }
  return {
    channelKey: String(o.channelKey ?? "").trim(),
    nodeName: sanitiseName(o.nodeName) || boatName(app),
    group,
    port: number(o.port, 47474, 1024, 65535, "UDP port", true),
    iface: String(o.iface ?? "auto").trim() || "auto",
    hops: number(o.hops, 4, 1, 16, "hop budget", true),
    voice: VOICES.includes(o.voice) ? o.voice : "slt",
    rate: number(o.rate, 1, 0.5, 2, "speaking rate"),
    chime: o.chime ?? true,
    waitForSilenceMs: number(o.waitForSilenceMs, 2000, 0, 30_000, "wait for a gap in talk", true),
    bridge: {
      enabled: b.enabled ?? true,
      minState: b.minState ?? "alarm",
      soundOnly: b.soundOnly ?? true,
      repeatSec: number(b.repeatSec, 30, 0, 3600, "repeat every", true),
      urgentStates: Array.isArray(b.urgentStates) ? b.urgentStates : ["emergency"],
      include: Array.isArray(b.include) ? b.include : [],
      exclude: Array.isArray(b.exclude) ? b.exclude : [],
      sayPath: b.sayPath ?? true,
    },
    warnings,
  };
}

/** True for a dotted IPv4 address in 224.0.0.0/4. */
function isMulticastV4(s) {
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(String(s));
  if (!m) return false;
  const o = m.slice(1).map(Number);
  return o[0] >= 224 && o[0] <= 239 && o.every((x) => x <= 255);
}

/** "json", "text" or null for a request, by its Content-Type (Express's req.is when it is there). */
function contentKind(req) {
  if (typeof req.is === "function") {
    if (req.is("application/json")) return "json";
    if (req.is("text/*")) return "text";
    return null;
  }
  const ct = String(req.headers?.["content-type"] ?? "").split(";")[0].trim().toLowerCase();
  return ct === "application/json" ? "json" : ct.startsWith("text/") ? "text" : null;
}

/** The request body as text, or null once it passes `limit` characters (the rest is left unread). */
function readBody(req, limit) {
  return new Promise((resolve) => {
    let raw = "";
    let over = false;
    if (typeof req.setEncoding === "function") req.setEncoding("utf8");
    req.on("data", (c) => {
      if (over) return;
      raw += c;
      if (raw.length > limit) { over = true; resolve(null); }
    });
    req.on("end", () => { if (!over) resolve(raw); });
    req.on("error", () => { if (!over) resolve(raw); });
  });
}

/** Transport flags as the app writes them: LAN+BT+Aware. */
function describeTransports(flags) {
  const names = [];
  if (flags & Transports.LAN) names.push("LAN");
  if (flags & Transports.BT) names.push("BT");
  if (flags & Transports.AWARE) names.push("Aware");
  return names.join("+");
}

let fallbackDir = null;   // one private scratch directory per process when the server has no data directory

function dataDir(app) {
  try {
    const d = app.getDataDirPath?.();
    if (typeof d === "string" && d) return d;
  } catch { /* older server */ }
  fallbackDir ??= fs.mkdtempSync(path.join(os.tmpdir(), "signalk-crewradio-"));
  return fallbackDir;
}

function boatName(app) {
  try {
    const n = app.getSelfPath?.("name");
    if (typeof n === "string" && n.trim()) return n.trim();
  } catch { /* no name */ }
  return "Boat";
}

function schema(app) {
  return {
    type: "object",
    required: ["channelKey"],
    properties: {
      channelKey: { type: "string", title: "Channel key", description: "The crew's channel key, exactly as on the phones (Settings › Channel key). Keeps the channel private; every node must share it." },
      nodeName: { type: "string", title: "Name on the roster", description: `How the phones list the server. Empty: the vessel's name (${boatName(app)}).`, default: "" },
      voice: { type: "string", title: "Voice", enum: VOICES, default: "slt", description: "Flite voices, English: slt (female), kal16, rms and awb (male)." },
      rate: { type: "number", title: "Speaking rate", default: 1, minimum: 0.7, maximum: 1.3, description: "1 is the voice's own pace; 0.8 slower for a noisy deck." },
      chime: { type: "boolean", title: "Chime before each announcement", default: true, description: "Two notes; three quick ones before an urgent announcement." },
      waitForSilenceMs: { type: "integer", title: "Wait for a gap in talk (ms)", default: 2000, minimum: 0, maximum: 30000, description: "An announcement waits this long at most for the crew to stop talking before it cuts in." },
      group: { type: "string", title: "Multicast group", default: "239.255.42.1", description: "Must match the phones' WLAN setting (Settings › WLAN group and port)." },
      port: { type: "integer", title: "UDP port", default: 47474, minimum: 1024, maximum: 65535 },
      iface: { type: "string", title: "Network interface", default: "auto", description: "The server's interface on the boat network: wired LAN (eth0) or WLAN (wlan0), as long as it is the same network the phones' WLAN is on. auto: a wlan interface, else eth/en, else the first with an IPv4 address." },
      hops: { type: "integer", title: "Hop budget", default: 4, minimum: 1, maximum: 16, description: "How far phones may relay the server's packets over Bluetooth and Wi-Fi Aware." },
      bridge: {
        type: "object",
        title: "Announce Signal K notifications",
        properties: {
          enabled: { type: "boolean", title: "Enabled", default: true },
          minState: { type: "string", title: "Announce from state", enum: ["alert", "warn", "alarm", "emergency"], default: "alarm" },
          sayPath: { type: "boolean", title: "Say the state and the path first", default: true, description: "\"Alarm, navigation position: no contact with sensor for 70 seconds\" rather than the message alone, so the crew hears where it comes from." },
          soundOnly: { type: "boolean", title: "Only notifications that ask for sound", default: true, description: "A notification's method lists visual and/or sound; off announces everything at or above the state." },
          repeatSec: { type: "integer", title: "Repeat every (s), 0 = once", default: 30, minimum: 0, maximum: 3600 },
          urgentStates: { type: "array", title: "Urgent states", items: { type: "string", enum: ["alert", "warn", "alarm", "emergency"] }, default: ["emergency"], description: "Said first, interrupting a normal announcement, with the urgent chime." },
          include: { type: "array", title: "Only these notification paths (globs)", items: { type: "string" }, default: [], description: "Relative to notifications., e.g. navigation.anchor or mob. Empty: all." },
          exclude: { type: "array", title: "Never these paths (globs)", items: { type: "string" }, default: [] },
        },
      },
    },
  };
}

function uiSchema() {
  return { channelKey: { "ui:widget": "password" } };
}

module.exports.withDefaults = withDefaults;
module.exports.isMulticastV4 = isMulticastV4;
module.exports.contentKind = contentKind;
module.exports.RATE_PER_MINUTE = RATE_PER_MINUTE;
module.exports.BRIDGE_RATE_PER_MINUTE = BRIDGE_RATE_PER_MINUTE;
module.exports.MAX_BODY = MAX_BODY;
