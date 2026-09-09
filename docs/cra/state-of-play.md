# State of play

One page. Where Crew Radio stands against the Cyber Resilience Act if it is sold, what blocks it,
and what happens next. Everything here is summarised from the other papers in this folder; follow
the links for the evidence.

| | |
| --- | --- |
| **Assessed** | 9 September 2026, at commit `b4c241a` (192 commits) |
| **Code fixed since** | The availability cluster, and eight further findings — see below |
| **Premise** | Placed on the EU market as a commercial product |
| **Route** | Module A, internal control — **no notified body, no fee, no audit** |
| **Products** | Two: the Android app, and `signalk-crewradio` |
| **Blockers** | 4 |
| **Findings** | 1 critical · 19 high · 28 medium · 22 low/info |
| **Nearest deadline** | **11 September 2026** — Article 14 reporting |
| **Full application** | 11 December 2027 |

## Verdict

The engineering is better than most commercial products of this size. AEAD on every packet, an
authenticated replay window, a receive pipeline whose look-charge-mark is genuinely one atomic step,
523 checksum-pinned dependencies, and a release pipeline where the token that can write to the
repository never coexists with the signing key. There is no rotten foundation — only artefacts to
write and a handful of features to add.

The CRA is nonetheless a *product-compliance* regime rather than a security-quality one. It wants a
named legal person, a declared support period, an update mechanism, a declaration of conformity and
a reporting capability. The repository has none of them.

**Availability** — the axis that matters most for a product a crew uses while manoeuvring — was
where the code did not support the claim the documentation made. That part is now fixed; see the
cluster below. What remains on that axis is the record rather than the defence: there is still no
persistent log, so an attempt leaves nothing behind for a support case or an Article 14 report.

> "The app is open source and not placed on the market commercially, so the Cyber Resilience Act's
> manufacturer obligations do not apply to it." — `docs/SECURITY.md`

True today. False, publicly and in writing, on the day of the first sale. It is the first line to
rewrite, and everything else in this folder follows from it.

## The four blockers

Each one, alone, prevents lawful placement on the market. Detail in
[`roadmap.md`](roadmap.md).

| | Blocker | Shape of the fix |
| --- | --- | --- |
| **B1** | No support period declared anywhere — zero matches across every doc and the strings resources. `SECURITY.md`'s "newest release only" is a *version* policy, which says which build, never for how long. | Declare an end date. Baseline ≥ 5 years. |
| **B2** | No update mechanism. The only update code in the app is `StatusActivity.openReleases()` — an intent to a web page. No automatic updates, no opt-out, no notification, no postpone. The README tells users to enable installs from unknown sources. | Google Play, or a signed update manifest verified in-app. |
| **B3** | No manufacturer identity, declaration of conformity, CE marking or Annex VII technical file. The only identification in the repository is a copyright line. | Decide the legal form first; ~55% of the technical file already exists in `docs/`. |
| **B4** | No Article 14 process. No CSIRT contact, no templates, no criteria, no deputy. | One page. See [`article-14-runbook.md`](article-14-runbook.md). |

## The availability cluster

The substantive engineering finding. Five items, one shape: an unauthenticated attacker within radio
range degrades or kills the channel, and nothing notices. Evidence in
[`gap-analysis.md`](gap-analysis.md).

| | Finding | Effect |
| --- | --- | --- |
| **C-1** | LAN peer table is populated six lines before the AEAD, and audio goes to the group *only while that table is empty* (`LanTransport.kt:194,233`) | 16 spoofed sources redirect every outgoing frame. The victim still looks healthy on everyone's roster and still hears them. No counter moves. |
| **H-1** | `ttl` is outside the AAD by design, and a duplicate returns before the relay block (`Packet.kt:90`, `PttEngine.kt:768`) | A replayed frame with ttl lowered to 1 stops a relay forwarding. The far side goes silent; the relay hears the audio normally. |
| **H-2** | The global ingress budget is charged before authentication (`Ingress.kt:72`) | 20 kpps of junk — a laptop — admits 24% of real frames. Speech unintelligible, every phone at once. |
| **H-3** | The plugin has no ingress rate limiting at all (`lan.js:51`, `node.js:223`) | Saturates the single-threaded Signal K server, taking NMEA, AIS and autopilot deltas with it. |
| **H-4** | `Transport.ready` has four implementations and zero call sites; the only log is 40 lines in memory | None of the above is detectable, displayable or recordable — including for Article 14, where you cannot report what you cannot detect. |

**Status: C-1, H-1, H-2 and H-3 are fixed, H-4 in part.** Peers are learned only through
`Transport.confirmPeer`, after the AEAD. The seen-cache keeps the highest ttl a packet has been
forwarded with and relays a copy that would reach further. A `SourceLimiter` bucket per source
address sits on the LAN socket, and a `WireLimiter` port gives the plugin the three budgets it
never had. The hello claims only transports that are `ready`, and the heartbeat says when none is.

What is **not** fixed is the recording half of H-4: still no persistent log, no crash reporter, no
diagnostics export, so an attempt still leaves nothing behind. That is Annex I (2)(l), and it is
what roadmap phase 4 is for.

## Annex I Part I at a glance

Full reasoning and closure paths in [`annex-i-mapping.md`](annex-i-mapping.md).

| Clause | | Clause | |
| --- | --- | --- | --- |
| (1) Risk-based | Partial | (2)(h) Availability / DoS | **Not met** |
| (2)(a) No known vulns | Partial | (2)(i) Impact on others | Partial |
| (2)(b) Secure by default | Partial | (2)(j) Attack surface | **Met** |
| (2)(c) Security updates | **Not met** | (2)(k) Incident impact | Partial |
| (2)(d) Access control | Partial | (2)(l) Logging | **Not met** |
| (2)(e) Confidentiality | Partial | (2)(m) Secure deletion | **Not met** |
| (2)(f) Integrity | Partial | | |
| (2)(g) Data minimisation | **Met** | | |

Part II: (5) coordinated disclosure **met**; (4) public disclosure of fixed vulnerabilities **not
met**; the other six partial. Annex II user information: **0 of 9 items complete, 5 missing
outright** — including the intended-purpose limitation that Crew Radio is not safety-of-life
equipment and not a substitute for VHF/DSC or GMDSS.

## What is genuinely strong

Worth foregrounding, because under Module A you are your own assessor and this is the evidence.

- **The release pipeline** — split `release`/`publish` jobs so no write-capable token ever runs
  beside the signing key or third-party code; certificate verified before publication and before
  attestation, failing closed four ways; keystore deleted under `if: always()`.
- **Dependency discipline** — 523 artefacts, every one sha256-pinned, zero trust exclusions,
  `.module` and `.pom` files pinned too, and the Gradle distribution itself.
- **The ingress pipeline** — the documented look-charge-mark-under-one-lock claim is *true* and
  tested, found by measuring concealed frames in a cabin and written down with its cause.
- **Privacy posture** — zero log statements in the whole app, every `PendingIntent` immutable,
  on-device speech recognition with no network fallback anywhere.
- **No injection in the TTS path** — hunted specifically and not found; the WASI sandbox is minimal
  and well contained.

## What happens next

Ordered by what unblocks the most, not by severity. Full plan in [`roadmap.md`](roadmap.md).

| Phase | | Days |
| --- | --- | --- |
| 0 | Article 14 runbook and a nominated deputy — **this week** | 1.5 |
| 1 | Correct the record: the disclaimer and seven doc-vs-code claims | 1.5 |
| 2 | The availability cluster | 5–8 |
| 3 | Support period and distribution channel | 3–5 |
| 4 | Security log, reset control, Keystore-wrapped secrets | 3–4 |
| 5 | The key model — generated-only keys, QR distribution, a key epoch | 3–4 |
| 6 | Close the Signal K surface | 3–4 |
| 7 | A vulnerability gate in CI, plugin SBOM, supply-chain tidy-up | 3–4 |
| 8 | Risk assessment, user information, technical file, declaration | 14–20 |
| 9 | Standing obligations: privacy policy, advisories, fuzzing, retention | 7–9 |
| | **Total setup** | **≈ 40–53** |

Then roughly **1–3 days a month, for the whole support period**.

Four decisions gate everything from phase 3 onward and only the maintainer can make them: whether
this is actually being sold and how; the support period; the distribution channel; and the legal
form named on the declaration. A fifth follows from the first — **security updates must be free of
charge for the whole support period**, so subscription-gated security fixes are not an option.

## The honest bottom line

Forty-odd days of setup, not four hundred, precisely because the engineering was done carefully in
the first place. The burden is not the setup. It is the **five-year, 24-hour-clock commitment held
by one person**, on a product whose testing happens at sea.

If only three things come out of this folder: decide the support period and mean it; nominate a
deputy for Article 14; and get the update mechanism onto a platform that does it for you, because a
manual sideload cannot satisfy "without delay" however good the rest of the file is.

---

*This page is a dated snapshot. Update it whenever a phase completes, a clause verdict moves, or the
findings change — and re-check it against the other papers, which are the source of truth for
everything summarised here.*
