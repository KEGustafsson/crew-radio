# Crew Radio — security design

What can go wrong, what the app does about it, and how that maps to the EU Cyber Resilience
Act's essential requirements. The disclosure policy is in the repository's
[SECURITY.md](../SECURITY.md); the code is described in [ARCHITECTURE.md](ARCHITECTURE.md).

## What the app is, security-wise

A voice intercom between a few phones over WLAN, Bluetooth and Wi‑Fi Aware, with each phone
repeating packets for the others. No server, no account, no internet traffic, no stored
recordings. The assets are the crew's conversation (confidentiality), the crew's ability to talk
(availability) and the certainty that a voice on the channel is a crew member (authenticity).

## Threats and what is done about them

| Threat | Where | What the app does |
| --- | --- | --- |
| Eavesdropping on the conversation | Anyone on the same WLAN; Bluetooth or Aware only after joining the link | Every packet is encrypted with AES‑256‑GCM under a key derived from the crew's **channel key**. Bluetooth links are additionally link-encrypted (bonded RFCOMM), Aware links by their own PSK, which is *derived from* the packet key rather than being the channel key itself, so capturing an Aware handshake gives an attacker nothing cheaper to attack than the packet key. |
| Injecting audio, spoofing a crew member, feeding junk into the relay | Same | Every packet is authenticated (GCM tag over the payload and the header). A packet without the key fails the tag and is dropped before it reaches the relay, the roster or a decoder. |
| Replaying captured packets | Same | Every packet carries an authenticated **timestamp**: one more than 60 s away from the receiver's clock is dropped before any cache is touched. Inside that window the seen-cache drops repeats (16 384 audio entries, 2 048 hellos — more than the window holds), and per-sender sequence high-water marks are kept for the life of the process, across reconnects and across a sender leaving the roster, so a late or repeated audio frame is dropped too. Those marks cover audio only: a hello is caught by its own seen-cache and by the timestamp, which is what bounds how long a captured hello could be replayed. Phones therefore need roughly correct clocks; a phone whose clock is far out says so on its status line. |
| Flooding a phone or the mesh | Any peer with the key, or a bug | A global ingress budget before authentication, a small separate budget charged only to packets that *fail* the tag (so junk is bounded without starving real traffic), and a per-sender budget charged after the AEAD check and the duplicate look, so a forged sender id can neither buy more nor starve a real sender. A hop limit (default 4, clamped to the receiver's own and to the sender's signed budget) bounds circulation; at most 64 roster entries, 128 rate-limited senders, 8 decoders, 16 links per transport, 8 outstanding Aware dials, bounded queues and caches; oversized packets (over 1024 bytes) are dropped unread. Outgoing frames sit in bounded per-link queues, so one stalled peer cannot block the mic, the heartbeat or the relay. |
| Reaching a phone's listening socket from outside the mesh | The boat or marina WLAN | The Wi‑Fi Aware data-path listener binds to the Aware interface and accepts only link-local peers on it; the LAN transport is UDP with no listening service beyond the channel port. |
| A stranger's device pretending to be a crew publisher | Wi‑Fi Aware radio range | The Aware service-specific info carries an HMAC of the sender id under the packet key. A discovery whose tag does not verify is ignored before any network request is made. |
| Malformed packets crashing the app | Any peer | Fixed-size header validated first; hello payload decoded strictly (length, UTF‑8, control *and* format characters stripped, trailing bytes refused); audio only reaches the platform Opus decoder, itself hardened, and a decoder failure is contained to that sender and retried later. Transport threads catch everything and report instead of dying. |
| A joined phone reading other phones' data | Crew member | There is nothing else to read: the wire carries voice and hellos (name, transports, hop budget, build number). |
| Someone with the key joining unnoticed | Anyone who learned the key | Not prevented: possession of the key is membership. The roster shows every member, where they arrive from and what build they run; change the channel key to evict. |
| Loss or theft of a phone | Physical | The channel key is in the app's private preferences on a device-encrypted phone, excluded from cloud backup and from device-to-device transfer. It is shown masked in Settings and revealed only on request. Change the key on the rest of the crew. |
| A malicious update | Supply chain | Releases are built by GitHub Actions from `main`, signed with the crew's release key held only as a repository secret (scoped to the two steps that need it and deleted from the runner afterwards), with a signed build-provenance attestation over the APK, its SBOM and its checksum. Gradle resolves only verified dependencies (`gradle/verification-metadata.xml`) from a checksum-pinned Gradle distribution. Dependencies are AndroidX and Material only, updated by Dependabot; CodeQL scans the Kotlin, the plugin's JavaScript and the workflows. |
| Weak default configuration | First use | There is no default key: each phone generates a random one (~59 bits) on first start and the crew shares it. A key typed by hand must be at least 12 characters. Relay, Opus and the rate limits need no configuration. |

### Asking the boat (Signal K)

A new outward path, and the app's only HTTP traffic. It is worth stating plainly what it does and
does not touch:

| | |
| --- | --- |
| What leaves the phone | A read of the boat's own Signal K tree, an optional announcement, and the bearer token that authorises them — to an address the crew configured, on the boat's LAN. |
| What never leaves the phone | The crew's speech. Recognition is on-device only (`SpeechRecognizer.createOnDeviceSpeechRecognizer`, Android 12+); where a phone cannot do it locally the feature is disabled and says so, rather than falling back to a network recogniser. |
| What the channel does not do | Nothing about the channel changes. Every packet between phones is still AES-256-GCM under the channel key over UDP, RFCOMM or Wi-Fi Aware, and none of it goes through the HTTP stack. |
| Cleartext | Permitted by `res/xml/network_security_config.xml`, because a boat server has no certificate anybody can issue for `192.168.1.9` and the host cannot be listed in advance. The token and the readings therefore cross the boat's own network in the clear; anyone already on that network can read them, and can read Signal K directly anyway. |
| The token | Issued by Signal K's own access-request flow — the phone asks, somebody approves once in the admin page — so no credential is typed, shown or read aloud. It is stored in the same SharedPreferences file as the channel key and is covered by the same cloud-backup and device-transfer exclusion, so it does not follow a phone that is sold or restored elsewhere. |
| Blast radius | Read-only by construction on the phone's side: the app only ever GETs vessel data and POSTs one announcement. It never PUTs to Signal K and never acts on an answer. |

Two things this deliberately does not do: it does not send anything to a cloud service, and it does
not let an answer trigger an action. The input is a voice on an open channel, which is not an
authenticated instruction.

## What it does not do

- It does not hide *that* phones are talking: packet timing and sizes are visible on the WLAN.
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
  are therefore authenticated; a relay cannot change them, and a relay never lets the ttl exceed
  `hops`, so bumping the ttl of a captured packet cannot extend its reach.
- `time` is Unix seconds, unsigned, wrap-safe on comparison; packets outside ±60 s are dropped.
- Aware secrets are derived from the packet key, never the channel key directly:
  the PSK is `Base64(HMAC‑SHA256(key, "CrewRadio aware v4"))` and the discovery tag is the first
  8 bytes of `HMAC‑SHA256(key, "CrewRadio aware id v4" ‖ senderId)`.
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
| (2)(h) Availability of essential functions, resilience to DoS | Two-layer rate limiting with a separate budget for junk, hop limit, bounded caches, bounded per-link send queues, link and dial caps, reconnect in every transport, loss concealment. |
| (2)(i) Minimising impact on other services | Packets are small and rate-limited; Wi‑Fi multicast plus broadcast is the only "noisy" behaviour and is confined to the WLAN. |
| (2)(j) Limited attack surface | No server, no internet, no third-party networking, crypto or analytics libraries (AndroidX and Material only, for the UI), release builds shrunk with R8, permissions only for the links in use. |
| (2)(k) Reduced impact of incidents | A compromised key is changed on the crew's phones; nothing else to leak. |
| (2)(l) Security-relevant logging | The Status screen keeps the last 40 status lines and counts rejected, stale and duplicate packets; nothing leaves the phone. |
| (2)(m) Secure deletion | Uninstalling the app removes its private storage; there is no other data. |

Vulnerability handling (Annex I, Part II): SBOM per release (attested alongside the APK), private
vulnerability reporting enabled, fixes shipped as releases, this document and SECURITY.md as the
public description.
