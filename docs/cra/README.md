# Crew Radio and the Cyber Resilience Act

Working papers for placing Crew Radio on the EU market **as a commercial product** under
Regulation (EU) 2024/2847. They exist because the moment the app is sold, the sentence this
project has relied on stops being true:

> "The app is open source and not placed on the market commercially, so the Cyber Resilience
> Act's manufacturer obligations do not apply to it." — `docs/SECURITY.md`

That is correct today. It is wrong, publicly and in writing, on the day of the first sale.

## What this folder is, and is not

**It is** a self-assessment: what the Regulation asks for, where the product stands against each
requirement, what is missing, and the order to fix it in. It is written for the maintainer, and it
is the raw material for the Annex VII technical documentation.

**It is not** the technical documentation, and it is not a declaration of conformity. Nothing here
has been reviewed by a lawyer. No EU declaration of conformity exists and none should be drafted
from these notes without that review — a declaration is a signed legal statement by a named person,
not a document generated from an audit.

The findings come from a code review of the tree at `b4c241a` (192 commits). Where a finding is
marked *verified at source* it was re-checked against the code a second time, independently.

## Status at a glance

| | |
| --- | --- |
| Classification | Not Annex III, not Annex IV → **Module A, internal control**. No notified body. |
| Products | Two, placed on the market separately: the Android app and `signalk-crewradio`. |
| Hard blockers | 4 — no support period, no update mechanism, no manufacturer identity or formal artefacts, no Art. 14 process. |
| Findings | 1 critical, 19 high, 28 medium, 22 low/info. |
| Nearest deadline | **11 September 2026** — Article 14 reporting obligations apply. |
| Full application | 11 December 2027. |

## The papers

| File | What it settles |
| --- | --- |
| [`scope-and-classification.md`](scope-and-classification.md) | Whether the CRA applies, to what, in which category, by which route — and the two classification arguments worth pre-answering in writing. |
| [`gap-analysis.md`](gap-analysis.md) | What the code review found, ranked, with evidence. Includes the seven places where `docs/SECURITY.md` and the code disagree. |
| [`annex-i-mapping.md`](annex-i-mapping.md) | Clause-by-clause verdicts: Annex I Part I (1) and (2)(a)–(m), Annex I Part II (1)–(8), Annex II items 1–9. |
| [`roadmap.md`](roadmap.md) | The plan to go forward: phases, effort, the decisions only the maintainer can make, and the steady-state cost. |
| [`article-14-runbook.md`](article-14-runbook.md) | The reporting duty that starts first. Clocks, criteria, templates, and the contacts to fill in. |

## Keeping it current

Art. 13 requires the risk assessment to be updated during the support period, and Annex VII item 5
requires the solutions adopted to be described where no harmonised standard is applied — which is
what `annex-i-mapping.md` is for. Update these papers on:

- any change to the wire format or the transports;
- any new external interface (a new HTTP endpoint, a new transport, a new permission);
- any vulnerability report, whether or not it is confirmed;
- any change to the release or signing pipeline;
- otherwise, annually.

Technical documentation and the declaration must be retained for **10 years** after the product is
placed on the market, or for the support period if longer. With a release on every merge to `main`,
the working rule is ten years from the last release — and not on GitHub alone.

## Article numbering

Annex I, II, V and VII contents are cited with confidence, including sub-letters. Article
*paragraph* numbers inside Art. 13 are cited by substance where the number could not be
corroborated, and are marked where that is so. Check every citation against the Official Journal
text before anything here is filed or published.

Note that `docs/SECURITY.md`'s existing Annex I mapping already uses the Regulation's correct
lettering — (2)(c) updates, (2)(e) confidentiality, (2)(g) minimisation, (2)(j) attack surface,
(2)(l) logging. Keep it that way; the lettering is easy to get subtly wrong.
