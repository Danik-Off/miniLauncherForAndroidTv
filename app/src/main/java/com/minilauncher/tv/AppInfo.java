package com.minilauncher.tv;

import android.content.ComponentName;

/** One launchable app. Immutable except for the user flags. */
final class AppInfo {
    final String packageName;
    final ComponentName component;
    final String label;
    /** Resolved through LEANBACK_LAUNCHER, so a 16:9 banner is expected. */
    final boolean leanback;
    /** APK modification time; part of the icon cache key so updated apps get a fresh icon. */
    final long apkModified;

    boolean favorite;
    boolean hidden;

    AppInfo(String packageName, ComponentName component, String label, boolean leanback, long apkModified) {
        this.packageName = packageName;
        this.component = component;
        this.label = label;
        this.leanback = leanback;
        this.apkModified = apkModified;
    }

    String cacheKey() {
        return packageName + "_" + apkModified;
    }
}
