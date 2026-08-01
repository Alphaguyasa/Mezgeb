# Mezgeb — Product Spec

## The user

Meseret runs a small supermarket. She has one Android phone, an intermittent data connection, and a counter that gets busy between 5pm and 8pm. Her phone receives 20–60 payment SMS a day across telebirr and CBE. She reads almost none of them. She also has a notebook where she writes down who took goods on credit, in handwriting only she can read, with no running total.

At the end of the month she wants to know whether the money that arrived matches the goods that left. Currently she cannot know this, so she doesn't ask.

She is not going to create an account, learn accounting vocabulary, or trust an app that asks to connect to the internet before showing her anything.

## Core loop

1. Payments arrive → app captures and parses them silently
2. Owner opens app → sees today's total in and out, at a glance
3. Owner records credit sales as they happen → a few taps, name + amount
4. Customer repays → owner matches the repayment to a captured transaction
5. End of day/week → owner closes a reconciliation period, sees whether it balances
6. Monthly → owner exports CSV, or just reads the report

## Feature set — v1

### F1. Payment capture
- Captures payment notifications in the background with no user action
- Two sources: notification listener (always) and SMS import (sideloaded flavor only)
- Deduplicates when both sources see the same payment
- Works with the app closed and after device reboot
- Shows a persistent-but-quiet status so the user knows capture is alive

### F2. Parsing
- Bundled rules for the major Ethiopian channels, built from a real corpus
- Extracts: amount, direction, counterparty name, counterparty phone, provider transaction ID, balance-after (if present), timestamp
- Unmatched messages go to the review queue rather than being dropped or guessed at
- User can teach a new rule by highlighting spans (see F3)

### F3. Review queue and rule induction
- Lists every RawMessage no rule matched, grouped by sender
- Tapping one opens the message text with selectable spans
- User long-presses to select the amount, then tags it "Amount"; same for counterparty, reference, direction keyword
- App induces a candidate rule, immediately tests it against all other unmatched messages from that sender, and shows "this rule would match 14 of 16 messages"
- User saves, edits, or discards. Saved rules apply retroactively to the whole backlog.
- Any message can also be resolved manually without creating a rule

### F4. Ledger
- Chronological list of Transactions, newest first
- Filters: date range, channel, direction, category, reconciled state
- Free-text search across counterparty and reference
- Transaction detail shows parsed fields alongside the original message text
- Manual transaction entry for cash sales (which produce no SMS)
- Edit and soft-delete with an audit note; nothing is ever hard-deleted

### F5. Categories
- Small default set: Sales, Supplier payment, Rent, Utilities, Salary, Transfer, Other
- Auto-categorization rules: "counterparty contains X → category Y"
- Rules are created from the transaction detail screen with one tap ("always categorize payments from this sender as Supplier")
- Uncategorized is a valid, visible state — never silently guess

### F6. Credit book (ዕዳ)
- Customer list with running balance, sorted by amount owed
- Add credit sale: customer, amount, optional note, date
- Record repayment: either standalone, or by linking to a captured Transaction
- Per-customer statement view showing every entry and the balance over time
- Reminder action: opens the default SMS app with a pre-filled message. Does **not** send it. Does not use `SEND_SMS`.
- Flag customers whose balance has been outstanding beyond N days

### F7. Reconciliation
- Owner opens a period (day, week, or custom range)
- Enters expected total sales for the period, or imports it (v2: from Suq)
- App shows: captured digital total, recorded cash total, recorded credit total, sum vs expected, and the gap
- Lists candidate explanations for the gap: unparsed messages in the queue, uncategorized transactions, credit entries with no matching repayment
- Closing a period snapshots the numbers so later edits don't rewrite history

### F8. Reports and export
- Daily / weekly / monthly summary: total in, total out, net, by channel, by category
- Simple bar chart of daily net over the period
- Top customers by outstanding credit
- Export CSV (ledger, credit book, or both) to a user-chosen location via the storage picker
- Export a one-page PDF summary for a chosen period

### F9. Security and data safety
- Optional PIN or biometric lock on launch and on resume
- `FLAG_SECURE` on all screens showing amounts (blocks screenshots and hides from recents)
- Encrypted local backup to a user-chosen file, restorable on a new device
- No data leaves the device by any path the app controls

### F10. Localization
- English and Amharic, switchable in settings, defaulting to system locale
- ETB formatting with Ethiopian thousands grouping
- Gregorian dates in v1; Ethiopian calendar display is a v2 item (and a natural consumer of your `#3` toolkit)

### F11. Demo mode
- Settings toggle that wipes to a seeded dataset: ~200 realistic transactions across 90 days, 12 customers with credit balances, 6 messages in the review queue, one closed reconciliation period
- Exists so anyone can install the APK and see a populated app in ten seconds without waiting for a real payment
- Clearly labelled while active; exiting demo mode restores the real database

## Screens

| Screen | Purpose | Key states |
|---|---|---|
| Onboarding | Explain, request notification access, optionally SMS import | permission granted / denied / partially granted |
| Today | Today's in, out, net; recent transactions; review-queue badge | empty (no capture yet), capture-disabled warning, populated |
| Ledger | Full transaction list with filters and search | empty, filtered-empty, populated, loading |
| Transaction detail | Parsed fields + original text, category, reconcile toggle | parsed, manually-resolved, manual-entry |
| Review queue | Unmatched messages grouped by sender | empty (good!), populated |
| Rule builder | Span selection, induced rule preview, hit-rate test | selecting, testing, conflict-with-existing-rule |
| Customers | Credit book list with balances | empty, populated, overdue-highlighted |
| Customer detail | Statement, add credit, record repayment | zero balance, outstanding, overdue |
| Reconcile | Open/close period, gap breakdown | no open period, open, balanced, gap-found, closed |
| Reports | Summaries, chart, export actions | insufficient data, populated |
| Settings | Locale, PIN, categories, parser rules, backup, demo mode | — |

## Non-goals

Restating for emphasis, because these are what will kill the timeline:

- Not a POS. Suq is the POS.
- Not inventory-aware.
- Not multi-device, multi-user, or cloud-backed.
- Not a tax tool.
- Never initiates a payment or sends an SMS programmatically.
- Does not attempt to parse messages that aren't financial. Non-matching promotional SMS should be filtered out by sender allowlist before ever reaching the review queue, or the queue becomes useless.

## Definition of done for v1

- Installs on a real phone, captures a real payment within 5 seconds of it arriving, and shows it in the ledger without the user touching anything
- Parser hits ≥95% on the fixture corpus
- A person unfamiliar with the app can teach it a new message format without instruction
- Demo mode makes the app look alive on first launch
- Release APK signed and under 15 MB
- README explains the Play permission constraint and the capture strategy in under 300 words
