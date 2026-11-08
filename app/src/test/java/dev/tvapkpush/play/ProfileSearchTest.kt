package dev.tvapkpush.play

import com.aurora.gplayapi.device.DeviceRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.Properties

class ProfileSearchTest {
    private val profiles = DeviceRegistry.available()
    private val television = checkNotNull(DeviceRegistry.byId("bravia_vu2"))

    @Test fun blankQueryPreservesProfilesAndOrder() {
        assertSame(profiles, filterProfiles(profiles, "  \t "))
    }

    @Test fun brandModelAndMultipleTermsIgnoreCase() {
        assertTrue(television in filterProfiles(profiles, "  SONY  bravia "))
        assertFalse(television in filterProfiles(profiles, "Sony Pixel"))
        assertEquals(listOf("bravia_vu2", "xp_5_dual"), filterProfiles(profiles, "Sony").map { it.id })
    }

    @Test fun systemAndArchitectureSearchMatchesActualConfiguration() {
        assertTrue(television in filterProfiles(profiles, "API 30 armeabi-v7a"))
        assertTrue(television in filterProfiles(profiles, "api30 android11"))
        assertTrue(television in filterProfiles(profiles, "Android 11 电视"))
        assertFalse(television in filterProfiles(profiles, "arm64-v8a"))
        assertTrue(filterProfiles(profiles, "nonexistent-device-xyz").isEmpty())
    }

    @Test fun nativeProfileMatchesItsLabelAndRealModel() {
        val native = DeviceRegistry.parse("native", Properties().apply { putAll(television.properties) })
        assertEquals(listOf(native), filterProfiles(listOf(native) + profiles, "本机"))
        assertTrue(native in filterProfiles(listOf(native), "Bravia"))
    }
}
