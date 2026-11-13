package com.kich.launcher.data.lock

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * A stored PIN verifier. Never the PIN itself: [hash] is PBKDF2-HMAC-SHA256 over the PIN with a
 * random per-credential [salt], then (scheme [SCHEME_KEYSTORE]) passed through an HMAC whose key
 * lives in the Android Keystore and cannot be exported, so a copy of the file alone cannot be
 * brute-forced off the device.
 */
class PinCredential(
    val scheme: String,
    val iterations: Int,
    val salt: ByteArray,
    val hash: ByteArray,
)

/** The non-exportable second factor mixed into a PIN hash. */
interface Pepper {
    /** Identifies the pepper in [PinCredential.scheme]. */
    val scheme: String

    /** Deterministic for a given [input]; throws when the secure element is unavailable. */
    fun apply(input: ByteArray): ByteArray
}

/** No pepper: only for JVM tests, or when the Keystore cannot create a key at all. */
object NoPepper : Pepper {
    override val scheme: String = PinHasher.SCHEME_PLAIN
    override fun apply(input: ByteArray): ByteArray = input
}

/** HMAC-SHA256 with a Keystore-resident key (hardware-backed where the device has it). */
class KeystorePepper(private val alias: String = DEFAULT_ALIAS) : Pepper {
    override val scheme: String = PinHasher.SCHEME_KEYSTORE

    override fun apply(input: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key())
        return mac.doFinal(input)
    }

    @Synchronized
    private fun key(): java.security.Key {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null))?.let { return it }
        val generator = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build())
        return generator.generateKey()
    }

    /** True when a key can be obtained now; used once at PIN creation to pick the scheme. */
    fun isUsable(): Boolean = runCatching { apply(ByteArray(1)); true }.getOrDefault(false)

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "ak_lock_pin_pepper_v1"
    }
}

/**
 * Creates and checks [PinCredential]s. Holds no state. [peppers] maps a stored scheme to the
 * pepper that verifies it, so a credential is always checked with the scheme it was created with.
 */
class PinHasher(
    private val creationPepper: () -> Pepper,
    private val peppers: (String) -> Pepper? = { scheme ->
        when (scheme) {
            SCHEME_KEYSTORE -> KeystorePepper()
            SCHEME_PLAIN -> NoPepper
            else -> null
        }
    },
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) {
    fun create(pin: CharArray): PinCredential {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val pepper = creationPepper()
        val hash = pepper.apply(pbkdf2(pin, salt, iterations))
        return PinCredential(pepper.scheme, iterations, salt, hash)
    }

    /** @return true when [pin] matches; throws when the pepper cannot be used (not "wrong PIN"). */
    fun verify(pin: CharArray, credential: PinCredential): Boolean {
        val pepper = peppers(credential.scheme) ?: throw IllegalStateException("unknown PIN scheme")
        val candidate = pepper.apply(pbkdf2(pin, credential.salt, credential.iterations))
        // Constant-time comparison: no early exit that leaks how many bytes matched.
        return MessageDigest.isEqual(candidate, credential.hash)
    }

    private fun pbkdf2(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val SCHEME_PLAIN = "pbkdf2-sha256"
        const val SCHEME_KEYSTORE = "pbkdf2-sha256+keystore-hmac"
        const val DEFAULT_ITERATIONS = 200_000
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
    }
}

/** What a PIN must look like. Digits only, so the lock screen can offer a numeric keypad. */
object PinPolicy {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8

    fun isValid(pin: CharSequence): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }
}
