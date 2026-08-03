# Corpus

How the message corpus behind Mezgeb's parser was collected, redacted and split.

> **Status: first pass collected, incomplete.** 84 financial samples across 3 senders
> plus 34 noise samples, all from the developer's own device. Short of the targets in
> `docs/04-SMS-SAMPLE-COLLECTION.md` (150 samples, 4 senders) — see "What is still
> missing". Accuracy figures land at the end of Session 2.

## What it is

The parser is built from real payment messages, not imagined ones. Message templates
vary by provider, by language, and by whether a field happens to be present, and no
amount of guessing reproduces that. The corpus is the ground truth the parser is
measured against.

## Sample counts

| Sender | Channel | Train | Holdout | Total |
|---|---|---:|---:|---:|
| CBE (Commercial Bank of Ethiopia) | BANK | 47 | 16 | 63 |
| EBIRR (Kaafi Microfinance wallet) | OTHER | 10 | 5 | 15 |
| telebirr (shortcode 127) | TELEBIRR | 4 | 2 | 6 |
| **Financial total** | | **61** | **23** | **84** |
| Noise (non-financial) | — | — | — | 34 |

Targets from `docs/04-SMS-SAMPLE-COLLECTION.md`: at least 150 financial samples across
at least 4 senders, plus at least 30 noise samples, including Amharic-language
variants. Run `./gradlew :parser:test --tests '*CorpusValidationTest*'` for a live
inventory against those targets.

### Message templates covered

| Sender | Templates |
|---|---|
| CBE | debit transaction with fee breakdown; transfer out to a named account; receipt from a named account; account credited, with and without a named party; account debited with inline fees; credit carrying an in-body timestamp and reference |
| EBIRR | wallet send with charges, counterparty name and phone; wallet receipt from a bank; transfer out to a bank account with an empty counterparty; airtime top-up received |
| telebirr | package purchase; receipt from a bank with reference and timestamp; airtime top-up recharge |

Amount formats present include `7.0`, `27.0`, `200`, `35.00`, `1,000`, `2,500.00` and
`1004.21`, with and without a space after `ETB`, plus totals that differ from the
transaction amount because fees are added — the case where naive extraction picks the
wrong number.

## Sources

| Source | Samples | Notes |
|---|---:|---|
| Developer's own device (Samsung SM-G965U1) | 118 | Own data, extracted over adb from the SMS inbox |
| Family and friends | 0 | **Not yet collected** — needs spoken consent |
| Shop counter phone | 0 | **Not yet collected** — needs spoken consent |

Everything so far is the developer's own inbox, so no third party's consent was
required to read it. The message *contents* do, however, name other people: 59
distinct personal names and 36 phone numbers appeared in the raw text. All were
tokenised before anything was written into this repository — see below.

## What is still missing

- **66 more financial samples** to reach the 150 target.
- **A fourth sender.** Awash, Dashen or Abyssinia, per `docs/04`. The inbox used here
  had no other bank.
- **Amharic-language transaction templates.** The corpus has plenty of Amharic *noise*,
  and one Amharic CBE security notice, but every transaction message in it is in
  English. This is the most significant gap: `docs/04` calls Amharic variants "the ones
  that break naive parsers", and the parser will currently be tuned without them.
- **Geez numerals.** None appeared. `GeezNumeralNormalizer` will be built without
  fixture coverage until a sample turns up.

Because the corpus is one person's account, it also skews toward small personal
transfers rather than shop takings. A 95% figure against it is a weaker claim than the
same figure against a corpus drawn from several real shops.

## Redaction method

Redaction happens **before** anything is written to disk inside the repository, never
after. `tools/redact-samples.ps1` applies the mechanical part; names are replaced by
hand because no pattern detects them reliably.

| Field | Treatment |
|---|---|
| Personal names | Consistent token: `Abebe K.` → `NAME_01` |
| Phone numbers | `+251912345678` → `+251900000001`, one stable token per real number |
| Account numbers | Same-length zeros |
| Transaction ids | Same length, same character class per position |
| Amounts | **Kept real** — not identifying, and the parser depends on real formatting |
| Dates and times | Format kept real; actual dates may be shifted |
| Balance after | Format kept, digits randomised |

Length, character class and separator placement are preserved throughout, because
those are precisely the properties the parser learns to read. A redaction that turns
`TXN8H2K9QL` into `XXXX` destroys the pattern.

Stable tokens matter: the same real phone number always redacts to the same fake one,
so "this customer appears twice" survives while identity does not.

Two categories were **excluded entirely rather than redacted**, because a redacted
credential is still a credential in the git history and neither is training data:

- 19 messages containing one-time codes, verification codes or PINs — including bank
  SMS that stated a newly reset mobile-banking PIN in cleartext;
- every OTP sender (TikTok, Instagram, Binance, National ID, verification services).

Personal conversations were never extracted: the inbox was filtered to financial and
promotional shortcodes before any body text was read, and the 15 messages from
personal phone numbers were discarded unread.

Unredacted originals are kept outside the repository and deleted once redaction is
verified. `.gitignore` blocks `**/corpus/raw/`, `**/*.unredacted.json` and `*.sms.txt`
from the first commit onward.

### Automated privacy guard

`CorpusValidationTest` fails the build on anything resembling an unredacted phone
number or account number, so a leak cannot reach a commit unnoticed. It also enforces
labelling discipline: unique ids, valid channel and direction values, required fields
on financial samples, `"expected": null` on noise, parseable timestamps.

## Train / holdout split

Roughly 70/30. Rules are built and tuned against `train/` only. `holdout/` is run
**once** at the end of Session 2 and once more at the end of the project — never tuned
against. A number you tuned against measures nothing.

## Accuracy

| Run | Date | Train + noise | Holdout |
|---|---|---:|---:|
| End of Session 2 | 2026-08-01 | 100.00% (95 samples) | 100.00% (23 samples) |
| End of Session 12 | 2026-08-03 | 100.00% (95 samples) | 100.00% (23 samples) |

The final run was made against the same rules that ship in the release APK, and nothing
was changed in response to it. Both figures are unchanged from Session 2 because the
corpus and the rules are unchanged: Sessions 3–12 built the app around the parser and
did not touch it. An unchanged number after ten sessions of downstream work is the
result to want, but it is not a second independent measurement, and quoting it as one
would be dishonest.

Zero false positives and zero wrong values on both runs; per-field precision and recall
100% across all seven extracted fields.

False positives are fatal and fail the build: a missed message lands in the review
queue where the user fixes it, but a wrong answer silently puts a wrong number in
someone's ledger.

### How much those figures are worth

Less than they look, and they should never be quoted without this paragraph.

The holdout holds out **samples, not templates**. Both splits come from the same three
senders and the same thirteen message templates, so every holdout message is a near-twin
of a training message carrying different amounts and names. That measures resistance to
overfitting on *values*. It does not measure generalisation to an unseen template or an
unseen provider, which is what a reader will assume a holdout figure means.

With thirteen templates, one person's account and no Amharic transaction messages at
all, 100% is the expected outcome for rules written by someone who has read every
template — not evidence of a strong parser. The figure becomes meaningful once the
corpus contains other shops, a fourth sender, and Amharic variants.

Reproduce:

```bash
./gradlew :parser:test --tests '*CorpusAccuracyTest*'
./gradlew :parser:test --tests '*CorpusHoldoutTest*' -Dmezgeb.holdout=true
```

The holdout run is opt-in by design: a test that runs on every build becomes a test
people tune against.
