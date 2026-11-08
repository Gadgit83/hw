package party.hedge.host;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Shares the diagnostic report with other apps (mail, messengers) through a content URI.
 * A small hand written provider, as androidx FileProvider is not a dependency of this app.
 * Only files/logs/report.txt can be opened, and only by apps given an explicit grant.
 */
public class LogProvider extends ContentProvider {
    static final String AUTHORITY = "party.hedge.host.logs";
    static final String REPORT_NAME = "hedge-party-log.txt";

    static Uri reportUri() {
        return Uri.parse("content://" + AUTHORITY + "/" + REPORT_NAME);
    }

    private File report() {
        return new File(new File(getContext().getFilesDir(), "logs"), "report.txt");
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!REPORT_NAME.equals(uri.getLastPathSegment()) || !mode.startsWith("r")) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(report(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String[] cols = projection != null ? projection : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor c = new MatrixCursor(cols);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = REPORT_NAME;
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = report().length();
        }
        c.addRow(row);
        return c;
    }

    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
