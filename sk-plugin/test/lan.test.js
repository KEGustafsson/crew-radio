// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const dgram = require("node:dgram");
const { LanLink, chooseInterface, broadcastOf } = require("../lib/lan");

test("broadcast address from address and netmask", () => {
  assert.equal(broadcastOf("192.168.0.34", "255.255.255.0"), "192.168.0.255");
  assert.equal(broadcastOf("10.1.2.3", "255.255.0.0"), "10.1.255.255");
  assert.equal(broadcastOf("172.16.5.9", "255.255.255.240"), "172.16.5.15");
  assert.equal(broadcastOf("192.168.0.34", undefined), "192.168.0.255");
  assert.equal(broadcastOf("bad", "255.255.255.0"), null);
});

test("chooseInterface: a named interface that does not exist is null; auto prefers Wi-Fi names", () => {
  assert.equal(chooseInterface("no-such-interface-xyz"), null);
  const auto = chooseInterface(null);
  if (auto) {
    assert.match(auto.address, /^\d+\.\d+\.\d+\.\d+$/);
    assert.equal(chooseInterface(auto.name).address, auto.address);
  }
});

test("opening a link on the machine's interface binds, joins the group and can send; closing is idempotent", async (t) => {
  const pick = chooseInterface(null);
  if (!pick) { t.skip("no IPv4 interface on this machine"); return; }
  const link = new LanLink({ group: "239.255.42.1", port: 40000 + Math.floor(Math.random() * 20000), iface: pick.name });
  let where;
  try {
    where = await link.open();
  } catch (e) {
    t.skip(`multicast not available here: ${e.message}`);
    return;
  }
  try {
    assert.equal(where.iface, pick.name);
    assert.equal(where.address, pick.address);
    assert.equal(link.send(Buffer.from("hello")), true);
  } finally {
    link.close();
    link.close();
  }
  assert.equal(link.send(Buffer.from("x")), false, "closed links do not send");
});

/** A link that is not bound to a socket, so send() can be watched without one. */
function fakeLink(sent) {
  const link = new LanLink({ group: "239.255.42.1", port: 47474 });
  link.sock = { send: (buf, port, addr) => sent.push(`${addr}:${port}`) };
  link.address = "192.168.0.10";
  link.netmask = "255.255.255.0";
  link.broadcast = "192.168.0.255";
  return link;
}

test("send() goes to the group, the broadcast address and each unicast target", () => {
  const sent = [];
  const link = fakeLink(sent);
  assert.equal(link.send(Buffer.from("x"), ["192.168.0.30", "192.168.0.35"]), true);
  assert.deepEqual(sent, ["239.255.42.1:47474", "192.168.0.255:47474", "192.168.0.30:47474", "192.168.0.35:47474"]);
  sent.length = 0;
  link.send(Buffer.from("x"));
  assert.deepEqual(sent, ["239.255.42.1:47474", "192.168.0.255:47474"]);
});

test("a unicast target off our subnet is skipped: the source address of a packet is not authenticated", () => {
  const sent = [];
  const link = fakeLink(sent);
  // What a node would have learnt from a genuine packet whose IP source was rewritten on the path.
  link.send(Buffer.from("x"), ["192.168.0.30", "203.0.113.7", "192.168.1.30", "not-an-address", "8.8.8.8"]);
  assert.deepEqual(sent, ["239.255.42.1:47474", "192.168.0.255:47474", "192.168.0.30:47474"],
    "the group and the broadcast still go out; only the off-subnet copies are dropped");
});

test("onSubnet: the netmask decides, and an unbound link trusts nothing", () => {
  const link = new LanLink({ group: "239.255.42.1", port: 47474 });
  assert.equal(link.onSubnet("192.168.0.30"), false, "not bound yet");
  link.address = "10.1.2.3";
  link.netmask = "255.255.0.0";
  assert.equal(link.onSubnet("10.1.250.9"), true);
  assert.equal(link.onSubnet("10.2.0.9"), false);
  link.netmask = "255.255.255.240";
  assert.equal(link.onSubnet("10.1.2.14"), true);
  assert.equal(link.onSubnet("10.1.2.17"), false);
  assert.equal(link.onSubnet("10.1.2"), false);
  assert.equal(link.onSubnet("10.1.2.999"), false);
  assert.equal(link.onSubnet(undefined), false);
});

test("chooseInterface reports the netmask, so a bound link can tell its own subnet", () => {
  const pick = chooseInterface(null);
  if (pick) assert.match(pick.netmask, /^\d+\.\d+\.\d+\.\d+$/);
});

/** Watches the sockets lan.js creates, so a test can see whether a failed one was closed. */
function watchSockets(t) {
  const made = [];
  const orig = dgram.createSocket;
  dgram.createSocket = (...a) => { const s = orig(...a); made.push(s); return s; };
  t.after(() => { dgram.createSocket = orig; });
  return made;
}
const isClosed = (s) => { try { s.address(); return false; } catch { return true; } };

test("a bad group address rejects open() instead of throwing later, and the socket is closed, not leaked", async (t) => {
  const pick = chooseInterface(null);
  if (!pick) { t.skip("no IPv4 interface on this machine"); return; }
  const made = watchSockets(t);
  const link = new LanLink({ group: "not-an-address", port: 40000 + Math.floor(Math.random() * 20000), iface: pick.name });
  await assert.rejects(link.open());
  assert.equal(made.length, 1);
  assert.ok(isClosed(made[0]));
  assert.equal(link.sock, null);
});

test("a port outside 1024-65535 rejects open() before a socket is made", async (t) => {
  const pick = chooseInterface(null);
  if (!pick) { t.skip("no IPv4 interface on this machine"); return; }
  const made = watchSockets(t);
  const link = new LanLink({ group: "239.255.42.1", port: 70000, iface: pick.name });
  await assert.rejects(link.open(), /port 70000 is not 1024-65535/);
  assert.equal(made.length, 0, "refused before a socket was made");
});

test("an unknown named interface rejects open()", async () => {
  const link = new LanLink({ group: "239.255.42.1", port: 47474, iface: "no-such-interface-xyz" });
  await assert.rejects(link.open(), /no IPv4 address/);
});

test("checkInterface: a link whose interface changed or went away closes and says so; one that did not stays", () => {
  const ifs = { eth0: [{ family: "IPv4", internal: false, address: "192.168.0.10", netmask: "255.255.255.0" }] };
  const link = new LanLink({ group: "239.255.42.1", port: 47474, interfaces: () => ifs });
  let closed = 0;
  const errors = [];
  link.on("error", (e) => errors.push(e.message));
  const bind = () => { link.sock = { close: () => closed++ }; link.iface = "eth0"; link.address = "192.168.0.10"; link.netmask = "255.255.255.0"; };
  bind();
  assert.equal(link.checkInterface(), true);
  assert.equal(errors.length, 0);
  ifs.eth0[0].address = "192.168.0.44";          // a new DHCP lease
  assert.equal(link.checkInterface(), false);
  assert.equal(closed, 1);
  assert.equal(link.sock, null);
  assert.match(errors[0], /interface changed \(eth0 192.168.0.10 -> eth0 192.168.0.44\)/);
  bind();
  ifs.wlan0 = [{ family: "IPv4", internal: false, address: "10.10.10.2", netmask: "255.255.255.0" }];
  assert.equal(link.checkInterface(), false, "a better interface came up (Wi-Fi after the lease)");
  bind();
  delete ifs.wlan0; delete ifs.eth0;
  assert.equal(link.checkInterface(), false);
  assert.match(errors[2], /went away/);
  assert.equal(link.checkInterface(), false, "a closed link checks nothing");
  assert.equal(errors.length, 3);
});

test("chooseInterface puts container, bridge and VPN interfaces last", () => {
  const v4 = (address) => [{ family: "IPv4", internal: false, address, netmask: "255.255.255.0" }];
  assert.equal(chooseInterface(null, { docker0: v4("172.17.0.1"), usb0: v4("192.168.7.2") }).name, "usb0");
  assert.equal(chooseInterface(null, { "br-1a2b": v4("172.18.0.1"), tailscale0: v4("100.64.0.1") }).name, "br-1a2b", "still picked when nothing else is there");
  assert.equal(chooseInterface(null, { docker0: v4("172.17.0.1"), wlan0: v4("10.0.0.5") }).name, "wlan0");
});

test("the interface re-check runs on a timer while bound and stops with close()", async (t) => {
  const pick = chooseInterface(null);
  if (!pick) { t.skip("no IPv4 interface on this machine"); return; }
  let lookups = 0;
  const link = new LanLink({ group: "239.255.42.1", port: 40000 + Math.floor(Math.random() * 20000), iface: pick.name, recheckMs: 10,
    interfaces: () => { lookups++; return require("node:os").networkInterfaces(); } });
  try {
    await link.open();
  } catch (e) {
    t.skip(`multicast not available here: ${e.message}`);
    return;
  }
  const t0 = Date.now();
  while (lookups < 3 && Date.now() - t0 < 2000) await new Promise((r) => setTimeout(r, 10));
  link.close();
  assert.ok(lookups >= 3, `re-checked while bound (${lookups} lookups)`);
  const after = lookups;
  await new Promise((r) => setTimeout(r, 40));
  assert.equal(lookups, after, "and not after close()");
});
