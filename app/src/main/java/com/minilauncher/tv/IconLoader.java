package com.minilauncher.tv;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Renders every app to one small RGB_565 tile (320x180, ~112 KB) on a single low-priority
 * thread, keeps the tiles in a bounded memory cache and mirrors them as raw pixel files in the
 * app cache dir so a cold start never re-decodes the original (often huge) banners.
 */
final class IconLoader {
    static final int TILE_W = 320;
    static final int TILE_H = 180;
    private static final int TILE_BYTES = TILE_W * TILE_H * 2;
    private static final int ICON_SIZE = 100;
    /** Bump when the tile look changes so cached tiles on disk are re-rendered. */
    private static final String TILE_DIR = "tiles-v5";
    private static final int BLUR_W = 6;
    private static final int BLUR_H = 4;
    private static final int BLUR_MID_W = 24;
    private static final int BLUR_MID_H = 14;
    private static final int MEM_CACHE_BYTES = 12 * 1024 * 1024;

    private final PackageManager pm;
    private final ContentResolver resolver;
    private final File diskDir;
    /** User-chosen tile images, kept in files/ (not cache/) so the system never evicts them. */
    private final File customDir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "icon-loader");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(MEM_CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    IconLoader(Context context) {
        pm = context.getApplicationContext().getPackageManager();
        resolver = context.getApplicationContext().getContentResolver();
        diskDir = new File(context.getCacheDir(), TILE_DIR);
        //noinspection ResultOfMethodCallIgnored
        diskDir.mkdirs();
        customDir = new File(context.getFilesDir(), "custom");
        //noinspection ResultOfMethodCallIgnored
        customDir.mkdirs();
        deleteLegacyDir(new File(context.getCacheDir(), "tiles"));
    }

    private void deleteLegacyDir(final File dir) {
        if (!dir.isDirectory()) return;
        executor.execute(() -> {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
                f.delete();
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        });
    }

    void load(final AppInfo app, final ImageView target) {
        load(appKey(app), target, () -> render(app), null);
    }

    /** Cache key of an app tile; changes when the app updates or the user swaps its image. */
    String appKey(AppInfo app) {
        File custom = customFile(app.packageName);
        return custom.exists() ? "c!" + app.packageName + "_" + custom.lastModified() : app.cacheKey();
    }

    // ---------------------------------------------------------------- custom images

    File customFile(String packageName) {
        return new File(customDir, packageName + ".jpg");
    }

    boolean hasCustom(String packageName) {
        return customFile(packageName).exists();
    }

    /**
     * Blocking. Re-encodes the picked file at a modest size (it may be a 20 MP photo from a
     * camera) into our own storage; the USB drive can be removed afterwards.
     */
    boolean importCustom(String packageName, File source) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getPath(), opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return false;
        int sample = 1;
        while (opts.outWidth / (sample * 2) >= TILE_W * 2 && opts.outHeight / (sample * 2) >= TILE_H * 2) sample *= 2;
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(source.getPath(), opts);
        if (bitmap == null) return false;
        File tmp = new File(customDir, packageName + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        } finally {
            bitmap.recycle();
        }
        return tmp.renameTo(customFile(packageName));
    }

    void removeCustom(String packageName) {
        //noinspection ResultOfMethodCallIgnored
        customFile(packageName).delete();
    }

    /**
     * Poster for a "continue watching" item. Downloaded once, composed into the same 320x180
     * tile format and cached like an app icon; {@code fallback} is drawn when the URL fails.
     */
    void loadPoster(final WatchNextItem item, final AppInfo fallback, final ImageView target) {
        if (item.posterUri == null || item.posterUri.isEmpty()) {
            if (fallback != null) load(fallback, target);
            return;
        }
        // A failed download must not be cached as if it were the poster: the fallback tile is
        // kept in memory only, so the next launch tries the network again.
        load(posterKey(item), target, () -> {
            Bitmap poster = fetchPoster(item.posterUri);
            return poster != null ? renderPoster(poster) : null;
        }, () -> fallback != null ? render(fallback) : blankTile());
    }

    /** '!' can't appear in a package name, so poster keys never collide with app keys. */
    static String posterKey(WatchNextItem item) {
        return POSTER_PREFIX + hash(item.posterUri);
    }

    private static final String POSTER_PREFIX = "p!";

    private interface Renderer {
        /** Null means "not available right now"; the fallback is shown and nothing is cached. */
        Bitmap render();
    }

    private void load(final String key, final ImageView target, final Renderer renderer, final Renderer fallback) {
        Bitmap cached = memory.get(key);
        target.setTag(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            target.setAlpha(1f);
            return;
        }
        target.setImageDrawable(null);
        executor.execute(() -> {
            Bitmap bitmap = readDisk(key);
            if (bitmap == null) {
                bitmap = renderer.render();
                if (bitmap != null) {
                    writeDisk(key, bitmap);
                } else {
                    bitmap = fallback != null ? fallback.render() : blankTile();
                }
            }
            memory.put(key, bitmap);
            final Bitmap result = bitmap;
            main.post(() -> {
                if (!key.equals(target.getTag())) return; // view was re-bound meanwhile
                target.setAlpha(0f);
                target.setImageBitmap(result);
                target.animate().alpha(1f).setDuration(150).start();
            });
        });
    }

    /** Deletes app tiles that belong to apps no longer installed (or updated since). */
    void pruneApps(Set<String> validKeys) {
        prune(validKeys, false);
    }

    /** Deletes poster tiles no longer referenced by the watch-next row. */
    void prunePosters(Set<String> validKeys) {
        prune(validKeys, true);
    }

    private void prune(final Set<String> validKeys, final boolean posters) {
        executor.execute(() -> {
            File[] files = diskDir.listFiles();
            if (files == null) return;
            for (File f : files) {
                String name = f.getName();
                if (!name.endsWith(".565")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    continue;
                }
                String key = name.substring(0, name.length() - 4);
                if (key.startsWith(POSTER_PREFIX) != posters) continue;
                if (!validKeys.contains(key)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        });
    }

    private Bitmap render(AppInfo app) {
        File custom = customFile(app.packageName);
        if (custom.exists()) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap picked = BitmapFactory.decodeFile(custom.getPath(), opts);
            if (picked != null) return renderPoster(picked);
        }

        Bitmap bitmap = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);

        Drawable banner = null;
        if (app.leanback) {
            try {
                ActivityInfo ai = pm.getActivityInfo(app.component, 0);
                banner = ai.loadBanner(pm);
                if (banner == null && ai.applicationInfo != null) banner = ai.applicationInfo.loadBanner(pm);
            } catch (Exception ignored) {
                // fall through to the icon tile
            }
        }

        if (banner != null && banner.getIntrinsicWidth() > 0 && banner.getIntrinsicHeight() > 0) {
            // center-crop the banner into the tile
            int iw = banner.getIntrinsicWidth();
            int ih = banner.getIntrinsicHeight();
            float scale = Math.max((float) TILE_W / iw, (float) TILE_H / ih);
            int dw = Math.round(iw * scale);
            int dh = Math.round(ih * scale);
            int left = (TILE_W - dw) / 2;
            int top = (TILE_H - dh) / 2;
            canvas.drawColor(Color.BLACK);
            banner.setBounds(left, top, left + dw, top + dh);
            banner.draw(canvas);
        } else {
            Drawable icon = null;
            try {
                icon = pm.getActivityIcon(app.component);
            } catch (Exception ignored) {
                // use default icon
            }
            if (icon == null) icon = pm.getDefaultActivityIcon();
            drawBlurredBackdrop(canvas, icon, tileColor(app.packageName));
            canvas.drawColor(0x2EE0A040); // warm the backdrop to match the palette
            canvas.drawColor(0x5A000000); // scrim so the icon stays the hero
            int left = (TILE_W - ICON_SIZE) / 2;
            int top = (TILE_H - ICON_SIZE) / 2;
            icon.setBounds(left, top, left + ICON_SIZE, top + ICON_SIZE);
            icon.draw(canvas);
        }
        return bitmap;
    }

    /**
     * Poor man's blur: draw the icon into a 6x4 bitmap, then upscale it twice with bilinear
     * filtering. Two filtered passes hide the grid a single pass would leave behind, and the
     * whole thing is a few microseconds of work done once per app.
     */
    private static void drawBlurredBackdrop(Canvas canvas, Drawable icon, int baseColor) {
        Bitmap tiny = Bitmap.createBitmap(BLUR_W, BLUR_H, Bitmap.Config.ARGB_8888);
        Canvas tc = new Canvas(tiny);
        tc.drawColor(baseColor);
        // Overscan the icon slightly so its edge pixels don't dominate the corners.
        icon.setBounds(-1, -2, BLUR_W + 1, BLUR_H + 2);
        icon.draw(tc);

        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        Bitmap mid = Bitmap.createBitmap(BLUR_MID_W, BLUR_MID_H, Bitmap.Config.ARGB_8888);
        new Canvas(mid).drawBitmap(tiny, new Rect(0, 0, BLUR_W, BLUR_H),
                new Rect(0, 0, BLUR_MID_W, BLUR_MID_H), paint);
        canvas.drawBitmap(mid, new Rect(0, 0, BLUR_MID_W, BLUR_MID_H),
                new Rect(0, 0, TILE_W, TILE_H), paint);
        tiny.recycle();
        mid.recycle();
    }

    // ---------------------------------------------------------------- posters

    private static final int POSTER_MAX_BYTES = 4 * 1024 * 1024;
    private static final int POSTER_TIMEOUT_MS = 8000;

    private Bitmap fetchPoster(String uri) {
        byte[] data;
        if (uri.startsWith("content://")) {
            // Some apps (RuStore, for one) serve posters through their own ContentProvider.
            data = null;
            try {
                InputStream in = resolver.openInputStream(Uri.parse(uri));
                data = in != null ? readAll(in, POSTER_MAX_BYTES) : null;
            } catch (Exception ignored) {
                // provider not exported to us (grants usually go to the stock launcher only)
            }
            if (data == null) {
                // ...but the provider URI often just wraps a plain web URL: use that instead.
                String wrapped = null;
                try {
                    wrapped = Uri.parse(uri).getQueryParameter("url");
                } catch (Exception ignored) {
                }
                if (wrapped != null && (wrapped.startsWith("http://") || wrapped.startsWith("https://"))) {
                    data = download(wrapped);
                }
            }
        } else if (uri.startsWith("http://") || uri.startsWith("https://")) {
            data = download(uri);
        } else {
            return null;
        }
        return data != null ? decodeToTileSize(data) : null;
    }

    private static byte[] download(String uri) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(uri).openConnection();
            conn.setConnectTimeout(POSTER_TIMEOUT_MS);
            conn.setReadTimeout(POSTER_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            return readAll(conn.getInputStream(), POSTER_MAX_BYTES);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Bitmap decodeToTileSize(byte[] data) {
        try {
            // Decode straight to roughly tile size: a 460x690 poster never needs full resolution.
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= TILE_W && opts.outHeight / (sample * 2) >= TILE_H) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) return null;
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Landscape posters are centre-cropped; portrait ones sit on a blurred copy of themselves. */
    private static Bitmap renderPoster(Bitmap poster) {
        Bitmap bitmap = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);
        int pw = poster.getWidth();
        int ph = poster.getHeight();
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (pw >= ph * 1.2f) {
            float scale = Math.max((float) TILE_W / pw, (float) TILE_H / ph);
            int dw = Math.round(pw * scale);
            int dh = Math.round(ph * scale);
            canvas.drawBitmap(poster, null, new Rect((TILE_W - dw) / 2, (TILE_H - dh) / 2,
                    (TILE_W + dw) / 2, (TILE_H + dh) / 2), paint);
        } else {
            drawBlurredBackdrop(canvas, new BitmapDrawable((android.content.res.Resources) null, poster), Color.BLACK);
            canvas.drawColor(0x40000000);
            int dw = Math.round(pw * (float) TILE_H / ph);
            canvas.drawBitmap(poster, null, new Rect((TILE_W - dw) / 2, 0, (TILE_W + dw) / 2, TILE_H), paint);
        }
        poster.recycle();
        return bitmap;
    }

    private static Bitmap blankTile() {
        Bitmap bitmap = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.RGB_565);
        bitmap.eraseColor(0xFF1F2637);
        return bitmap;
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** A muted, dark hue derived from the package name so icon-only tiles don't all look the same. */
    private static int tileColor(String packageName) {
        // Hues limited to the warm half of the wheel (red..yellow..olive), muted and dark.
        float hue = (packageName.hashCode() & 0xFFFF) % 80;
        return Color.HSVToColor(new float[]{hue, 0.40f, 0.30f});
    }

    private Bitmap readDisk(String key) {
        File file = new File(diskDir, key + ".565");
        if (file.length() != TILE_BYTES) return null;
        byte[] data = new byte[TILE_BYTES];
        try (FileInputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < TILE_BYTES) {
                int n = in.read(data, read, TILE_BYTES - read);
                if (n < 0) return null;
                read += n;
            }
        } catch (IOException e) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.RGB_565);
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(data));
        return bitmap;
    }

    private void writeDisk(String key, Bitmap bitmap) {
        ByteBuffer buffer = ByteBuffer.allocate(TILE_BYTES);
        bitmap.copyPixelsToBuffer(buffer);
        File tmp = new File(diskDir, key + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(buffer.array());
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(new File(diskDir, key + ".565"));
    }
}
