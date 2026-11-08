package org.hedgewars.android;

import android.app.Application;

/** Starts the on-device diagnostics in every process of the app (setup screen and game). */
public class HpApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Diag.init(this);
    }
}
