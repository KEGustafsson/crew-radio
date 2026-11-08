// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const F = require("../lib/forwarding");
const { Transports: T } = require("../lib/packet");

const UP = T.LAN | T.RETICULUM | T.RNS_UP;

test("forwarder rank: FWD_PREFER 2, neither 1, FWD_AVOID 0", () => {
  assert.equal(F.rank(UP | T.FWD_PREFER), 2);
  assert.equal(F.rank(UP), 1);
  assert.equal(F.rank(UP | T.FWD_AVOID), 0);
  assert.equal(F.LOCAL_MS, 15_000);
});

test("election: the highest rank wins, ties to the lowest senderId as unsigned 32-bit", () => {
  assert.equal(F.elect([]), null);
  assert.equal(F.elect([{ senderId: 5, transports: UP }]), 5);
  // A phone with a lower id loses to the plugin, which prefers to forward; an ESP32 loses to the phone.
  assert.equal(F.elect([{ senderId: 1, transports: UP }, { senderId: 9, transports: UP | T.FWD_PREFER }]), 9);
  assert.equal(F.elect([{ senderId: 9, transports: UP | T.FWD_AVOID }, { senderId: 100, transports: UP }]), 100);
  // 0x80000000 is negative as an int32: compared signed it would be the lowest; unsigned, 1 is.
  const high = 0x80000000 | 0;
  assert.ok(high < 0);
  assert.equal(F.elect([{ senderId: high, transports: UP }, { senderId: 1, transports: UP }]), 1);
  assert.equal(F.elect([{ senderId: 1, transports: UP }, { senderId: high, transports: UP }]), 1, "whatever the order");
  assert.equal(F.elect([{ senderId: -1, transports: UP }, { senderId: 0x7fffffff, transports: UP }]), 0x7fffffff, "0xffffffff is the highest");
});

test("local peers: RNS_UP required to be a candidate, expiry after LOCAL_MS, me only while my own RNS_UP is set", () => {
  let t = 1_000_000;
  const local = new F.LocalPeers({ now: () => t });
  const me = { senderId: 50, transports: UP | T.FWD_PREFER };
  assert.equal(local.forwarder(me), 50, "alone with Reticulum up: me");
  assert.equal(local.forwarder({ senderId: 50, transports: T.LAN | T.FWD_PREFER }), null, "without RNS_UP not even me");

  local.heard(10, T.LAN | T.RETICULUM);             // on Reticulum but not connected right now: no candidate
  assert.equal(local.isLocal(10), true);
  assert.equal(local.rnsUp(10), false);
  assert.equal(local.forwarder({ senderId: 50, transports: UP }), 50);
  local.heard(10, UP);
  assert.equal(local.rnsUp(10), true);
  assert.equal(local.forwarder({ senderId: 50, transports: UP }), 10, "a tie: the lower id");
  assert.equal(local.forwarder(me), 50, "FWD_PREFER outranks it");
  assert.equal(local.forwarder({ senderId: 50, transports: T.LAN }), 10, "me without RNS_UP: the phone");

  t += F.LOCAL_MS - 1;
  assert.equal(local.isLocal(10), true, "still within LOCAL_MS");
  t += 1;
  assert.equal(local.isLocal(10), false, "exactly LOCAL_MS old: expired, as in the app");
  assert.equal(local.rnsUp(10), false);
  assert.equal(local.forwarder({ senderId: 50, transports: UP }), 50, "expired: no longer a candidate");
  assert.deepEqual(local.list(), []);
  local.sweep();
  assert.equal(local.peers.size, 0);

  // A full table: the entry heard longest ago makes room, fresh or not (the ESP32's rule).
  const small = new F.LocalPeers({ now: () => t, max: 2 });
  small.heard(1, UP);
  t += 10;
  small.heard(2, UP);
  t += 10;
  small.heard(1, UP);                                // heard again: now the newest
  small.heard(3, UP);                                // 2, heard longest ago though still fresh, goes
  assert.deepEqual(small.list().map((p) => p.senderId), [1, 3]);
  assert.equal(small.isLocal(2), false);
});

test("forwarding rules D1-D4: where an audio frame goes", () => {
  // D1: our own voice goes to the local transports and to Reticulum, forwarder or not.
  for (const forwarder of [true, false]) assert.deepEqual(F.route({ origin: "own", forwarder, senderRnsUp: false }), { local: true, rns: true });
  // D2: heard locally: to Reticulum only from the forwarder, and only for a talker not on Reticulum itself.
  assert.deepEqual(F.route({ origin: "local", forwarder: true, senderRnsUp: false }), { local: false, rns: true });
  assert.deepEqual(F.route({ origin: "local", forwarder: true, senderRnsUp: true }), { local: false, rns: false });
  assert.deepEqual(F.route({ origin: "local", forwarder: false, senderRnsUp: false }), { local: false, rns: false });
  // D3: heard over Reticulum: to the local transports only from the forwarder. D4: never back into Reticulum.
  assert.deepEqual(F.route({ origin: "rns", forwarder: true, senderRnsUp: false }), { local: true, rns: false });
  assert.deepEqual(F.route({ origin: "rns", forwarder: false, senderRnsUp: true }), { local: false, rns: false });
  assert.deepEqual(F.route({ origin: "elsewhere" }), { local: false, rns: false });
});

test("split horizon: a link to a local sender or to the talker gets nothing; an unknown far end gets it", () => {
  const isLocal = (id) => id === 10;
  const me = 50;
  assert.equal(F.linkTakes(null, me, isLocal), true, "far end not learnt yet");
  assert.equal(F.linkTakes(undefined, me, isLocal), true);
  assert.equal(F.linkTakes(10, me, isLocal), false, "it hears the boat on the LAN");
  assert.equal(F.linkTakes(20, me, isLocal), true, "a phone ashore");
  assert.equal(F.linkTakes(20, 20, isLocal), false, "the talker's own link");
  assert.equal(F.linkTakes(0x80000000 | 0, 0x80000000 | 0, isLocal), false);
});
