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

// The app's Ingress.RelayOnly: the ttl is outside the AAD, so a copy with it lowered can come
// first; a later copy that goes further is forwarded too, each one only if it beats the last.
test("a copy that would be forwarded further than the first is 'relay', once per better ttl", () => {
  const g = new ReplayGuard();
  assert.equal(g.admit(1, 0, "audio", 0), "new", "first heard with its ttl spent");
  assert.equal(g.admit(1, 0, "audio", 0), "seen");
  assert.equal(g.admit(1, 0, "audio", 2), "relay", "the sender's own copy, ttl to spare");
  assert.equal(g.admit(1, 0, "audio", 2), "seen", "its broadcast twin");
  assert.equal(g.admit(1, 0, "audio", 1), "seen", "a worse copy after it");
  assert.equal(g.admit(1, 0, "audio", 2), "seen", "which did not lower the ttl kept");
  assert.equal(g.admit(1, 0, "audio", 3), "relay", "a better one still");
  assert.equal(g.admit(1, 0, "hello", 0), "new", "hellos keep their own");
  assert.equal(g.admit(1, 0, "audio"), "seen", "no ttl given: never relay");
});

// The app's Ingress asks the sender's budget between the look and the write: a packet over it
// leaves nothing behind, so a later copy is judged afresh (and charged), never relayed free.
test("a packet the sender's budget refuses is 'rejected' and not remembered", () => {
  const g = new ReplayGuard();
  let asked = 0;
  const no = () => { asked++; return false; };
  const yes = () => { asked++; return true; };
  assert.equal(g.admit(1, 0, "audio", 0, no), "rejected");
  assert.equal(g.admit(1, 0, "audio", 2, yes), "new", "the later copy is judged afresh, not relayed");
  assert.equal(g.admit(1, 0, "audio", 3, no), "relay", "a copy of an admitted packet is not charged");
  assert.equal(asked, 2);
  assert.equal(g.admit(1, 0, "hello", 0, no), "rejected");
  assert.equal(g.admit(1, 0, "hello", 0, yes), "new", "a refused hello does not block the next copy's roster update");
});

// CodeRabbit on #69: with the mark table full, a refused newcomer must not evict another
// sender's mark, or that sender's old numbers (out of the cache by then) would pass again.
test("a refused packet from a new sender leaves the mark table as it was", () => {
  const g = new ReplayGuard({ audioCapacity: 2, senders: 2 });
  g.admit(1, 5, "audio"); g.admit(2, 5, "audio");
  g.admit(1, 6, "audio"); g.admit(2, 6, "audio");          // 1:5 and 2:5 out of the cache
  assert.equal(g.admit(3, 0, "audio", 0, () => false), "rejected");
  assert.equal(g.admit(1, 5, "audio"), "late", "sender 1's mark is still there");
  assert.equal(g.admit(2, 5, "audio"), "late", "and sender 2's");
});
