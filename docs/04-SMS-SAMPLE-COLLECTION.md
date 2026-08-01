# Session 0 — Build the corpus first

**Do this before Session 1. No code yet.**

The parser is the heart of this app and it can only be built from real message text. Writing regexes against imagined formats guarantees a rewrite the first time you install on a real phone. Budget a day.

## Target

| Sender | Minimum samples | Notes |
|---|---|---|
| telebirr | 40 | Both received and sent. Merchant payments if you can get them. |
| CBE / CBE Birr | 30 | Bank SMS and mobile-wallet SMS use different templates. Get both. |
| Awash / Dashen / Abyssinia / other banks | 10 each | Whichever you can reach. |
| Amharic-language variants | 15 | Some providers send Amharic templates. These are the ones that break naive parsers. |
| Non-financial noise | 30 | Promos, airtime top-up ads, network messages. You need these to test the allowlist. |

More is better. 200 total is a good corpus; 400 is a great one.

## Where to get them

1. **Your own phone first.** Scroll your inbox, that's your baseline.
2. **Family and friends.** Ask for screenshots or forwarded text. Explain plainly: you're building an app that reads payment messages, you need examples of what they look like, you'll remove all names and numbers.
3. **Rowda.** This is the natural excuse to reopen that conversation without pitching anything. "I'm building something for shops and I need to see what the payment messages actually look like — can I photograph a few from your counter phone?" You get corpus data *and* you get the shop owner mentally invested before you've shown a product.
4. **Small variations matter.** A payment of 10 birr and a payment of 10,450.75 birr may format differently. Get both ends of the range. Get one that failed. Get one that's a reversal.

## Privacy rules — follow these strictly

You are handling other people's financial data. This is also the section a scholarship reviewer or a serious client will look at, so do it properly and write it down.

**Get spoken consent** before taking anything from someone else's phone. Say what it's for.

**Redact before the file is saved anywhere**, not after:

| Field | Action |
|---|---|
| Personal names | Replace with a consistent token: `Abebe K.` → `NAME_01` |
| Phone numbers | Replace digits after the prefix: `+251912345678` → `+251900000001` |
| Account numbers | Replace with same-length zeros, preserving format |
| Transaction IDs | Replace with same-length, same-charset synthetic IDs |
| Amounts | **Keep real.** These are not identifying and the parser depends on realistic formatting. |
| Dates/times | **Keep real** format; you may shift the actual dates. |
| Balance after | Keep the format, randomize the digits |

Preserve **length, character class, and separator placement** exactly. A redaction that turns `TXN8H2K9QL` into `XXXX` destroys the pattern you're trying to learn.

**Never commit unredacted samples.** Add this to `.gitignore` on day one:

```
**/corpus/raw/
**/*.unredacted.json
*.sms.txt
```

Keep the unredacted originals off the repo entirely — a local folder outside the project, deleted once redaction is verified.

## Corpus file format

One file per sender at `parser/src/test/resources/corpus/<sender>.json`:

```json
{
  "sender": "telebirr",
  "channel": "TELEBIRR",
  "collectedAt": "2026-08-01",
  "samples": [
    {
      "id": "telebirr_001",
      "body": "<redacted message text exactly as received>",
      "expected": {
        "direction": "IN",
        "amountMinor": 45000,
        "counterpartyName": "NAME_01",
        "counterpartyPhone": "+251900000001",
        "providerTxnId": "AAA1BBB2CC",
        "balanceAfterMinor": 128350,
        "occurredAt": "2026-07-14T09:22:00+03:00"
      },
      "notes": "standard receive template"
    },
    {
      "id": "telebirr_002",
      "body": "<redacted amharic template>",
      "expected": { "direction": "IN", "amountMinor": 120000, "counterpartyName": "NAME_04" },
      "notes": "amharic variant, no txn id present"
    }
  ]
}
```

Noise goes in `corpus/noise.json` with `"expected": null`, meaning the parser must return `Ignored` or `Unmatched` — never a false positive. **A false positive is worse than a miss.** A missed message shows up in the review queue where the user fixes it. A false positive silently puts a wrong number in someone's ledger.

## Labeling discipline

Fill `expected` **by hand, by reading the message**, before you write a single regex. If you write the regex first and then generate the expectations from its output, your test suite proves only that your code does what it does.

Leave a field out of `expected` if the message genuinely doesn't contain it. Absence is a valid expectation and the parser must return null rather than inventing a value.

## Split the corpus

Split roughly 70/30 into `corpus/train/` and `corpus/holdout/`. Build and tune the rules against train only. Run holdout **once**, at the end of Session 2, and once more at the end of the project. That holdout number is the one you put in the README — and unlike a number you tuned against, it means something. (Same discipline as the DocSearch eval harness; it worked there, it works here.)

## Done when

- [ ] ≥150 redacted samples across ≥4 senders, plus ≥30 noise samples
- [ ] Every sample hand-labeled with expectations
- [ ] Amharic-language variants included
- [ ] Split into train/holdout
- [ ] Unredacted originals deleted from disk
- [ ] `.gitignore` in place and verified with `git status` before the first commit
- [ ] A short `CORPUS.md` noting sample counts, sources (anonymized), and the redaction method — this is what you point a reviewer at when they ask how you handled the data
