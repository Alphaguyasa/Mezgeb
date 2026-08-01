# Mezgeb — Portfolio and Pitch

## The 20-second demo

This is the sequence. Practice it until it's muscle memory.

1. Open the app. Today screen, populated with demo data. "This shop took in 14,200 birr today. It didn't type any of that in."
2. Tap the review badge. "This message is in a format the app has never seen."
3. Highlight the amount. Tap "Amount." Highlight the name. Tap "Who."
4. Tap Test. **"Matches 14 of 16 messages."**
5. Save. "It just resolved fourteen messages in the backlog. No app update."

Step 4 is the moment. Everything else is setup. If you only ever record one thing, record steps 2 through 5 as a GIF and put it at the top of the README.

## README opening

The first three lines decide whether anyone reads the fourth:

> **Mezgeb** turns the payment SMS backlog on a shop owner's phone into a ledger, a credit book, and a monthly reconciliation — automatically, entirely offline, with no account and no internet permission.
>
> Google Play does not allow finance apps to read SMS. This one captures payment notifications instead, through a permission class Play does allow, with a separate sideloaded build that adds full inbox backfill.
>
> When a provider changes its message format, the user teaches the app the new format by highlighting the amount in the message. No update required.

Then the GIF. Then the accuracy number.

## What to lead with, by audience

**Upwork clients.** Lead with the constraint-navigation. Most Android developers would have built the SMS version, shipped it, and been removed from Play. "I read the policy first and designed around it" is a hiring signal that generalizes far beyond this app. Put it in your profile summary, not just the project description.

**Rowda.** Do not lead with technology at all. Lead with the notebook. "Can I show you something — how do you currently know if the telebirr money matches what you sold?" Then show the reconcile screen. The credit book is the feature that will actually land, because the paper debt book is a daily irritation they already feel. Offer to install it on their counter phone for free and check back in two weeks. That conversation is worth more than the app.

**Scholarship applications.** Frame it as financial-inclusion infrastructure: small merchants in cash-and-mobile-money economies have no bookkeeping tooling, the dominant global solutions assume cloud connectivity and formal banking, and this addresses that gap with an offline-first, privacy-preserving design. Pair it with the corpus methodology — collection, consent, redaction, train/holdout evaluation — which reads as research practice rather than hobby coding. Chevening in particular responds to "I identified a local problem and built the thing" much more than to technical depth.

**Other developers, if you post it.** Lead with the rule induction. Generating a regex from user-highlighted spans, generalizing the literals, and reporting a hit rate against the unmatched corpus before saving is the technically interesting part and the part that gets discussed.

## Three numbers to have ready

- **Parser accuracy on holdout**, with corpus size. "97% on 60 held-out messages across five senders."
- **Time to teach a new format.** Stopwatch it. If it's under a minute, say so.
- **APK size and permission count.** "11 MB, four permissions, no internet access." For a money app this lands.

## Profile copy

Short version for the Upwork project slot:

> **Mezgeb — offline mobile-money ledger (Android, Kotlin)**
> Captures telebirr and bank payment notifications automatically, parses them into a ledger and credit book, and reconciles digital payments against recorded sales. Built around Google Play's SMS permission restrictions using a notification-capture architecture with a dual build flavor for sideloaded historical import. Includes a parser that users can teach new message formats by highlighting fields — 97% extraction accuracy on a held-out corpus of real messages. Kotlin, Compose, Room, WorkManager. No network permission; all data stays on device.

Adjust the number to whatever you actually measure. Do not round up.

## Sequencing with project #3

Build Mezgeb first, then the Ethiopic toolkit — in that order, for a specific reason.

Mezgeb will force you to write, in production, against real requirements: Geez numeral normalization, Ethiopian phone number handling, ETB formatting, and Ethiopic-script rendering in PDF and CSV exports. That's most of the toolkit's surface area, already written and already battle-tested against a real corpus.

Extracting proven code into a library is a weekend. Writing a library speculatively and hoping someone uses it is a month you won't get back. And the extraction gives the library the one thing that makes people trust a small package: a real application already depending on it.

When you do extract it, the README line writes itself — *"extracted from Mezgeb, where it processes real payment messages in production."*

## A caution worth taking seriously

You will be handling other people's financial data on their phones. If you put this on Rowda's counter phone, that is a real shop's real money, and a parsing bug that silently records 1,045 birr as 104,500 is worse than no app.

Two protections, both already in the build sessions, both worth honoring rather than skipping when you're tired:

- **False positives fail the build.** A missed message goes to the review queue. A wrong number goes into someone's ledger and gets trusted.
- **The original message is always visible** on the transaction detail screen. The user can always check what the app read. Never hide the source.

Tell whoever installs it that it's early, that they should keep the notebook for a month in parallel, and that you want to hear about anything that looks wrong. That framing costs you nothing, protects them, and makes you look like someone who has shipped software before.
