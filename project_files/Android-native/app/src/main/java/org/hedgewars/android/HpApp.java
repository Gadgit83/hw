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
        Diag.init(this);
    }
}
