# Changelog

All notable changes to signalk-crewradio. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow semver.

## Unreleased

### Security

- Ingress budgets on the wire, which the plugin did not have at all while the app has had three.
  The server is single-threaded and carries the boat's NMEA, AIS and autopilot deltas, so a flood
  of well-formed headers and 29 bytes of garbage — no channel key needed — made it attempt an
  AES-GCM open per packet and took the whole vessel's data with it. Now a global budget before the
  packet is opened, a junk budget charged only when the open fails, a per-sender budget after the
  AEAD and the duplicate look, and one bucket per source address on the socket itself, so a flood
  costs the flooder. `lib/wirelimit.js`, mirroring the app's `RateLimiter` and `SourceLimiter`.
- The packet path can no longer end the process. It is entered from a dgram callback, the top of a
  libuv tick, where an uncaught exception exits by default — so a listener that throws (a delta the
  server rejects, say) took the Signal K server down. `receive()` now catches and emits `fault`.
- `openLink()` and `pump()` were called and never awaited, so a throw became an unhandled
  rejection, which is also a process exit by default. Both have handlers now.

### Changed

- The channel key is checked against the app's own length rules (8–64, and 12 or more for a key
  being typed in) and the schema carries them, so a one-character key is refused by the admin UI
  and a short one is said out loud in the settings warnings rather than silently stretched.

### Fixed

- A roster name cut to 32 bytes could split an emoji and reach the phones ending in "�": the name
  is now cut between code points, as the app's `Hello` does.
- An alarm the crew silenced went on being announced every 30 s. Silencing takes "sound" out of the
  notification's method and leaves the state; the bridge now forgets it (and one that stops
  matching the include/exclude globs), and says it again only when it is raised with sound anew.
- An urgent announcement could fail to cut a normal one short: a cancel that landed between
  `speak()` and the first frame found nothing speaking yet (seen with two notifications in one
  delta and no wait for a gap). The queue's own flag now goes into `speak()` and is checked before
  every frame, the first included.
- An announcement waiting for a gap in talk was thrown away ("Cannot read properties of null") when
  the network link dropped meanwhile; it now goes back to waiting for the link, as the comment
  always promised.
- After a sentence timed out, the stuck speech worker ending late failed the next sentence on its
  replacement; only the current worker's end fails what is in flight now, and a worker with
  nothing left to answer no longer holds the process open.
- `POST /say`: a body cut short by the client going away was spoken as far as it had arrived ("Do
  not start the engine" is a different order cut short). Only a complete body is said now; an
  incomplete one is neither answered nor said.
- The network interface was chosen once, at open. A server that started before its Wi-Fi had a
  lease stayed on whatever else had an address (docker0, a VPN) for good, and a new DHCP address
  left the group joined on the old one. The link now looks again every 5 s and reopens on a change,
  as the app's LanTransport does, and container, bridge and VPN interfaces rank last in `auto`.

## 0.1.0 - 2026-09-06

First release.

### Added

- The Signal K server as a node on the Crew Radio channel over the boat's LAN or WLAN: the app's
  wire format and AES-256-GCM channel crypto, hellos and roster, duplicate suppression, packets
  paced at 20 ms. Byte-compatible with the Android app; the two test suites share one packet vector.
- Text to speech inside the plugin: Flite in WebAssembly, English, four voices (slt by default),
  16 kHz output, a chime in front, a cache for repeated sentences, units and numbers spelled out.
- say() through a PUT on `communication.crewradio.say`, `POST /plugins/signalk-crewradio/say`,
  and the in-process PropertyValue `signalk-crewradio.api`; an announcement queue where urgent
  announcements go first and cut a normal one short.
- Notification bridge: Signal K notifications at or above a chosen state that ask for sound are
  announced, urgent for `emergency`, repeated until they clear; include and exclude globs.
- The roster in Signal K: `communication.crewradio.online`, `.nodes`, `.talking`, `.speaking`.
- A web page (Webapps › Crew Radio): the network link, who is on the channel and talking, the
  queue, and a test call that says a text on the channel, normal or urgent.
- A notification is said with its state and path first ("Alarm, navigation position: no contact
  with sensor for 70 seconds"), so the crew hears where it comes from; setting `Say the state and
  the path first`. Long decimals are rounded to one place before they are spoken.
- `tools/cli.js` for testing from any machine on the boat network without Signal K.
- Delivery: frames go out 100 ms ahead of real time, and each one is also sent unicast to every
  phone heard from directly, because access points drop a few percent of multicast even in the
  same cabin; found with the phones' concealed-frame counter and the CLI.

### Security

- Packets are authenticated before duplicate suppression, so a forged header cannot displace an
  authentic packet. Texts are capped at 500 characters and the queue at 20 waiting announcements.
