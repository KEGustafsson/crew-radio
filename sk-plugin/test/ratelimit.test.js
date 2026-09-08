// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { SourceLimiter } = require("../lib/ratelimit");

test("each source has its own bucket of `perMinute`, refilled with time", () => {
  let clock = 0;
  const l = new SourceLimiter({ perMinute: 3, now: () => clock });
  assert.deepEqual([l.allow("rest"), l.allow("rest"), l.allow("rest")], [true, true, true]);
  assert.equal(l.allow("rest"), false, "the fourth in the same minute");
  assert.equal(l.allow("put"), true, "another door has its own budget");
  clock = 20_000;
  assert.equal(l.allow("rest"), true, "a third of a minute later: one token back");
  assert.equal(l.allow("rest"), false);
  clock = 600_000;
  assert.deepEqual([l.allow("rest"), l.allow("rest"), l.allow("rest"), l.allow("rest")], [true, true, true, false], "never more than the burst");
});

test("a source in `rates` has its own rate and burst, and the table is looked up safely", () => {
  let clock = 0;
  const l = new SourceLimiter({ perMinute: 2, rates: { bridge: 6 }, now: () => clock });
  assert.equal(l.rateOf("bridge"), 6);
  assert.equal(l.rateOf("api"), 2);
  assert.equal(l.rateOf("toString"), 2, "an Object.prototype name is not a configured source");
  let ok = 0;
  for (let i = 0; i < 10; i++) if (l.allow("bridge")) ok++;
  assert.equal(ok, 6);
  clock = 10_000;
  assert.equal(l.allow("bridge"), true, "6 a minute: one every 10 s");
});
