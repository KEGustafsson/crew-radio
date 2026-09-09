// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { WireLimiter, PeerBudget } = require("../lib/wirelimit");

test("the global bucket bounds what is looked at, whatever id the packets claim", () => {
  let clock = 0;
  const l = new WireLimiter({ globalPerSecond: 0, globalBurst: 3, now: () => clock });
  assert.deepEqual([l.allowGlobal(), l.allowGlobal(), l.allowGlobal()], [true, true, true]);
  assert.equal(l.allowGlobal(), false, "the fourth, from any sender at all");
});

test("the junk bucket is separate, so unreadable traffic cannot starve the crew", () => {
  let clock = 0;
  const l = new WireLimiter({ junkPerSecond: 0, junkBurst: 2, now: () => clock });
  assert.deepEqual([l.allowJunk(), l.allowJunk()], [true, true]);
  assert.equal(l.allowJunk(), false);
  assert.equal(l.allowGlobal(), true, "the global budget is untouched by junk");
  assert.equal(l.allowSender(1), true, "and so is a real sender's");
});

test("a sender spends its own budget and refills with time", () => {
  let clock = 0;
  const l = new WireLimiter({ perSecond: 100, burst: 3, now: () => clock });
  assert.deepEqual([l.allowSender(1), l.allowSender(1), l.allowSender(1)], [true, true, true]);
  assert.equal(l.allowSender(1), false);
  assert.equal(l.allowSender(2), true, "another sender is unaffected");
  clock = 10;
  assert.equal(l.allowSender(1), true, "100/s: one token back after 10 ms");
});

test("the sender table is bounded, and idle senders are swept", () => {
  let clock = 0;
  const l = new WireLimiter({ maxSenders: 2, forgetMs: 1000, now: () => clock });
  assert.equal(l.allowSender(1), true);
  assert.equal(l.allowSender(2), true);
  assert.equal(l.allowSender(3), false, "the table is full: no state for a newcomer");
  clock = 5000;
  assert.equal(l.allowSender(3), true, "the idle two are swept and it fits");
});

test("one flooding source spends only its own peer budget", () => {
  let clock = 0;
  const p = new PeerBudget({ perSecond: 100, burst: 5, now: () => clock });
  for (let i = 0; i < 20; i++) p.allow("10.0.0.9");
  assert.equal(p.allow("10.0.0.9"), false, "the flooder is out");
  for (let i = 0; i < 5; i++) assert.equal(p.allow("10.0.0.2"), true, "the crew's phone is not");
});

test("a flood of addresses evicts the eldest rather than locking a newcomer out", () => {
  let clock = 0;
  const p = new PeerBudget({ perSecond: 100, burst: 3, maxSources: 4, now: () => clock });
  for (let i = 0; i < 50; i++) p.allow(`10.0.1.${i}`);
  for (let i = 0; i < 3; i++) assert.equal(p.allow("10.0.0.2"), true, "a phone arriving after the flood");
});

test("tokens do not pile up past the burst", () => {
  let clock = 0;
  const p = new PeerBudget({ perSecond: 100, burst: 3, now: () => clock });
  clock = 60_000;
  assert.deepEqual([p.allow("a"), p.allow("a"), p.allow("a")], [true, true, true]);
  assert.equal(p.allow("a"), false, "a minute of silence is still only a burst");
});
