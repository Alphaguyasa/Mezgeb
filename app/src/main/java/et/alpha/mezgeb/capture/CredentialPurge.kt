package et.alpha.mezgeb.capture

import et.alpha.mezgeb.data.local.dao.RawMessageDao
import et.alpha.mezgeb.parser.privacy.CredentialDetector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deletes credential-bearing messages that an earlier build stored.
 *
 * [CaptureCoordinator] now refuses them on the way in, but that does nothing for the
 * phones that already have them: a PIN sitting in a ledger does not become harmless
 * because the next version would not have kept it. This runs on every launch and is
 * idempotent, so it cleans up on update and costs one scan afterwards.
 *
 * **Transactions are left alone.** The foreign key is `SET NULL`, so a payment that
 * happened to be parsed out of such a message keeps its row and loses its link to the
 * text. That is the right trade: the money record is what the shop needs, and the
 * message text is the part that must not be kept.
 */
@Singleton
class CredentialPurge @Inject constructor(
    private val rawMessages: RawMessageDao,
) {

    /** @return how many rows were removed. */
    suspend fun run(): Int {
        var removed = 0
        var offset = 0

        while (true) {
            val page = rawMessages.getPage(limit = PAGE_SIZE, offset = offset)
            if (page.isEmpty()) break

            val offenders = page.filter { CredentialDetector.containsCredential(it.body) }
            offenders.forEach { rawMessages.deleteById(it.id) }
            removed += offenders.size

            // Deleting shifts everything after it, so only advance past what was kept.
            offset += page.size - offenders.size
            if (page.size < PAGE_SIZE) break
        }

        return removed
    }

    private companion object {
        const val PAGE_SIZE = 200
    }
}
