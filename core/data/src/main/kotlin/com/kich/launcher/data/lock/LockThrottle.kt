package com.kich.launcher.data.lock

/** How long a profile refuses PIN attempts after [failures] consecutive wrong PINs. */
object LockThrottle {
    const val FREE_ATTEMPTS = 4

    fun lockoutMillis(failures: Int): Long = when {
        failures <= FREE_ATTEMPTS -> 0L
        failures <= 9 -> 30_000L
        failures <= 14 -> 5 * 60_000L
        else -> 30 * 60_000L
    }

    /** Wrong attempts left before the next lockout starts (0 once locked out). */
    fun attemptsLeft(failures: Int): Int = (FREE_ATTEMPTS + 1 - failures).coerceAtLeast(0)
}
