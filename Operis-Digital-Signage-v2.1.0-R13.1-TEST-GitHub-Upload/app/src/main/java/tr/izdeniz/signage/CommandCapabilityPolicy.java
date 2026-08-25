package tr.izdeniz.signage;

import java.util.Locale;

/**
 * Central command capability policy shared by the native bridge and tests.
 * It deliberately separates commands that are safe in a normal APK from
 * commands that require Device Owner privileges or external power support.
 */
public final class CommandCapabilityPolicy {
    private CommandCapabilityPolicy() {}

    public static boolean supports(boolean deviceOwner, boolean powerOnSupported, String commandType) {
        if (commandType == null) return false;
        String type = commandType.trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "REFRESH":
            case "SCREEN_REFRESH":
            case "PROFILE_SYNC":
            case "CACHE_CLEAR":
            case "TELEMETRY_REFRESH":
            case "APP_RESTART":
            case "TIME_REFRESH":
            case "CONNECTION_REFRESH":
            case "PLAYLIST_REFRESH":
            case "SCHEDULE_REFRESH":
            case "RETURN_TO_BASE_LAYOUT":
            case "SCREEN_PREVIEW_REFRESH":
                return true;
            case "DEVICE_REBOOT":
            case "KIOSK_LOCK":
            case "KIOSK_UNLOCK":
                return deviceOwner;
            case "POWER_ON":
                return powerOnSupported;
            default:
                return false;
        }
    }
}
