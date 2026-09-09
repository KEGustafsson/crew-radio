# Scope and classification

Whether the Cyber Resilience Act applies to Crew Radio, to which artefacts, in which category, and
by which conformity assessment route. Assessed on the premise that the product is **sold** — the
free-and-open-source carve-out is therefore unavailable.

## 1. Two products, not one

Art. 3(1) covers software placed on the market separately. Both artefacts qualify, and they should
be treated as **two products** with two declarations, two support periods and two technical files:

| | Android app | Signal K plugin |
| --- | --- | --- |
| Identity | `fi.crewradio`, "Crew Radio" | `signalk-crewradio` |
| Versioning | `1.<commit count>`, `BuildConfig.GIT_SHA` | semver, currently `0.2.0` |
| Platform | Android 10 (API 29) and above | Node ≥ 24, Signal K server |
| Installed by | A crew member, on a phone | A boat-server operator, on a server |
| Artefact | `CrewRadio-<version>.apk` | `signalk-crewradio-<version>.tgz` |

They are coupled technically — `sk-plugin/test/vector.json` and `CrossLanguageVectorTest` hold the
wire format in step — but that is a dependency, not a shared product identity. Their lifecycles,
platforms, users and attack surfaces all differ. Bundling them into one declaration creates an
ambiguity that would have to be defended later.

Art. 2(1) requires a logical or physical data connection. Satisfied several times over: UDP
multicast and unicast over WLAN, Bluetooth Classic RFCOMM, Wi-Fi Aware data paths, and HTTP to a
Signal K server.

## 2. Exclusions — none apply

Art. 2 excludes products covered by the medical device regulations, motor vehicles and civil
aviation. None is relevant.

**Marine equipment (Directive 2014/90/EU)** deserves a sentence because of the domain, and the
answer is no: the MED covers type-approved equipment carried aboard SOLAS ships under a flag-State
certificate and bearing the wheelmark. Crew Radio is a consumer application on crew-owned phones.
It cannot claim that exclusion and should not try.

The **Radio Equipment Directive** and its delegated Regulation (EU) 2022/30 place obligations on the
*radio equipment* — the phone — not on an application running on it. Those sit with the phone
manufacturer.

## 3. The plugin is not a "remote data processing solution"

Art. 3(2) has two cumulative limbs. The plugin meets the first (developed by the manufacturer) and
fails the second (its absence would prevent the product from performing one of its functions):

- **Every core function works without it.** Join, talk, relay, roster, headsets, hardware keys. The
  plugin's own README says so: *"An optional extra, off until you switch it on, and not needed to
  talk on the channel."*
- **Nothing is hosted by the manufacturer.** The plugin runs on the *user's own* boat server, on the
  user's network, under the user's configuration. Remote data processing contemplates processing
  performed by or for the manufacturer as part of the product's function. There is no
  manufacturer-operated endpoint anywhere in this product.
- **GitHub Releases is a distribution channel**, not a remote data processing solution. Hosting an
  APK for download does not pull GitHub into the product boundary.

*Keep the README's framing.* The "Ask boat data — Whole crew" mode does require the plugin. If
"Whole crew answers" is ever marketed as a headline function rather than an add-on, the limb-2
argument weakens.

## 4. Annex III and Annex IV — category by category

Classification turns on the product's cybersecurity-related functionality and criticality, not on
whether it handles sensitive data or uses cryptography.

### Annex III Class I

| Category | Verdict | Reasoning |
| --- | --- | --- |
| Identity management, privileged access management | No | A single symmetric group key is not identity management. No identities, accounts, roles or provisioning. `docs/SECURITY.md` says so: *"It does not authenticate individual people."* |
| Standalone and embedded browsers | No | No WebView. `StatusActivity.openReleases()` fires an `ACTION_VIEW` to the *system* browser. |
| Password managers | No | Stores its own channel key and its own Signal K token. Storing your own secret is not managing others' credentials. |
| Malware detection, removal, quarantine | No | Nothing of the kind. |
| **VPN products** | No | See §5 — argue this one in writing. |
| **Network management systems** | No | See §5 — argue this one too. |
| SIEM | No | Forty in-memory status lines for this phone. No collection from other systems, no correlation, no retention. (This is evidence of a (2)(l) gap, not a classification risk.) |
| Boot managers | No | Android application. |
| PKI, certificate issuance | No | Symmetric key use only — PBKDF2, AES-GCM, HMAC. |
| Physical and virtual network interfaces | No | Uses platform sockets. Creates no interface. |
| Operating systems | No | — |
| Routers, modems, switches | No | Application-layer forwarding of its own packets. Not an internetworking device. |
| Microprocessors, microcontrollers, ASICs, FPGAs with security functionality | No | No hardware. |
| Smart home general purpose virtual assistants | No | "Ask boat data" is voice-driven but neither smart-home nor general-purpose: `AskIntents` matches a closed vocabulary of boat quantities, `SignalKClient` only ever GETs, and the design rule is recorded — *"it does not let an answer trigger an action."* |
| Smart home products with security functionality | No | The notification bridge announces alarms generated by someone else's system. It is a loudspeaker, not an alarm system. |
| Internet-connected toys | No | Not a toy. |
| Personal wearables with a health purpose | No | Runs on a phone. |

### Annex III Class II

Hypervisors and container runtimes — no. **Firewalls, IDS/IPS** — no: the rate limiting and
seen-cache are self-protection of the app's own ingress, not a network security product offered to
protect other assets. Tamper-resistant microprocessors — no hardware.

### Annex IV (critical)

Hardware devices with security boxes; smart meter gateways; smartcards and secure elements. All no
— no hardware, and no cryptoprocessing performed on behalf of other systems.

## 5. The two arguments to write down in advance

An unanswered classification question costs more than a documented one. Both of these belong in the
technical file verbatim.

### Not a VPN product

*The case for:* Crew Radio establishes authenticated, encrypted communication between endpoints
across untrusted media using a shared pre-shared secret, derives further link secrets from it
(`ChannelCrypto.awarePassphrase`), and relays multi-hop between nodes. Superficially that describes
a mesh VPN.

*The case against, and it is decisive:* a VPN product in the Annex III sense extends a private
network by tunnelling **arbitrary third-party traffic** — it presents a network interface, routes
IP, and other applications' traffic transits it. Crew Radio does none of that. It encrypts only its
own application payload (codec 0 PCM frames, 1 Opus packets, 2 hellos); `PttEngine.onPacket`
forwards only its own `'P''T'` packets and only after the AEAD verifies; `Packet.MAX_SIZE` caps them
at 1024 bytes; and no API exposes the channel to any other app on the phone. Functionally it sits
with encrypted messengers and VoIP clients — categories the legislator conspicuously did not place
in Annex III.

### Not a network management system

*The case for:* it maintains a peer roster with hop counts and reachability, runs a heartbeat,
controls a hop budget, chooses transports and re-forms links. That is topology awareness.

*The case against:* it manages only its own overlay of its own peers. It administers no third-party
network device, offers no configuration or monitoring surface for any network but its own, and its
purpose — the README's first line — is *"A walkie-talkie for a crew."* An NMS in Annex III is a tool
for administering network infrastructure: controllers, SNMP managers, orchestration.

## 6. Conformity assessment route

Default category → **internal control, Module A (Annex VIII Part I)**, under Art. 32.

- You draw up the technical documentation (Annex VII).
- You verify conformity against Annex I Parts I and II.
- You draw up and sign the EU declaration of conformity (Annex V).
- You affix the CE marking.
- **No notified body. No fee. No audit.**

Harmonised standards under the CRA (CEN/CENELEC JTC 13) are still in development; do not plan around
their availability before December 2027. Where none is applied, Annex VII item 5 requires the
solutions adopted to be described instead — which is what `annex-i-mapping.md` provides.

The compliance cost here is documentation and process discipline, not certification. That is the
single most important favourable fact in this assessment.

## 7. What monetisation changes

The carve-out is **per-supply, not per-licence**. EUPL-1.2 expressly permits commercial
distribution, so the licence is not the obstacle; the obstacle is that the current compliance
posture was written for the excluded case.

| Today (not placed on the market commercially) | After monetisation |
| --- | --- |
| Art. 2 exemption; no manufacturer obligations | Full Art. 13 obligations |
| No support period | Support period required, ≥ 5 years baseline |
| No declaration, no CE marking | Both mandatory before placing on the market |
| No technical documentation | Annex VII file, retained 10 years |
| Voluntary vulnerability handling | Annex I Part II mandatory, all 8 points |
| No reporting duty | Art. 14: 24 h / 72 h / 14-day to CSIRT and ENISA |
| Fixes when convenient | Without delay, **free of charge**, with advisory messages |
| No market surveillance exposure | Full Chapter V exposure |
| No software liability regime | PLD (EU) 2024/2853 applies |

Four traps specific to this repository:

1. **You probably cannot run a "free exempt channel" beside a paid one.** Assume the free GitHub
   Releases channel is caught too. In practice this costs nothing, since CE marking and support
   obligations are per-product rather than per-channel — but it removes the escape route.
2. **Security updates must be free of charge** for the whole support period (Annex I II(8)). You may
   charge for the product, a licence, support, or a commercial edition. You may not put security
   fixes behind a lapsed subscription. **This is a pricing constraint, and it must be decided before
   pricing rather than after.**
3. **Directive (EU) 2019/770** imposes a second, independent update obligation for paid consumer
   supply, with its own remedies. Align its period with the CRA support period.
4. **A monetising forker becomes a manufacturer** with their own obligations. Worth a line in the
   README so downstream users understand what they are taking on.

Single authorship is a real asset here: all copyright sits with one person, so re-licensing and dual
licensing remain available. Require a CLA before accepting outside contributions, or that option
closes.

## 8. Timeline

| Date | Event |
| --- | --- |
| 10 December 2024 | Entry into force. |
| 11 June 2026 | Chapter IV — notification of conformity assessment bodies. |
| **11 September 2026** | **Article 14 reporting obligations apply.** See `article-14-runbook.md`. |
| 9 December 2026 | PLD (EU) 2024/2853 transposition due — *ahead of* CRA full application. |
| **11 December 2027** | **Full application**: Art. 13, essential requirements, conformity assessment, declaration, CE marking. |

Two consequences for a product that ships continuously:

- **No grandfathering.** `versionCode` is the commit count and every merge to `main` publishes a
  release. Each release placed on the market from 11 December 2027 is a fresh placing on the market
  and must be conformant. The effective deadline is that date for everything.
- **Art. 14 is the near one.** Whether the reporting duty reaches products placed on the market
  before full application is genuinely uncertain, and the prudent reading is that it does. Treat it
  as live from 11 September 2026. The artefact is a one-page runbook, not a project.
