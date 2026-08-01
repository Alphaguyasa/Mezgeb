# Corpus

How the message corpus behind Mezgeb's parser was collected, redacted and split.

> **Status: not yet collected.** This file is a template. Fill the tables in as
> collection proceeds; the accuracy figures land at the end of Session 2 and again in
> Session 12.

## What it is

The parser is built from real payment messages, not imagined ones. Message templates
vary by provider, by language, and by whether a field happens to be present, and no
amount of guessing reproduces that. The corpus is the ground truth the parser is
measured against.

## Sample counts

| Sender | Channel | Train | Holdout | Total |
|---|---|---:|---:|---:|
| telebirr | TELEBIRR | — | — | — |
| CBE / CBE Birr | CBE_BIRR | — | — | — |
| _(other banks)_ | BANK | — | — | — |
| **Noise** (non-financial) | — | — | — | — |
| **Total** | | | | |

Targets from `docs/04-SMS-SAMPLE-COLLECTION.md`: at least 150 financial samples across
at least 4 senders, plus at least 30 noise samples, including Amharic-language
variants. Run `./gradlew :parser:test --tests '*CorpusValidationTest*'` for a live
inventory against those targets.

## Sources

Collected with spoken consent, with the purpose explained before anything was taken.

| Source | Samples | Notes |
|---|---:|---|
| _(own device)_ | — | |
| _(family and friends)_ | — | consent obtained verbally |
| _(shop counter phone)_ | — | consent obtained verbally |

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

| Run | Date | Train | Holdout |
|---|---|---:|---:|
| End of Session 2 | — | — | — |
| End of Session 12 | — | — | — |

False positives are fatal and fail the build: a missed message lands in the review
queue where the user fixes it, but a wrong answer silently puts a wrong number in
someone's ledger.
