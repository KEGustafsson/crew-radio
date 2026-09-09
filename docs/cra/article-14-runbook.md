# Article 14 reporting runbook

The duty that starts first. From **11 September 2026**, a manufacturer must notify actively
exploited vulnerabilities and severe incidents to the coordinating CSIRT and to ENISA on fixed
clocks, and must inform affected users.

Whether the duty reaches products placed on the market before full application is genuinely
uncertain. The prudent reading is that it does. Treat it as live.

> **Placeholders below marked `<< … >>` must be filled in before this document is of any use.**
> An unfilled runbook is not a process. Nothing here has been reviewed by a lawyer.

## Who

| Role | Who | Contact |
| --- | --- | --- |
| Responsible person (decides and files) | `<< name >>` | `<< email / phone >>` |
| Deputy (files if the responsible person is unreachable) | `<< name >>` | `<< email / phone >>` |
| Coordinating CSIRT | `<< confirm current designation — for a Finnish establishment, the national CSIRT function at Traficom (NCSC-FI) >>` | `<< intake URL / email >>` |
| ENISA | via the single reporting platform under Art. 16 | `<< platform URL, or interim route >>` |

**The deputy is not optional.** A single maintainer is a single point of failure against a 24-hour
legal clock, and this product's field testing happens offshore with no coverage. The clock runs
anyway. Give the deputy this document, the contacts, and the ability to file.

## Clocks

Both tracks are notified **simultaneously** to the CSIRT and ENISA.

### Actively exploited vulnerability

| Stage | Deadline | Content |
| --- | --- | --- |
| Early warning | **24 h** of becoming aware | That the vulnerability exists and is actively exploited; whether other Member States may be affected. |
| Vulnerability notification | **72 h** of becoming aware | General information about the product, the general nature of the exploit and the vulnerability, any corrective or mitigating measures taken and available to users. |
| Final report | **14 days** after a corrective or mitigating measure is available | Description including severity and impact; information about any malicious actor where available; details of the security update or other corrective measures. |

### Severe incident affecting the security of the product

| Stage | Deadline | Content |
| --- | --- | --- |
| Early warning | **24 h** of becoming aware | That a severe incident occurred; whether unlawful or malicious; whether other Member States may be affected. |
| Incident notification | **72 h** of becoming aware | Nature of the incident, an initial assessment, corrective or mitigating measures taken and available. |
| Final report | **1 month** after the incident notification | Detailed description including severity and impact; the type of threat or root cause likely to have triggered it; applied and ongoing mitigations. |

**Plus: inform affected users**, and where appropriate all users, about the vulnerability or
incident and, where necessary, about mitigation and any corrective measures they can take.

This is the duty Crew Radio is least equipped to discharge: there is no server, no accounts and no
telemetry, by design. The available channels are the Releases page, a store listing, and an in-app
notice compiled into a build. Build the in-app notice path (roadmap phase 3) — it also serves the
end-of-support notification.

## What counts, for this product

Decide this in advance. Under a 24-hour clock, the judgement cannot be made from scratch.

**"Actively exploited" means:** credible evidence that someone is using the flaw against real
deployments — a reporter's working exploit seen in the wild, a crew reporting effects consistent
with an attack, or an observation on a boat network. It does not mean a theoretical finding, a
CodeQL alert, or a researcher's proof of concept with no evidence of use.

**Worked examples that would qualify** if exploitation were observed:

- An AEAD bypass allowing audio to be injected into a channel without the key.
- A key-derivation weakness enabling practical offline recovery of a channel key from captured
  traffic.
- Peer-table poisoning silently redirecting a phone's outgoing audio (`gap-analysis.md` C-1) — note
  that this one currently produces **no** counter movement and no log line, which is exactly why the
  detection work in roadmap phases 2 and 4 matters here.
- Relay suppression partitioning a mesh from off-channel (H-1).
- A remotely triggerable crash reachable from the network by an unauthenticated party.
- Any compromise of the release pipeline or the signing key — which would be a severe incident as
  well as a vulnerability.

**Examples that would not, by themselves:** a crew member misplacing the channel key; a Signal K
server misconfigured by its operator; a platform bug in Android's Bluetooth or audio stack (report
that onward to the vendor, and say so); a denial of service that is indistinguishable from ordinary
Wi-Fi congestion, unless evidence points to deliberate action.

**When genuinely unsure, file the early warning.** It is a short notice, the 24-hour stage asks only
for the fact and the possible cross-border reach, and withdrawing an over-cautious report costs far
less than missing a deadline.

## How you would find out

With no telemetry, every route is a human telling you. Keep all of them alive and monitored:

- GitHub private vulnerability reporting (the route `SECURITY.md` publishes).
- The security email address — once one exists; see roadmap phase 1.
- Store developer notifications, if distributing through a store.
- A crew or a commercial operator reporting symptoms.

Once the security log and diagnostics export exist (roadmap phase 4), a user-supplied diagnostic
bundle becomes the first real technical evidence available to you. Until then there is none.

## On becoming aware

1. **Note the timestamp.** Both clocks run from "becoming aware". Write down the moment and how you
   learned, before anything else.
2. **Decide the track** — vulnerability or incident — against the criteria above. If both, both.
3. **File the early warning within 24 hours.** Template below. Do not wait for analysis; the stage
   exists precisely to be filed before you understand the problem.
4. **Open a register entry** (format below) and keep it as the single record.
5. **Analyse, and file the 72-hour notification.**
6. **Fix, ship, and inform users.** Follow the CVD commitments in `SECURITY.md` — acknowledgement
   within 7 days, fix or mitigation within 30 — which are tighter than the reporting deadlines and
   are the ones a reporter is holding you to.
7. **File the final report** — 14 days after a measure is available for a vulnerability, one month
   after the notification for an incident.
8. **Publish the advisory** under Annex I II(4): description, affected versions, impact, severity,
   remediation. The clause permits a delay where publication risk outweighs the benefit; record the
   reasoning if you use it.

## Templates

Keep these filled in as far as they can be in advance — the product identification block never
changes, and under a 24-hour clock a template is the difference between compliance and a missed
deadline.

### Early warning (24 h)

```text
Subject: CRA Art. 14 early warning - << product >> - << internal ref >>

Manufacturer:        << legal name, address, contact >>
Product:             << Crew Radio (fi.crewradio) | signalk-crewradio >>
Affected versions:   << range, or "under investigation" >>
Became aware:        << UTC timestamp >> via << route >>

Type:                << actively exploited vulnerability | severe incident >>
Summary:             << two or three sentences, no analysis required at this stage >>
Unlawful or malicious cause suspected:  << yes | no | unknown >>   [incidents only]
Other Member States possibly affected:  << yes | no | unknown >>
   Basis: the product is distributed via << channel >> without geographic restriction;
   deployments are not known to the manufacturer, as the product has no telemetry.

Status:              Investigation in progress. Notification to follow within 72 hours.
Contact:             << responsible person, direct contact >>
```

### Vulnerability / incident notification (72 h)

```text
Subject: CRA Art. 14 notification - << product >> - << internal ref >>

[product identification block as above]

General nature of the vulnerability:  << component, class of flaw >>
General nature of the exploit:        << how it is being used; pre-conditions an attacker needs >>
Assessed severity:                    << CVSS or a stated qualitative scale, with reasoning >>
Affected versions:                    << confirmed range >>

Measures taken:                       << what has been done >>
Measures available to users:          << mitigations users can apply now, e.g. rotate the channel
                                         key, disable a feature, leave the channel >>
Expected remediation:                 << target date >>

[incidents only]
Initial assessment:                   << scope, what is known and not known >>
```

### Final report

```text
Subject: CRA Art. 14 final report - << product >> - << internal ref >>

[product identification block as above]

Description:               << full technical description >>
Severity and impact:       << assessed severity, and observed impact on users >>
Malicious actor:           << information available, or "none available" >>
Root cause:                << for an incident: the threat type or root cause likely to have
                              triggered it >>
Corrective measures:       << version that fixes it, how it reaches users, and by when >>
Ongoing mitigations:       << anything still in place >>
Users informed:            << how and when >>
Public advisory:           << link, or the reasoning for any permitted delay >>
```

## Register

One row per reportable event, kept alongside the general vulnerability register.

| Field | Notes |
| --- | --- |
| Internal reference | |
| Became aware (UTC) | The moment both clocks start from. |
| Source | Private report, email, store, user. |
| Track | Vulnerability / incident / both. |
| Product and affected versions | |
| Reportable? | Yes / no, **with the reasoning either way** — a documented decision not to report is itself the evidence that a process ran. |
| Early warning filed | Timestamp, reference. |
| Notification filed | Timestamp, reference. |
| Final report filed | Timestamp, reference. |
| Users informed | How, when. |
| Fixed in | Version. |
| Advisory published | Link, or the reason for delay. |

## Review

Re-read this page whenever the runbook is used, whenever the responsible person or deputy changes,
and at least annually. Record the review date here: `<< date, reviewer >>`.
