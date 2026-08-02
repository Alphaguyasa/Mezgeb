package et.alpha.mezgeb.util

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Primary keys are generated, not derived, and they are injected rather than called
 * statically so a capture test can assert on the id it expects instead of on
 * "something that looks like a UUID".
 */
fun interface IdGenerator {
    fun newId(): String
}

@Singleton
class UuidGenerator @Inject constructor() : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}
