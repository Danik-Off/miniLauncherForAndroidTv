package com.minilauncher.tv;

/** One row of the system "watch next" table (TvProvider), as published by a video app. */
final class WatchNextItem {
    final long id;
    final String packageName;
    final String title;
    final String posterUri;
    final String intentUri;
    final long lastEngagement;
    final long position;
    final long duration;

    WatchNextItem(long id, String packageName, String title, String posterUri, String intentUri,
                  long lastEngagement, long position, long duration) {
        this.id = id;
        this.packageName = packageName;
        this.title = title;
        this.posterUri = posterUri;
        this.intentUri = intentUri;
        this.lastEngagement = lastEngagement;
        this.position = position;
        this.duration = duration;
    }

    /** 0..1 playback progress, or -1 when unknown. */
    float progress() {
        if (duration <= 0 || position < 0) return -1f;
        return Math.min(1f, (float) position / duration);
    }

    /** Stable identity used to decide whether the row changed and needs a re-render. */
    String signature() {
        return id + ":" + lastEngagement + ":" + position;
    }
}
