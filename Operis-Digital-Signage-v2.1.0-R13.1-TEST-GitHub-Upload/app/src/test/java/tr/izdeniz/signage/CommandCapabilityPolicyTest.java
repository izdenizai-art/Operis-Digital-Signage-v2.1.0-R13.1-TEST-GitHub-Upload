package tr.izdeniz.signage;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CommandCapabilityPolicyTest {
    @Test public void normalApkSupportsOnlySafePlayerCommands() {
        assertTrue(CommandCapabilityPolicy.supports(false, false, "REFRESH"));
        assertTrue(CommandCapabilityPolicy.supports(false, false, "SCREEN_REFRESH"));
        assertTrue(CommandCapabilityPolicy.supports(false, false, "PROFILE_SYNC"));
        assertTrue(CommandCapabilityPolicy.supports(false, false, "CACHE_CLEAR"));
        assertTrue(CommandCapabilityPolicy.supports(false, false, "TELEMETRY_REFRESH"));
        assertTrue(CommandCapabilityPolicy.supports(false, false, "APP_RESTART"));
        assertTrue(CommandCapabilityPolicy.supports(false, false, "TIME_REFRESH"));
        assertTrue(CommandCapabilityPolicy.supports(false, false, "CONNECTION_REFRESH"));
        assertTrue(CommandCapabilityPolicy.supports(false, false, "PLAYLIST_REFRESH"));
        assertTrue(CommandCapabilityPolicy.supports(false, false, "SCHEDULE_REFRESH"));
        assertTrue(CommandCapabilityPolicy.supports(false, false, "RETURN_TO_BASE_LAYOUT"));
        assertTrue(CommandCapabilityPolicy.supports(false, false, "SCREEN_PREVIEW_REFRESH"));
        assertFalse(CommandCapabilityPolicy.supports(false, false, "DEVICE_REBOOT"));
        assertFalse(CommandCapabilityPolicy.supports(false, false, "KIOSK_LOCK"));
        assertFalse(CommandCapabilityPolicy.supports(false, false, "KIOSK_UNLOCK"));
    }

    @Test public void deviceOwnerEnablesRebootAndKioskButNotPhysicalPowerOn() {
        assertTrue(CommandCapabilityPolicy.supports(true, false, "DEVICE_REBOOT"));
        assertTrue(CommandCapabilityPolicy.supports(true, false, "KIOSK_LOCK"));
        assertTrue(CommandCapabilityPolicy.supports(true, false, "KIOSK_UNLOCK"));
        assertFalse(CommandCapabilityPolicy.supports(true, false, "POWER_ON"));
    }

    @Test public void powerOnRequiresAnExplicitExternalCapability() {
        assertTrue(CommandCapabilityPolicy.supports(false, true, "POWER_ON"));
        assertFalse(CommandCapabilityPolicy.supports(true, true, "FORMAT_DISK"));
    }
}
