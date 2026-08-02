package et.alpha.mezgeb.capture

import et.alpha.mezgeb.data.local.dao.RawMessageDao
import et.alpha.mezgeb.parser.privacy.CredentialDetector
import et.alpha.mezgeb.parser.rule.SenderAllowlist
import javax.inject.Inject
import javax.inject.Singleton

/** What a purge pass removed, so it can be reported rather than guessed at. */
data class PurgeSummary(
    val credentials: Int = 0,
    val nonFinancial: Int = 0,
) {
    val total: Int get() = credentials + nonFinancial
}

/**
 * Deletes messages that earlier builds stored and this one would refuse.
 *
 * [CaptureCoordinator] now declines both kinds on the way in, which does nothing for
 * the phones that already have them. A PIN in a ledger, or a friend's message, does
 * not become harmless because the next version would not have kept it. This runs on
 * every launch and is idempotent, so it cleans up on update and costs one scan after.
 *
 * Two rules, both found by putting the app on a real phone:
 *
 *  1. **Credentials.** Banks send PINs and one-time codes in cleartext from the same
 *     senders as payments, so they sailed through the allowlist.
 *  2. **Messages from people.** The notification listener sees everything the phone's
 *     messaging app posts. Those were stored and labelled `IGNORED`, which kept the
 *     text — while the onboarding screen promised they were ignored.
 *
 * **Transactions are left alone.** The foreign key is `SET NULL`, so a payment keeps
 * its row and loses its link to the text. The money record is what the shop needs; the
 * message is the part that must not be kept.
 */
@Singleton
class PrivacyPurge @Inject constructor(
    private val rawMessages: RawMessageDao,
) {

    private val allowlist = SenderAllowlist.DEFAULT

    suspend fun run(): PurgeSummary {
        var credentials = 0
        var nonFinancial = 0
        var offset = 0

        while (true) {
            val page = rawMessages.getPage(limit = PAGE_SIZE, offset = offset)
            if (page.isEmpty()) break

            var removedInPage = 0
            page.forEach { message ->
                val isCredential = CredentialDetector.containsCredential(message.body)
                val isNotFinancial = !allowlist.allows(message.sender)

                if (isCredential || isNotFinancial) {
                    rawMessages.deleteById(message.id)
                    removedInPage++
                    // A credential from a personal sender counts once, as a credential:
                    // it is the more serious of the two reasons it had to go.
                    if (isCredential) credentials++ else nonFinancial++
                }
            }

            // Deleting shifts everything after it, so only advance past what was kept.
            offset += page.size - removedInPage
            if (page.size < PAGE_SIZE) break
        }

        return PurgeSummary(credentials = credentials, nonFinancial = nonFinancial)
    }

    private companion object {
        const val PAGE_SIZE = 200
    }
}
