# Gap analysis

What a code review of `b4c241a` found, ranked, with evidence. The premise throughout is commercial
placement; see `scope-and-classification.md`.

## Method

Six parallel reviews over the whole tree: cryptography and wire protocol; Android platform,
privacy and data protection; the Signal K plugin; supply chain, build and release; robustness,
concurrency and test adequacy; and CRA conformity. Findings were consolidated and de-duplicated.
Four were re-checked independently against the code and are marked **verified at source**.

Not verified: the Gradle build was never executed (no Android SDK, JDK 21 in the review
environment), so every statement about build behaviour comes from reading `build.yml`, not from
running it. The plugin's tests ran 78 pass / 5 fail, all five being the absent WASM dependency.

No CVE identifiers are asserted anywhere. The absence of a known vulnerability in a dependency is a
negative claim from memory, not a clearance — which is exactly why the CI gate matters (see F-16).

## Verdict

The engineering is better than most commercial products of this size. AEAD on every packet, an
authenticated replay window, a receive pipeline whose look-charge-mark is genuinely one atomic step,
523 checksum-pinned dependencies, and a release pipeline where the token that can write to the
repository never coexists with the signing key. There is no rotten foundation here — only artefacts
to write and a handful of features to add.

Two things are nonetheless true:

1. The CRA is a product-compliance regime. It wants a named legal person, a declared support period,
   an update mechanism, a declaration and a reporting capability. The repository has none of them.
2. On **availability** — the axis that matters most for a product a crew uses while manoeuvring —
   the code does not support the claim the documentation makes. Four verified findings let an
   attacker with no channel key degrade or silence the channel, and the product cannot detect,
   display or record any of them.

| | Count |
| --- | --- |
| Critical | 1 |
| High | 19 |
| Medium | 28 |
| Low / Info | 22 |
| Hard blockers (non-technical) | 4 |

## The availability cluster

Confidentiality and integrity are strong. These five findings share one shape: an unauthenticated
attacker within radio range degrades or kills the channel, and nothing notices.

### C-1 — Unauthenticated peer-table poisoning silently redirects all outgoing audio
**Critical · verified at source · Annex I I(2)(h)**

The LAN transport inserts a datagram's source address into its peer table six lines before the
packet is parsed — before the AEAD, before the version check, before any rate limiter. Audio reaches
the multicast group and broadcast **only while that table is empty**.

```text
LanTransport.kt:194   peers.put(from, Unit, System.currentTimeMillis())
LanTransport.kt:200   onPacket(buf.copyOf(p.length), this, from)   <- parsing starts here

LanTransport.kt:233   if (hello || live.isEmpty()) { sendTo(groupAddr); sendTo(broadcastAddr) }
LanTransport.kt:237   for (a in live) if (a != except) sendTo(s, packet, a)
```

Spray UDP at port 47474 from 16 rotating source addresses, refreshed inside the 5 s `PEER_TTL_MS`.
The content is irrelevant — the packets need not parse. `MAX_PEERS` is 16 with stalest-first
eviction, so the table becomes entirely attacker-owned and every audio frame thereafter leaves the
phone addressed only to the attacker.

Hellos still go to the group, so the victim still appears healthy on everyone else's roster and
still hears everyone. They press talk while berthing, see `ON AIR`, see four shipmates listed, and
are inaudible. `txPackets` climbs normally. No counter moves and no log line is written.

**Fix.** Populate the peer table only from packets that passed the AEAD. The plumbing exists —
`onPacket` already receives the originating transport and link token, so `Ingress` can call back on
`Result.Accept`. As defence in depth, keep the group copy for the first frame of each talk burst, so
a poisoned table degrades to packet loss rather than total loss.

### H-1 — Keyless relay suppression partitions the mesh via the unauthenticated ttl
**High · verified at source · Annex I I(2)(f), (2)(h)**

The `ttl` byte is excluded from the AAD by design, because relays rewrite it. But the dedupe
decision runs before the relay decision, so the *first* copy to arrive decides whether a packet is
forwarded at all.

```text
Packet.kt:90     fun aadOf(p: ByteArray) = p.copyOf(HEADER).also { it[4] = 0 }
PttEngine.kt:768 Ingress.Result.Duplicate -> { c.duplicates.incrementAndGet(); return }
PttEngine.kt:783 if (relay && relayTtl > 0) { ... }      <- never reached for a duplicate
```

An attacker with **no key** captures a frame off the multicast group, sets byte 4 to `1`, and
unicasts it to the relay node. The tag still verifies. `Ingress.relayTtl` returns 0, so the node does
not forward — and the genuine full-ttl copy arriving moments later is discarded as a duplicate
before the relay block. The far side of the mesh goes silent; the relay node plays the audio
normally and notices nothing.

The race is structurally winnable: the genuine copy travels as multicast, which an access point
buffers until the next DTIM beacon for any station in power-save (100–300 ms on a phone), while the
attacker's re-injection is unicast and delivered promptly. A mains-powered laptop that never sleeps
has a head start of tens to hundreds of milliseconds.

The same primitive corrupts the roster hop count, which is `hello.ttl - h.ttl` (`PttEngine.kt:851`)
— see the claims table below.

**Fix, and it needs no wire change.** Store the largest usable ttl seen per `(senderId, seq)` and
forward a duplicate whose ttl is strictly greater. `SeenCache` becomes `LinkedHashMap<Long, Int>`,
compared in the same synchronized block at `Ingress.kt:81-85`. Keep the one-relay-per-packet cap;
"strictly greater" is what keeps it finite.

### H-2 — A keyless flood consumes the shared ingress budget and starves real audio
**High · Annex I I(2)(h)**

The global bucket is charged before the AEAD — necessarily, since that is its purpose — so it is
first-come-first-served and blind to authenticity. It bounds CPU, which it does well. It provides no
availability guarantee.

```text
crew of 8  ~= 816 pps on the wire (multicast + broadcast)
global bucket = 5000/s (RateLimiter.kt:24, charged at Ingress.kt:72)

attacker  5 000 pps  (2.4 Mbit/s) -> 86% of legitimate frames admitted
attacker 20 000 pps  (9.6 Mbit/s) -> 24%  - speech unintelligible
attacker 50 000 pps  (24 Mbit/s)  -> 10%  - channel dead
```

20 kpps of 60-byte UDP is a laptop, a phone, or a five-euro microcontroller. Because the target is
the multicast group, one flood hits every phone at once. Loss concealment covers three frames, not
three in four.

There is a quieter variant: `PttEngine.kt:761` returns on `h.senderId == senderId` *before*
`ingress.admit`. An attacker who reads our sender id out of any packet we send — it is cleartext —
floods with it, consumes no bucket, and triggers no "unreadable packets" warning. A dead channel
with no diagnostic at all.

**Fix.** Per-source-address token buckets in `LanTransport`, before the engine — a hash lookup, no
crypto. Reserve a share of the global budget for addresses that have recently produced an authentic
packet; the peer table already knows which those are. Remove or separately account the
`senderId == self` shortcut.

### H-3 — The Signal K plugin has no ingress rate limiting at all
**High · Annex I I(2)(h), (2)(i)**

The plugin is an otherwise faithful port: size cap, version check, AEAD before dedupe, timestamp
before any cache, wrap-aware sequence marks, bounded caches, all tested against a shared vector.
The three rate buckets are the one thing missing. Every datagram goes straight into
`createDecipheriv`.

```text
lan.js:51-53   dgram 'message' -> size check only -> emit
node.js:223    crypto.createDecipheriv(...)   <- no global, junk or per-sender budget
```

Signal K plugins run **in-process and single-threaded**, in the server carrying the boat's NMEA, AIS
and autopilot deltas. At 20 kpps the event loop saturates and the whole vessel's data server
degrades with it. That engages I(2)(i) — negative impact on the availability of services provided by
other devices — on top of I(2)(h).

Two adjacent process-killers in the same component:

- an exception anywhere in the roster-publishing chain (`node.js:283` → `index.js:338` →
  `app.handleMessage`) reaches the dgram callback as an **uncaught exception**;
- `openLink()` (`index.js:135`, `:187`) and `pump()` (`queue.js:117`) are called without `.catch`,
  so a throw becomes an **unhandled rejection**.

Node's default for both is to exit the process. The app enforces exactly this discipline with
`transportThread` — *"catch broadly, report, stay up"* — and the plugin has no equivalent.

**Fix.** Port `RateLimiter` with the same three buckets and the same order, charging the global one
in `lan.js`'s message handler before `emit`. Wrap `ChannelNode.receive` and every emit-to-host
callback in `try/catch`. Add `.catch` at both `openLink` call sites and both `pump` call sites, and
move `emit("started")` inside `pump`'s `try`.

### H-4 — None of the above is detectable, displayable or recordable
**High · verified at source · Annex I I(2)(l), Art. 14**

`Transport.ready` has four implementations and **zero call sites**.

```text
Transport.kt:22            val ready: Boolean get() = true
LanTransport.kt:54         override val ready get() = socket != null
BluetoothTransport.kt:62   override val ready get() = server != null || links.isNotEmpty()
WifiAwareTransport.kt:90   override val ready get() = session != null || links.isNotEmpty()
grep for call sites ->     none
```

So `PttEngine.isConnected` means "a Transport object exists", not "a link is up", and `sendHello`
ORs in every transport's flag unconditionally — a phone with Bluetooth switched off still advertises
`BT` on the crew's roster, contradicting the flag's own KDoc.

The only record of anything is **40 status lines held in memory** (`PttService.kt:72`,
`LOG_LINES = 40`), discarded when the service stops — roughly 40 seconds on a busy session. No
persistence, no export, no crash reporter, no `UncaughtExceptionHandler`, and counters that reset on
every Connect.

This is what turns four attacks into a support and compliance problem. When a skipper reports "it
went quiet and then the app closed itself", the process that held the evidence is the one that died.
And under Art. 14 you cannot report an actively exploited vulnerability you have no mechanism to
detect: C-1 produces no counter movement anywhere.

**Fix.** Use `ready` in `sendHello`; add `healthy = transports.any { it.ready }` and render a
distinct "ON CHANNEL — NO LINK" state on the disc, the channel row and the notification; add a
receive-side watchdog that says so audibly when peers were present and have gone. Then add a
bounded, local, append-only security log with the opt-out (2)(l) requires — see `annex-i-mapping.md`.

## Where the documentation and the code disagree

The threat model is unusually honest and mostly holds up. These seven claims do not, and each is
checkable from outside.

| Claimed in `docs/SECURITY.md` | Verified against the code |
| --- | --- |
| "(2)(h) Availability of essential functions, resilience to DoS — two-layer rate limiting…" | Contradicted by C-1 and H-1..H-3. The limiter bounds CPU; it offers no availability guarantee against a flooding source, and the plugin has no limiter at all. |
| "a relay cannot change them, so the roster's hop count is exact" | False against an active attacker. The hop count is `hello.ttl - h.ttl`, and `h.ttl` is precisely the byte excluded from the AAD. |
| "(2)(l) Security-relevant logging — the Status screen keeps the last 40 status lines" | Volatile, non-persistent, records no access to or modification of data, and has no opt-out because there is nothing to opt out of. Clause unmet. |
| "`ready` … false while the adapter is off, so the hello does not claim BT" | Dead code. `sendHello` ORs every transport's flag unconditionally. |
| "(2)(a) … CodeQL; Dependabot alerts; dependency and Gradle-distribution checksums" | None is a release gate. Checksums pin *identity*, not safety — they will pin a known-vulnerable version forever. No OSV, Trivy, `npm audit` or dependency-review anywhere in CI. |
| "cleartext HTTP is permitted … and it is the app's only HTTP traffic" | Code true, config false. `SignalKClient` is the only HTTP user, but `network_security_config.xml:14` sets a blanket app-wide `cleartextTrafficPermitted="true"` with no domain scoping. |
| "Anything blocking … never on the main thread" | Partly false. `disconnect()` runs on the main thread from `onDestroy` and can block ~3 s; `syncMonitor` contends for `monitorLock` with the capture thread; `AudioRoute.apply` does binder IPC on the main thread. |

Correcting these is cheap and it is the first thing a market surveillance authority or a commercial
buyer can check without asking a question.

## Finding register

Critical and high, then the medium findings worth carrying into planning. Low and informational
findings — temp-file permissions, webapp CSP, key-fingerprint disclosure, an unregistered
`PhoneAccount`, an unused `ACCESS_COARSE_LOCATION`, wall-clock peer expiry, SBOM licence enrichment
and others — are recorded in the review notes and folded into the roadmap phases.

| ID | Sev | Finding | Evidence | Clause |
| --- | --- | --- | --- | --- |
| C-1 | Crit | LAN peer table populated before authentication; audio redirected | `LanTransport.kt:194,233` | I(2)(h) |
| H-1 | High | Keyless ttl rewrite suppresses relaying, partitions the mesh | `Packet.kt:90`, `PttEngine.kt:768` | I(2)(f),(h) |
| H-2 | High | Keyless flood consumes the shared global bucket | `RateLimiter.kt:24`, `Ingress.kt:72` | I(2)(h) |
| H-3 | High | Plugin has no wire rate limiting; saturates the Signal K event loop | `lan.js:51`, `node.js:223` | I(2)(h),(i) |
| H-4 | High | `Transport.ready` dead; no liveness state; no persistent log | `Transport.kt:22`, `PttService.kt:72` | I(2)(l) |
| H-5 | High | Plugin: uncaught exception on the packet path exits the process | `node.js:283`, `index.js:338` | I(2)(h),(k) |
| H-6 | High | Plugin: unhandled rejection from `openLink()` / `pump()` | `index.js:135,187`, `queue.js:117` | I(2)(h),(k) |
| H-7 | High | Audio threads bypass `transportThread`; a survivable fault crashes the app | `AudioCapture.kt:61`, `Mixer.kt:95` | I(2)(h) |
| H-8 | High | AudioRecord/AudioTrack released while the worker may still be inside read/write | `AudioCapture.kt:85`, `Mixer.kt:284` | I(2)(h) |
| H-9 | High | Fixed global PBKDF2 salt enables cross-deployment precomputation | `ChannelCrypto.kt:93` | I(2)(b),(e) |
| H-10 | High | Typed 12-character key floor; ~2^30 candidates is roughly $10 of GPU time | `Prefs.kt:57` | I(2)(a),(e) |
| H-11 | High | Channel key exported in cleartext to any app via the share sheet | `SettingsActivity.kt:316` | I(2)(b),(e) |
| H-12 | High | Channel key and Signal K token stored as plaintext XML; no Keystore | `Prefs.kt:100,154,206` | I(2)(e) |
| H-13 | High | Blanket app-wide cleartext HTTP; bearer token crosses the WLAN in clear | `network_security_config.xml:14` | I(2)(e),(f),(j) |
| H-14 | High | No update mechanism; only `openReleases()` exists | `StatusActivity.kt:260` | I(2)(c), II(7),(8) |
| H-15 | High | No privacy policy; Data Safety declaration not derivable | absent | Play, GDPR |
| H-16 | High | No dependency-vulnerability gate in CI; a vulnerable release ships green | `build.yml` — absent | I(2)(a), II(1) |
| H-17 | High | No SBOM for the plugin, whose sole dependency is a 21 MB WASM binary | `sk-plugin` — absent | II(1) |
| H-18 | High | No advisory practice: template release notes, no app changelog, no CVEs | `build.yml:229` | II(4),(8) |
| H-19 | High | Unpinned third-party reusable workflow at `@master` | `signalk-ci.yml:25` | I(2)(a) |
| H-20 | High | No signing-key rotation or compromise path; key loss ends the product | `BUILDING.md:125,148` | II(7) |
| M-1 | Med | Insider seq-mark poisoning permanently mutes a victim; roster still shows "talking" | `SeqTracker.kt:29`, `PttEngine.kt:798` | I(2)(h) |
| M-2 | Med | Managed configuration accepts an 8-character key via `validPassphrase` | `Prefs.kt:110,156` | I(2)(a),(b) |
| M-3 | Med | mDNS-discovered Signal K server auto-adopted and persisted with no confirmation | `SettingsActivity.kt:175` | I(2)(d),(f) |
| M-4 | Med | Server-controlled `href` concatenated onto the base URL — authority injection | `SignalKClient.kt:107` | I(2)(f),(j) |
| M-5 | Med | CSRF on `POST /say` via the `text/plain` simple content type | `index.js:223,428` | I(2)(d),(f) |
| M-6 | Med | Plugin authorisation fully delegated; open on a security-disabled server | `index.js:199` | I(2)(b),(d) |
| M-7 | Med | Urgent-priority abuse suppresses genuine alarm announcements indefinitely | `queue.js:85`, `index.js:276` | I(2)(f),(h) |
| M-8 | Med | Device-transfer exclusion omits `path`; protection unverified | `data_extraction_rules.xml:13` | I(2)(e),(m) |
| M-9 | Med | A NotificationListener-privileged app can key the mic and end the session | `PttService.kt:303,331` | I(2)(d),(j) |
| M-10 | Med | Bluetooth links have no read timeout; a half-open link never redials | `BluetoothTransport.kt:279` | I(2)(h) |
| M-11 | Med | Unbounded accepted BT links and Aware responder callbacks | `BluetoothTransport.kt:153`, `WifiAwareTransport.kt:276` | I(2)(h) |
| M-12 | Med | Deep JSON raises `StackOverflowError` past the `JSONException` catch, in an `execute()` task | `AskController.kt:193`, `SignalKClient.kt:200` | I(2)(h) |
| M-13 | Med | Channel key plaintext in Signal K plugin config; rides along in every backup | `index.js:395` | I(2)(e),(m) |
| M-14 | Med | Flite WASM dependency unmaintained since 2023; BSD-4-Clause notice not reproduced | `package.json:54` | Art. 13(5), II(2) |
| M-15 | Med | Plugin accepts a channel key of any length; no minimum in schema | `index.js:395,485` | I(2)(b) |
| M-16 | Med | Plugin trims the key, the app does not — silent key mismatch, near-undiagnosable | `index.js:395` vs `Prefs.kt:156` | I(2)(a) |

## Assurance evidence — what is genuinely strong

This belongs in the technical file, not buried in developer docs. Under Module A you are your own
assessor, and this is the evidence.

**The release pipeline.** Split `release`/`publish` jobs so no token that can write to the
repository ever coexists with the signing key or with third-party code. `publish` has exactly two
steps and no checkout. The certificate is verified against `CREWRADIO_CERT_SHA256` before
publication, failing closed on four independent paths, and *before* the attestation step — so
nothing is ever attested that failed verification. Keystore deleted under `if: always()`. Passwords
passed as step `env`, never on a command line. Refuses to release-sign from a shallow clone. The
`VERSION` value a PR author could influence is validated against an anchored `^1\.[0-9]+$` before it
reaches `$GITHUB_ENV`. This is better than industry norm at any size.

**Dependency discipline.** 302 components, 523 artefact entries in `gradle/verification-metadata.xml`,
every one sha256, zero without, no `trusted-artifacts` or `ignored-keys` exclusions, and
`verify-metadata: true` so `.module` and `.pom` files are pinned too. The Gradle distribution itself
checksum-pinned. Five UI dependencies in total. A hand-written SBOM task, so the build pulls in
nothing extra in order to describe what it pulls in.

**The ingress pipeline.** The documented claim that the cache look, the sender charge and the cache
mark are one step under one lock is **true** — `Ingress.kt:79-85`, and unit-tested by
`IngressTest.copiesAreNotChargedToTheSender`. So is the ordering claim, and so is the atomic gap
reservation in `admitAudio`. It was found by watching a concealed-frame counter in a cabin,
understood as budget exhaustion, fixed, and then written down *with its cause so it cannot be
undone*. That is what an Annex I II(3) narrative is supposed to contain and almost never does.

**Parser hardening.** `Hello.decode` validates ranges before indexing, refuses trailing bytes, uses
strict UTF-8 with `REPORT`, and strips ISO control *and* Unicode format characters — bidi overrides,
zero-width joiners — before a name reaches a TextView. The Kotlin and JavaScript whitespace
classifiers were checked across eleven code points and agree on every one. `Packet.parse` checks
length both ways before any index and never throws.

**Privacy posture.** Zero `Log`, `println` or `printStackTrace` in the entire app, so the key, the
token and crew speech provably cannot reach logcat. Every `PendingIntent` immutable. Every component
explicitly exported or not, with only two exported and one of those gated on
`BIND_TELECOM_CONNECTION_SERVICE`. On-device speech recognition hard-gated on both `SDK_INT >= S`
and `isOnDeviceRecognitionAvailable`, with no network fallback anywhere in the code. A Data Safety
declaration of "no data collected by the developer" would be honest.

**No command injection in the TTS path.** Specifically hunted and not found. The spoken text is a
single argv element, the voice is checked against a frozen allowlist, the rate is `clamp` then
`toFixed(3)`, the output filename is 64 bits from `crypto.randomBytes`, and the WASI sandbox grants
one preopen with an empty environment, `stdout`/`stderr` to `devNull` and `returnOnExit: true`. A
hang is caught by a 30 s timer that terminates the worker. This is the strongest-engineered part of
the plugin. One caveat worth a comment and a test: the invariant that makes `say({text: "/etc/passwd"})`
speak a string rather than open a file is the space in `` ` ${text} ` `` — undocumented and untested.
