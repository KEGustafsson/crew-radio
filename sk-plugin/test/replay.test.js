// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { ReplayGuard, AUDIO_CAPACITY, HELLO_CAPACITY, SENDERS } = require("../lib/replay");

test("a new number is admitted, its copy is seen, an older one is late; audio and hellos count apart", () => {
  const g = new ReplayGuard();
  assert.equal(g.admit(1, 0, "audio"), "new");
  assert.equal(g.admit(1, 0, "audio"), "seen", "the broadcast twin");
  assert.equal(g.admit(1, 1, "audio"), "new");
  assert.equal(g.admit(1, 0, "audio"), "seen", "still in the cache");
  assert.equal(g.admit(1, 5, "audio"), "new", "a gap is a loss, not a fault");
  assert.equal(g.admit(1, 3, "audio"), "late", "never seen, but the sender moved past it");
  assert.equal(g.admit(1, 0, "hello"), "new", "hellos have their own sequence");
  assert.equal(g.admit(1, 5, "hello"), "new");
  assert.equal(g.admit(2, 3, "audio"), "new", "another sender");
});

test("the marks are wrap-aware: rolling over from the top of int32 is a distance of one", () => {
  const g = new ReplayGuard();
  assert.equal(g.admit(9, 0x7fffffff, "audio"), "new");
  assert.equal(g.admit(9, -0x80000000, "audio"), "new", "the next number after Int.MAX_VALUE");
  assert.equal(g.admit(9, 0x7fffffff, "audio"), "seen");
  assert.equal(g.admit(9, 0x7ffffffe, "audio"), "late");
  assert.equal(g.admit(9, -0x7fffffff, "audio"), "new");
});

test("the seen-caches are bounded and the marks keep the last 256 senders", () => {
  const g = new ReplayGuard({ audioCapacity: 4, helloCapacity: 2, senders: 3 });
  for (let i = 0; i < 6; i++) g.admit(1, i, "audio");
  assert.equal(g.audio.size, 4);
  assert.equal(g.admit(1, 0, "audio"), "late", "out of the cache, but the mark still knows");
  for (let i = 0; i < 3; i++) g.admit(1, i, "hello");
  assert.equal(g.hello.size, 2);
  g.admit(2, 0, "audio"); g.admit(3, 0, "audio");
  assert.deepEqual([...g.marks.keys()], [1, 2, 3]);
  g.admit(1, 7, "audio");                                   // touched: most recently used
  g.admit(4, 0, "audio");                                   // evicts the least recently used, 2
  assert.deepEqual([...g.marks.keys()], [3, 1, 4]);
  // The mark is gone, but the packet itself is still in the seen-cache, so the replay is
  // still refused: the two structures only ever add to each other.
  assert.equal(g.admit(2, 0, "audio"), "seen", "refused by the cache once the mark is gone");
  assert.equal(AUDIO_CAPACITY, 16_384);
  assert.equal(HELLO_CAPACITY, 2048);
  assert.equal(SENDERS, 256);
});
