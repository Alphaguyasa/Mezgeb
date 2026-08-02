package et.alpha.mezgeb.parser.privacy

/**
 * Recognises a message that states a secret, so the app can refuse to keep it.
 *
 * Banks and wallets send PINs, one-time codes and reset passwords over SMS in
 * cleartext. Those messages come from the same senders as payment messages and sail
 * straight through the allowlist, so without this the ledger quietly accumulates live
 * banking credentials — visible in the review queue, and carried into any backup or
 * export.
 *
 * This is the app-side counterpart of a rule the corpus collection already followed:
 * messages carrying codes or PINs were **dropped rather than redacted**, because a
 * redacted credential is still a credential. The same reasoning applies to a shop's
 * phone, and it was not applied there until a real device produced a real TPIN.
 *
 * ### Precision matters more than recall here
 *
 * Two failure modes, both bad, and they pull in opposite directions:
 *
 * - miss one, and a credential sits at rest in the ledger;
 * - fire on a genuine payment, and that payment silently never appears — which is the
 *   one thing this whole app exists to prevent.
 *
 * So detection requires a credential word **followed by an actual value**. Payment
 * messages routinely carry footers like "never share your PIN with anyone", and those
 * must not cost the shop a transaction. `CredentialDetectorTest` pins both directions.
 */
object CredentialDetector {

    /** True when the message states a secret value, not merely mentions one. */
    fun containsCredential(body: String): Boolean = PATTERNS.any { it.containsMatchIn(body) }

    private val CREDENTIAL_WORD =
        """(?:t-?pin|pin|otp|passcode|password|one[\s-]?time\s+(?:pin|password|code)|""" +
            """verification\s+code|activation\s+code|access\s+code|secret\s+code|""" +
            """የይለፍ\s*ቃል|ሚስጥራዊ\s*ቁጥር)"""

    private val PATTERNS = listOf(
        // "New TPIN is 4768", "Your OTP: 123456", "password = abc123"
        Regex(
            """$CREDENTIAL_WORD[^.;\n]{0,24}?(?:is|:|=|are)\s*["']?([A-Za-z0-9]{4,10})\b""",
            RegexOption.IGNORE_CASE,
        ),
        // "4768 is your OTP", "123456 is your verification code"
        Regex(
            """\b([A-Za-z0-9]{4,10})\s+is\s+(?:your|the)\s+[^.;\n]{0,16}?$CREDENTIAL_WORD""",
            RegexOption.IGNORE_CASE,
        ),
        // "Use code 483920 to confirm"
        Regex(
            """\buse\s+(?:the\s+)?code\s+([A-Za-z0-9]{4,10})\b""",
            RegexOption.IGNORE_CASE,
        ),
        // Amharic puts the copula after the value — "የይለፍ ቃልዎ 4768 ነው" — so the
        // English "word then is then value" shape never matches. The trailing ነው is
        // required rather than optional, to keep the same precision: a bare word
        // followed by digits would start catching reference numbers.
        Regex(
            """(?:የይለፍ\s*ቃል|ሚስጥራዊ\s*ቁጥር|ኮድ)\S{0,3}\s*([A-Za-z0-9]{4,10})\s*(?:ነው|ነዉ)""",
        ),
    )
}
