# Open findings, and why

What the review found and the code does **not** yet do, with the argument for each. Written after
PR #45 closed the availability cluster.

Nothing in this folder is withdrawn. The analysis in `gap-analysis.md`, `annex-i-mapping.md`,
`scope-and-classification.md` and `roadmap.md` stands as written; this paper adds the disposition of
what is still open, and one statement of current fact:

> **Crew Radio is not being sold yet.** It is free and open source, supplied outside the course of a
> commercial activity.

That statement is worth recording because the rest of the folder was written on the opposite
premise — deliberately, as the harder case to plan against.

## What the statement changes, and what it does not

The Cyber Resilience Act's manufacturer obligations attach to a product **placed on the market**.
While that is not happening the Art. 2 free-and-open-source carve-out holds, so those obligations —
including the reporting duty this folder called the nearest deadline — are **dormant rather than
overdue**. The Article 14 clocks bind manufacturers; there is no manufacturer here yet, so there is
nothing to file and no deadline. `article-14-runbook.md` keeps its value as a prepared template, not
as an outstanding task.

What the statement does **not** change: a defect is a defect whoever is or is not paying. The
essential requirements remain the best available checklist for a product a crew leans on while
manoeuvring, and the security findings below are worth closing on their own merits. Several are
scheduled. The compliance *artefacts* are the part that waits.

**Re-read this folder in full** if any of these becomes true: charging for the app or the plugin;
charging for support, deployment or fleet provisioning around it; bundling it with something sold;
or an employer or customer distributing it in the course of their business.

## How each finding is dispositioned

| Disposition | Meaning |
| --- | --- |
| **Open — scheduled** | A real defect with no blocker. Not done yet, and that is the only reason. |
| **Open — needs v5** | Requires a wire change, and the wire has no legacy mode, so it must be bundled. |
| **Open — needs a decision** | Blocked on a product choice, not on effort. |
| **Accepted** | Understood, tolerated, with the reasoning and the revisit trigger recorded. |
| **Dormant** | A CRA obligation only. Not owed while the product is not on the market. |

---

## Open — scheduled

Ordinary bugs. None needs a decision or a wire version; the only reason each is open is that the
pass which closed the availability cluster stopped before it.

### The observability half of H-4 — the most valuable thing left

The hello now claims only transports that are `ready`, and the heartbeat says when no link is up.
The *recording* half is untouched: 40 status lines in memory, discarded with the process, no crash
reporter, no `UncaughtExceptionHandler`, no diagnostics export.

**Argument for doing this before anything else.** Four attacks were fixed in PR #45 and **not one of
them would have left a trace**. Three consequences outlast any individual bug. A crew reporting "it
went quiet" hands you nothing, because the process that held the evidence is the one that died. You
cannot tell whether any of these attacks ever actually happens in the field, so the fixes are
unfalsifiable. And if the product is ever sold, Article 14 asks for reports of exploitation there is
no means to detect.

Perhaps two days: a bounded ring log in app-private storage, a default uncaught-exception handler
that writes the trace before dying, and a redacted "Share diagnostics" action on the Status screen.
It makes everything else measurable.

*Keep it local.* Sending it anywhere would undo the zero-log privacy posture, which is one of the
genuinely strong things about this codebase.

### M-1 — a crew member can permanently mute another

One authentic packet carrying a victim's `senderId` and a sequence number 2^30 ahead sets the
high-water mark so far forward that every real frame from that phone is dropped for the life of the
process. Worse, `heardAudio` runs before `admitAudio`, so the victim's name **lights up as talking
on the roster while nothing is heard**.

**Argument.** It needs the channel key, so it is a crew member or someone once told the key — which
is the "possession of the key is membership" model this app has chosen, not a new hole. But "I can
see you transmitting and I hear nothing" is the most dangerous thing this app can display on a boat,
and the fix is small and local: expire a sender's mark after a couple of minutes of silence, clamp a
single forward jump, and move `heardAudio` after `admitAudio`. Expiry costs nothing in replay
protection — the plus-or-minus 60 s timestamp window is what actually stops a recording; the mark
was only ever the second line.

### M-3 — a discovered Signal K server is adopted without asking

The first mDNS responder wins, its address is **persisted**, and the crew then pairs with whatever
answered — sending it a client id and accepting a bearer token back. Every "ask the boat" answer
after that is the attacker's, read aloud to the helm as the vessel's own instruments: a false depth
under the keel, a false position.

**Argument.** The sharpest unfixed item, and it has no blocker at all. It is open only because the
fix needs a confirmation dialog and three user-visible strings, and strings here live in
`strings.xml` with a tone to match — a small piece of design work rather than a small piece of code.
The fix must be a *confirmation*: an attacker on the boat LAN passes any address-range test that
could be written instead.

### M-5, M-6, M-7 — the plugin's HTTP surface

CSRF through the `text/plain` simple content type on `POST /say`. Authorisation delegated to
signalk-server and never verified, so a server running without a security strategy — including the
repository's own capture recipe — leaves the door open. And `priority: "urgent"` taken straight from
the caller, letting anyone who can reach say() cut a genuine alarm short every few seconds
indefinitely.

**Argument for treating them as one item.** These are not three bugs. They are one unanswered
question: **what is the trust boundary of a Signal K server?** The plugin's README already takes a
position — anyone who can reach the server's REST API or PUT paths can make it speak, so treat the
server as a crew member — and that position is defensible. What is missing is enforcing it
consistently: if the server is trusted, the plugin should refuse to start when it cannot confirm
that security is on, rather than silently trusting the mount. Answer the question once and the three
patches follow. Patch them separately and the next surface added repeats the argument.

### M-10, M-11 — Bluetooth and Wi-Fi Aware lack what the LAN path now has

RFCOMM links have no read timeout, so a peer that sends a length prefix and then nothing blocks the
reader for ever; the `finally` never runs, the link is never removed, and **`redial()` is never
called** — Bluetooth relaying is silently dead until the session ends. Accepted BT links are
unbounded. On API below 31 the Aware responder registers a `NetworkCallback` per message with no
per-peer dedupe, so a flapping peer can reach the framework's 100-request ceiling and stop Aware
forming links process-wide, reported as nothing.

**Argument.** Both need a paired or in-range device, so neither is the open-WLAN class PR #45
addressed. But `WifiAwareTransport` already has the answer to both — a link cap, a dial cap, an idle
sweep — and Bluetooth simply never got them. This is bringing one transport up to the standard the
other two already meet, which is the kind of asymmetry that rots quietly.

### M-9 — a notification-listener app can key the microphone

Not an arbitrary app: it needs `MEDIA_CONTENT_CONTROL` or an enabled `NotificationListenerService`,
which users routinely grant to watch companions and automation tools. Such an app can dispatch a
media button and transmit the cabin to the whole crew, with a short vibration to show for it.

**Argument.** Low likelihood, unpleasant consequence, cheap fix: a synthesised
`dispatchMediaButtonEvent` carries `deviceId == -1`, so checking that the event came from a real
input device closes it in about one line. Open only because it sat below the waterline of the last
pass.

---

## Open — needs v5

**The wire has no legacy mode.** `Packet` refuses anything but version 4, and `docs/SECURITY.md`
says every phone and the plugin must run the matching build. Each wire change is therefore a
coordinated fleet-wide upgrade, and a security fix that needs one is *also an outage*.

That is the argument against doing these piecemeal: **each would cost its own flag day.** Design
them together, ship them once.

| Finding | What it needs |
| --- | --- |
| **H-9** fixed global PBKDF2 salt | A per-crew salt changes every derived key: a flag day by definition. |
| **H-10** typed keys weak enough to brute-force | The real fix is not a longer minimum but removing human transcription — pairing by QR, which makes a 128-bit key practical and the salt question moot. |
| No key id or epoch on the wire | Today a mismatched key is silent: the peer simply never appears. An epoch byte makes it diagnosable *and* makes staged re-key — that is, revocation — possible for the first time. |
| No per-sender authentication | `senderId` is authenticated to the key, not to a person. That is what M-1 exploits, and what leaves Annex I (2)(d) with no identity component. Signed hellos and a per-device keypair fix the class rather than the instance. |
| No version tolerance | The deepest one. Without a way for a receiver to act on a version it does not know, a security-only update can never reach a fleet that cannot all upgrade at once. |

**Recommendation: write the v5 design down before writing any of it**, and treat version tolerance
as the first requirement rather than the last. Everything else here is cheaper once a fleet can run
mixed versions.

---

## Open — needs a decision

| Finding | The decision, not the effort |
| --- | --- |
| **H-11** channel key exported in cleartext through the system share sheet | An on-screen QR code is the right answer and retires H-9 and H-10 with it — but that is the same decision as the v5 pairing design, so it belongs there rather than being made twice. |
| **H-12** channel key and Signal K token in plaintext SharedPreferences | Android Keystore is the answer. `EncryptedSharedPreferences` is the easy route and adds a third-party dependency, which this project has deliberately avoided — five UI dependencies, every artefact checksum-pinned. A hand-rolled Keystore AES-GCM wrapper of about sixty lines keeps that property. Which of the two is a real choice about what the project values. |
| **H-13** blanket app-wide cleartext HTTP | The justification is sound — a boat server at a bare IPv4 address can hold no certificate anyone could issue — but the config is broader than the justification. Scoping it to private ranges in code, or moving to HTTPS with a certificate pinned at pairing, are different amounts of work and different amounts of friction for the crew. |
| **H-14** no update mechanism; the README asks users to allow unknown sources | Under the CRA this is a blocker; while not selling it is not. Still worth noting that telling every crew member to weaken their device is a durable cost, and a store channel would remove it. A distribution decision, not a code one. |

---

## Accepted

Understood, tolerated, reasoning recorded. Not oversights.

**The group-key model.** One shared symmetric key means no per-person authentication, no forward
secrecy, no revocation short of a manual re-key, and no post-compromise recovery. This is inherent
to a serverless, any-topology mesh with no handshake, and `docs/SECURITY.md` says so plainly. One
thing there is worth sharpening: the comparison to VHF. VHF carries no expectation of
confidentiality at all and this product does offer one, so the *retroactive* consequence — a season
of recorded ciphertext plus a later key leak — deserves stating rather than implying.

**Random 96-bit GCM nonces.** Quantified: at eight nodes, twelve hours a day connected and two hours
a day of speech, the NIST SP 800-38D random-IV ceiling of 2^32 packets under one key arrives in
roughly sixteen years. Random is not merely adequate here, it is *correct*: the only identifier
available to partition a deterministic nonce space is `senderId`, which is 32 bits, sender-chosen
and unauthenticated — so a malicious key-holder could force nonce reuse deliberately and recover the
GHASH subkey. Revisit if a key ever outlives a decade of continuous use.

**Relay amplification.** Up to roughly 48 transmissions per received packet across three transports,
but bounded per sender by the 75/s budget charged before any forwarding, and reachable only by
someone holding the key. Battery and airtime, not a channel-killer.

**The unpinned reusable workflow.** `signalk-ci.yml` calls the Signal K project's
`plugin-ci.yml@master`. Deliberate and documented: the App Store's Indicators tab matches on that
canonical reference, so pinning it costs the listing. It runs with a read-only token and no secrets,
so the exposure is unreviewed code execution in a sandbox that can reach nothing — the same class as
any fork PR. Accepted with the reason on the record rather than inferred.

**`-dontobfuscate`.** Release APKs ship full class and method names and line numbers. The security
rests on the channel key, not on secrecy of code, and the stated reason — a readable crash trace
from a phone at sea, with no mapping file to fetch — is legitimate. Revisit if a `mapping.txt` is
ever published alongside the release, which would give both.

**Clock dependence for freshness.** A phone more than 60 s out cannot be heard, and Android's clock
comes from unauthenticated NITZ or SNTP. `docs/SECURITY.md` calls this "the cost of stopping replays
without a handshake", which is honest. Worth revisiting only alongside v5, where a session challenge
would remove the dependency. Until then the failure should at least be *loud*: it is currently one
status line among many, and a crew will read it as a bug.

**M-14, the Flite WASM dependency.** Last published 2023, a 21 MB prebuilt binary with no upstream to
fix it. The WASI sandbox is a genuinely good compensating control — one preopen, empty environment,
`returnOnExit`, a fresh instance per sentence, a 30 s timeout — and that is why this is accepted
rather than urgent. **One part is not accepted and should be fixed cheaply: the licence.** It is
BSD-4-Clause, not BSD-3, so it carries the advertising clause, and neither the plugin's `LICENSE`
nor its README reproduces the CMU notice. A licensing defect independent of any security question.

---

## Worth doing regardless — cheap, and not compliance theatre

- **A dependency vulnerability gate in CI.** Nothing in the pipeline would notice if a shipped
  dependency acquired a published, exploitable vulnerability; checksums pin identity, not safety.
  OSV or Trivy reading the SBOM the build already produces, placed before the attestation step so
  nothing vulnerable is ever attested. Useful whether or not anyone is paying.
- **A differential fuzz harness.** The plugin missing all three rate buckets was a *parity* failure,
  and `vector.json` covers only the happy path. Feeding the same random bytes to `Packet.parse` and
  `lib/packet.js` and asserting they agree on accept and reject would have caught it, and will catch
  the next one without anyone noticing first. Both parsers are pure and already have harnesses.
- **An app `CHANGELOG.md`.** The plugin has one with a `### Security` section; the app has none, so a
  release note has never once said what a release fixed.
- **The Flite licence notice**, as above.

---

## Dormant — CRA obligations only

Not owed while the product is not placed on the market, and not withdrawn: each is described in full
in `roadmap.md` and `annex-i-mapping.md`, which stand as written.

Support period (B1) · update mechanism as a *conformity* matter (B2) · manufacturer identity,
declaration of conformity, CE marking, Annex VII technical file (B3) · Article 14 reporting process
(B4) · Annex II user information · plugin SBOM (H-17) · published advisories for fixed
vulnerabilities (H-18) · signing-key rotation and custody as a *compliance* matter (H-20) · privacy
policy and Play Data Safety (H-15).

Two are worth a thought even now, for reasons that are not compliance. **Signing-key custody**,
because `docs/BUILDING.md` says outright that there is no recovery, and a lost key ends the
project's ability to update anyone who already installed. And an **end-of-support notice**, because
there is currently no mechanism at all by which a user who installed once and never returns to the
Releases page could be told anything at all.
