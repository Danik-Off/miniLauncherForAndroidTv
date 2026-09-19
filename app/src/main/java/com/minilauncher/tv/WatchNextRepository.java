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
 * Reads what video apps publish into the system TvProvider: the "watch next" table and their
 * recommendation channels ("Popular", "Recommended"...). One cheap ContentResolver query per
 * table when the launcher comes to the foreground; no observers, no background work.
 * Needs READ_TV_LISTINGS (a runtime permission) and Android 8.0+.
 */
final class WatchNextRepository {
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final Uri WATCH_NEXT_URI = Uri.parse("content://android.media.tv/watch_next_program");
    private static final Uri CHANNEL_URI = Uri.parse("content://android.media.tv/channel");
    private static final Uri PREVIEW_URI = Uri.parse("content://android.media.tv/preview_program");
    private static final int WATCH_NEXT_LIMIT = 6;
    private static final int CHANNEL_LIMIT = 6;
    private static final String KEY_DISMISSED = "wn_dismissed";

    private static final String[] WATCH_NEXT_PROJECTION = {
            "_id", "package_name", "title", "poster_art_uri", "intent_uri",
            "last_engagement_time_utc_millis", "last_playback_position_millis", "duration_millis",
            "browsable"
    };
    private static final String[] PREVIEW_PROJECTION = {
            "_id", "package_name", "title", "poster_art_uri", "intent_uri", "browsable", "weight"
    };
    private static final String[] CHANNEL_PROJECTION = {"_id", "package_name", "display_name", "type"};

    /** A recommendation channel published by an app. */
    static final class Channel {
        final long id;
        final String packageName;
        final String name;

        Channel(long id, String packageName, String name) {
            this.id = id;
            this.packageName = packageName;
            this.name = name;
        }
    }

    /** A channel together with the programs to show for it. */
    static final class ChannelRow {
        final Channel channel;
        final List<WatchNextItem> items;

        ChannelRow(Channel channel, List<WatchNextItem> items) {
            this.channel = channel;
            this.items = items;
        }
    }

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
            c = context.getContentResolver().query(WATCH_NEXT_URI, WATCH_NEXT_PROJECTION, null, null, null);
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
        return items.size() > WATCH_NEXT_LIMIT ? new ArrayList<>(items.subList(0, WATCH_NEXT_LIMIT)) : items;
    }

    /** Channels that currently have something to show; apps leave empty duplicates behind. */
    List<Channel> loadChannelsWithContent() {
        List<Channel> result = new ArrayList<>();
        for (Channel ch : loadChannels()) {
            if (!loadPrograms(ch).isEmpty()) result.add(ch);
        }
        return result;
    }

    /** All preview channels apps have published, whether the user enabled them or not. */
    List<Channel> loadChannels() {
        List<Channel> channels = new ArrayList<>();
        if (!hasPermission()) return channels;
        Cursor c = null;
        try {
            c = context.getContentResolver().query(CHANNEL_URI, CHANNEL_PROJECTION, null, null, null);
            if (c == null) return channels;
            while (c.moveToNext()) {
                if (!"TYPE_PREVIEW".equals(c.getString(3))) continue;
                String name = c.getString(2);
                if (name == null || name.isEmpty()) continue;
                channels.add(new Channel(c.getLong(0), c.getString(1), name));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return channels;
    }

    /** Programs of one channel, in the order the app ranked them. */
    List<WatchNextItem> loadPrograms(Channel channel) {
        List<WatchNextItem> items = new ArrayList<>();
        Cursor c = null;
        try {
            // TvProvider rejects a WHERE clause from ordinary apps; the channel filter goes in the URI.
            Uri uri = PREVIEW_URI.buildUpon().appendQueryParameter("channel", String.valueOf(channel.id)).build();
            c = context.getContentResolver().query(uri, PREVIEW_PROJECTION, null, null, null);
            if (c == null) return items;
            while (c.moveToNext() && items.size() < CHANNEL_LIMIT) {
                if (!c.isNull(5) && c.getInt(5) == 0) continue;
                String title = c.getString(2);
                String intent = c.getString(4);
                if (title == null || title.isEmpty() || intent == null) continue;
                items.add(new WatchNextItem(c.getLong(0), c.getString(1), title, c.getString(3), intent,
                        0, -1, -1));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return items;
    }

    /** Rows for the channels the user enabled; channels that vanished are skipped. */
    List<ChannelRow> loadEnabledChannelRows(Set<String> enabledIds) {
        List<ChannelRow> rows = new ArrayList<>();
        if (enabledIds.isEmpty()) return rows;
        for (Channel ch : loadChannels()) {
            if (!enabledIds.contains(String.valueOf(ch.id))) continue;
            List<WatchNextItem> items = loadPrograms(ch);
            if (!items.isEmpty()) rows.add(new ChannelRow(ch, items));
        }
        return rows;
    }

    /** Hide an item locally: other apps' rows can't be modified by a launcher. */
    void dismiss(long id) {
        Set<String> set = new HashSet<>(prefs.getStringSet(KEY_DISMISSED, Collections.<String>emptySet()));
        set.add(String.valueOf(id));
        prefs.edit().putStringSet(KEY_DISMISSED, set).apply();
    }
}
