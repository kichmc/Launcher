package com.kich.launcher.data.lock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.io.File

/** Identifies one boot, so a lockout or unlock never outlives a restart. */
data class BootIdentity(val count: Int, val startedAtWall: Long) {
    fun sameBootAs(other: BootIdentity): Boolean =
        if (count >= 0 && other.count >= 0) count == other.count
        else kotlin.math.abs(startedAtWall - other.startedAtWall) < 120_000L
}

/** Time sources, injectable for tests. */
interface LockClock {
    fun elapsed(): Long
    fun boot(): BootIdentity
}

class AndroidLockClock(private val context: Context) : LockClock {
    override fun elapsed(): Long = SystemClock.elapsedRealtime()
    override fun boot(): BootIdentity = BootIdentity(
        count = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1),
        startedAtWall = System.currentTimeMillis() - SystemClock.elapsedRealtime(),
    )
}

/** One profile's lock configuration. Never contains a PIN. */
data class ProfileLockConfig(
    val enabled: Boolean = false,
    val credential: PinCredential? = null,
    val fingerprint: Boolean = false,
    /** Keystore-bound biometric verifier (IV + GCM ciphertext); see [BiometricUnlock]. */
    val bioIv: ByteArray? = null,
    val bioBlob: ByteArray? = null,
    val failedAttempts: Int = 0,
    val lockoutUntilElapsed: Long = 0L,
    val lockoutBoot: BootIdentity? = null,
    val lockoutWallRemainingAtWrite: Long = 0L,
)

sealed interface PinCheck {
    data object Success : PinCheck
    data class Wrong(val attemptsLeft: Int, val lockoutMs: Long) : PinCheck
    data class LockedOut(val remainingMs: Long) : PinCheck
    data object NotConfigured : PinCheck
    /** The secure element could not be used. Not counted as a wrong attempt. */
    data object Unavailable : PinCheck
}

/**
 * Per-profile lock configuration, one private file per AK profile id under files/profile_lock/.
 * Profiles never share a file, a salt or a counter. All access is serialised by one process-wide
 * lock; slow hashing therefore must be called off the main thread.
 */
class ProfileLockStore(
    context: Context,
    private val hasher: PinHasher = PinHasher(creationPepper = {
        KeystorePepper().let { if (it.isUsable()) it else NoPepper }
    }),
    private val clock: LockClock = AndroidLockClock(context.applicationContext),
    directory: File = File(context.applicationContext.filesDir, "profile_lock"),
) {
    private val dir = directory
    private val cache = HashMap<String, ProfileLockConfig>()

    private fun file(id: String): File {
        require(isValidId(id)) { "bad profile id" }
        return File(dir.apply { mkdirs() }, "$id.json")
    }

    @Synchronized
    fun config(id: String): ProfileLockConfig = read(id)

    /** Fails closed: a lock whose file is unreadable stays enabled (and unverifiable) rather than vanishing. */
    fun isEnabled(id: String): Boolean = config(id).enabled

    /** Creates (or replaces) the PIN and turns the lock on. Resets the attempt counter. */
    @Synchronized
    fun setPin(id: String, pin: CharArray): Boolean {
        if (!PinPolicy.isValid(String(pin))) return false
        val credential = runCatching { hasher.create(pin) }.getOrNull() ?: return false
        val old = read(id)
        return write(id, old.copy(enabled = true, credential = credential, failedAttempts = 0, lockoutUntilElapsed = 0L))
    }

    @Synchronized
    fun changePin(id: String, oldPin: CharArray, newPin: CharArray): PinCheck {
        val check = verifyPin(id, oldPin)
        if (check != PinCheck.Success) return check
        return if (setPin(id, newPin)) PinCheck.Success else PinCheck.Unavailable
    }

    /** Turns the lock off after the current PIN verifies. Wipes the credential and biometric verifier. */
    @Synchronized
    fun disable(id: String, pin: CharArray): PinCheck {
        val check = verifyPin(id, pin)
        if (check != PinCheck.Success) return check
        return if (write(id, ProfileLockConfig())) PinCheck.Success else PinCheck.Unavailable
    }

    @Synchronized
    fun setBiometric(id: String, iv: ByteArray?, blob: ByteArray?): Boolean {
        val cfg = read(id)
        if (!cfg.enabled) return false
        return write(id, cfg.copy(fingerprint = iv != null && blob != null, bioIv = iv, bioBlob = blob))
    }

    @Synchronized
    fun clearBiometric(id: String): Boolean = write(id, read(id).copy(fingerprint = false, bioIv = null, bioBlob = null))

    @Synchronized
    fun lockoutRemainingMs(id: String): Long = remaining(read(id))

    /**
     * Checks [pin]. The attempt is recorded BEFORE the slow comparison and cleared only on success,
     * so killing the process mid-check cannot buy a free guess.
     */
    @Synchronized
    fun verifyPin(id: String, pin: CharArray): PinCheck {
        val cfg = read(id)
        val credential = cfg.credential
        if (!cfg.enabled || credential == null) return PinCheck.NotConfigured
        val left = remaining(cfg)
        if (left > 0) return PinCheck.LockedOut(left)
        val charged = cfg.copy(failedAttempts = cfg.failedAttempts + 1)
        if (!write(id, charged)) return PinCheck.Unavailable
        val ok = try {
            hasher.verify(pin, credential)
        } catch (_: Exception) {
            write(id, cfg) // refund: a Keystore fault is not a wrong guess
            return PinCheck.Unavailable
        }
        if (ok) {
            write(id, cfg.copy(failedAttempts = 0, lockoutUntilElapsed = 0L, lockoutBoot = null))
            return PinCheck.Success
        }
        val ms = LockThrottle.lockoutMillis(charged.failedAttempts)
        if (ms > 0) {
            write(id, charged.copy(
                lockoutUntilElapsed = clock.elapsed() + ms,
                lockoutBoot = clock.boot(),
                lockoutWallRemainingAtWrite = ms,
            ))
        }
        return PinCheck.Wrong(LockThrottle.attemptsLeft(charged.failedAttempts), ms)
    }

    /** Called after a successful biometric: clears the counter as a PIN success would. */
    @Synchronized
    fun recordExternalSuccess(id: String) {
        val cfg = read(id)
        if (cfg.failedAttempts != 0 || cfg.lockoutUntilElapsed != 0L) {
            write(id, cfg.copy(failedAttempts = 0, lockoutUntilElapsed = 0L, lockoutBoot = null))
        }
    }

    @Synchronized
    fun delete(id: String) {
        runCatching { file(id).delete() }
        cache.remove(id)
    }

    // A restart ends the countdown measured on the old boot's clock; the stored length then applies in full
    // (never shorter than what was imposed), so rebooting does not shorten a lockout.
    private fun remaining(cfg: ProfileLockConfig): Long {
        if (cfg.lockoutUntilElapsed <= 0L) return 0L
        val boot = cfg.lockoutBoot ?: return 0L
        return if (boot.sameBootAs(clock.boot())) (cfg.lockoutUntilElapsed - clock.elapsed()).coerceAtLeast(0L)
        else cfg.lockoutWallRemainingAtWrite
    }

    private fun read(id: String): ProfileLockConfig {
        cache[id]?.let { return it }
        val f = file(id)
        val cfg = if (!f.exists()) ProfileLockConfig() else runCatching { parse(JSONObject(f.readText())) }
            // An unreadable file must not silently disable a lock that was on.
            .getOrElse { ProfileLockConfig(enabled = true, credential = null) }
        cache[id] = cfg
        return cfg
    }

    private fun write(id: String, cfg: ProfileLockConfig): Boolean = runCatching {
        val target = file(id)
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(toJson(cfg).toString())
        if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
        cache[id] = cfg
        true
    }.getOrDefault(false)

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun toJson(c: ProfileLockConfig) = JSONObject().apply {
        put("v", 1)
        put("enabled", c.enabled)
        put("fingerprint", c.fingerprint)
        c.credential?.let {
            put("scheme", it.scheme); put("iter", it.iterations)
            put("salt", b64(it.salt)); put("hash", b64(it.hash))
        }
        c.bioIv?.let { put("bioIv", b64(it)) }
        c.bioBlob?.let { put("bioBlob", b64(it)) }
        put("fails", c.failedAttempts)
        put("lockUntil", c.lockoutUntilElapsed)
        put("lockWallMs", c.lockoutWallRemainingAtWrite)
        c.lockoutBoot?.let { put("lockBootCount", it.count); put("lockBootAt", it.startedAtWall) }
    }

    private fun parse(j: JSONObject): ProfileLockConfig {
        val cred = if (j.has("hash")) PinCredential(j.getString("scheme"), j.getInt("iter"), unb64(j.getString("salt")), unb64(j.getString("hash"))) else null
        return ProfileLockConfig(
            enabled = j.optBoolean("enabled"),
            credential = cred,
            fingerprint = j.optBoolean("fingerprint"),
            bioIv = j.optString("bioIv").takeIf { it.isNotEmpty() }?.let(::unb64),
            bioBlob = j.optString("bioBlob").takeIf { it.isNotEmpty() }?.let(::unb64),
            failedAttempts = j.optInt("fails"),
            lockoutUntilElapsed = j.optLong("lockUntil"),
            lockoutWallRemainingAtWrite = j.optLong("lockWallMs"),
            lockoutBoot = if (j.has("lockBootAt")) BootIdentity(j.optInt("lockBootCount", -1), j.getLong("lockBootAt")) else null,
        )
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9_-]{1,40}")
        fun isValidId(id: String) = ID.matches(id)
    }
}
