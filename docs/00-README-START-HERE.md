# Mezgeb (መዝገብ) — Build Pack

**Automatic mobile-money ledger and credit book for Ethiopian small businesses.**

---

## What this pack is

Seven files. Read them in order once, then work from `05-BUILD-SESSIONS.md`.

| File | Purpose |
|---|---|
| `00-README-START-HERE.md` | This file. Product, positioning, why it's worth building. |
| `01-.md` | Copy to repo root as `.md`. The project's conventions and hard constraints. |
| `02-SPEC.md` | Product spec — features, screens, user stories, scope fences. |
| `03-ARCHITECTURE.md` | Technical design — modules, data model, capture strategy, parser engine. |
| `04-SMS-SAMPLE-COLLECTION.md` | **Do this before writing code.** How to gather the corpus the parser is built from. |
| `05-BUILD-SESSIONS.md` | 12 build sessions with copy-paste prompts. The main artifact. |
| `06-PORTFOLIO-AND-PITCH.md` | Demo script, README copy, Upwork positioning, the Rowda conversation. |

---

## The product in one paragraph

Every digital payment an Ethiopian shop receives — telebirr, CBE Birr, bank transfer — arrives as an SMS notification. A busy shop accumulates hundreds of these and reads none of them. Meanwhile the same shop sells on credit constantly, tracked in a paper notebook that nobody reconciles against anything. Mezgeb reads the payment notifications automatically, builds a running ledger, keeps the credit book digitally, and reconciles the two — so at the end of any day the owner can answer three questions in five seconds: *what came in, who still owes me, and does the money match the sales.*

## Why this one is worth your time

**It solves a problem you can watch happen.** Walk into any shop and the payment SMS backlog is right there on the counter phone. You don't have to explain the problem to the customer; they'll explain it to you.

**Zero-network is a feature, not a limitation.** v1 never touches the internet. No account, no cloud, no signup. For a product handling someone's money that's a trust argument, and it's also why the demo works instantly with no backend to keep alive.

**It composes with Suq.** Suq knows what was *sold*. Mezgeb knows what was *paid*. The reconciliation between them is a product you can sell as one thing later. Build Mezgeb standalone now, keep the seam clean, and the integration is a v2 story rather than a rewrite.

**The hard parts are legibly hard.** The Play Store permission constraint, the adaptive parser, the deduplication between two capture paths — these are all things a reviewer can look at and understand *why* they were difficult. That's the difference between a portfolio project and a tutorial.

## The two technical hooks

These are what you put at the top of the README and lead with in any pitch.

**1. Play-compliant payment capture.** SMS permissions are effectively unavailable to finance apps on Play — apps that aren't the default SMS handler can't even declare the permission in the manifest. Mezgeb captures via `NotificationListenerService` instead, which sees the same content through a permission class Play does allow. A second build flavor adds true SMS access for sideloaded installs, enabling historical backfill that the notification path can't do. Same parser, two sources, one deduplication layer.

**2. A parser that learns from the user.** Payment SMS formats change without warning and vary by provider. Rather than hard-coding regexes and shipping an update every time telebirr rewrites a template, unknown messages land in a review queue where the user highlights the amount, the counterparty, and the reference directly in the message text. The app generates a candidate rule from those spans, tests it against every other unmatched message from the same sender, reports the hit rate, and offers to save it. The parser gets better on the user's device without a release.

## Scope discipline

The single biggest risk to this project is scope creep into "accounting software." It is not accounting software. Hold these lines:

- **No cloud, no accounts, no sync in v1.** Backup is an encrypted file the user exports.
- **No inventory.** That's Suq's job.
- **No tax or VAT logic.** Report totals, export CSV, stop there.
- **No multi-user or roles.** One phone, one owner.
- **No payment initiation.** Read-only forever. Never send money, never touch a payment API.

Anything that doesn't serve "what came in, who owes me, does it match" goes in a `BACKLOG.md` and stays there.

## Before you start

Complete `04-SMS-SAMPLE-COLLECTION.md` first. The parser is built from a real corpus, and every session after Session 2 depends on it existing. Collecting samples takes a day of asking around; skipping it means writing regexes against imagined message formats, which will not survive contact with a real phone.

---

**Stack:** Kotlin 2.x · Jetpack Compose (Material 3) · Room · Hilt · WorkManager · DataStore · Pure-JVM parser module · No network dependencies
