package et.alpha.mezgeb.domain.backup

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Why a backup could not be read. Every one of these is a message to a user. */
enum class RestoreFailure {
    /** Not a Mezgeb backup at all. */
    NOT_A_BACKUP,

    /** A backup from a newer app than this one. */
    UNSUPPORTED_FORMAT,

    /** A backup of a newer database schema than this build understands. */
    UNSUPPORTED_SCHEMA,

    /** The passphrase is wrong, or the file has been altered since it was written. */
    WRONG_PASSPHRASE_OR_CORRUPT,

    /** Truncated, unreadable, or otherwise not a whole file. */
    MALFORMED,
}

sealed interface RestoreResult {
    data class Success(val json: String, val schemaVersion: Int) : RestoreResult
    data class Failed(val reason: RestoreFailure) : RestoreResult
}

/**
 * The backup file format, and the encryption that makes it safe to keep anywhere.
 *
 * ```
 * ┌──────────┬─────────┬────────┬──────┬─────┬────────────┐
 * │ "MZGB"   │ format  │ schema │ salt │ IV  │ ciphertext │
 * │ 4 bytes  │ 4 bytes │ 4      │ 16   │ 12  │ …          │
 * └──────────┴─────────┴────────┴──────┴─────┴────────────┘
 * ```
 *
 * The header is deliberately plaintext. Restore has to be able to say *why* it
 * refused — wrong file, too new, wrong passphrase — and it cannot tell a wrong
 * passphrase from a wrong file if it has to decrypt before it can look. Nothing in
 * the header is a secret: the salt and IV are public inputs by design, and the schema
 * version says nothing a shop would mind.
 *
 * AES-256-GCM, so the file is authenticated as well as encrypted: a single flipped
 * byte fails the tag rather than silently restoring a ledger with one wrong number in
 * it. GCM's tag is also what makes "wrong passphrase" detectable at all.
 *
 * Plaintext is not the same as unauthenticated. From format 2 the whole header is
 * passed to GCM as associated data, so the tag covers it too. In format 1 it was not:
 * the schema byte could be edited and the file still decrypted, and restore would then
 * read the payload as if a different schema had written it. Format 1 files are still
 * read, without the associated data, because a shop's only backup may be one.
 * Relabelling a format 2 file as format 1 does not get around this: its tag was
 * computed over the header, so decrypting it without the header fails.
 */
object BackupCrypto {

    /** `MZGB`. A file that does not start with this is not ours to explain. */
    val MAGIC = byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 'G'.code.toByte(), 'B'.code.toByte())

    const val FORMAT_VERSION = 2

    /** The first format whose header is authenticated as GCM associated data. */
    const val FIRST_AUTHENTICATED_FORMAT = 2

    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val KEY_BITS = 256
    const val TAG_BITS = 128

    /** Magic, format, schema, salt, IV: everything before the ciphertext. */
    private const val HEADER_BYTES = 4 + 4 + 4 + SALT_BYTES + IV_BYTES

    /**
     * High enough to make a weak passphrase expensive to attack, low enough that a
     * cheap phone does not appear to hang. Reconsider upward on every device refresh.
     */
    const val PBKDF2_ITERATIONS = 210_000

    private const val KEY_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER = "AES/GCM/NoPadding"

    fun write(
        json: String,
        passphrase: CharArray,
        schemaVersion: Int,
        output: OutputStream,
        random: SecureRandom = SecureRandom(),
    ) = write(json, passphrase, schemaVersion, output, random, FORMAT_VERSION)

    /**
     * Writes a file in a given format. Only tests call this with anything but the
     * current format, to prove that files written by older versions still restore.
     */
    internal fun write(
        json: String,
        passphrase: CharArray,
        schemaVersion: Int,
        output: OutputStream,
        random: SecureRandom,
        formatVersion: Int,
    ) {
        require(formatVersion in 1..FORMAT_VERSION) { "Unknown backup format $formatVersion" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt)
        val header = MAGIC + intBytes(formatVersion) + intBytes(schemaVersion) + salt + iv

        val cipher = Cipher.getInstance(CIPHER).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            if (formatVersion >= FIRST_AUTHENTICATED_FORMAT) updateAAD(header)
        }
        val ciphertext = cipher.doFinal(json.toByteArray(Charsets.UTF_8))

        output.write(header)
        output.write(ciphertext)
        output.flush()
    }

    /**
     * Reads a backup, checking the header before it touches the passphrase.
     *
     * @param maxSchemaVersion the schema this build understands. A backup from a
     *   newer version is refused rather than partially applied, because restoring
     *   half a schema into a shop's ledger is worse than restoring none of it.
     */
    fun read(input: InputStream, passphrase: CharArray, maxSchemaVersion: Int): RestoreResult {
        val bytes = runCatching { input.readBytes() }.getOrNull()
            ?: return RestoreResult.Failed(RestoreFailure.MALFORMED)

        // The magic is checked before the length, so a photograph picked by mistake is
        // told "that is not a backup" rather than "that backup is damaged" — the second
        // sends someone hunting for a corrupted file that never existed.
        if (bytes.size < MAGIC.size || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            return RestoreResult.Failed(RestoreFailure.NOT_A_BACKUP)
        }

        if (bytes.size <= HEADER_BYTES) return RestoreResult.Failed(RestoreFailure.MALFORMED)

        var offset = MAGIC.size
        val formatVersion = readInt(bytes, offset).also { offset += 4 }
        if (formatVersion > FORMAT_VERSION) {
            return RestoreResult.Failed(RestoreFailure.UNSUPPORTED_FORMAT)
        }

        val schemaVersion = readInt(bytes, offset).also { offset += 4 }
        if (schemaVersion > maxSchemaVersion) {
            return RestoreResult.Failed(RestoreFailure.UNSUPPORTED_SCHEMA)
        }

        val salt = bytes.copyOfRange(offset, offset + SALT_BYTES).also { offset += SALT_BYTES }
        val iv = bytes.copyOfRange(offset, offset + IV_BYTES).also { offset += IV_BYTES }
        val header = bytes.copyOfRange(0, offset)
        val ciphertext = bytes.copyOfRange(offset, bytes.size)

        // A wrong passphrase and a tampered file both fail the GCM tag, and they are
        // reported the same way — telling them apart would tell an attacker which of
        // the two they had achieved.
        val plaintext = runCatching {
            Cipher.getInstance(CIPHER).apply {
                init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
                // The format number read here is itself covered by the tag, so a file
                // cannot choose the unauthenticated path by lying about its format.
                if (formatVersion >= FIRST_AUTHENTICATED_FORMAT) updateAAD(header)
            }.doFinal(ciphertext)
        }.getOrElse {
            return RestoreResult.Failed(RestoreFailure.WRONG_PASSPHRASE_OR_CORRUPT)
        }

        return RestoreResult.Success(
            json = String(plaintext, Charsets.UTF_8),
            schemaVersion = schemaVersion,
        )
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        val derived = SecretKeyFactory.getInstance(KEY_ALGORITHM).generateSecret(spec)
        // PBEKeySpec holds a copy of the passphrase; clearing it is cheap and means one
        // fewer place the secret lingers in memory.
        spec.clearPassword()
        return SecretKeySpec(derived.encoded, "AES")
    }

    /** Big-endian, so a hex dump of the header reads the way the diagram does. */
    private fun intBytes(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF shl 24) or
            (bytes[offset + 1].toInt() and 0xFF shl 16) or
            (bytes[offset + 2].toInt() and 0xFF shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
