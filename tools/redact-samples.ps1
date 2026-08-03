<#
.SYNOPSIS
    Redacts raw payment messages before they are allowed anywhere near the repository.

.DESCRIPTION
    Reads a plain-text file of raw messages (one per line, blank lines ignored) and
    applies the redaction rules from docs/04-SMS-SAMPLE-COLLECTION.md:

      phone numbers    -> +251900000NNN, one stable token per distinct real number
      account numbers  -> same-length zeros
      transaction ids  -> same-length, same-character-class synthetic ids
      amounts          -> KEPT REAL. The parser depends on realistic formatting.
      dates and times  -> KEPT REAL in format.

    Length, character class and separator placement are preserved, because those are
    exactly the properties the parser is being trained to read.

    Personal NAMES cannot be detected reliably by pattern. The script flags likely
    candidates and you replace them by hand with NAME_01, NAME_02, ... Read every
    line of the output before you save it.

.PARAMETER InputPath
    Text file of raw messages. Keep this file OUTSIDE the repository and delete it
    once redaction has been verified.

.PARAMETER OutputPath
    Where to write the redacted messages. Also outside the repository — you then paste
    the bodies into the corpus JSON.

.EXAMPLE
    .\tools\redact-samples.ps1 -InputPath C:\raw\telebirr.txt -OutputPath C:\raw\telebirr.redacted.txt
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$InputPath,
    [Parameter(Mandatory = $true)][string]$OutputPath
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $InputPath)) { throw "Input file not found: $InputPath" }

$lines = Get-Content -Path $InputPath -Encoding UTF8 | Where-Object { $_.Trim() -ne '' }
Write-Host "Read $($lines.Count) message(s) from $InputPath`n"

# Stable token maps, so the same real value always redacts to the same fake value.
# That preserves the "same customer appears twice" signal without preserving identity.
$phoneMap = @{}
$accountMap = @{}
$txnMap = @{}
$nameCandidates = [System.Collections.Generic.HashSet[string]]::new()

function Get-PhoneToken([string]$real) {
    if (-not $phoneMap.ContainsKey($real)) {
        $phoneMap[$real] = '+251900000{0:d3}' -f ($phoneMap.Count + 1)
    }
    return $phoneMap[$real]
}

function Get-AccountToken([string]$real) {
    # Same length, all zeros — preserves the shape the parser must skip over.
    if (-not $accountMap.ContainsKey($real)) { $accountMap[$real] = '0' * $real.Length }
    return $accountMap[$real]
}

function Get-TxnToken([string]$real) {
    # Same length, same character class per position.
    if (-not $txnMap.ContainsKey($real)) {
        $rng = [System.Random]::new($real.GetHashCode())
        $sb = [System.Text.StringBuilder]::new()
        foreach ($ch in $real.ToCharArray()) {
            if ([char]::IsDigit($ch))          { [void]$sb.Append($rng.Next(0, 10)) }
            elseif ([char]::IsUpper($ch))      { [void]$sb.Append([char](65 + $rng.Next(0, 26))) }
            elseif ([char]::IsLower($ch))      { [void]$sb.Append([char](97 + $rng.Next(0, 26))) }
            else                               { [void]$sb.Append($ch) }
        }
        $txnMap[$real] = $sb.ToString()
    }
    return $txnMap[$real]
}

$out = New-Object System.Collections.Generic.List[string]

foreach ($line in $lines) {
    $redacted = $line

    # 1. Phones. International first, then local trunk form, so the longer pattern wins.
    $redacted = [regex]::Replace($redacted, '\+?251[79]\d{8}', { param($m) Get-PhoneToken $m.Value })
    $redacted = [regex]::Replace($redacted, '(?<!\d)0[79]\d{8}(?!\d)', { param($m) Get-PhoneToken $m.Value })

    # 2. Account numbers: long digit runs that are not part of an amount.
    #
    #    The lookarounds must exclude a separator only when it is *inside* a number.
    #    An earlier version used (?<![\d,.]) ... (?![\d,.]), which also exempted any
    #    run merely followed by a comma or a full stop — so "Id: 000000000001," and
    #    any run ending a sentence passed through unredacted. Fifteen real-shaped
    #    transaction references reached the committed corpus that way.
    #
    #    Now: not touching a digit on either side, and not sitting next to a separator
    #    that has a digit on its far side (1,234 / 1234.56), which is what "part of an
    #    amount" actually means.
    $redacted = [regex]::Replace($redacted, '(?<!\d)(?<![\d][.,])\d{10,}(?!\d)(?![.,]\d)', { param($m) Get-AccountToken $m.Value })

    # 3. Transaction references: 6+ char alphanumeric tokens containing both a letter
    #    and a digit. Pure words and pure numbers are left alone.
    $redacted = [regex]::Replace($redacted, '\b(?=[A-Za-z0-9]{6,}\b)(?=[A-Za-z0-9]*\d)(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]+\b', { param($m) Get-TxnToken $m.Value })

    # 4. Name candidates — flagged only, never auto-replaced.
    foreach ($m in [regex]::Matches($redacted, '\b[A-Z][a-z]{2,}(?:\s+[A-Z][a-z]?\.?)?(?:\s+[A-Z][a-z]{2,})?\b')) {
        [void]$nameCandidates.Add($m.Value)
    }

    $out.Add($redacted)
}

Set-Content -Path $OutputPath -Value $out -Encoding UTF8

Write-Host "Redactions applied" -ForegroundColor Green
Write-Host ("  phone numbers  : {0} distinct -> +251900000NNN" -f $phoneMap.Count)
Write-Host ("  account numbers: {0} distinct -> same-length zeros" -f $accountMap.Count)
Write-Host ("  transaction ids: {0} distinct -> synthetic, same shape" -f $txnMap.Count)
Write-Host "`nWritten to $OutputPath`n"

if ($nameCandidates.Count -gt 0) {
    Write-Host "MANUAL STEP — capitalised words that may be personal names." -ForegroundColor Yellow
    Write-Host "Replace real people with NAME_01, NAME_02, ... Bank and product names stay.`n"
    $nameCandidates | Sort-Object | ForEach-Object { Write-Host "  $_" }
}

Write-Host "`nBefore this text goes near the repo:" -ForegroundColor Cyan
Write-Host "  1. Read every redacted line yourself. Pattern matching is a first pass, not a guarantee."
Write-Host "  2. Replace personal names by hand."
Write-Host "  3. Paste bodies into parser/src/test/resources/corpus/<split>/<sender>.json."
Write-Host "  4. Run: .\gradlew :parser:test --tests '*CorpusValidationTest*'"
Write-Host "  5. Delete the unredacted original from disk."
