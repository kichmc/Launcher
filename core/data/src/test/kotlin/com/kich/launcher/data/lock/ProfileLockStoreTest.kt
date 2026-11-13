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
}
