package et.alpha.mezgeb.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * The file format on its own, without a database. The header is plaintext but, from
 * format 2, authenticated: editing it must fail exactly like editing the ciphertext.
 */
class BackupCryptoTest {

    private val passphrase = "correct horse battery staple".toCharArray()
    private val json = """{"transactions":[]}"""

    /** Offsets into the header, as drawn in the [BackupCrypto] diagram. */
    private val formatOffset = 4
    private val schemaOffset = 8

    private fun file(formatVersion: Int = BackupCrypto.FORMAT_VERSION, schemaVersion: Int = 6): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.write(json, passphrase, schemaVersion, out, SecureRandom(), formatVersion)
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray, maxSchemaVersion: Int = 6) =
        BackupCrypto.read(ByteArrayInputStream(bytes), passphrase, maxSchemaVersion)

    @Test
    fun `a new file is written in format 2 and reads back`() {
        val bytes = file()

        assertEquals(2, bytes[formatOffset + 3].toInt())
        assertEquals(RestoreResult.Success(json, schemaVersion = 6), read(bytes))
    }

    @Test
    fun `a format 2 file with one schema byte changed fails as corrupt`() {
        // 6 becomes 5: still a schema this build accepts, so only the tag can catch it.
        val bytes = file().also { it[schemaOffset + 3] = 5 }

        assertEquals(RestoreResult.Failed(RestoreFailure.WRONG_PASSPHRASE_OR_CORRUPT), read(bytes))
    }

    @Test
    fun `a format 2 file relabelled as format 1 fails as corrupt`() {
        // Format 1 skips the associated data. A file must not be able to pick that path
        // by claiming to be older than it is.
        val bytes = file().also { it[formatOffset + 3] = 1 }

        assertEquals(RestoreResult.Failed(RestoreFailure.WRONG_PASSPHRASE_OR_CORRUPT), read(bytes))
    }

    @Test
    fun `a format 1 file still reads`() {
        val bytes = file(formatVersion = 1, schemaVersion = 5)

        assertEquals(1, bytes[formatOffset + 3].toInt())
        assertEquals(RestoreResult.Success(json, schemaVersion = 5), read(bytes))
    }

    @Test
    fun `a format from a newer app is refused before decrypting`() {
        val bytes = file().also { it[formatOffset + 3] = (BackupCrypto.FORMAT_VERSION + 1).toByte() }

        assertEquals(RestoreResult.Failed(RestoreFailure.UNSUPPORTED_FORMAT), read(bytes))
    }

    @Test
    fun `the wrong passphrase still fails as before`() {
        val result = BackupCrypto.read(ByteArrayInputStream(file()), "wrong".toCharArray(), 6)

        assertTrue(result is RestoreResult.Failed)
        assertEquals(RestoreFailure.WRONG_PASSPHRASE_OR_CORRUPT, (result as RestoreResult.Failed).reason)
    }
}
