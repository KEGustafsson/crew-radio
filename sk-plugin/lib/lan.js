// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * The LAN/WLAN link, the way the app's LanTransport does it (the server may sit on the wired LAN
 * that the phones' WLAN is bridged to; multicast and broadcast cross that bridge like any other frame): one UDP socket bound to the port on
 * every address, joined to the multicast group on the chosen interface, and every packet sent
 * twice, to the group and to the interface's IPv4 broadcast address, because plenty of access
 * points filter multicast. The receiving side drops the duplicate by (sender, seq).
 *
 * The interface is looked at again every few seconds (checkInterface): a socket bound to every
 * address raises no error when the address it joined the group on goes away or a better interface
 * comes up (a Pi whose Signal K starts before wlan0 has its lease), so a change is reported as
 * an error and the owner reopens, as the app's LanTransport does on an interface or address change.
 *
 * Events: 'packet' (buf, rinfo), 'error' (the socket is dead; the owner reopens it), 'listening'.
 */

const dgram = require("node:dgram");
const os = require("node:os");
const { EventEmitter } = require("node:events");
const { MAX_SIZE } = require("./packet");
const { PeerBudget } = require("./wirelimit");

class LanLink extends EventEmitter {
  /**
   * @param {{group: string, port: number, iface?: string, recheckMs?: number, interfaces?: () => object}} opts
   *   iface: interface name, or "auto"/empty; recheckMs: how often the interface is looked at again
   *   (0: never); interfaces: os.networkInterfaces, replaceable for tests
   */
  constructor(opts) {
    super();
    this.group = opts.group;
    this.port = opts.port;
    this.ifaceName = opts.iface && opts.iface !== "auto" ? opts.iface : null;
    this.sock = null;
    this.address = null;
    this.netmask = null;
    this.broadcast = null;
    this.iface = null;
    this.peers = opts.peers ?? new PeerBudget();   // one ingress budget per source address
    this.recheckMs = opts.recheckMs ?? 5000;
    this.interfaces = opts.interfaces ?? (() => os.networkInterfaces());
    this.recheck = null;
  }

  /** Resolves with `{iface, address, broadcast}` once bound and joined; a socket that fails to bind is closed, not leaked. */
  open() {
    if (!Number.isInteger(this.port) || this.port < 1024 || this.port > 65535) {
      return Promise.reject(new Error(`UDP port ${this.port} is not 1024-65535`));
    }
    const pick = chooseInterface(this.ifaceName, this.interfaces());
    if (!pick) return Promise.reject(new Error(this.ifaceName ? `interface ${this.ifaceName} has no IPv4 address` : "no usable IPv4 interface"));
    return new Promise((resolve, reject) => {
      const sock = dgram.createSocket({ type: "udp4", reuseAddr: true });
      let bound = false;
      const fail = (e) => { try { sock.close(); } catch { /* already closed */ } reject(e); };
      sock.on("error", (e) => {
        if (!bound) return fail(e);
        this.close();
        this.emit("error", e);
      });
      sock.on("message", (buf, rinfo) => {
        if (buf.length > MAX_SIZE) return; // dropped unread, as the app does
        // Per-source budget before anything else. Everything downstream is charged to a budget
        // that cannot yet tell who sent the packet, so without this one host on the boat network
        // takes the lot and the crew's frames queue behind it.
        if (rinfo && rinfo.address && !this.peers.allow(rinfo.address)) return;
        this.emit("packet", buf, rinfo);
      });
      try {
        sock.bind(this.port, "0.0.0.0", () => {
          try {
            sock.setBroadcast(true);
            sock.setMulticastTTL(1);
            sock.setMulticastLoopback(false);
            sock.setMulticastInterface(pick.address);
            sock.addMembership(this.group, pick.address);
          } catch (e) {
            fail(e);
            return;
          }
          bound = true;
          this.sock = sock;
          this.iface = pick.name;
          this.address = pick.address;
          this.netmask = pick.netmask;
          this.broadcast = pick.broadcast;
          if (this.recheckMs > 0) {
            this.recheck = setInterval(() => this.checkInterface(), this.recheckMs);
            if (this.recheck.unref) this.recheck.unref();
          }
          this.emit("listening", { iface: pick.name, address: pick.address, broadcast: pick.broadcast });
          resolve({ iface: pick.name, address: pick.address, broadcast: pick.broadcast });
        });
      } catch (e) {
        fail(e);                                   // a port out of range throws before any callback
      }
    });
  }

  /**
   * Sends to the group, to the subnet broadcast and, when given, to each address in `unicast`:
   * an access point sends multicast and broadcast at its lowest rate without acknowledgement,
   * so phones lose a few percent of them even in the same cabin, whereas unicast is retried
   * and rate-adapted. The phones drop the copies they get twice by (sender, seq). Transient
   * failures are ignored, as in the app.
   *
   * A unicast address off this interface's subnet is skipped: see [onSubnet].
   */
  send(buf, unicast = []) {
    const s = this.sock;
    if (!s) return false;
    s.send(buf, this.port, this.group, () => {});
    if (this.broadcast) s.send(buf, this.port, this.broadcast, () => {});
    for (const a of unicast) if (this.onSubnet(a)) s.send(buf, this.port, a, () => {});
    return true;
  }

  /**
   * True when `addr` is on the same IPv4 subnet as the bound interface.
   *
   * The address a packet was heard from is not authenticated: the AEAD covers the header and the
   * payload, not the IP source, so anyone on the path can rewrite the source of a genuine packet
   * and have the copies sent wherever they like. Keeping them on our own subnet costs nothing
   * real - the channel is a LAN one, and the group and the broadcast go out either way - and
   * leaves nothing to reflect off the plugin with.
   */
  onSubnet(addr) {
    if (!this.address || !this.netmask || typeof addr !== "string") return false;
    const a = addr.split(".").map(Number);
    const me = this.address.split(".").map(Number);
    const m = this.netmask.split(".").map(Number);
    if (a.length !== 4 || m.length !== 4) return false;
    if (a.some((o) => !Number.isInteger(o) || o < 0 || o > 255)) return false;
    return a.every((o, i) => (o & m[i]) === (me[i] & m[i]));
  }

  /**
   * Looks at the interfaces again; when the one this link would pick now is not the one it is on
   * (gone, readdressed, or a better one up), the link closes and emits 'error' for the owner to
   * reopen. True while the link stays as it is.
   */
  checkInterface() {
    if (!this.sock) return false;
    let pick;
    try {
      pick = chooseInterface(this.ifaceName, this.interfaces());
    } catch {
      return true;                                 // the lookup failing is not the interface changing
    }
    if (pick && pick.name === this.iface && pick.address === this.address && pick.netmask === this.netmask) return true;
    const was = `${this.iface} ${this.address}`;
    this.close();
    this.emit("error", new Error(pick ? `interface changed (${was} -> ${pick.name} ${pick.address})` : `interface ${was} went away`));
    return false;
  }

  close() {
    if (this.recheck) { clearInterval(this.recheck); this.recheck = null; }
    const s = this.sock;
    this.sock = null;
    if (s) {
      try { s.close(); } catch { /* already closed */ }
    }
  }
}

/**
 * Prefer the named interface; else a wlan interface, then eth or en, then any up non-internal IPv4
 * interface, and last the virtual ones (containers, bridges, VPNs), which are never the boat's network.
 */
function chooseInterface(name, all = os.networkInterfaces()) {
  const candidates = [];
  for (const [ifName, addrs] of Object.entries(all)) {
    for (const a of addrs ?? []) {
      if (a.family !== "IPv4" && a.family !== 4) continue;
      if (a.internal) continue;
      candidates.push({ name: ifName, address: a.address, netmask: a.netmask || "255.255.255.0", broadcast: broadcastOf(a.address, a.netmask) });
    }
  }
  if (name) return candidates.find((c) => c.name === name) ?? null;
  const rank = (c) => (/^wl|wi-?fi|wlan/i.test(c.name) ? 0 : /^(eth|en)/i.test(c.name) ? 1 : VIRTUAL.test(c.name) ? 3 : 2);
  candidates.sort((a, b) => rank(a) - rank(b));
  return candidates[0] ?? null;
}

const VIRTUAL = /^(docker|veth|br-|virbr|cni|flannel|podman|lxc|lxd|vmnet|vboxnet|tun|tap|tailscale|zt|wg)/i;

function broadcastOf(address, netmask) {
  const a = address.split(".").map(Number);
  const m = (netmask || "255.255.255.0").split(".").map(Number);
  if (a.length !== 4 || m.length !== 4) return null;
  return a.map((o, i) => (o & m[i]) | (~m[i] & 0xff)).join(".");
}

module.exports = { LanLink, chooseInterface, broadcastOf };
