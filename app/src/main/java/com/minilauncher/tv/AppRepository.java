package com.minilauncher.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lists installed apps and stores the user's launcher settings (favorites, hidden, sizes). */
final class AppRepository {
    private static final String PREFS = "launcher";
    private static final String KEY_FAVORITES = "favorites_order"; // ordered, comma separated
    private static final String KEY_FAVORITES_LEGACY = "favorites";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_CARD_SIZE = "card_size";
    private static final String KEY_WATCH_NEXT_HIDDEN = "watch_next_hidden";
    private static final String KEY_TV_PERMISSION_ASKED = "tv_permission_asked";
    private static final String KEY_NIGHT_MODE = "night_mode";
    private static final String KEY_LAST_APP = "last_app";
    private static final String KEY_LARGE_TEXT = "large_text";
    private static final String KEY_CHANNELS = "channels";
    private static final String KEY_COLOR_KEY = "color_key_"; // + index 0..3

    static final int SIZE_COMPACT = 0;
    static final int SIZE_NORMAL = 1;
    static final int SIZE_LARGE = 2;

    static final int NIGHT_AUTO = 0;
    static final int NIGHT_ON = 1;
    static final int NIGHT_OFF = 2;

    /** Actions assignable to the remote's coloured keys; index is what gets stored. */
    static final int ACT_NONE = 0;
    static final int ACT_LAST_APP = 1;
    static final int ACT_WIFI = 2;
    static final int ACT_BLUETOOTH = 3;
    static final int ACT_SOUND = 4;
    static final int ACT_SETTINGS = 5;
    static final int ACT_NIGHT = 6;
    private static final int[] COLOR_KEY_DEFAULTS = {ACT_SETTINGS, ACT_LAST_APP, ACT_WIFI, ACT_NIGHT};

    private final Context context;
    private final SharedPreferences prefs;

    AppRepository(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    SharedPreferences prefs() {
        return prefs;
    }

    /** Blocking; call from a background thread. Alphabetical; favorites keep their own order. */
    List<AppInfo> loadApps() {
        PackageManager pm = context.getPackageManager();
        Map<String, AppInfo> byPackage = new LinkedHashMap<>();
        // TV activities first: they come with banners. Phone-style apps fill in the rest.
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, byPackage);
        collect(pm, Intent.CATEGORY_LAUNCHER, false, byPackage);

        List<String> favorites = getFavorites();
        Set<String> hidden = getSet(KEY_HIDDEN);
        List<AppInfo> apps = new ArrayList<>(byPackage.values());
        for (AppInfo app : apps) {
            app.favorite = favorites.contains(app.packageName);
            app.hidden = hidden.contains(app.packageName);
        }

        final Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(apps, (a, b) -> collator.compare(a.label, b.label));
        return apps;
    }

    private void collect(PackageManager pm, String category, boolean leanback, Map<String, AppInfo> out) {
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> resolved = pm.queryIntentActivities(probe, 0);
        String self = context.getPackageName();
        for (ResolveInfo ri : resolved) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || self.equals(ai.packageName) || out.containsKey(ai.packageName)) continue;
            // File mtime instead of getPackageInfo(): no extra binder call per app.
            long modified = ai.applicationInfo != null && ai.applicationInfo.sourceDir != null
                    ? new File(ai.applicationInfo.sourceDir).lastModified() : 0L;
            CharSequence label = ri.loadLabel(pm);
            out.put(ai.packageName, new AppInfo(
                    ai.packageName,
                    new ComponentName(ai.packageName, ai.name),
                    label != null ? label.toString() : ai.packageName,
                    leanback,
                    modified));
        }
    }

    // ---------------------------------------------------------------- favorites (ordered)

    List<String> getFavorites() {
        String raw = prefs.getString(KEY_FAVORITES, null);
        if (raw == null) {
            // migrate from the unordered set used by the first version
            Set<String> legacy = getSet(KEY_FAVORITES_LEGACY);
            List<String> list = new ArrayList<>(legacy);
            Collections.sort(list);
            if (!list.isEmpty()) saveFavorites(list);
            return list;
        }
        if (raw.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(raw.split(",")));
    }

    void setFavorite(String packageName, boolean favorite) {
        List<String> list = getFavorites();
        list.remove(packageName);
        if (favorite) list.add(packageName);
        saveFavorites(list);
    }

    /** Moves a favorite one step; returns false when it is already at the edge. */
    boolean moveFavorite(String packageName, int delta) {
        List<String> list = getFavorites();
        int i = list.indexOf(packageName);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= list.size()) return false;
        Collections.swap(list, i, j);
        saveFavorites(list);
        return true;
    }

    private void saveFavorites(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String p : list) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        prefs.edit().putString(KEY_FAVORITES, sb.toString()).apply();
    }

    // ---------------------------------------------------------------- hidden

    void setHidden(String packageName, boolean hidden) {
        // Always copy: mutating the set returned by getStringSet() is undefined behaviour.
        Set<String> set = new HashSet<>(getSet(KEY_HIDDEN));
        if (hidden) set.add(packageName); else set.remove(packageName);
        prefs.edit().putStringSet(KEY_HIDDEN, set).apply();
    }

    private Set<String> getSet(String key) {
        Set<String> set = prefs.getStringSet(key, null);
        return set != null ? set : Collections.<String>emptySet();
    }

    // ---------------------------------------------------------------- misc settings

    int getCardSize() {
        return prefs.getInt(KEY_CARD_SIZE, SIZE_NORMAL);
    }

    void setCardSize(int size) {
        prefs.edit().putInt(KEY_CARD_SIZE, size).apply();
    }

    int getNightMode() {
        return prefs.getInt(KEY_NIGHT_MODE, NIGHT_AUTO);
    }

    void setNightMode(int mode) {
        prefs.edit().putInt(KEY_NIGHT_MODE, mode).apply();
    }

    boolean isWatchNextHidden() {
        return prefs.getBoolean(KEY_WATCH_NEXT_HIDDEN, false);
    }

    void setWatchNextHidden(boolean hidden) {
        prefs.edit().putBoolean(KEY_WATCH_NEXT_HIDDEN, hidden).apply();
    }

    String getLastApp() {
        return prefs.getString(KEY_LAST_APP, null);
    }

    void setLastApp(String packageName) {
        prefs.edit().putString(KEY_LAST_APP, packageName).apply();
    }

    boolean isLargeText() {
        return prefs.getBoolean(KEY_LARGE_TEXT, false);
    }

    void setLargeText(boolean large) {
        prefs.edit().putBoolean(KEY_LARGE_TEXT, large).apply();
    }

    /** Ids of the recommendation channels the user switched on. */
    Set<String> getEnabledChannels() {
        return getSet(KEY_CHANNELS);
    }

    void setEnabledChannels(Set<String> ids) {
        prefs.edit().putStringSet(KEY_CHANNELS, new HashSet<>(ids)).apply();
    }

    /** @param key 0 red, 1 green, 2 yellow, 3 blue */
    int getColorKeyAction(int key) {
        return prefs.getInt(KEY_COLOR_KEY + key, COLOR_KEY_DEFAULTS[key]);
    }

    void setColorKeyAction(int key, int action) {
        prefs.edit().putInt(KEY_COLOR_KEY + key, action).apply();
    }

    boolean wasTvPermissionAsked() {
        return prefs.getBoolean(KEY_TV_PERMISSION_ASKED, false);
    }

    void setTvPermissionAsked() {
        prefs.edit().putBoolean(KEY_TV_PERMISSION_ASKED, true).apply();
    }
}
