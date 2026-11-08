package party.hedge.host;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** Builds the diagnostic report off the UI thread, then offers it to mail and messaging apps. */
public final class LogShare {
    private static volatile boolean sharing;

    private LogShare() { }

    public static void share(Activity a, java.util.function.Consumer<String> onError) {
        if (sharing) return;
        sharing = true;
        Toast.makeText(a, "Collecting the log...", Toast.LENGTH_SHORT).show();
        final Context app = a.getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                Diag.buildReport(app);
            } catch (Throwable t) {
                // Share what there is: Diag writes whatever it collected before the failure.
                android.util.Log.w("HedgeParty", "log report failed", t);
            } finally {
                // However the report ended, the button must work again.
                sharing = false;
            }
            ui.post(() -> {
                if (a.isDestroyed() || a.isFinishing()) return;
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                Uri uri = LogProvider.reportUri();
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_SUBJECT, "Hedge Party log " + Diag.versionName(app));
                send.putExtra(Intent.EXTRA_TEXT, "Hedge Party log from " + Diag.deviceLine());
                send.setClipData(ClipData.newRawUri("log", uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    a.startActivity(Intent.createChooser(send, "Share the Hedge Party log"));
                } catch (Exception e) {
                    onError.accept("Could not open the share sheet: " + e.getMessage());
                }
            });
        }, "log-report").start();
    }
}
