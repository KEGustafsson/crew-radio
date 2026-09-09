# Annex I and Annex II mapping

Clause-by-clause self-assessment. Under Module A there is no notified body, so this document is the
conformity assessment — and under Annex VII item 5, where no harmonised standard is applied, it is
the required description of the solutions adopted instead.

It supersedes the informative table in `docs/SECURITY.md`, which should eventually point here rather
than duplicate it. The lettering below is the Regulation's own; `docs/SECURITY.md` already uses it
correctly and should keep doing so.

Verdicts: **Met** / **Partial** / **Not met**. Every "Partial" and "Not met" carries what is needed
to close it. Finding IDs refer to `gap-analysis.md`.

## Annex I Part I — essential cybersecurity requirements

### (1) Appropriate level of cybersecurity based on the risks

**Partial.** The design is proportionate and well-bounded: AES-256-GCM under a PBKDF2-derived key
(600 000 rounds, application-specific salt written out deliberately rather than taken from a
provider, because Android has treated non-ASCII inconsistently across releases); an authenticated
±60 s replay window; three-layer rate limiting; `Ingress` collapsing the whole receive decision into
one pure, tested class.

The clause is judged against a documented risk assessment, and none exists in the required form.
`docs/SECURITY.md` is a genuinely good threat model — eleven threats with locations and mitigations,
plus a candid "What it does not do" — but it has no asset register, no likelihood or impact ratings,
no explicit residual-risk acceptance with a named accepting person, no stated intended purpose or
foreseeable misuse, no date, no version, and no coverage of the plugin as a product in its own right.

*To close:* restructure it into a rated, dated, versioned risk assessment covering both products,
with foreseeable misuse — above all, use as safety-of-life or distress communication — stated and
addressed. This upgrades to Met once that exists.

### (2)(a) Made available without known exploitable vulnerabilities

**Partial.** CodeQL (`security-and-quality`, Kotlin + JavaScript + workflows, per-push and weekly),
Dependabot across three ecosystems, complete sha256 dependency verification, a checksum-pinned
Gradle distribution, `lintRelease` with `abortOnError` as a real gate, and 224 unit tests.

But **no control here is a release gate for vulnerabilities**. Checksums pin identity, not safety.
There is no OSV, Trivy, Grype, `npm audit` or dependency-review anywhere in CI (H-16), so a release
can be built, signed, attested and published carrying a known-vulnerable component with every check
green. Dependabot's `ignore` rule is written as `"*"` for major versions, so a fix shipping only in
a new major is silent; and transitive AndroidX artefacts (`recyclerview 1.2.1`, `window 1.0.0`) are
unreachable by Dependabot altogether.

Also open: the legacy 8-character channel key remains acceptable for stored and managed values
(H-10, M-2).

*To close:* an OSV/Trivy gate in `release` *before* the attestation step, mirroring where the
certificate gate already sits; `npm audit` for the plugin; narrow the Dependabot ignore rule to the
toolchain; force migration off short keys or record it as an accepted residual risk with a rationale.

### (2)(b) Secure by default configuration, including reset to the original state

**Partial.** The secure-by-default half is strong and deserves recording:

- **No default channel key.** A random ~59-bit key is generated on first use. This is the most
  common failure in this product class and it is avoided outright.
- The Wi-Fi Aware PSK and discovery tag are HMACs of the *derived packet key*, never the channel key
  — so capturing an Aware handshake gives an attacker nothing cheaper to attack.
- Rate limits, hop limit, cache sizes and queue bounds are all secure defaults needing no action.
- The exposing options default off: `headset_vox`, `cue_tones`, `headset_call`, `ask_enabled`; and
  `ask_mode` defaults to `just_me`.
- `allowBackup="false"` plus extraction and backup rules excluding the sharedpref domain.

Two defects. **Blanket cleartext**: `network_security_config.xml` permits cleartext app-wide, which
is broader than the justification for it (H-13). And **there is no reset to the original state** —
searched for factory/reset/wipe/erase across `app/src/main`; the only hits are engine internals.
Android's "Clear storage" and uninstall arguably satisfy the clause in substance, but that is
nowhere documented.

*To close:* a "Reset Crew Radio" preference behind a confirm dialog, clearing all preferences
including the key and the Signal K token. It discharges most of (2)(m) at the same time. Scope
cleartext to the configured Signal K host in code, or record the acceptance with its compensating
controls.

### (2)(c) Security updates, including automatic updates by default with an opt-out

**Not met — blocker B2.** Confirmed by search: the only update-related code in the app is
`StatusActivity.openReleases()`, an `ACTION_VIEW` intent to a web page.

| Requirement | Status |
| --- | --- |
| Updates possible | Yes — a new APK |
| Automatic, enabled by default | No |
| Clear, easy opt-out | No — nothing to opt out of |
| Notification of available updates | No. `OLD BUILD` on the roster reports that a *peer* differs; it detects no release and is silent for a lone user or a uniformly stale crew |
| Option to postpone | No |
| Installed within an appropriate timeframe | No — a human must open a browser, download, and approve an install from an unknown source |

Worse than merely absent: the README instructs users to enable installs from unknown sources, which
an authority reads as an aggravating factor under this clause and (2)(j).

*To close:* Google Play is the cleanest answer — automatic updates on by default with a per-app
opt-out, update notifications, and a distribution channel that does not require weakening the
device. If sideloading must remain, a signed update manifest fetched over HTTPS from a fixed origin,
verified in-app against a pinned public key, with an in-app advisory banner. See `roadmap.md` phase 3
for the signing-key consequences, which are not small.

### (2)(d) Protection from unauthorised access, and reporting on possible unauthorised access

**Partial.** AEAD on every packet is a real access-control mechanism: no key, no access, and the tag
is checked before the packet reaches the relay, the roster or a decoder. Listening sockets are bound
to the link they serve, and the Aware data-path listener accepts only link-local peers.

But a single group PSK provides **no identity management** — `senderId` is authenticated to the key,
not to a person, so any key-holder can transmit as any id under any display name. And the second
half of the clause, *report on possible unauthorised access*, is unmet: the only alert is a
"unreadable packets" status line that fires above 200 junk packets per second. A single quiet
intruder holding the key is invisible.

*To close:* accept the group-key model as a documented design decision — it is inherent to a
serverless any-topology mesh — but state its consequences plainly in the user information, and add
detection: surface AEAD-failure bursts and abnormal roster churn as a user-visible state rather than
a status line, and record them in the security log added under (2)(l).

### (2)(e) Confidentiality of stored, transmitted or processed data

**Partial.** In transit this is sound: AES-256-GCM with a fresh 96-bit `SecureRandom` nonce per
packet and a full 128-bit tag. The nonce strategy is correct and should be *documented as an
accepted residual*, not "improved": at realistic traffic a crew reaches the NIST SP 800-38D random-IV
ceiling of 2^32 packets after roughly 16 years on one key, and the obvious deterministic alternative
would have to partition the nonce space by `senderId` — which is 32 bits, sender-chosen and
unauthenticated, so a malicious key-holder could force nonce reuse deliberately and recover the
GHASH subkey.

At rest it is not met. The channel key and the Signal K bearer token sit in plaintext XML in default
SharedPreferences with no Keystore anywhere in the app (H-12), the key is exported verbatim to any
app through the system share sheet (H-11), no screen holding it sets `FLAG_SECURE`, and the bearer
token crosses the boat WLAN in cleartext (H-13). On the plugin side the key sits in the server's
plugin config and rides along in any backup of `~/.signalk` (M-13).

*To close:* Keystore-wrap both secrets with `setUnlockedDeviceRequired`, with a migration path;
replace share-as-text with an on-screen QR code; `FLAG_SECURE` on any screen showing the key; scope
cleartext; support an env-var or `0600` file for the plugin's key.

### (2)(f) Integrity of stored, transmitted or processed data, commands and configuration

**Partial.** The GCM tag covers the whole 18-byte header except the ttl byte, so `codec`, `seq`,
`time`, `hops` and `senderId` are not malleable by an off-channel attacker — the seq-poisoning and
window-shifting attacks one would look for first are **not** available without the key.

The exception is the ttl, which is a forwarding command an attacker owns (H-1), and which also
corrupts the roster hop count. Two further integrity paths on the Signal K side: an auto-adopted
mDNS server (M-3) and `href` authority injection (M-4) both let an attacker on the boat LAN feed
false instrument readings that are then spoken aloud to the helm.

*To close:* ttl-aware dedupe; confirm discovered servers rather than auto-persisting them; reject
any `href` that is not a same-origin absolute path; pin the server identity at pairing.

### (2)(g) Data minimisation

**Met.** The wire carries voice frames, a display name, transports, hop budget and build number, and
nothing else. There is no server, no account, no analytics, no telemetry and no third-party SDK.
Zero `Log`, `println` or `printStackTrace` in the entire app, so nothing sensitive can reach logcat.
Speech recognition is on-device only, hard-gated, with no network fallback anywhere in the code.
Permissions are requested just-in-time and are genuinely minimal, with `neverForLocation` on both
`BLUETOOTH_SCAN` and `NEARBY_WIFI_DEVICES`.

Two residuals worth recording rather than fixing under this clause: the display name defaults to the
phone's own name, which is usually a person's name and is broadcast to the whole crew and published
into the Signal K tree; and `AskVoice` hands the answer text to the user's *default* TTS engine,
which on some devices synthesises server-side.

*To improve:* prompt for a name on first run rather than defaulting to the device name; prefer a TTS
voice with `isNetworkConnectionRequired == false`, or state the caveat.

### (2)(h) Availability of essential and basic functions, resilience against denial of service

**Partial** — was Not met; the cluster behind that verdict has been fixed.

C-1 (peer-table poisoning), H-1 (ttl relay suppression), H-2 (keyless flood starvation), H-3 (the
plugin's missing budgets) and H-7/H-8 (the audio threads and their release-vs-join race) are all
closed: peers are learned only after the AEAD, a duplicate that would reach further is relayed, a
per-source bucket sits ahead of the global one on both the app's socket and the plugin's, and the
two audio threads catch, report, and release exactly once. `docs/SECURITY.md` has been corrected
to match, including the hop-count claim it could no longer support.

It stays Partial rather than Met for two reasons, both real and both needing the channel key or a
paired device, which is why they are Medium rather than High. An attacker inside the channel can
still poison a sender's sequence high-water mark and mute that sender for the life of the process
(M-1). And the Bluetooth and Wi-Fi Aware transports still lack the link liveness and the caps the
LAN path now has (M-10, M-11).

*To close:* expire the sequence marks after a couple of minutes of silence and clamp a forward
jump; give the stream transports an idle sweep and the same link caps.

### (2)(i) Minimising negative impact on the availability of services provided by other devices

**Met** — was Partial. Packets are small, bounded and rate-limited; `LanLink.onSubnet` refuses to
send unicast copies off its own subnet, a deliberate and tested anti-reflection control; multicast
plus broadcast is confined to the WLAN.

The gap was H-3. The plugin runs in-process in the boat's Signal K server, so an unauthenticated
flood degraded NMEA, AIS, autopilot deltas, the admin UI and every other plugin along with it. It
now carries the same three budgets as the app plus a per-source bucket on the socket, and the
packet path can no longer throw or reject its way out of the process.

### (2)(j) Limiting attack surfaces, including external interfaces

**Met.** No server, no accounts, no internet backend, no analytics, no third-party networking or
crypto libraries — five UI dependencies in total. No WebView, no `loadUrl`, no
`addJavascriptInterface`, no `Runtime.exec`, no reflection, no dynamic code loading, no
`ContentProvider`. Every component declares `android:exported` explicitly; only two are exported,
one being the launcher activity (which reads no extras and has no `onNewIntent`) and the other a
`ConnectionService` gated on `BIND_TELECOM_CONNECTION_SERVICE`. The app writes exactly one file.

Two blemishes carried under other clauses: blanket cleartext (H-13) and an unused
`ACCESS_COARSE_LOCATION` declaration.

### (2)(k) Reducing the impact of incidents through exploitation mitigation

**Partial.** Decoder failures are contained per sender and retried; transport threads catch broadly
and stay up; `Packet.MAX_SIZE` drops oversized packets unread; the plugin's WASI sandbox contains
the WASM speech engine well.

Against it: `-dontobfuscate` with `SourceFile,LineNumberTable` kept ships a full map of
`ChannelCrypto`, `Prefs.KEY_CHANNEL_KEY` and the `Ingress` decision order, and no `mapping.txt` is
published; the audio threads are the only threads in the app with no uncaught-exception isolation
(H-7); and a compromised channel key has no remediation but a manual re-key of every phone and the
plugin, with no key id on the wire to make a staged rollover diagnosable.

*To close:* turn obfuscation on and publish an attested `mapping.txt` (keeping readable traces via
`retrace`), or record the acceptance; wrap the audio threads; add a key-epoch byte at the next wire
version.

### (2)(l) Security-relevant logging and monitoring, with an opt-out

**Not met.** The only record is 40 status lines in memory, discarded with the process. Nothing
records the key being read, changed, regenerated or shared; a managed-configuration override being
applied; a Signal K pairing attempt and its outcome; a token being stored or cleared; or a sustained
authentication-failure burst. There is no opt-out because there is nothing to opt out of.

*To close:* a bounded, **local-only**, append-only audit log — a few hundred entries in app-private
storage, itself encrypted — recording exactly those events, never payloads. Read-only on the Status
screen with an explicit "record security events" toggle (the required opt-out) and a clear action.
Do not send it anywhere: that would undo the (2)(g) posture, which is currently a strength. Then
correct the (2)(l) row in `docs/SECURITY.md`.

### (2)(m) Secure and permanent removal of data and settings

**Not met.** There is no in-app erase path. Uninstalling removes app-private storage and platform
"Clear storage" works, so the substance is arguably available — but it is undocumented, and Annex II
8(d) independently requires decommissioning instructions.

Also unresolved: `data_extraction_rules.xml:13` and `:16` write `<exclude domain="sharedpref" />`
with **no `path`**. The legacy backup-scheme parser skips such elements; whether the Android 12+
extraction-rules parser treats a missing path as the whole domain could not be confirmed from the
repository. Since `allowBackup="false"` already disables cloud backup, this rule is the *only*
control over device-to-device transfer — the one path that would carry the channel key and the
Signal K token to a new phone (M-8).

*To close:* the reset control from (2)(b); explicit `path` attributes in both rule blocks, verified
empirically with a real device transfer before release; decommissioning instructions covering the
phone, the Signal K token (which uninstalling does *not* revoke) and the plugin's stored key.

## Annex I Part II — vulnerability handling

| Point | Verdict | Position |
| --- | --- | --- |
| (1) Identify and document components and vulnerabilities; SBOM | **Partial** | App SBOM is strong — CycloneDX 1.5 from `releaseRuntimeClasspath`, full dependency graph, SHA-256 per artefact, published and attested. No SBOM for the plugin, whose sole dependency is a 21 MB prebuilt WASM binary (H-17). No vulnerability register exists. |
| (2) Address and remediate without delay; security updates separate from functionality updates | **Partial** | The 30-day commitment in `SECURITY.md` is real. But there is no mechanism to separate a security fix from a feature release, and the single-version wire rule means any fix requires a fleet-wide upgrade — so a security update is also a coordinated outage, which discourages prompt patching. |
| (3) Effective and regular tests and reviews | **Partial** | 224 tests, honestly written, covering the ordering invariants that field testing paid for. But all are pure-JVM: no `androidTest` source set exists, so there are zero instrumented tests. No fuzzing of `Packet.parse`, `Hello.decode`, `StreamLink.readLoop` or `lib/packet.js` — three of which parse unauthenticated attacker-controlled bytes and are pure, ideal fuzz targets. No concurrency test of the one invariant the codebase most depends on. No soak test. No documented test plan. |
| (4) Publicly disclose information about fixed vulnerabilities | **Not met** | Release notes are template-generated and never mention security content. No app `CHANGELOG.md` at all. No advisories, no CVE route. The plugin's changelog has a good `### Security` section, so the habit exists — it is simply not applied to the app, and `package.json` declares `0.2.0` while the changelog documents only `0.1.0`. |
| (5) Coordinated vulnerability disclosure policy | **Met** | `SECURITY.md` is a real policy: private reporting route, what to include, 7-day acknowledgement, 30-day fix or mitigation, reporter credit, and explicit scope in and out. The best-developed control in the repository. What is missing is *enforcement* evidence — a record of how reports are tracked and closed. |
| (6) Facilitate information sharing; contact address | **Partial** | GitHub private reporting is the only channel. No email, no postal address, no `security.txt`, no PGP key, and no route for a reporter without a GitHub account — which excludes most CSIRT workflows. No commitment to report vulnerabilities *upstream* in integrated components. |
| (7) Mechanisms to securely distribute updates | **Partial** | Integrity is excellent: signed APK, certificate verified against a repository variable before publication, SHA-256 published, build provenance attestation over the APK, checksum, SBOM and plugin tarball, with a documented `gh attestation verify` procedure. Delivery is absent (H-14). Three documentation gaps: the verify command should carry `--signer-workflow`, the tarball verification procedure is missing entirely, and `CREWRADIO_CERT_SHA256` is published nowhere a customer could compare against. |
| (8) Disseminated without delay, free of charge, with advisory messages | **Partial** | Free of charge: yes today, and **must remain so** — see the pricing constraint in `scope-and-classification.md` §7. Without delay: blocked by (7). Advisory messages: absent. |

## Annex II — information and instructions to the user

Checked against `README.md`. **0 of 9 items fully met; 5 missing outright.**

| # | Requirement | Status | Gap |
| --- | --- | --- | --- |
| 1 | Manufacturer name, postal address, email or other digital contact | **Missing** | Only a copyright line. No legal entity, no address, no email. Decide the legal form first — the declaration names a legal person. |
| 2 | Single point of contact for vulnerability reports; where the CVD policy is found | **Partial** | `SECURITY.md` exists and is good, but is not referenced from the user-facing text as *the* contact point, is GitHub-only, and offers no email. |
| 3 | Name, type, and information enabling unique identification | **Partial** | Version scheme and `GIT_SHA` give good traceability. Missing an explicit identification block: product name, type ("application software for Android"), `fi.crewradio`, minimum platform — and the same for the plugin. |
| 4 | Intended purpose, security environment, essential functionality, security properties | **Partial** | The README describes function superbly. Missing, and **critically**, a formal intended-purpose statement with limitations: that Crew Radio is *not* safety-of-life or distress equipment and is *not* a substitute for VHF/DSC, GMDSS or EPIRB. Also missing the security environment — the assumptions the model rests on: all crew phones trusted, the key kept confidential, roughly correct clocks, the boat LAN treated as trusted for the Ask feature. |
| 5 | Known or foreseeable circumstances leading to significant cybersecurity risk | **Partial** | The raw material is excellent but sits in a developer document. Must be collected and stated plainly: possession of the key *is* membership and rotation is manual; a compromised crew phone compromises the channel; the Signal K token and readings cross the boat LAN in the clear; sideloading requires weakening the device; a clock more than 60 s out **cannot communicate at all**; guest-Wi-Fi client isolation blocks WLAN links; traffic analysis is possible; the display name is usually a person's name; a legacy short key is materially weaker. |
| 6 | Internet address where the EU declaration of conformity can be accessed | **Missing** | No declaration exists. |
| 7 | Type of technical support, **and the end date of the support period** | **Missing** | Blocker B1. `SECURITY.md`'s "Supported versions" is a version policy, not a support period, and states no date. |
| 8(a) | Measures at commissioning and through the lifetime | **Partial** | Key sharing, rotation and download verification are covered. Missing: guidance on choosing a typed key, when to rotate, and how to read and export the security log once it exists. |
| 8(b) | How changes to the product affect the security of data | **Missing** | Needs: changing the key strands phones left on the old one; a version mismatch prevents communication entirely; enabling Ask opens the only outbound HTTP path and stores a bearer token; enabling "Whole crew" announces questions and answers to everyone; managed configuration overrides local settings. |
| 8(c) | How security-relevant updates are installed | **Partial** | Explains downloading an APK. Cannot distinguish security from feature updates, because no mechanism does. Rewrite once distribution is settled. |
| 8(d) | Secure decommissioning and removal of user data | **Missing** | Must cover the phone; **revoking the Signal K token in the server admin UI**, which uninstalling does not do; deleting the plugin configuration so the key does not remain in the server's config; and rotating the crew key when a phone is lost, sold or leaves. |
| 8(e) | How to turn off the automatic-update default | **N/A today** | Becomes mandatory once (2)(c) is satisfied. |
| 8(f) | Information for integrators | **Partial** | Applies to the plugin, which is explicitly integrable via a PUT path, a REST route and an in-process API. Its README carries the right warning — *"treat the server as a crew member"* — which should be elevated into formal integrator information. |
| 9 | Where the SBOM can be accessed | **Partial** | Met for the app; the plugin has no SBOM. |

**Do not bend the README into this.** Its voice is an asset. Write `docs/USER-INFORMATION.md` as the
formal set, link it from the README, and ship it behind an About screen — which the app currently
lacks entirely.

One further obligation with a recurring cost: the information must be in a language easily
understood by users, as determined by the Member State concerned. Everything is English-only. The
codebase is admirably ready for translation — every user-visible string already lives in
`strings.xml` or `arrays.xml` — but nothing is translated. Scope the target markets before
committing, because each one may add a language.
