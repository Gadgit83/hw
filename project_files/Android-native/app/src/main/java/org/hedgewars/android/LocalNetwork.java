package org.hedgewars.android;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * The "local network" permission of newer Android (docs/tv-viewer-plan.md section 4, "Permissions to add"). From Android 16 (API 36)
 * a system can restrict an app's access to devices on the LAN, and the permission {@code ACCESS_LOCAL_NETWORK} (a runtime permission
 * in the nearby devices group) is the way to be allowed; for an app that targets Android 17 (API 37) it is required. This app
 * targets API 34, so today the system should not enforce it; the code asks anyway on 36 and later, so that raising the target
 * later does not break joining, and degrades to the typed address when the answer is no. NEARBY_WIFI_DEVICES is deliberately not
 * declared: NSD, plain TCP listening and connecting do not need it (it is for Wi-Fi Direct, Wi-Fi Aware and Wi-Fi scans).
 * Not verified on a device with Android 16 or 17 (none here): the device test steps in docs/multiphone-test.md say what to look for.
 */
final class LocalNetwork {
    private LocalNetwork() { }

    static final String PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    static final int REQUEST = 8;

    /** Test hook: forces "the system knows this permission" on or off (null: ask the real OS level). */
    static volatile Boolean forceKnown;
    /** Test hook: forces the grant (null: ask the system). */
    static volatile Boolean forceGranted;

    static boolean known() {
        Boolean f = forceKnown;
        return f != null ? f : Build.VERSION.SDK_INT >= 36;
    }

    static boolean granted(Context c) {
        Boolean f = forceGranted;
        if (f != null) return f;
        if (!known()) return true;
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Asks for the permission if this Android knows it and it is not granted. Returns true if a prompt was started. */
    static boolean request(Activity a) {
        if (granted(a)) return false;
        try {
            a.requestPermissions(new String[] { PERMISSION }, REQUEST);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
