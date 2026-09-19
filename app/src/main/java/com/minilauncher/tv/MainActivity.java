package com.minilauncher.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements QuickActions.Host {
    private static final long RELOAD_DEBOUNCE_MS = 400;
    private static final float FOCUS_SCALE = 1.08f;
    private static final int FOCUS_ANIM_MS = 130;
    private static final int REQUEST_TV_LISTINGS = 1;
    private static final int REQUEST_STORAGE = 2;

    private AppRepository repository;
    private WatchNextRepository tv;
    private IconLoader icons;
    private NetworkMonitor network;
    private ExecutorService io;
    private LayoutInflater inflater;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DecelerateInterpolator interpolator = new DecelerateInterpolator();

    private ScrollView scroll;
    private LinearLayout sections;
    private TextView greeting;

    private List<AppInfo> apps = Collections.emptyList();
    private final Map<String, AppInfo> appsByPackage = new HashMap<>();
    private List<WatchNextItem> watchItems = Collections.emptyList();
    private List<WatchNextRepository.ChannelRow> channelRows = Collections.emptyList();
    private String tvSignature = "";
    private final Map<String, View> cards = new HashMap<>();
    private boolean showHidden;
    private boolean firstRender = true;
    private boolean largeText;
    private int colorPrimary;
    private int colorSecondary;
    private float cardRadius;
    private float focusLift;
    private int cellMinWidth;
    private boolean nightDim;
    /** Multiplies banners by a warm grey: darker and less blue, like a dimmed lamp. */
    private final ColorFilter nightFilter = new PorterDuffColorFilter(0xFFB9AC9A, PorterDuff.Mode.MULTIPLY);
    /** Package we just launched; on return, focus lands on its "continue watching" card if any. */
    private String pendingReturnPackage;
    private AppInfo pendingImageApp;

    private static final long TV_RELOAD_DEBOUNCE_MS = 600;
    private static final Uri TV_PROVIDER_ROOT = Uri.parse("content://android.media.tv/");

    private final Runnable reloadRunnable = this::reload;
    private final Runnable tvReloadRunnable = this::loadTvContent;
    /**
     * Video apps write their "watch next" position a few seconds after the player closes, i.e.
     * after we are already back on screen. TvProvider notifies observers on every change, so
     * we listen while visible instead of polling.
     */
    private final ContentObserver tvObserver = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            handler.removeCallbacks(tvReloadRunnable);
            handler.postDelayed(tvReloadRunnable, TV_RELOAD_DEBOUNCE_MS);
        }
    };
    private boolean tvObserverRegistered;
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Installs arrive as a burst of ADDED/REPLACED/CHANGED; collapse them into one reload.
            handler.removeCallbacks(reloadRunnable);
            handler.postDelayed(reloadRunnable, RELOAD_DEBOUNCE_MS);
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        repository = new AppRepository(this);
        tv = new WatchNextRepository(this, repository.prefs());
        icons = new IconLoader(this);
        io = Executors.newSingleThreadExecutor(r -> new Thread(r, "launcher-io"));
        inflater = getLayoutInflater();
        colorPrimary = getResources().getColor(R.color.text_primary);
        colorSecondary = getResources().getColor(R.color.text_secondary);
        cardRadius = getResources().getDimension(R.dimen.card_radius);
        focusLift = 10 * getResources().getDisplayMetrics().density;
        cellMinWidth = cellWidthFor(repository.getCardSize());
        largeText = repository.isLargeText();

        scroll = findViewById(R.id.scroll);
        sections = findViewById(R.id.sections);
        greeting = findViewById(R.id.greeting);
        network = new NetworkMonitor(this, findViewById(R.id.net_icon), findViewById(R.id.net_text));

        LinearLayout quick = findViewById(R.id.quick_actions);
        QuickActions.build(this, quick, this);
        for (int i = 0; i < quick.getChildCount(); i++) setupQuickAction(quick.getChildAt(i));

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, filter);
        }

        // One soft fade instead of the window popping in; runs once, on the GPU.
        View content = findViewById(android.R.id.content);
        content.setAlpha(0f);
        content.animate().alpha(1f).setDuration(220).start();

        reload();
        maybeRequestTvPermission();
    }

    @Override
    protected void onStart() {
        super.onStart();
        network.start();
        updateGreeting();
        updateNightMode();
        loadTvContent();
        if (tv.hasPermission() && !tvObserverRegistered) {
            try {
                getContentResolver().registerContentObserver(TV_PROVIDER_ROOT, true, tvObserver);
                tvObserverRegistered = true;
            } catch (Exception ignored) {
                // no TvProvider on this build; onStart refreshes are all we get
            }
        }
    }

    @Override
    protected void onStop() {
        network.stop();
        if (tvObserverRegistered) {
            getContentResolver().unregisterContentObserver(tvObserver);
            tvObserverRegistered = false;
        }
        handler.removeCallbacks(tvReloadRunnable);
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME pressed while already on the home screen: jump back to the top.
        pendingReturnPackage = null;
        scroll.smoothScrollTo(0, 0);
        focusFirstCard();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // The home screen is the bottom of the stack; BACK does nothing here.
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU:
                showLauncherMenu();
                return true;
            case KeyEvent.KEYCODE_PROG_RED:
                return runColorKey(0);
            case KeyEvent.KEYCODE_PROG_GREEN:
                return runColorKey(1);
            case KeyEvent.KEYCODE_PROG_YELLOW:
                return runColorKey(2);
            case KeyEvent.KEYCODE_PROG_BLUE:
                return runColorKey(3);
        }
        if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9) {
            return launchFavorite(keyCode - KeyEvent.KEYCODE_1);
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_TV_LISTINGS) loadTvContent();
        if (requestCode == REQUEST_STORAGE && pendingImageApp != null) {
            AppInfo app = pendingImageApp;
            pendingImageApp = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) pickImage(app);
        }
    }

    private void maybeRequestTvPermission() {
        if (!WatchNextRepository.supported() || tv.hasPermission()) return;
        if (repository.wasTvPermissionAsked() || repository.isWatchNextHidden()) return;
        repository.setTvPermissionAsked();
        requestTvPermission();
    }

    private void requestTvPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{WatchNextRepository.PERMISSION}, REQUEST_TV_LISTINGS);
        }
    }

    // ---------------------------------------------------------------- data

    private void reload() {
        io.execute(() -> {
            final List<AppInfo> loaded = repository.loadApps();
            Set<String> keys = new HashSet<>(loaded.size() * 2);
            for (AppInfo app : loaded) keys.add(icons.appKey(app));
            icons.pruneApps(keys);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                apps = loaded;
                appsByPackage.clear();
                for (AppInfo app : loaded) appsByPackage.put(app.packageName, app);
                render();
            });
        });
    }

    /** Watch-next row plus the enabled recommendation channels; re-renders only on change. */
    private void loadTvContent() {
        if (!tv.hasPermission()) {
            if (!watchItems.isEmpty() || !channelRows.isEmpty()) {
                watchItems = Collections.emptyList();
                channelRows = Collections.emptyList();
                tvSignature = "";
                render();
            }
            pendingReturnPackage = null;
            return;
        }
        final boolean wantWatchNext = !repository.isWatchNextHidden();
        final Set<String> enabledChannels = repository.getEnabledChannels();
        io.execute(() -> {
            final List<WatchNextItem> items = wantWatchNext ? tv.load() : Collections.<WatchNextItem>emptyList();
            final List<WatchNextRepository.ChannelRow> rows = tv.loadEnabledChannelRows(enabledChannels);
            StringBuilder sb = new StringBuilder();
            Set<String> keys = new HashSet<>();
            for (WatchNextItem item : items) {
                sb.append(item.signature()).append('|');
                if (item.posterUri != null) keys.add(IconLoader.posterKey(item));
            }
            for (WatchNextRepository.ChannelRow row : rows) {
                sb.append('#').append(row.channel.id);
                for (WatchNextItem item : row.items) {
                    sb.append(item.id).append(',');
                    if (item.posterUri != null) keys.add(IconLoader.posterKey(item));
                }
            }
            final String signature = sb.toString();
            icons.prunePosters(keys);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (!signature.equals(tvSignature)) {
                    watchItems = items;
                    channelRows = rows;
                    tvSignature = signature;
                    render();
                }
                focusReturnCard();
            });
        });
    }

    /** Back from a player: put the focus on that app's "continue watching" card, if it has one. */
    private void focusReturnCard() {
        String pkg = pendingReturnPackage;
        pendingReturnPackage = null;
        if (pkg == null) return;
        for (WatchNextItem item : watchItems) {
            if (pkg.equals(item.packageName)) {
                View card = cards.get("wn:" + item.id);
                if (card != null) {
                    card.requestFocus();
                    scroll.smoothScrollTo(0, 0);
                }
                return;
            }
        }
    }

    // ---------------------------------------------------------------- top bar

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour >= 5 && hour < 12) res = R.string.greeting_morning;
        else if (hour < 17) res = R.string.greeting_day;
        else if (hour < 23) res = R.string.greeting_evening;
        else res = R.string.greeting_night;
        greeting.setText(res);
    }

    /** Decided once per return to the home screen; no timers involved. */
    private void updateNightMode() {
        int mode = repository.getNightMode();
        boolean dim;
        if (mode == AppRepository.NIGHT_ON) {
            dim = true;
        } else if (mode == AppRepository.NIGHT_OFF) {
            dim = false;
        } else {
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            dim = hour >= 20 || hour < 7;
        }
        if (dim == nightDim) return;
        nightDim = dim;
        for (int i = 0; i < sections.getChildCount(); i++) {
            View child = sections.getChildAt(i);
            if (!(child instanceof AppGridView)) continue;
            AppGridView grid = (AppGridView) child;
            for (int j = 0; j < grid.getChildCount(); j++) {
                View item = grid.getChildAt(j);
                applyNight(item.findViewById(R.id.banner), item.hasFocus());
            }
        }
    }

    private void applyNight(ImageView banner, boolean focused) {
        // The focused card is shown at full brightness so you still see what you are choosing.
        banner.setColorFilter(nightDim && !focused ? nightFilter : null);
    }

    // ---------------------------------------------------------------- ui

    private void render() {
        String focusedKey = null;
        View focused = getCurrentFocus();
        if (focused != null && focused.getTag() instanceof String) {
            focusedKey = (String) focused.getTag();
        }

        sections.removeAllViews();
        cards.clear();

        List<AppInfo> visible = new ArrayList<>();
        List<AppInfo> hidden = new ArrayList<>();
        for (AppInfo app : apps) (app.hidden ? hidden : visible).add(app);
        List<AppInfo> favorites = new ArrayList<>();
        for (String pkg : repository.getFavorites()) {
            AppInfo app = appsByPackage.get(pkg);
            if (app != null && !app.hidden) favorites.add(app);
        }

        // Order: what you were watching, what you open most, then what apps suggest, then the rest.
        if (!watchItems.isEmpty()) addMediaSection(getString(R.string.continue_watching), watchItems, "wn:");
        if (!favorites.isEmpty()) addSection(R.string.favorites, favorites, false, true);
        for (WatchNextRepository.ChannelRow row : channelRows) {
            AppInfo source = appsByPackage.get(row.channel.packageName);
            String title = (source != null ? source.label : row.channel.packageName) + " · " + row.channel.name;
            addMediaSection(title, row.items, "ch" + row.channel.id + ":");
        }
        if (!visible.isEmpty()) addSection(R.string.all_apps, visible, false, false);
        if (showHidden && !hidden.isEmpty()) addSection(R.string.hidden_apps, hidden, true, false);

        if (sections.getChildCount() == 0) {
            TextView empty = (TextView) inflater.inflate(R.layout.section_header, sections, false);
            empty.setText(R.string.no_apps);
            sections.addView(empty);
        }

        View restore = focusedKey != null ? cards.get(focusedKey) : null;
        if (restore != null) {
            restore.requestFocus();
        } else if (firstRender || getCurrentFocus() == null || getCurrentFocus() == scroll) {
            // On the very first render the window has already given focus to a quick action
            // button (the only focusable views at that point); the first card is the right start.
            focusFirstCard();
        }
        firstRender = false;
    }

    private AppGridView newGrid() {
        AppGridView grid = new AppGridView(this);
        grid.setCellMinWidth(cellMinWidth);
        return grid;
    }

    private void addHeader(CharSequence title) {
        TextView header = (TextView) inflater.inflate(R.layout.section_header, sections, false);
        header.setText(title);
        sections.addView(header);
    }

    private void addSection(int titleRes, List<AppInfo> list, boolean dimmed, boolean numbered) {
        addHeader(getString(titleRes));
        AppGridView grid = newGrid();
        if (dimmed) grid.setAlpha(0.55f);
        int index = 0;
        for (AppInfo app : list) {
            View item = inflater.inflate(R.layout.item_app, grid, false);
            bindApp(item, app);
            if (numbered && index < 9) {
                TextView badge = item.findViewById(R.id.badge);
                badge.setText(String.valueOf(index + 1));
                badge.setVisibility(View.VISIBLE);
            }
            grid.addView(item);
            if (!cards.containsKey(app.packageName)) cards.put(app.packageName, item);
            index++;
        }
        sections.addView(grid);
    }

    private void addMediaSection(CharSequence title, List<WatchNextItem> items, String keyPrefix) {
        addHeader(title);
        AppGridView grid = newGrid();
        for (WatchNextItem item : items) {
            View view = inflater.inflate(R.layout.item_app, grid, false);
            bindMedia(view, item, keyPrefix + item.id);
            grid.addView(view);
            cards.put(keyPrefix + item.id, view);
        }
        sections.addView(grid);
    }

    /** Common card chrome: rounded clip, focus scale/lift, label colour and size. */
    private void bindCard(final View item, String key, String title) {
        final View card = item.findViewById(R.id.card);
        final TextView label = item.findViewById(R.id.label);
        final ImageView banner = item.findViewById(R.id.banner);
        item.setTag(key);
        label.setText(title);
        label.setTextSize(largeText ? 16 : 13);
        ((TextView) item.findViewById(R.id.sublabel)).setTextSize(largeText ? 13 : 11);
        applyNight(banner, false);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cardRadius);
            }
        });
        card.setClipToOutline(true);
        disableDefaultFocusHighlight(item);
        item.setOnFocusChangeListener((v, hasFocus) -> {
            float scale = hasFocus ? FOCUS_SCALE : 1f;
            v.animate().scaleX(scale).scaleY(scale)
                    .setDuration(FOCUS_ANIM_MS).setInterpolator(interpolator).start();
            card.animate().translationZ(hasFocus ? focusLift : 0f)
                    .setDuration(FOCUS_ANIM_MS).setInterpolator(interpolator).start();
            label.setTextColor(hasFocus ? colorPrimary : colorSecondary);
            applyNight(banner, hasFocus);
        });
    }

    private void bindApp(final View item, final AppInfo app) {
        bindCard(item, app.packageName, app.label);
        icons.load(app, item.findViewById(R.id.banner));
        item.setOnClickListener(v -> launch(app));
        item.setOnLongClickListener(v -> {
            showAppMenu(app);
            return true;
        });
    }

    private void bindMedia(final View item, final WatchNextItem wn, String key) {
        bindCard(item, key, wn.title);
        final AppInfo source = appsByPackage.get(wn.packageName);
        icons.loadPoster(wn, source, item.findViewById(R.id.banner));

        TextView label = item.findViewById(R.id.label);
        label.setSingleLine(false);
        label.setMaxLines(2);

        TextView sublabel = item.findViewById(R.id.sublabel);
        String appName = source != null ? source.label : wn.packageName;
        long leftMs = wn.duration > 0 && wn.position > 0 ? wn.duration - wn.position : -1;
        sublabel.setText(leftMs > 0 ? getString(R.string.wn_left, appName, formatMinutes(leftMs)) : appName);
        sublabel.setVisibility(View.VISIBLE);

        float progress = wn.progress();
        if (progress > 0f) {
            LinearLayout bar = item.findViewById(R.id.progress);
            int h = LinearLayout.LayoutParams.MATCH_PARENT;
            bar.getChildAt(0).setLayoutParams(new LinearLayout.LayoutParams(0, h, progress));
            bar.getChildAt(1).setLayoutParams(new LinearLayout.LayoutParams(0, h, 1f - progress));
            bar.setVisibility(View.VISIBLE);
        }

        item.setOnClickListener(v -> launchMedia(wn));
        item.setOnLongClickListener(v -> {
            showMediaMenu(wn, source);
            return true;
        });
    }

    private String formatMinutes(long ms) {
        int minutes = (int) Math.max(1, (ms + 30_000) / 60_000);
        return minutes >= 60
                ? getString(R.string.time_h_m, minutes / 60, minutes % 60)
                : getString(R.string.time_m, minutes);
    }

    private void setupQuickAction(final View item) {
        disableDefaultFocusHighlight(item);
        final TextView caption = item.findViewById(R.id.caption);
        item.setOnFocusChangeListener((v, hasFocus) -> {
            caption.setTextColor(hasFocus ? colorPrimary : colorSecondary);
            float scale = hasFocus ? 1.1f : 1f;
            v.animate().scaleX(scale).scaleY(scale).setDuration(FOCUS_ANIM_MS).start();
        });
    }

    private void focusFirstCard() {
        for (int i = 0; i < sections.getChildCount(); i++) {
            View child = sections.getChildAt(i);
            if (child instanceof AppGridView && ((AppGridView) child).getChildCount() > 0) {
                ((AppGridView) child).getChildAt(0).requestFocus();
                return;
            }
        }
    }

    private static void disableDefaultFocusHighlight(View view) {
        // We draw our own focus stroke; the framework's ripple-like highlight would double it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.setDefaultFocusHighlightEnabled(false);
        }
    }

    private int cellWidthFor(int size) {
        float density = getResources().getDisplayMetrics().density;
        switch (size) {
            case AppRepository.SIZE_COMPACT: return Math.round(136 * density);
            case AppRepository.SIZE_LARGE: return Math.round(212 * density);
            default: return getResources().getDimensionPixelSize(R.dimen.cell_min_width);
        }
    }

    // ---------------------------------------------------------------- actions

    private void launch(AppInfo app) {
        repository.setLastApp(app.packageName);
        pendingReturnPackage = app.packageName;
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(app.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (tryStart(intent)) return;
        // Component may have been disabled or removed after the list was built; fall back.
        Intent fallback = getPackageManager().getLeanbackLaunchIntentForPackage(app.packageName);
        if (fallback == null) fallback = getPackageManager().getLaunchIntentForPackage(app.packageName);
        if (fallback == null || !tryStart(fallback)) {
            pendingReturnPackage = null;
            Toast.makeText(this, R.string.err_launch, Toast.LENGTH_SHORT).show();
        }
    }

    private void launchMedia(WatchNextItem wn) {
        try {
            Intent intent = Intent.parseUri(wn.intentUri, Intent.URI_INTENT_SCHEME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (tryStart(intent)) {
                repository.setLastApp(wn.packageName);
                pendingReturnPackage = wn.packageName;
                return;
            }
        } catch (Exception ignored) {
            // malformed intent URI, open the app instead
        }
        AppInfo app = appsByPackage.get(wn.packageName);
        if (app != null) launch(app);
        else Toast.makeText(this, R.string.err_launch, Toast.LENGTH_SHORT).show();
    }

    private boolean launchFavorite(int index) {
        int i = 0;
        for (String pkg : repository.getFavorites()) {
            AppInfo app = appsByPackage.get(pkg);
            if (app == null || app.hidden) continue;
            if (i == index) {
                launch(app);
                return true;
            }
            i++;
        }
        return false;
    }

    private boolean runColorKey(int key) {
        switch (repository.getColorKeyAction(key)) {
            case AppRepository.ACT_LAST_APP: {
                String last = repository.getLastApp();
                AppInfo app = last != null ? appsByPackage.get(last) : null;
                if (app != null) launch(app);
                else Toast.makeText(this, R.string.no_last_app, Toast.LENGTH_SHORT).show();
                return true;
            }
            case AppRepository.ACT_WIFI:
                open(new Intent(Settings.ACTION_WIFI_SETTINGS));
                return true;
            case AppRepository.ACT_BLUETOOTH:
                open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                return true;
            case AppRepository.ACT_SOUND:
                open(new Intent(Settings.ACTION_SOUND_SETTINGS));
                return true;
            case AppRepository.ACT_SETTINGS:
                open(new Intent(Settings.ACTION_SETTINGS));
                return true;
            case AppRepository.ACT_NIGHT:
                repository.setNightMode(nightDim ? AppRepository.NIGHT_OFF : AppRepository.NIGHT_ON);
                updateNightMode();
                return true;
            default:
                return false;
        }
    }

    private boolean tryStart(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void open(Intent intent) {
        if (!tryStart(intent)) Toast.makeText(this, R.string.err_launch, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- custom images

    private void pickImage(final AppInfo app) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? "android.permission.READ_MEDIA_IMAGES" : "android.permission.READ_EXTERNAL_STORAGE";
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                pendingImageApp = app;
                requestPermissions(new String[]{permission}, REQUEST_STORAGE);
                return;
            }
        }
        new ImagePicker(this, file -> importImage(app, file)).show();
    }

    private void importImage(final AppInfo app, final File file) {
        io.execute(() -> {
            final boolean ok = icons.importCustom(app.packageName, file);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (ok) reload();
                else Toast.makeText(this, R.string.picker_failed, Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ---------------------------------------------------------------- menus

    private void showAppMenu(final AppInfo app) {
        final List<CharSequence> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        items.add(getString(R.string.app_open));
        actions.add(() -> launch(app));

        items.add(getString(app.favorite ? R.string.app_favorite_remove : R.string.app_favorite_add));
        actions.add(() -> {
            app.favorite = !app.favorite;
            repository.setFavorite(app.packageName, app.favorite);
            render();
        });
        if (app.favorite) {
            items.add(getString(R.string.app_move_left));
            actions.add(() -> { if (repository.moveFavorite(app.packageName, -1)) render(); });
            items.add(getString(R.string.app_move_right));
            actions.add(() -> { if (repository.moveFavorite(app.packageName, 1)) render(); });
        }

        items.add(getString(R.string.app_custom_image));
        actions.add(() -> pickImage(app));
        if (icons.hasCustom(app.packageName)) {
            items.add(getString(R.string.app_custom_image_remove));
            actions.add(() -> {
                icons.removeCustom(app.packageName);
                reload();
            });
        }

        items.add(getString(app.hidden ? R.string.app_unhide : R.string.app_hide));
        actions.add(() -> {
            app.hidden = !app.hidden;
            repository.setHidden(app.packageName, app.hidden);
            render();
        });

        items.add(getString(R.string.app_info));
        actions.add(() -> tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + app.packageName))));

        items.add(getString(R.string.app_uninstall));
        actions.add(() -> tryStart(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.packageName))));

        new AlertDialog.Builder(this)
                .setTitle(app.label)
                .setItems(items.toArray(new CharSequence[0]), (dialog, which) -> actions.get(which).run())
                .show();
    }

    private void showMediaMenu(final WatchNextItem wn, final AppInfo source) {
        String appName = source != null ? source.label : wn.packageName;
        CharSequence[] items = {
                getString(R.string.app_open),
                getString(R.string.wn_open_app, appName),
                getString(R.string.wn_remove)
        };
        new AlertDialog.Builder(this)
                .setTitle(wn.title)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            launchMedia(wn);
                            break;
                        case 1:
                            if (source != null) launch(source);
                            break;
                        case 2:
                            tv.dismiss(wn.id);
                            tvSignature = "";
                            loadTvContent();
                            break;
                    }
                })
                .show();
    }

    private void showLauncherMenu() {
        final List<CharSequence> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        items.add(getString(showHidden ? R.string.menu_hide_hidden : R.string.menu_show_hidden));
        actions.add(() -> {
            showHidden = !showHidden;
            render();
        });

        items.add(getString(R.string.menu_card_size));
        actions.add(this::showCardSizeMenu);

        items.add(getString(R.string.menu_large_text) + (largeText ? "  ✓" : ""));
        actions.add(() -> {
            largeText = !largeText;
            repository.setLargeText(largeText);
            render();
        });

        items.add(getString(R.string.menu_night_mode));
        actions.add(this::showNightModeMenu);

        items.add(getString(R.string.menu_color_keys));
        actions.add(this::showColorKeysMenu);

        if (WatchNextRepository.supported()) {
            if (!tv.hasPermission()) {
                items.add(getString(R.string.menu_watch_next_enable));
                actions.add(() -> {
                    repository.setWatchNextHidden(false);
                    requestTvPermission();
                });
            } else {
                final boolean hiddenNow = repository.isWatchNextHidden();
                items.add(getString(hiddenNow ? R.string.menu_watch_next_show : R.string.menu_watch_next_hide));
                actions.add(() -> {
                    repository.setWatchNextHidden(!hiddenNow);
                    tvSignature = "";
                    loadTvContent();
                });
                items.add(getString(R.string.menu_channels));
                actions.add(this::showChannelsMenu);
            }
        }

        items.add(getString(R.string.menu_default_home));
        actions.add(this::requestDefaultHome);

        items.add(getString(R.string.menu_system_settings));
        actions.add(() -> tryStart(new Intent(Settings.ACTION_SETTINGS)));

        items.add(getString(R.string.menu_about));
        actions.add(() -> new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.about_text)
                .setPositiveButton(android.R.string.ok, null)
                .show());

        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setItems(items.toArray(new CharSequence[0]), (dialog, which) -> actions.get(which).run())
                .show();
    }

    private void showCardSizeMenu() {
        CharSequence[] items = {
                getString(R.string.size_compact),
                getString(R.string.size_normal),
                getString(R.string.size_large)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_card_size)
                .setSingleChoiceItems(items, repository.getCardSize(), (dialog, which) -> {
                    dialog.dismiss();
                    repository.setCardSize(which);
                    cellMinWidth = cellWidthFor(which);
                    for (int i = 0; i < sections.getChildCount(); i++) {
                        View child = sections.getChildAt(i);
                        if (child instanceof AppGridView) ((AppGridView) child).setCellMinWidth(cellMinWidth);
                    }
                })
                .show();
    }

    private void showNightModeMenu() {
        CharSequence[] items = {
                getString(R.string.night_auto),
                getString(R.string.night_on),
                getString(R.string.night_off)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_night_mode)
                .setSingleChoiceItems(items, repository.getNightMode(), (dialog, which) -> {
                    dialog.dismiss();
                    repository.setNightMode(which);
                    updateNightMode();
                })
                .show();
    }

    private CharSequence[] colorActionNames() {
        return new CharSequence[]{
                getString(R.string.act_none),
                getString(R.string.act_last_app),
                getString(R.string.qa_wifi),
                getString(R.string.qa_bluetooth),
                getString(R.string.qa_sound),
                getString(R.string.qa_settings),
                getString(R.string.act_night)
        };
    }

    private void showColorKeysMenu() {
        final int[] keyNames = {R.string.key_red, R.string.key_green, R.string.key_yellow, R.string.key_blue};
        final CharSequence[] actionNames = colorActionNames();
        CharSequence[] items = new CharSequence[keyNames.length];
        for (int i = 0; i < keyNames.length; i++) {
            items[i] = getString(keyNames[i]) + " — " + actionNames[repository.getColorKeyAction(i)];
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_color_keys)
                .setItems(items, (dialog, key) -> new AlertDialog.Builder(this)
                        .setTitle(keyNames[key])
                        .setSingleChoiceItems(actionNames, repository.getColorKeyAction(key), (d, action) -> {
                            d.dismiss();
                            repository.setColorKeyAction(key, action);
                        })
                        .show())
                .show();
    }

    private void showChannelsMenu() {
        io.execute(() -> {
            final List<WatchNextRepository.Channel> channels = tv.loadChannelsWithContent();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (channels.isEmpty()) {
                    Toast.makeText(this, R.string.channels_none, Toast.LENGTH_LONG).show();
                    return;
                }
                final Set<String> enabled = new HashSet<>(repository.getEnabledChannels());
                CharSequence[] names = new CharSequence[channels.size()];
                boolean[] checked = new boolean[channels.size()];
                for (int i = 0; i < channels.size(); i++) {
                    WatchNextRepository.Channel ch = channels.get(i);
                    AppInfo source = appsByPackage.get(ch.packageName);
                    names[i] = (source != null ? source.label : ch.packageName) + " · " + ch.name;
                    checked[i] = enabled.contains(String.valueOf(ch.id));
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.menu_channels)
                        .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> {
                            String id = String.valueOf(channels.get(which).id);
                            if (isChecked) enabled.add(id); else enabled.remove(id);
                        })
                        .setPositiveButton(android.R.string.ok, (dialog, w) -> {
                            repository.setEnabledChannels(enabled);
                            tvSignature = "";
                            loadTvContent();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    private void requestDefaultHome() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roles = getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                if (tryStart(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))) return;
            }
        }
        if (!tryStart(new Intent(Settings.ACTION_HOME_SETTINGS))) {
            Toast.makeText(this, R.string.err_no_home_settings, Toast.LENGTH_LONG).show();
        }
    }
}
