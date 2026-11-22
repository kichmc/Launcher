package com.kich.launcher.data.lock

/**
 * How one AK workspace is unlocked. Each workspace has exactly one primary method; fingerprint is an
 * optional extra (see [BiometricUnlock]) and never replaces it. This is AK's own workspace lock, not
 * Android's device lock.
 */
enum class LockMethod(val id: String) {
    PIN("pin"),
    PASSWORD("password"),
    PATTERN("pattern");

    companion object {
        /** Unknown or missing ids (including files written before methods existed) mean [PIN]. */
        fun fromId(id: String?): LockMethod = entries.firstOrNull { it.id == id } ?: PIN
    }
}

/** Passwords: any printable characters, no leading/trailing-space surprises are trimmed (never altered). */
object PasswordPolicy {
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 64

    fun isValid(password: CharSequence): Boolean =
        password.length in MIN_LENGTH..MAX_LENGTH && password.any { !it.isWhitespace() }
}

/**
 * Patterns are a sequence of distinct dot indices 0..8 on a 3x3 grid (row-major). They are encoded as
 * a string of digits so the same PBKDF2 + Keystore pipeline as a PIN stores them; the string itself is
 * never persisted (only the salted hash is).
 */
object PatternPolicy {
    const val MIN_DOTS = 4
    const val MAX_DOTS = 9
    const val GRID = 3

    fun encode(dots: List<Int>): CharArray = CharArray(dots.size) { ('0' + dots[it]) }

    fun isValid(encoded: CharSequence): Boolean =
        encoded.length in MIN_DOTS..MAX_DOTS &&
            encoded.all { it in '0'..'8' } &&
            encoded.toSet().size == encoded.length

    fun isValidDots(dots: List<Int>): Boolean = dots.all { it in 0..8 } && isValid(String(encode(dots)))
}

/** One entry point so storage and UI always agree on what is acceptable for a method. */
object LockPolicy {
    fun isValid(method: LockMethod, secret: CharSequence): Boolean = when (method) {
        LockMethod.PIN -> PinPolicy.isValid(secret)
        LockMethod.PASSWORD -> PasswordPolicy.isValid(secret)
        LockMethod.PATTERN -> PatternPolicy.isValid(secret)
    }
}
