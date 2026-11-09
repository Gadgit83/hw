package org.hedgewars.android;

import android.app.Application;

/** Starts the on-device diagnostics in every process of the app (setup screen and game). */
public class HpApp extends Application {
    /** The application context, for code that has no Context of its own (network session callbacks). */
    static volatile android.content.Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = this;
        if (BuildConfig.TEST_BUILD || (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            // Fail loudly in test and debug builds: network I/O on the main thread kills the app (it crashed Start on a phone once).
            android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder()
                    .detectNetwork().penaltyDeath().build());
        }
        Diag.init(this);
    }
}
