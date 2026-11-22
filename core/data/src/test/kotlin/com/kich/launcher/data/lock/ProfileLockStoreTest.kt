package com.kich.launcher.data.lock

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

private class FakeClock(var elapsed: Long = 1_000L, var boot: BootIdentity = BootIdentity(1, 0L)) : LockClock {
    override fun elapsed() = elapsed
    override fun boot() = boot
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProfileLockStoreTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val clock = FakeClock()
    private val dir = File(context.filesDir, "lock_test_${System.nanoTime()}")
    // Fast, pepper-less hasher: the Keystore does not exist on the JVM.
    private val store = ProfileLockStore(context, PinHasher({ NoPepper }, { NoPepper }, iterations = 1_000), clock, dir)

    @Test fun pinsAreIndependentPerProfile() {
        store.setPin("user1", "1111".toCharArray()); store.setPin("ak_b", "2222".toCharArray())
        assertThat(store.verifyPin("user1", "1111".toCharArray())).isEqualTo(PinCheck.Success)
        assertThat(store.verifyPin("user1", "2222".toCharArray())).isInstanceOf(PinCheck.Wrong::class.java)
        assertThat(store.verifyPin("ak_b", "2222".toCharArray())).isEqualTo(PinCheck.Success)
    }

    @Test fun pinIsNeverWrittenToDisk() {
        store.setPin("user1", "135790".toCharArray())
        assertThat(dir.listFiles()!!.all { !it.readText().contains("135790") }).isTrue()
    }

    @Test fun profileWithoutLockIsNotConfigured() {
        assertThat(store.isEnabled("ak_new")).isFalse()
        assertThat(store.verifyPin("ak_new", "1234".toCharArray())).isEqualTo(PinCheck.NotConfigured)
    }

    @Test fun lockoutStartsAtFifthWrongPinAndBlocksEvenTheRightOne() {
        store.setPin("user1", "1234".toCharArray())
        repeat(4) { assertThat(store.verifyPin("user1", "0000".toCharArray())).isInstanceOf(PinCheck.Wrong::class.java) }
        val fifth = store.verifyPin("user1", "0000".toCharArray()) as PinCheck.Wrong
        assertThat(fifth.lockoutMs).isEqualTo(30_000L)
        assertThat(store.verifyPin("user1", "1234".toCharArray())).isInstanceOf(PinCheck.LockedOut::class.java)
        clock.elapsed += 30_001
        assertThat(store.verifyPin("user1", "1234".toCharArray())).isEqualTo(PinCheck.Success)
    }

    @Test fun lockoutOfOneProfileDoesNotAffectAnother() {
        store.setPin("user1", "1234".toCharArray()); store.setPin("ak_b", "4321".toCharArray())
        repeat(5) { store.verifyPin("user1", "0000".toCharArray()) }
        assertThat(store.verifyPin("ak_b", "4321".toCharArray())).isEqualTo(PinCheck.Success)
    }

    @Test fun rebootDoesNotShortenALockout() {
        store.setPin("user1", "1234".toCharArray())
        repeat(5) { store.verifyPin("user1", "0000".toCharArray()) }
        clock.boot = BootIdentity(2, 99_999_999L); clock.elapsed = 5L // fresh boot, tiny uptime
        assertThat(store.lockoutRemainingMs("user1")).isEqualTo(30_000L)
    }

    @Test fun attemptsPersistAcrossStoreInstances() {
        store.setPin("user1", "1234".toCharArray())
        repeat(4) { store.verifyPin("user1", "0000".toCharArray()) }
        val reopened = ProfileLockStore(context, PinHasher({ NoPepper }, { NoPepper }, iterations = 1_000), clock, dir)
        assertThat(reopened.verifyPin("user1", "0000".toCharArray())).isInstanceOf(PinCheck.Wrong::class.java)
        assertThat(reopened.verifyPin("user1", "1234".toCharArray())).isInstanceOf(PinCheck.LockedOut::class.java)
    }

    @Test fun disableNeedsCurrentPin() {
        store.setPin("user1", "1234".toCharArray())
        assertThat(store.disable("user1", "9999".toCharArray())).isInstanceOf(PinCheck.Wrong::class.java)
        assertThat(store.isEnabled("user1")).isTrue()
        assertThat(store.disable("user1", "1234".toCharArray())).isEqualTo(PinCheck.Success)
        assertThat(store.isEnabled("user1")).isFalse()
    }

    @Test fun corruptFileFailsClosed() {
        dir.mkdirs(); File(dir, "user1.json").writeText("{not json")
        assertThat(store.isEnabled("user1")).isTrue()
    }

    @Test fun badProfileIdsAreRejected() {
        assertThat(ProfileLockStore.isValidId("../x")).isFalse()
    }

    @Test fun pinPolicy() {
        assertThat(PinPolicy.isValid("123")).isFalse()
        assertThat(PinPolicy.isValid("1234")).isTrue()
        assertThat(PinPolicy.isValid("123456789")).isFalse()
        assertThat(PinPolicy.isValid("12a4")).isFalse()
    }

    @Test fun passwordAndPatternVerifyOnlyWithTheirOwnMethod() {
        assertThat(store.setCredential("user1", LockMethod.PASSWORD, "correct horse".toCharArray())).isTrue()
        assertThat(store.config("user1").method).isEqualTo(LockMethod.PASSWORD)
        assertThat(store.verify("user1", "correct horse".toCharArray())).isEqualTo(PinCheck.Success)
        assertThat(store.verify("user1", "wrong horse!".toCharArray())).isInstanceOf(PinCheck.Wrong::class.java)

        assertThat(store.setCredential("ak_b", LockMethod.PATTERN, PatternPolicy.encode(listOf(0, 1, 2, 5)))).isTrue()
        assertThat(store.verify("ak_b", PatternPolicy.encode(listOf(0, 1, 2, 5)))).isEqualTo(PinCheck.Success)
        assertThat(store.verify("ak_b", PatternPolicy.encode(listOf(5, 2, 1, 0)))).isInstanceOf(PinCheck.Wrong::class.java)
    }

    @Test fun methodsAreIndependentPerProfile() {
        store.setPin("user1", "1234".toCharArray())
        store.setCredential("ak_b", LockMethod.PASSWORD, "hunter22!".toCharArray())
        store.setCredential("ak_c", LockMethod.PATTERN, PatternPolicy.encode(listOf(0, 4, 8, 7)))
        assertThat(store.config("user1").method).isEqualTo(LockMethod.PIN)
        assertThat(store.config("ak_b").method).isEqualTo(LockMethod.PASSWORD)
        assertThat(store.config("ak_c").method).isEqualTo(LockMethod.PATTERN)
        // A secret valid for one workspace never opens another.
        assertThat(store.verify("user1", "hunter22!".toCharArray())).isInstanceOf(PinCheck.Wrong::class.java)
    }

    @Test fun passwordAndPatternAreNeverWrittenToDisk() {
        store.setCredential("user1", LockMethod.PASSWORD, "Zebra-Quartz-77".toCharArray())
        store.setCredential("ak_b", LockMethod.PATTERN, PatternPolicy.encode(listOf(3, 4, 5, 8, 7)))
        val text = dir.listFiles()!!.joinToString { it.readText() }
        assertThat(text).doesNotContain("Zebra-Quartz-77")
        assertThat(text).doesNotContain("34587")
    }

    @Test fun changingMethodNeedsCurrentSecretAndKeepsOldLockOnFailure() {
        store.setPin("user1", "1234".toCharArray())
        // Wrong current secret: nothing changes.
        assertThat(store.changeCredential("user1", "0000".toCharArray(), LockMethod.PASSWORD, "new-password".toCharArray()))
            .isInstanceOf(PinCheck.Wrong::class.java)
        assertThat(store.config("user1").method).isEqualTo(LockMethod.PIN)
        // Invalid new secret (too short): old PIN still works.
        assertThat(store.changeCredential("user1", "1234".toCharArray(), LockMethod.PASSWORD, "abc".toCharArray()))
            .isEqualTo(PinCheck.Unavailable)
        assertThat(store.verify("user1", "1234".toCharArray())).isEqualTo(PinCheck.Success)
        // Valid switch: new method active, old PIN no longer works.
        assertThat(store.changeCredential("user1", "1234".toCharArray(), LockMethod.PASSWORD, "new-password".toCharArray()))
            .isEqualTo(PinCheck.Success)
        assertThat(store.config("user1").method).isEqualTo(LockMethod.PASSWORD)
        assertThat(store.verify("user1", "new-password".toCharArray())).isEqualTo(PinCheck.Success)
    }

    @Test fun lockFileWithoutMethodIsAPin() {
        store.setPin("user1", "1234".toCharArray())
        val f = File(dir, "user1.json")
        f.writeText(org.json.JSONObject(f.readText()).apply { remove("method") }.toString())
        val reopened = ProfileLockStore(context, PinHasher({ NoPepper }, { NoPepper }, iterations = 1_000), clock, dir)
        assertThat(reopened.config("user1").method).isEqualTo(LockMethod.PIN)
        assertThat(reopened.verify("user1", "1234".toCharArray())).isEqualTo(PinCheck.Success)
    }

    @Test fun passwordAndPatternPolicies() {
        assertThat(PasswordPolicy.isValid("12345")).isFalse()
        assertThat(PasswordPolicy.isValid("      ")).isFalse()
        assertThat(PasswordPolicy.isValid("abc def")).isTrue()
        assertThat(PatternPolicy.isValidDots(listOf(0, 1, 2))).isFalse()        // too short
        assertThat(PatternPolicy.isValidDots(listOf(0, 1, 2, 2))).isFalse()     // repeated dot
        assertThat(PatternPolicy.isValidDots(listOf(0, 1, 2, 9))).isFalse()     // off grid
        assertThat(PatternPolicy.isValidDots(listOf(0, 1, 2, 5))).isTrue()
    }

    @Test fun fingerprintSettingSurvivesSwitchingMethod() {
        store.setPin("user1", "1234".toCharArray())
        store.setBiometric("user1", byteArrayOf(1), byteArrayOf(2))
        store.changeCredential("user1", "1234".toCharArray(), LockMethod.PATTERN, PatternPolicy.encode(listOf(0, 1, 2, 5)))
        assertThat(store.config("user1").fingerprint).isTrue()
    }
}
