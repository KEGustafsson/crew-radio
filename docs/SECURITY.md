# Crew Radio — security design

What can go wrong, what the app does about it, and how that maps to the EU Cyber Resilience
Act's essential requirements. The disclosure policy is in the repository's
[SECURITY.md](../SECURITY.md); the code is described in [ARCHITECTURE.md](ARCHITECTURE.md).

## What the app is, security-wise

A voice intercom between a few phones over WLAN, Bluetooth and Wi‑Fi Aware, with each phone
repeating packets for the others, and optionally over Reticulum through a transport node the crew
chooses. No server of the app's own, no account, no internet traffic unless Reticulum is switched
on and pointed across it, no stored recordings. The assets are the crew's conversation (confidentiality), the crew's ability to talk
(availability) and the certainty that a voice on the channel is a crew member (authenticity).

## Threats and what is done about them

| Threat | Where | What the app does |
| --- | --- | --- |
| Eavesdropping on the conversation | Anyone on the same WLAN; Bluetooth or Aware only after joining the link | Every packet is encrypted with AES‑256‑GCM under a key derived from the crew's **channel key**. Bluetooth links are additionally link-encrypted (bonded RFCOMM), Aware links by their own PSK, which is *derived from* the packet key rather than being the channel key itself, so capturing an Aware handshake gives an attacker nothing cheaper to attack than the packet key. |
| Injecting audio, spoofing a crew member, feeding junk into the relay | Same | Every packet is authenticated (GCM tag over the payload and the header). A packet without the key fails the tag and is dropped before it reaches the relay, the roster or a decoder. |
| Replaying captured packets | Same | Every packet carries an authenticated **timestamp**: one more than 60 s away from the receiver's clock is dropped before any cache is touched. Inside that window the seen-cache drops repeats (16 384 audio entries, 2 048 hellos — more than the window holds), and per-sender sequence high-water marks are kept for the life of the process, across reconnects and across a sender leaving the roster, so a late or repeated audio frame is dropped too. Those marks cover audio only: a hello is caught by its own seen-cache and by the timestamp, which is what bounds how long a captured hello could be replayed. Phones therefore need roughly correct clocks; a phone whose clock is far out says so on its status line. |
| Flooding a phone or the mesh | Anyone within radio range; no key needed | A per-source budget on the LAN socket itself, so a flood costs the flooder rather than the crew: the budgets below are charged before the packet is opened and so cannot tell a crew frame from a stranger's, and without it whoever asked fastest took them. Then a global ingress budget before authentication, a small separate budget charged only to packets that *fail* the tag (so junk is bounded without starving real traffic), and a per-sender budget charged after the AEAD check and the duplicate look, so a forged sender id can neither buy more nor starve a real sender. A hop limit (default 4, clamped to the receiver's own and to the sender's signed budget) bounds circulation; at most 64 roster entries, 128 rate-limited senders, 8 decoders, 16 links per Bluetooth or Aware transport (32 links and 64 peers on Reticulum), 8 outstanding Aware dials, bounded queues and caches; oversized packets (over 1024 bytes) are dropped unread. Outgoing frames sit in bounded per-link queues, so one stalled peer cannot block the mic, the heartbeat or the relay. |
| Taking a phone's outgoing audio | Anyone on the same WLAN | Audio goes unicast to the peers heard from, and to the group and broadcast only while none is known (and for the first five frames of every talk burst, a floor that keeps the fault audible) — so a table filled with a stranger's addresses would take every frame while the phone still looked healthy on the crew's roster and still heard them. Addresses are therefore learned only from packets that opened with the channel key (`Transport.confirmPeer`), never from the datagram. (Reticulum does not use this: its links are confirmed by a key proof of their own, below.) |
| Reaching a phone's listening socket from outside the mesh | The boat or marina WLAN | The Wi‑Fi Aware data-path listener binds to the Aware interface and accepts only link-local peers on it; the LAN transport is UDP with no listening service beyond the channel port. |
| A stranger's device pretending to be a crew publisher | Wi‑Fi Aware radio range | The Aware service-specific info carries an HMAC of the sender id under the packet key. A discovery whose tag does not verify is ignored before any network request is made. |
| Malformed packets crashing the app | Any peer | Fixed-size header validated first; hello payload decoded strictly (length, UTF‑8, control *and* format characters stripped, trailing bytes refused); audio only reaches the platform Opus decoder, itself hardened, and a decoder failure is contained to that sender and retried later. Transport threads catch everything and report instead of dying. |
| A joined phone reading other phones' data | Crew member | There is nothing else to read: the wire carries voice and hellos (name, transports, hop budget, build number). |
| Someone with the key joining unnoticed | Anyone who learned the key | Not prevented: possession of the key is membership. The roster shows every member, where they arrive from and what build they run; change the channel key to evict. |
| Loss or theft of a phone | Physical | The channel key is in the app's private preferences on a device-encrypted phone, excluded from cloud backup and from device-to-device transfer. It is shown masked in Settings and revealed only on request. Change the key on the rest of the crew. |
| A malicious update | Supply chain | Releases are built by GitHub Actions from `main`, signed with the crew's release key held only as a repository secret (scoped to the two steps that need it and deleted from the runner afterwards), with a signed build-provenance attestation over the APK, its SBOM and its checksum. Gradle resolves only verified dependencies (`gradle/verification-metadata.xml`) from a checksum-pinned Gradle distribution. Dependencies are AndroidX and Material only, updated by Dependabot; CodeQL scans the Kotlin, the plugin's JavaScript and the workflows. |
| Weak default configuration | First use | There is no default key: each phone generates a random one (~59 bits) on first start and the crew shares it. A key typed by hand must be at least 12 characters. Relay, Opus and the rate limits need no configuration. |

### Reticulum (optional, off by default)

The one transport that can leave the boat. With the **RETICULUM** tile on, the phone (or the Signal K
plugin) opens a TCP connection to the Reticulum transport node the crew set, and the channel's
sealed packets travel inside Reticulum links through whatever network that node belongs to —
possibly the public internet and other people's transport nodes.

| | |
| --- | --- |
| Confidentiality and authenticity | Unchanged: every channel packet is still AES‑256‑GCM under the packet key end to end, and passes the same `Ingress` checks (budgets, AEAD, timestamp, seen-cache) as a packet from any other link. Reticulum adds its own link encryption around it (X25519, HKDF‑SHA256, AES‑256‑CBC + HMAC‑SHA256), so a transport node sees neither the audio nor the header; nor does anyone who links in, since a link carries nothing until the far end has proved the key (below). A hub that tries to sit in the middle, ending each link itself, cannot prove the key to either side: the proof is bound to each link's id. |
| What a transport node and the network do see | That a destination named `crewradio.channel.<tag>` exists and announces, the timing and size of the link traffic, and the TCP peer's IP address. The tag is an HMAC of the packet key (`ChannelCrypto.reticulumTag`), so it does not reveal the channel key or which crew it is, but the same channel announces the same name hash, so its announces can be linked to each other. Each session uses a fresh Reticulum identity, never stored. The tag also lets whoever sees an announce test guesses at the channel key offline (one 600 000-round PBKDF2 per guess), as a packet captured on the boat's WLAN always did, but announces travel further: the random key the app makes is out of reach, a short typed one is not. |
| A stranger linking in | Anyone can copy the public name hash, announce under it and be dialled, or link to a crew node directly. A link carries nothing, in either direction, until the far end has sent its key proof: `0x80 \| HMAC-SHA256(confirm key, role \| link id)`, the confirm key an HMAC of the packet key. It is bound to the link id (fresh keys on every link) and to the sender's role, so a proof cannot be copied from another link nor reflected back to its maker, and a sealed channel packet copied from anywhere proves nothing (an earlier design confirmed a link on any packet that opened with the key, which a copied packet did). A link whose far end has not proved the key within 15 s is closed. So a stranger learns that the node is there, nothing more, and never receives a packet of the channel. At most 32 links and 64 peers per node; a full table makes room by closing the unconfirmed link that has waited longest (else dropping the oldest of our own requests still unanswered), never one younger than 5 s (a crew link needs a round trip to prove itself), or by forgetting the peer heard from longest ago with no confirmed link (a peer whose link just dropped counts as heard then). A link that has proved the key is never evicted. A per-link budget (400 packets/s) comes before the engine's own. |
| Flooding through Reticulum | Only confirmed links reach the engine, so a stranger's link cannot spend the global, junk or per-sender budgets the LAN shares. Reticulum packets that are not ours (other destinations' announces) cost a name-hash comparison; announces under our name and link requests to us cost a signature (and a link request a key agreement), so they come out of a budget of 10 a second each (bursts of 20) before any of that work, done in the app outside the lock the audio path takes. Copies of an announce already checked (the same announce arrives by several paths) and requests that could get no slot spend nothing. A link request says nothing about who sent it, so a sustained flood at or above that rate can still keep *new* links from forming while it lasts; confirmed links are unaffected. |
| Replay | Unchanged: the channel timestamp and seen-cache apply to what arrives over Reticulum as to everything else. A Reticulum announce replayed with an older emission time is ignored. |
| Implementation | Written from the Reticulum manual; no Reticulum code is included. X25519 and Ed25519 are hand-written in Kotlin (`rns/Curve25519`: the public-domain TweetNaCl design, masked swaps instead of secret-dependent branches, constants computed from their definitions) because the platform has neither before API 33, tested against RFC 7748 and RFC 8032 and against Node's OpenSSL through `sk-plugin/test/rns.vector.json`, whose values were checked against the reference implementation. The JVM's `BigInteger` is used only on public values (the constants, and a signature's S when checking that it is canonical). Interface access codes are not supported. |
| Relaying | A phone or the plugin with Reticulum and another transport relays between them like any bridge, under the same hop limit; nothing is relayed from one Reticulum link to another. |

### Asking the boat (Signal K)

A new outward path, and the app's only HTTP traffic. It is worth stating plainly what it does and
does not touch:

| | |
| --- | --- |
| What leaves the phone | A read of the boat's own Signal K tree, an optional announcement, and the bearer token that authorises them — to an address the crew configured, on the boat's LAN. |
| What never leaves the phone | The crew's speech. Recognition is on-device only (`SpeechRecognizer.createOnDeviceSpeechRecognizer`, Android 12+); where a phone cannot do it locally the feature is disabled and says so, rather than falling back to a network recogniser. |
| What the channel does not do | Nothing about the channel changes. Every packet between phones is still AES-256-GCM under the channel key over UDP, RFCOMM or Wi-Fi Aware, and none of it goes through the HTTP stack. |
| Cleartext | Permitted by `android:usesCleartextTraffic` in the manifest (the reasoning is written beside it), because a boat server has no certificate anybody can issue for `192.168.1.9` and the host cannot be listed in advance. The token and the readings therefore cross the boat's own network in the clear; anyone already on that network can read them, and can read Signal K directly anyway. |
| The token | Issued by Signal K's own access-request flow — the phone asks, somebody approves once in the admin page — so no credential is typed, shown or read aloud. It is stored in the same SharedPreferences file as the channel key and is covered by the same cloud-backup and device-transfer exclusion, so it does not follow a phone that is sold or restored elsewhere. |
| Blast radius | Read-only by construction on the phone's side: the app only ever GETs vessel data and POSTs one announcement. It never PUTs to Signal K and never acts on an answer. |

Two things this deliberately does not do: it does not send anything to a cloud service, and it does
not let an answer trigger an action. The input is a voice on an open channel, which is not an
authenticated instruction.

## What it does not do

- It does not hide *that* phones are talking: packet timing and sizes are visible on the WLAN,
  and with Reticulum on, to the transport nodes on the way and their networks.
- It does not authenticate individual people: the key is shared by the crew, as on a VHF channel.
- It does not protect against a crew member's phone that is itself compromised.
- It does not survive a badly wrong clock: a phone more than a minute out cannot be heard, by
  design. This is the cost of stopping replays without a handshake.
- The Bluetooth and Aware links, the phone's audio stack and headsets are the platform's; see
  SECURITY.md for what is in scope.

## The wire format

```text
'P' 'T' | version = 4 | codec | ttl | hops | senderId int32 | seq int32 | time uint32   (18-byte header)
nonce (12) | ciphertext | tag (16)                                                      (sealed payload)
```

- Key: PBKDF2‑HMAC‑SHA256 over the channel key with a fixed application salt
  (`CrewRadio channel key v4`), 600 000 iterations, 256 bits. Deterministic, so every phone with
  the same channel key derives the same key; derived once per session, off the main thread,
  because it takes about a second on a phone.
- AEAD: AES‑256‑GCM, a fresh 96‑bit random nonce per packet (`SecureRandom`), 128‑bit tag. Random
  nonces under one key are safe to about 2³² packets (NIST SP 800‑38D); a crew talking
  continuously would reach that in years, and changing the channel key resets it.
- Associated data: the 18‑byte header with the ttl byte zeroed, because relays decrement the ttl
  in place. Sender id, sequence, codec, timestamp and the sender's original hop budget (`hops`)
  are therefore authenticated, and a relay never lets the ttl exceed `hops`, so bumping the ttl of
  a captured packet cannot extend its reach. The ttl itself is not authenticated and cannot be:
  relays rewrite it. Anyone in radio range can therefore replay a captured frame with it
  *lowered*, and that used to silence the far side of the mesh — arriving first, the forged copy
  took the packet's place in the seen-cache with a ttl that relays nothing, and the genuine copy
  behind it was only a duplicate. The seen-cache now holds the highest ttl a packet has been
  forwarded with and forwards a copy that would reach further, so lowering one buys nothing. What
  a lowered copy can still do is make the *roster's* hop count for that packet wrong, since the
  count is read off the first copy admitted: a number on a screen, not the audio.
- `time` is Unix seconds, unsigned, wrap-safe on comparison; packets outside ±60 s are dropped.
- Aware secrets are derived from the packet key, never the channel key directly:
  the PSK is `Base64(HMAC‑SHA256(key, "CrewRadio aware v4"))` and the discovery tag is the first
  8 bytes of `HMAC‑SHA256(key, "CrewRadio aware id v4" ‖ senderId)`.
- Reticulum: the destination is `crewradio.channel.<tag>`, the tag the first 8 bytes of
  `HMAC‑SHA256(key, "CrewRadio reticulum v1")` in hex. Each channel packet rides in one Reticulum
  link packet behind a byte `0x01`, or, when it does not fit the 431-byte link payload (a PCM
  frame), in two or three parts `count | index | id | bytes`. Before any of that, each end sends
  its key proof `0x80 | HMAC‑SHA256(confirm key, role | link id)` (role 1 = the end that dialled),
  the confirm key `HMAC‑SHA256(key, "CrewRadio reticulum confirm v1")`; a link carries nothing
  else until the far end's proof has checked out.
- Codec 0 = PCM16LE frame, 1 = Opus packet, 2 = hello (`ver=2 | transports | ttl | versionCode
  uint16 | nameLen | name`).
- Cost: 46 bytes on top of the payload (18 header, 12 nonce, 16 tag), about 2.3 kB/s at 50
  packets a second; 28 of those bytes are the AEAD's.

There is no legacy decoding: every phone and the Signal K plugin must run the matching build. The
roster marks a peer whose build differs from this phone's.

## CRA Annex I mapping (informative)

Working papers for the commercial case — what would apply, where the product actually stands
against each requirement, and the order to close the gaps in — are in
[docs/cra/](cra/README.md).

The app is open source and not placed on the market commercially, so the Cyber Resilience Act's
manufacturer obligations do not apply to it; its essential requirements are still a good
checklist, and this is where the app stands against each:

| Requirement (Annex I, Part I) | Status |
| --- | --- |
| (1) Appropriate level of cybersecurity based on the risks | Threat model above; risks are eavesdropping, injection, replay and flooding on a shared radio medium. |
| (2)(a) No known exploitable vulnerabilities at release | CodeQL (Kotlin, JavaScript, workflows) on every change; Dependabot alerts; dependency and Gradle-distribution checksums; only the latest release supported. |
| (2)(b) Secure by default configuration | Random channel key generated on first start; no default passphrase; Aware secrets derived, not shared; relay and rate limits bounded by default. |
| (2)(c) Security updates | A signed release per merge; the crew updates together (the wire format enforces a single version, and the roster shows who is behind). No automatic update: the app has no network access to a server by design. |
| (2)(d) Protection from unauthorised access | AEAD on every packet: no key, no access. Listening sockets are bound to the link they serve. |
| (2)(e) Confidentiality of data | AES‑256‑GCM on the wire; nothing stored except settings, in app-private storage excluded from backup and device transfer. |
| (2)(f) Integrity of data, commands and configuration | GCM tag over payload and header, including the timestamp and hop budget; settings are local to each phone, or set by an administrator through managed configuration. |
| (2)(g) Data minimisation | The wire carries voice frames, a name and a build number; nothing else is collected or kept. |
| (2)(h) Availability of essential functions, resilience to DoS | A budget per source address on the LAN socket, then the global, junk and per-sender budgets in the engine; a duplicate is relayed when it would reach further, so a lowered ttl cannot cut the mesh; peers are learned only from packets the AEAD opened, so a stranger cannot take a phone's outgoing audio; hop limit, bounded caches, bounded per-link send queues, link and dial caps, reconnect in every transport, loss concealment. The audio threads catch and report like the transport threads, and the roster says when no link is up. |
| (2)(i) Minimising impact on other services | Packets are small and rate-limited; Wi‑Fi multicast plus broadcast is the only "noisy" behaviour and is confined to the WLAN. |
| (2)(j) Limited attack surface | No server, no internet unless Reticulum is switched on (off by default, one outgoing TCP connection to a node the crew names, no listening socket), no third-party networking, crypto or analytics libraries (AndroidX and Material only, for the UI), release builds shrunk with R8, permissions only for the links in use. |
| (2)(k) Reduced impact of incidents | A compromised key is changed on the crew's phones; nothing else to leak. |
| (2)(l) Security-relevant logging | The Status screen keeps the last 40 status lines and counts rejected, stale and duplicate packets; nothing leaves the phone. |
| (2)(m) Secure deletion | Uninstalling the app removes its private storage; there is no other data. |

Vulnerability handling (Annex I, Part II): SBOM per release (attested alongside the APK), private
vulnerability reporting enabled, fixes shipped as releases, this document and SECURITY.md as the
public description.
