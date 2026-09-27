// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const zlib = require("node:zlib");
const Ask = require("../lib/rns/ask");
const { BoatAnswers, prune, branchesOf, PER_MINUTE, MAX_BRANCHES } = require("../lib/askboat");

const MODEL = {
  navigation: {
    speedOverGround: { value: 3.1, timestamp: "2026-09-27T10:00:00.000Z", $source: "gps.1", meta: { units: "m/s" }, values: { "gps.1": { value: 3.1 } } },
    position: { value: { latitude: 60.1, longitude: 24.9 }, timestamp: "2026-09-27T10:00:01.000Z", pgn: 129025 },
    meta: { description: "branch meta" },
    empty: {},
  },
  propulsion: { port: { revolutions: { value: 30, timestamp: "2026-09-27T10:00:00.000Z" }, label: "Port" } },
};

function answers(over = {}) {
  const said = [];
  const a = new BoatAnswers({
    enabled: () => true,
    getSelfPath: (p) => MODEL[p],
    say: async (o) => { said.push(o); return { ok: true }; },
    ...over,
  });
  return { a, said };
}

const read = (s) => Ask.request(Ask.Op.READ, Buffer.from(s));
const tree = (answer) => {
  assert.equal(answer[0], Ask.Status.OK);
  return JSON.parse(zlib.inflateSync(answer.subarray(1)).toString("utf8"));
};

test("prune: a leaf keeps value, timestamp and source; meta, per-source values and loose scalars go", () => {
  assert.deepEqual(prune(MODEL.navigation), {
    speedOverGround: { value: 3.1, timestamp: "2026-09-27T10:00:00.000Z", $source: "gps.1" },
    position: { value: { latitude: 60.1, longitude: 24.9 }, timestamp: "2026-09-27T10:00:01.000Z" },
  });
  assert.deepEqual(prune(MODEL.propulsion), { port: { revolutions: { value: 30, timestamp: "2026-09-27T10:00:00.000Z" } } });
  assert.equal(prune(undefined), undefined);
  assert.equal(prune("text"), undefined);
  assert.equal(prune([1, 2]), undefined);
  assert.deepEqual(prune({ v: { value: null } }), { v: { value: null } }, "a null value is still a leaf");
  let deep = { value: 1 };
  for (let i = 0; i < 40; i++) deep = { x: deep };
  assert.equal(prune(deep), undefined, "deeper than any Signal K tree: cut off");
});

test("branches: top-level names only, one per line, a bounded number", () => {
  assert.deepEqual(branchesOf(Buffer.from("navigation\nenvironment\nnavigation\n")), ["navigation", "environment"]);
  for (const bad of ["", "navigation.position", "../x", "a b", "1abc", "\n\n"]) assert.equal(branchesOf(Buffer.from(bad)), null, JSON.stringify(bad));
  const many = Array.from({ length: MAX_BRANCHES + 1 }, (_, i) => `b${i}`).join("\n");
  assert.equal(branchesOf(Buffer.from(many)), null);
});

test("a read answers the branches the boat has, deflated; the rest are left out", async () => {
  const { a } = answers();
  assert.deepEqual(tree(await a.handle(read("navigation\ntanks\npropulsion"), "l")), {
    navigation: prune(MODEL.navigation),
    propulsion: prune(MODEL.propulsion),
  });
  assert.deepEqual(tree(await a.handle(read("tanks"), "l")), {}, "nothing to read is an empty tree, not a fault");
  assert.equal(a.stats.reads, 2);
  const bad = await a.handle(read("navigation.position"), "l");
  assert.equal(bad[0], Ask.Status.FAILED);
  assert.match(bad.subarray(1).toString(), /branches/);
});

test("a say goes to the plugin's say() as a normal announcement; its refusals come back as busy or failed", async () => {
  const { a, said } = answers();
  assert.deepEqual(await a.handle(Ask.request(Ask.Op.SAY, Buffer.from("Anna asked speed. Speed 6 knots.")), "l"), Ask.answer(Ask.Status.OK));
  assert.deepEqual(said, [{ text: "Anna asked speed. Speed 6 knots.", priority: "normal" }]);
  const busy = answers({ say: async () => { throw new Error("say: over the rate limit (10 a minute for reticulum)"); } }).a;
  assert.deepEqual(await busy.handle(Ask.request(Ask.Op.SAY, Buffer.from("x")), "l"), Ask.answer(Ask.Status.BUSY));
  const full = answers({ say: async () => { throw new Error("say: queue full (8 waiting)"); } }).a;
  assert.deepEqual(await full.handle(Ask.request(Ask.Op.SAY, Buffer.from("x")), "l"), Ask.answer(Ask.Status.BUSY));
  const empty = answers({ say: async () => { throw new Error("say: text is required"); } }).a;
  const r = await empty.handle(Ask.request(Ask.Op.SAY, Buffer.alloc(0)), "l");
  assert.equal(r[0], Ask.Status.FAILED);
  assert.equal(r.subarray(1).toString(), "say: text is required");
});

test("off unless the setting is on, read on every question; unknown and empty questions fail; a thrown read fails", async () => {
  let on = false;
  const { a, said } = answers({ enabled: () => on });
  assert.deepEqual(await a.handle(read("navigation"), "l"), Ask.answer(Ask.Status.OFF));
  assert.deepEqual(await a.handle(Ask.request(Ask.Op.SAY, Buffer.from("hi")), "l"), Ask.answer(Ask.Status.OFF));
  assert.deepEqual(said, [], "nothing said while off");
  assert.equal(a.stats.refused, 2);
  on = true;
  assert.equal((await a.handle(read("navigation"), "l"))[0], Ask.Status.OK);
  assert.equal((await a.handle(Buffer.from([9]), "l"))[0], Ask.Status.FAILED);
  assert.equal((await a.handle(Buffer.alloc(0), "l"))[0], Ask.Status.FAILED);
  const broken = answers({ getSelfPath: () => { throw new Error("no model"); } }).a;
  const r = await broken.handle(read("navigation"), "l");
  assert.equal(r[0], Ask.Status.FAILED);
  assert.equal(r.subarray(1).toString(), "no model");
});

test("each link has its own budget, and the table of budgets stays bounded", async () => {
  let t = 0;
  const { a } = answers({ now: () => t });
  for (let i = 0; i < PER_MINUTE; i++) assert.equal((await a.handle(read("navigation"), "one"))[0], Ask.Status.OK);
  assert.deepEqual(await a.handle(read("navigation"), "one"), Ask.answer(Ask.Status.BUSY));
  assert.equal((await a.handle(read("navigation"), "two"))[0], Ask.Status.OK, "another link is not charged for it");
  t += 60_000 / PER_MINUTE;
  assert.equal((await a.handle(read("navigation"), "one"))[0], Ask.Status.OK, "and it refills");
  for (let i = 0; i < 200; i++) await a.handle(read("navigation"), `link-${i}`);
  assert.ok(a.limiter.buckets.size <= 64);
});
