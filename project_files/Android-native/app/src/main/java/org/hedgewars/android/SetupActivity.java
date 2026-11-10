package org.hedgewars.android;

import android.app.Activity;

/** Base of the setup, team and options screens: full screen, so the status and navigation bars stay out of the way. */
public abstract class SetupActivity extends Activity {
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.hideBars(this);
    }
}
