package app.gamenative.ui.screen.wow

import android.app.Application
import android.content.Context
import app.gamenative.ui.screen.wow.auth.BattleNetBrowserLogin
import app.gamenative.utils.ContainerUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WowProfileTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        context.getSharedPreferences("wow_profiles", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultProfile_keepsTheNamesAnInstallHadBeforeProfiles() {
        val default = WowProfiles.all(context).single()

        assertTrue(default.isDefault)
        assertEquals(default, WowProfiles.active(context))
        assertEquals("wow_forever", default.containerId)
        assertEquals("", default.storageSuffix)
        assertNull(default.webProfile)
        assertEquals("wow_identity.properties", WowMachineIdentity.file(context, default).name)
    }

    @Test
    fun add_givesEveryProfileItsOwnContainerAndStorage() {
        val second = WowProfiles.add(context, "  Alt   account ")!!
        val third = WowProfiles.add(context, "Bank")!!

        assertEquals("Alt account", second.name)
        assertEquals(listOf("default", second.id, third.id), WowProfiles.all(context).map { it.id })
        assertEquals(3, setOf(WowProfile.DEFAULT, second, third).map { it.containerId }.toSet().size)
        assertEquals(3, setOf(WowProfile.DEFAULT, second, third).map { it.storageSuffix }.toSet().size)
        assertNotEquals(second.webProfile, third.webProfile)
        assertNotEquals(
            WowMachineIdentity.file(context, second).name,
            WowMachineIdentity.file(context, third).name,
        )
    }

    @Test
    fun containerIds_areNotReadAsGameIds() {
        // ContainerUtils turns a trailing "_<number>" into a game id; every profile must read like the default one.
        val expected = ContainerUtils.extractGameIdFromContainerId(WowProfile.DEFAULT.containerId)
        repeat(3) { index ->
            val profile = WowProfiles.add(context, "Profile $index")!!
            assertEquals(expected, ContainerUtils.extractGameIdFromContainerId(profile.containerId))
        }
    }

    @Test
    fun add_rejectsBlankDuplicateAndOverflowingNames() {
        assertNull(WowProfiles.add(context, "   "))
        assertNull(WowProfiles.add(context, "main"))
        val added = WowProfiles.add(context, "x".repeat(60))!!
        assertEquals(WowProfiles.MAX_NAME_LENGTH, added.name.length)

        while (WowProfiles.all(context).size < WowProfiles.MAX_PROFILES) {
            WowProfiles.add(context, "Profile ${WowProfiles.all(context).size}")!!
        }
        assertNull(WowProfiles.add(context, "One too many"))
    }

    @Test
    fun remove_neverReusesAnIdAndFallsBackToTheDefaultProfile() {
        val second = WowProfiles.add(context, "Alt")!!
        WowProfiles.select(context, second)
        assertEquals(second, WowProfiles.active(context))

        WowProfiles.remove(context, second)

        assertEquals(WowProfile.DEFAULT, WowProfiles.active(context))
        assertEquals(listOf(WowProfile.DEFAULT), WowProfiles.all(context))
        assertNotEquals(second.id, WowProfiles.add(context, "Alt")!!.id)
    }

    @Test
    fun add_skipsAnIdThatStillHasDataOnTheDevice() {
        // As if the profile list was lost while a removed profile's identity file stayed behind.
        val leftover = WowMachineIdentity.file(context, WowProfile("p2", "Old"))
        leftover.parentFile?.mkdirs()
        leftover.writeText("machineGuid=old\n")

        assertEquals("p3", WowProfiles.add(context, "Alt")!!.id)
        leftover.delete()
    }

    @Test(expected = IllegalArgumentException::class)
    fun remove_refusesTheDefaultProfile() {
        WowProfiles.remove(context, WowProfile.DEFAULT)
    }

    @Test
    fun rename_keepsTheIdAndAppliesToTheDefaultProfileToo() {
        val second = WowProfiles.add(context, "Alt")!!

        val renamed = WowProfiles.rename(context, second, "Bank")!!
        val main = WowProfiles.rename(context, WowProfile.DEFAULT, "Raider")!!

        assertEquals(second.id, renamed.id)
        assertEquals(listOf("Raider", "Bank"), WowProfiles.all(context).map { it.name })
        assertTrue(main.isDefault)
        assertEquals("wow_forever", main.containerId)
        assertNull(WowProfiles.rename(context, renamed, "raider"))
    }

    @Test
    fun forContainer_fallsBackToTheDefaultProfile() {
        val second = WowProfiles.add(context, "Alt")!!

        assertEquals(second, WowProfiles.forContainer(context, second.containerId))
        assertTrue(WowProfiles.forContainer(context, "wow_forever").isDefault)
        assertTrue(WowProfiles.forContainer(context, "something_else").isDefault)
    }

    @Test
    fun decode_survivesDamagedData() {
        assertEquals(listOf(WowProfile.DEFAULT), WowProfiles.decode(null))
        assertEquals(listOf(WowProfile.DEFAULT), WowProfiles.decode("not json"))
        val decoded = WowProfiles.decode(
            """[{"id":"p2","name":"Alt"},{"id":"../x","name":"Bad"},{"id":"p2","name":"Twice"},{"id":"p3","name":" "},7]""",
        )
        assertEquals(listOf(WowProfile.DEFAULT, WowProfile("p2", "Alt")), decoded)
        val profiles = listOf(WowProfile("default", "Raider"), WowProfile("p5", "Alt"))
        assertEquals(profiles, WowProfiles.decode(WowProfiles.encode(profiles)))
    }

    @Test
    fun machineIdentity_isSeparateAndStablePerProfile() {
        val second = WowProfiles.add(context, "Alt")!!
        val third = WowProfiles.add(context, "Bank")!!

        val identity = WowMachineIdentity.load(context, second)

        assertNotEquals(identity, WowMachineIdentity.load(context, third))
        assertNotEquals(identity, WowMachineIdentity.load(context, WowProfile.DEFAULT))
        assertEquals(identity, WowMachineIdentity.load(context, second))
        assertTrue(WowMachineIdentity.file(context, second).isFile)

        WowMachineIdentity.forget(context, second)
        assertFalse(WowMachineIdentity.file(context, second).exists())
    }

    @Test
    fun browserLoginSetting_isPerProfile() {
        val second = WowProfiles.add(context, "Alt")!!
        BattleNetBrowserLogin.setEnabled(context, false, WowProfile.DEFAULT)

        BattleNetBrowserLogin.setEnabled(context, true, second)

        assertTrue(BattleNetBrowserLogin.enabled(context, second))
        assertFalse(BattleNetBrowserLogin.enabled(context, WowProfile.DEFAULT))
        WowProfiles.select(context, second)
        assertTrue(BattleNetBrowserLogin.enabled(context))
        BattleNetBrowserLogin.delete(context, second)
        assertFalse(BattleNetBrowserLogin.enabled(context, second))
    }
}
