package com.kich.launcher.data.lock

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Fingerprint unlock through the platform BiometricPrompt, bound to the Android Keystore.
 *
 * Each profile owns one AES key that can only be used right after a STRONG biometric
 * authentication (timeout 0 + CryptoObject) and is destroyed by the system if the enrolled
 * biometrics change. Enabling stores a small blob encrypted under that key; unlocking succeeds only
 * if the prompt authorises the key and the blob decrypts. A forged "success" callback therefore
 * cannot unlock anything. No fingerprint data is ever read or stored by AK.
 *
 * Never asks Android to create or change a device PIN: if the phone has no secure lock screen or no
 * enrolled biometric, [availability] says so and the option stays off.
 */
object BiometricUnlock {
    enum class Availability { Available, NoHardware, NotEnrolled, Unavailable }

    fun availability(context: Context): Availability {
        val bm = context.getSystemService(BiometricManager::class.java) ?: return Availability.NoHardware
        return when (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.Available
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Availability.NoHardware
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NotEnrolled
            else -> Availability.Unavailable
        }
    }

    sealed interface Result {
        class Success(val iv: ByteArray?, val blob: ByteArray?) : Result
        /** Cancelled or negative button: stay on the PIN. */
        data object Cancelled : Result
        /** Key gone (new fingerprint enrolled, device lock removed): fingerprint must be re-enabled. */
        data object KeyInvalidated : Result
        data class Failed(val message: String) : Result
    }

    private fun alias(profileId: String) = "ak_lock_bio_$profileId"

    private fun key(profileId: String): SecretKey? {
        val store = KeyStore.getInstance(KeystorePepper.ANDROID_KEYSTORE).apply { load(null) }
        return store.getKey(alias(profileId), null) as? SecretKey
    }

    private fun createKey(profileId: String): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KeystorePepper.ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias(profileId), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        return gen.generateKey()
    }

    fun deleteKey(profileId: String) {
        runCatching {
            KeyStore.getInstance(KeystorePepper.ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias(profileId))
        }
    }

    /** Enrolls [profileId]: prompts once, then returns the encrypted verifier to store. */
    fun enroll(context: Context, profileId: String, title: String, negative: String, onResult: (Result) -> Unit) {
        val cipher = try {
            deleteKey(profileId)
            Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, createKey(profileId)) }
        } catch (e: Exception) {
            onResult(Result.Failed(e.javaClass.simpleName)); return
        }
        prompt(context, cipher, title, negative) { r ->
            if (r !is Result.Success) { deleteKey(profileId); onResult(r); return@prompt }
            onResult(runCatching { Result.Success(cipher.iv, cipher.doFinal(VERIFIER)) }.getOrElse {
                deleteKey(profileId); Result.Failed("encrypt")
            })
        }
    }

    /** Unlock: succeeds only if the Keystore releases the key and [blob] decrypts to the verifier. */
    fun authenticate(context: Context, profileId: String, iv: ByteArray, blob: ByteArray, title: String, negative: String, onResult: (Result) -> Unit) {
        val cipher = try {
            val k = key(profileId) ?: run { onResult(Result.KeyInvalidated); return }
            Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv)) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            deleteKey(profileId); onResult(Result.KeyInvalidated); return
        } catch (e: Exception) {
            onResult(Result.Failed(e.javaClass.simpleName)); return
        }
        prompt(context, cipher, title, negative) { r ->
            if (r !is Result.Success) { onResult(r); return@prompt }
            val ok = runCatching { cipher.doFinal(blob).contentEquals(VERIFIER) }.getOrDefault(false)
            onResult(if (ok) Result.Success(null, null) else Result.Failed("verify"))
        }
    }

    private fun prompt(context: Context, cipher: Cipher, title: String, negative: String, done: (Result) -> Unit) {
        val executor = context.mainExecutor
        val signal = CancellationSignal()
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton(negative, executor) { _, _ -> done(Result.Cancelled) }
            .build()
        prompt.authenticate(BiometricPrompt.CryptoObject(cipher), signal, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                done(Result.Success(null, null))
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                done(if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED)
                    Result.Cancelled else Result.Failed(errString?.toString() ?: "error $errorCode"))
            }
        })
    }

    private const val TRANSFORM = "AES/GCM/NoPadding"
    private val VERIFIER = "ak-lock-verifier-v1".toByteArray()
}
