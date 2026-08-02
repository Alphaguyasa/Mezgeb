package et.alpha.mezgeb.capture.sms

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `play` flavor cannot read the inbox, so it says so and does nothing.
 *
 * A no-op implementation rather than a nullable binding: shared UI asks
 * [isSupported] and hides the offer, and there is no code path where a caller can
 * accidentally start an import that this build has no permission to perform.
 */
@Singleton
class PlaySmsImporter @Inject constructor() : SmsImporter {

    override val isSupported: Boolean = false

    override fun requiredPermissions(): List<String> = emptyList()

    override fun start() = Unit

    override fun observeStatus(): Flow<SmsImportStatus> = flowOf(SmsImportStatus.Unavailable)
}
