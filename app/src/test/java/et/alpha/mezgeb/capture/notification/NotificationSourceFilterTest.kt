package et.alpha.mezgeb.capture.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSourceFilterTest {

    @Test
    fun `the default messaging app is read`() {
        val filter = NotificationSourceFilter(
            defaultSmsPackage = "com.samsung.android.messaging",
            watchedPackages = emptySet(),
        )

        assertTrue(filter.allows("com.samsung.android.messaging"))
    }

    @Test
    fun `everything else is ignored`() {
        val filter = NotificationSourceFilter(
            defaultSmsPackage = "com.samsung.android.messaging",
            watchedPackages = emptySet(),
        )

        assertFalse(filter.allows("com.whatsapp"))
        assertFalse(filter.allows("com.instagram.android"))
    }

    @Test
    fun `a configured banking app is read`() {
        val filter = NotificationSourceFilter(
            defaultSmsPackage = "com.samsung.android.messaging",
            watchedPackages = setOf("et.example.bank"),
        )

        assertTrue(filter.allows("et.example.bank"))
    }

    @Test
    fun `a second messaging app is not read when the default is known`() {
        // Only the default handler receives SMS. Letting a known-messaging-app name in
        // alongside a known default would read one message from two apps.
        val filter = NotificationSourceFilter(
            defaultSmsPackage = "com.samsung.android.messaging",
            watchedPackages = emptySet(),
        )

        assertFalse(filter.allows("com.google.android.apps.messaging"))
    }

    @Test
    fun `known messaging apps are the fallback when the system will not say`() {
        val filter = NotificationSourceFilter(defaultSmsPackage = null, watchedPackages = emptySet())

        assertTrue(filter.allows("com.google.android.apps.messaging"))
        assertFalse(filter.allows("com.whatsapp"))
    }
}
