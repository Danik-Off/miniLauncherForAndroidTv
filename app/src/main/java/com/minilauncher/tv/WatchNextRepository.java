package com.minilauncher.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the system "watch next" table that video apps (Kinopoisk, Rutube, YouTube...) fill in.
 * One cheap ContentResolver query when the launcher comes to the foreground; no observers, no
 * background work. Needs READ_TV_LISTINGS (a runtime permission) and Android 8.0+.
 */
final class WatchNextRepository {
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final Uri URI = Uri.parse("content://android.media.tv/watch_next_program");
    private static final int LIMIT = 6;
    private static final String KEY_DISMISSED = "wn_dismissed";

    private static final String[] PROJECTION = {
            "_id", "package_name", "title", "poster_art_uri", "intent_uri",
            "last_engagement_time_utc_millis", "last_playback_position_millis", "duration_millis",
            "browsable"
    };

    private final Context context;
    private final SharedPreferences prefs;

    WatchNextRepository(Context context, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    boolean hasPermission() {
        return supported() && context.checkPermission(PERMISSION, Process.myPid(), Process.myUid())
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Blocking; call from a background thread. Returns the newest items first. */
    List<WatchNextItem> load() {
        if (!hasPermission()) return Collections.emptyList();
        Set<String> dismissed = prefs.getStringSet(KEY_DISMISSED, Collections.<String>emptySet());
        List<WatchNextItem> items = new ArrayList<>();
        Cursor c = null;
        try {
            c = context.getContentResolver().query(URI, PROJECTION, null, null, null);
            if (c == null) return items;
            while (c.moveToNext()) {
                if (!c.isNull(8) && c.getInt(8) == 0) continue; // user removed it in the source app
                String title = c.getString(2);
                String intent = c.getString(4);
                if (title == null || title.isEmpty() || intent == null) continue;
                long id = c.getLong(0);
                if (dismissed.contains(String.valueOf(id))) continue;
                items.add(new WatchNextItem(id, c.getString(1), title, c.getString(3), intent,
                        c.isNull(5) ? 0 : c.getLong(5), c.isNull(6) ? -1 : c.getLong(6),
                        c.isNull(7) ? -1 : c.getLong(7)));
            }
        } catch (Exception ignored) {
            // Some firmwares ship a crippled TvProvider; just show nothing.
        } finally {
            if (c != null) c.close();
        }
        Collections.sort(items, (a, b) -> Long.compare(b.lastEngagement, a.lastEngagement));
        return items.size() > LIMIT ? new ArrayList<>(items.subList(0, LIMIT)) : items;
    }

    /** Hide an item locally: other apps' rows can't be modified by a launcher. */
    void dismiss(long id) {
        Set<String> set = new HashSet<>(prefs.getStringSet(KEY_DISMISSED, Collections.<String>emptySet()));
        set.add(String.valueOf(id));
        prefs.edit().putStringSet(KEY_DISMISSED, set).apply();
    }
}
