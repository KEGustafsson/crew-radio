# Plan to go forward

The order to do things in, what each step unblocks, and what it costs. Ordered by what removes the
most obstruction, not by severity — the critical finding is not step one, because a one-page runbook
with a legal deadline two days out is.

Finding IDs (C-1, H-1, M-4…) refer to `gap-analysis.md`; clause references to `annex-i-mapping.md`.

## Before anything: four decisions only you can make

These are not engineering tasks and they gate everything downstream. Nothing in phases 3 onwards can
be finished without them.

| # | Decision | Why it must come first | Recorded |
| --- | --- | --- | --- |
| D-1 | **Is this actually being sold, and how?** Paid app, paid support, dual licence, or a commercial edition. | The whole regime turns on it. If the answer is "not yet", phases 0–2 are still worth doing on their merits and the rest can wait. | ☐ |
| D-2 | **The support period.** A date, not a duration. Baseline ≥ 5 years. | Annex II item 7 and Annex VII item 4 both need it, and it commits you to a working Android toolchain and a physical test fleet for that long. | ☐ |
| D-3 | **The distribution channel.** Google Play, sideload with a signed update manifest, or both. | Determines whether (2)(c), II(7) and II(8) can be satisfied at all, and drags the signing key with it — see phase 3. | ☐ |
| D-4 | **The legal form and the name on the declaration.** Sole trader or limited company. | The declaration and the CE marking name a legal person; changing it later means re-issuing everything. Given the liability exposure in a marine context, a limited company is the conventional answer. | ☐ |

A fifth, which follows from D-1 and is easy to get wrong: **security updates must be free of charge
for the whole support period.** Paid-once with five years of free security updates is compliant.
Subscription-gated security fixes are not. Decide the pricing model with that constraint in hand.

## Phase 0 — This week

**Article 14 reporting obligations apply on 11 September 2026.** The artefact is a page, and it is
the only deadline that is not fifteen months away.

- Identify the coordinating CSIRT and record the intake route. For a manufacturer established in
  Finland that is the national CSIRT function at Traficom (NCSC-FI); confirm the current designation
  rather than assuming it.
- Fill in the placeholders in `article-14-runbook.md` — contacts, the responsible person, the deputy.
- **Nominate the deputy.** A single maintainer is a single point of failure against a 24-hour legal
  clock, and this product's field testing happens offshore with no coverage. The clock still runs.
  This is the most important non-technical item on this page.
- Make sure GitHub private reports and the security mailbox reach a device you actually read. With
  no telemetry, a human telling you is the *only* way you learn of exploitation.

*Effort: 1.5 days. Closes: blocker B4.*

## Phase 1 — Correct the record

Cheap, and it is the first thing anyone can check from outside without asking you a question.

- Rewrite the CRA section of `docs/SECURITY.md`: drop the "obligations do not apply" paragraph, and
  point at this folder rather than duplicating the mapping.
- Correct the seven claims in `gap-analysis.md` § *Where the documentation and the code disagree* —
  in particular the (2)(h) DoS row, the (2)(l) logging row, the "hop count is exact" claim and the
  `ready` KDoc in `BluetoothTransport.kt`.
- Add the manufacturer contact block: postal address, security email, `security.txt`. One sentence
  committing to report vulnerabilities upstream in integrated components.
- Start `CHANGELOG.md` for the app, with a `### Security` section. Fix the plugin's `0.2.0` gap.

*Effort: 1.5 days. Closes: part of II(4), II(6); Annex II items 1–2.*

## Phase 2 — The availability cluster

The engineering heart. Every item here is small, local and independently shippable, and together
they close the clause the documentation currently claims to satisfy.

| Task | Finding | Shape of the fix |
| --- | --- | --- |
| Authenticate before populating the LAN peer table | C-1 | Call back into `LanTransport` on `Ingress.Result.Accept`; `onPacket` already carries the transport and link token. Keep the group copy for the first frame of each talk burst. |
| ttl-aware dedupe | H-1 | `SeenCache` becomes `LinkedHashMap<Long, Int>`; forward a duplicate only on a strictly greater ttl, compared in the same synchronized block. No wire change. |
| Per-source ingress buckets in `LanTransport` | H-2 | A hash lookup before the engine. Reserve a share of the global budget for recently-authentic addresses. Remove the pre-budget `senderId == self` shortcut. |
| Port `RateLimiter` to the plugin | H-3 | Same three buckets, same order; charge the global one in `lan.js` before `emit`. Add it to the cross-language parity tests. |
| Contain the plugin's two process-killers | H-5, H-6 | `try/catch` around `receive` and every emit-to-host callback; `.catch` at both `openLink` and both `pump` call sites; move `emit("started")` inside the `try`. |
| Isolate the audio threads | H-7, H-8 | Give `ptt-capture` and `ptt-mixer` the same discipline as `transportThread`. Confirm the worker is dead before `release()` rather than joining for 500 ms and releasing regardless. |
| Wire up `ready` and add a health state | H-4 | Use it in `sendHello`; add `healthy = transports.any { it.ready }`; render "ON CHANNEL — NO LINK" on the disc, the channel row and the notification; add a receive-side watchdog with an audible cue. |
| Link liveness and caps | M-10, M-11 | Application-level idle sweep for RFCOMM links (there is no socket timeout); apply Aware's `MAX_LINKS` cap to accepted BT links; dedupe Aware responder callbacks by peer. |

Then, and only then, restore the (2)(h) claim in `docs/SECURITY.md`.

*Effort: 5–8 days. Closes: I(2)(h), I(2)(i); most of I(2)(f).*

## Phase 3 — Support period and distribution

Both are D-2 and D-3 made real. Start early: the signing consequences have a long lead time.

- Publish the support period as an absolute end date, per major line, on the Releases page, in the
  README and in the user information. Add a compile-time end-of-support date that raises a persistent
  in-app notice once passed — the only mechanism that reaches a user who never opens the Releases
  page, and it doubles for Art. 14 user notification.
- Implement the update path chosen in D-3.
- **If Google Play:** plan the migration deliberately. Play App Signing re-signs with a Google-held
  key, so a Play-signed install cannot upgrade an existing sideloaded one — the user must uninstall,
  and `docs/BUILDING.md` correctly warns that uninstalling destroys the channel key. Ship a version
  first that makes key export easy, warn in-app and in release notes well ahead, and document the
  transition. Do not discover this at launch.
- **If sideload remains:** a signed `latest.json` over HTTPS from a fixed origin, verified in-app
  against a pinned public key, with an advisory banner, on by default with an opt-out.
- Either way, resolve the key custody question (H-20). Today the signing key is one file in one
  `~/.crewradio/` plus one repository secret, with no escrow, no rotation runbook and no succession
  plan — `docs/BUILDING.md` says outright "there is no recovery". `minSdk = 29` means v3 signing
  lineage rotation is available on every supported device and is currently unused.
- Publish `CREWRADIO_CERT_SHA256` out of band so a customer can compare independently; add
  `--signer-workflow` to the documented verify command and a verification procedure for the plugin
  tarball.

*Effort: 0.5 day to decide, 3–5 days to implement. Closes: blockers B1, B2; I(2)(c), II(7), II(8).*

## Phase 4 — The three missing features

Small, and between them they close four clauses.

- **Security log with an opt-out** (2)(l): bounded, append-only, app-private and encrypted, recording
  key read/change/regenerate/share, managed-config override applied, pairing requested/granted/denied,
  token stored/cleared, and sustained AEAD-failure or rate-limit events with a peer count — never
  payloads. Read-only on the Status screen with a "record security events" toggle and a clear action.
  **Local only.** Sending it anywhere would undo the (2)(g) posture, which is a genuine strength.
- **Reset control** (2)(b), (2)(m): a "Reset Crew Radio" preference behind a confirm dialog, clearing
  all preferences including the key and the token.
- **Keystore-wrapped secrets** (2)(e): both the channel key and the Signal K token, with
  `setUnlockedDeviceRequired(true)` and a one-shot migration from the plaintext values.

Alongside: explicit `path` attributes in both blocks of `data_extraction_rules.xml`, **verified with
a real device transfer** before release (M-8), and a persistent crash log plus a
`Thread.setDefaultUncaughtExceptionHandler` and a redacted "Share diagnostics" action — without which
no commercial support process works and no Art. 14 detection is possible.

*Effort: 3–4 days. Closes: I(2)(b), I(2)(e), I(2)(l), I(2)(m).*

## Phase 5 — The key model

Five findings are one problem: the key is human-transcribable, weakly floored, freely exportable and
unrotatable. One change collapses most of it.

- **Generated-only, high-entropy keys distributed by QR code.** A scanned key need never be typed or
  read aloud, so it can be 128 bits — which retires the typed-key floor (H-10) and makes the fixed
  salt (H-9) academic, and it removes the share-sheet export (H-11) at the same time.
- If free text stays, raise `NEW_KEY_MIN` and reject anything matching a bundled wordlist.
- Fix the managed-configuration floor: `Prefs.kt:110` and `:156` validate an EMM-pushed key with
  `validPassphrase` (8 characters), but a key an administrator provisions is a *new* key and should
  meet `validChannelKey` (M-2).
- Trim the key on the app side, or reject leading and trailing spaces — the plugin trims and the app
  does not, which produces a silent, near-undiagnosable key mismatch (M-16). Add a minimum length to
  the plugin schema (M-15).
- Expire `SeqTracker` marks after a couple of minutes of silence rather than keeping them for the
  process lifetime. This removes the permanent-mute primitive (M-1) with no loss of replay protection,
  since the 60 s timestamp window is the real control. Move `heardAudio` after `admitAudio` so the
  roster never shows "talking" for audio being dropped.
- At the next wire version: a **key id / epoch byte**, so a mismatched key is reported precisely
  instead of as a silent failure, and a staged re-key becomes possible. This is the single
  highest-value wire change available, and it is what makes revocation practical.

*Effort: 3–4 days, plus a wire version bump for the epoch byte. Closes: much of I(2)(a), I(2)(e), I(2)(k).*

## Phase 6 — Close the Signal K surface

One coherent change across `SignalKUrl`, `SignalKClient`, `SettingsActivity` and the plugin.

- Scope cleartext to private address ranges and `.local`, or require HTTPS with a TOFU certificate
  pin recorded at pairing (H-13).
- Reject any `href` that is not a same-origin absolute path; resolve with `URI.resolve` and assert
  scheme, host and port are unchanged (M-4).
- Stop auto-persisting mDNS results: present a pickable list showing name, IP and port, and refuse
  results outside the phone's own subnet (M-3).
- Plugin: reject `text/plain` on `POST /say` so a cross-origin post is preflighted (M-5); hard-fail
  when an access level cannot be established rather than silently trusting the mount (M-6); restrict
  `priority: "urgent"` to the in-process API and the bridge so it cannot be used to suppress alarm
  announcements (M-7); cap `NotificationBridge.active`; add a `readBody` timeout.
- Support an env-var or `0600` file for the plugin's channel key, as `tools/cli.js` already models
  (M-13).

*Effort: 3–4 days.*

## Phase 7 — The CI gate and the supply chain

- **OSV or Trivy against the SBOM you already generate**, placed in `release` *before* the
  attestation step, mirroring where the certificate gate already sits — so nothing vulnerable is ever
  attested (H-16). `npm audit` for the plugin.
- Generate, publish and attest an **SBOM for the plugin** (H-17), including the Flite WASM
  component's integrity hash and its BSD-4-Clause notice, which is currently reproduced nowhere.
- Pin or fork the `@master` reusable workflow, or move it to a non-blocking job (H-19).
- Narrow the Dependabot `ignore: "*"` rule to the toolchain.
- Add `gradle/actions/wrapper-validation`; verify the wrapper jar hash once against Gradle's
  published checksums.
- Pin `buildToolsVersion`, the JDK patch level and the runner image, so a rebuild is at least
  reproducible by you.
- Record `npm-shrinkwrap.json` for the plugin — honoured by `npm install`, ships inside the tarball,
  and is not named `package-lock.json`, so it should not trip the reusable workflow's cache
  detection. Verify that before adopting.
- Enable and evidence branch protection on `main`: required review, required checks, **no
  force-push** (a rewritten history lowers `versionCode` and permanently strands every installed
  phone), signed commits. Export the ruleset as technical-documentation evidence.

*Effort: 3–4 days. Closes: I(2)(a), II(1).*

## Phase 8 — The formal artefacts

The largest block, and roughly half of it already exists in `docs/`.

| Artefact | Source | Work |
| --- | --- | --- |
| Risk assessment (both products) | `docs/SECURITY.md` threat model | Restructure: assets, ratings, residual acceptance with a named person, intended purpose and foreseeable misuse, version and date. |
| Annex II user information | README + this folder | New `docs/USER-INFORMATION.md` per product, plus an About screen in the app. |
| Annex VII technical file | `ARCHITECTURE.md`, `BUILDING.md`, this folder | Assemble. Architecture and production process reuse nearly as-is; items 1(a), 4, 6, 7 are new. |
| Test plan and reports | Existing CI | Write the plan; capture per-release results. |
| EU declaration of conformity | — | **Draft with legal review.** Generate per release from a template in the `release` job, published as a release asset and attested with the rest. Do not draft one from these notes alone. |
| CE marking | — | On the declaration and on the download page, accessible including to persons with disabilities. No notified body number — Module A. |

*Effort: 14–20 days. Closes: blocker B3.*

## Phase 9 — Standing obligations

- Privacy policy (a Play prerequisite, and short here: no server, no accounts, no analytics, nothing
  collected by the developer) and the Data Safety declaration. A prominent disclosure before the
  first `RECORD_AUDIO` prompt, explicitly covering the always-on monitor that `headset_vox` enables —
  a user enabling "voice keying" is unlikely to realise the mic stays open for the session.
- Advisory practice: GitHub Security Advisories per fixed vulnerability, a CVE route, and the
  vulnerability register.
- Component due-diligence notes: the Flite WASM binary (unmaintained since 2023, sandbox as the
  compensating control, and a stated plan if a Flite vulnerability is disclosed), AndroidX, and the
  platform Opus codec you cannot patch and must therefore monitor.
- Test depth for II(3): fuzz `Packet.parse`, `Hello.decode`, `StreamLink.readLoop` and
  `lib/packet.js` — ideally **differentially**, feeding the same random bytes to both parsers and
  asserting they agree on accept/reject, seeded from `test/vector.json`. A concurrency hammer over
  `Ingress.admit` asserting "accepted once, charged once". A two-engine in-memory loopback test,
  which would cover the wire round trip, relay, roster and mixer on the JVM with no device at all.
- Retention: technical documentation and declarations kept for ten years, off GitHub as well as on.

*Effort: 7–9 days, then continuing.*

## Effort summary

| Block | Days |
| --- | --- |
| Documentation and process (phases 0, 1, 8, 9) | 22–28 |
| Engineering (phases 2, 3, 4, 5, 6, 7) | 18–25 |
| **Total setup** | **≈ 40–53** |

Plus, outside the repository: legal review of the declaration, the EULA and the intended-purpose
wording; Play onboarding; optionally an external review of `ChannelCrypto`, `Packet` and `Ingress`,
which is the one place a single maintainer has no second pair of eyes; possibly translations.

**Steady state, for the whole support period: roughly 1–3 days a month.** Dependabot review and
verification-metadata regeneration, CodeQL triage, a per-release declaration and technical-file
update, vulnerability triage inside the 7-day/30-day commitments, an annual risk-assessment review,
and toolchain majors by hand.

## When is it done

Not a date but a checklist. The product may be placed on the market when all of these hold:

- ☐ D-1 to D-4 decided and recorded.
- ☐ Support period declared as an end date, in the user information and on the download page.
- ☐ An update mechanism that installs security updates without a manual sideload, on by default,
      with an opt-out and a postpone.
- ☐ Annex I Part I: no clause at **Not met**. (2)(h) and (2)(l) are the two that must move.
- ☐ Annex I Part II: no point at **Not met**.
- ☐ Annex II: all nine items present.
- ☐ Risk assessment, technical file and declaration drawn up; CE marking affixed.
- ☐ Article 14 runbook filled in, with a named deputy.
- ☐ A vulnerability scan gating the release, with evidence retained per release.
- ☐ Retention arrangement that does not depend on GitHub alone.

## An honest note on the burden

The engineering here is the work of someone who cares about doing it properly — the `Ingress`
ordering fix found by measuring concealed frames in a cabin, the split-token release pipeline, the
refusal to fall back to a network speech recogniser. That standard of care is why this plan is
forty days and not four hundred: there is no rotten foundation, only artefacts to write and a
handful of features to add.

The burden is not the setup. It is the **five-year, 24-hour-clock commitment held by one person**,
on a product whose testing happens at sea. If only three things come out of this folder, make them:
decide the support period and mean it; nominate a deputy for Article 14; and get the update
mechanism onto a platform that does it for you, because a manual sideload cannot satisfy "without
delay" no matter how good the rest of the file is.
