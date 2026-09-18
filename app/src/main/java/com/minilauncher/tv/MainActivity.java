package com.minilauncher.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Outline;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
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

    private AppRepository repository;
    private WatchNextRepository watchNext;
    private IconLoader icons;
    private NetworkMonitor network;
    private ExecutorService io;
    private LayoutInflater inflater;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DecelerateInterpolator interpolator = new DecelerateInterpolator();

    private ScrollView scroll;
    private LinearLayout sections;

    private List<AppInfo> apps = Collections.emptyList();
    private final Map<String, AppInfo> appsByPackage = new HashMap<>();
    private List<WatchNextItem> watchItems = Collections.emptyList();
    private String watchSignature = "";
    private final Map<String, View> cards = new HashMap<>();
    private boolean showHidden;
    private boolean firstRender = true;
    private int colorPrimary;
    private int colorSecondary;
    private float cardRadius;
    private float focusLift;
    private int cellMinWidth;

    private final Runnable reloadRunnable = this::reload;
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
        watchNext = new WatchNextRepository(this, repository.prefs());
        icons = new IconLoader(this);
        io = Executors.newSingleThreadExecutor(r -> new Thread(r, "launcher-io"));
        inflater = getLayoutInflater();
        colorPrimary = getResources().getColor(R.color.text_primary);
        colorSecondary = getResources().getColor(R.color.text_secondary);
        cardRadius = getResources().getDimension(R.dimen.card_radius);
        focusLift = 10 * getResources().getDisplayMetrics().density;
        cellMinWidth = cellWidthFor(repository.getCardSize());

        scroll = findViewById(R.id.scroll);
        sections = findViewById(R.id.sections);
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

        reload();
        maybeRequestTvPermission();
    }

    @Override
    protected void onStart() {
        super.onStart();
        network.start();
        loadWatchNext();
    }

    @Override
    protected void onStop() {
        network.stop();
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME pressed while already on the home screen: jump back to the top.
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
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showLauncherMenu();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_TV_LISTINGS) loadWatchNext();
    }

    private void maybeRequestTvPermission() {
        if (!WatchNextRepository.supported() || watchNext.hasPermission()) return;
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
            for (AppInfo app : loaded) keys.add(app.cacheKey());
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

    private void loadWatchNext() {
        if (repository.isWatchNextHidden() || !watchNext.hasPermission()) {
            if (!watchItems.isEmpty()) {
                watchItems = Collections.emptyList();
                watchSignature = "";
                render();
            }
            return;
        }
        io.execute(() -> {
            final List<WatchNextItem> items = watchNext.load();
            StringBuilder sb = new StringBuilder();
            Set<String> keys = new HashSet<>();
            for (WatchNextItem item : items) {
                sb.append(item.signature()).append('|');
                if (item.posterUri != null) keys.add(IconLoader.posterKey(item));
            }
            final String signature = sb.toString();
            icons.prunePosters(keys);
            runOnUiThread(() -> {
                if (isFinishing() || signature.equals(watchSignature)) return;
                watchItems = items;
                watchSignature = signature;
                render();
            });
        });
    }

    // ---------------------------------------------------------------- ui

    private void render() {
        String focusedKey = null;
        View focused = getCurrentFocus();
        if (focused != null && focused.getTag() instanceof AppInfo) {
            focusedKey = ((AppInfo) focused.getTag()).packageName;
        } else if (focused != null && focused.getTag() instanceof WatchNextItem) {
            focusedKey = "wn:" + ((WatchNextItem) focused.getTag()).id;
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

        if (!watchItems.isEmpty()) addWatchNextSection();
        if (!favorites.isEmpty()) addSection(R.string.favorites, favorites, false);
        if (!visible.isEmpty()) addSection(R.string.all_apps, visible, false);
        if (showHidden && !hidden.isEmpty()) addSection(R.string.hidden_apps, hidden, true);

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

    private void addHeader(int titleRes) {
        TextView header = (TextView) inflater.inflate(R.layout.section_header, sections, false);
        header.setText(titleRes);
        sections.addView(header);
    }

    private void addSection(int titleRes, List<AppInfo> list, boolean dimmed) {
        addHeader(titleRes);
        AppGridView grid = newGrid();
        if (dimmed) grid.setAlpha(0.55f);
        for (AppInfo app : list) {
            View item = inflater.inflate(R.layout.item_app, grid, false);
            bindApp(item, app);
            grid.addView(item);
            if (!cards.containsKey(app.packageName)) cards.put(app.packageName, item);
        }
        sections.addView(grid);
    }

    private void addWatchNextSection() {
        addHeader(R.string.continue_watching);
        AppGridView grid = newGrid();
        for (WatchNextItem item : watchItems) {
            View view = inflater.inflate(R.layout.item_app, grid, false);
            bindWatchNext(view, item);
            grid.addView(view);
            cards.put("wn:" + item.id, view);
        }
        sections.addView(grid);
    }

    /** Common card chrome: rounded clip, focus scale/lift, label colour. */
    private void bindCard(final View item, Object tag, String title) {
        final View card = item.findViewById(R.id.card);
        final TextView label = item.findViewById(R.id.label);
        item.setTag(tag);
        label.setText(title);
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
        });
    }

    private void bindApp(final View item, final AppInfo app) {
        bindCard(item, app, app.label);
        icons.load(app, item.findViewById(R.id.banner));
        item.setOnClickListener(v -> launch(app));
        item.setOnLongClickListener(v -> {
            showAppMenu(app);
            return true;
        });
    }

    private void bindWatchNext(final View item, final WatchNextItem wn) {
        bindCard(item, wn, wn.title);
        final AppInfo source = appsByPackage.get(wn.packageName);
        icons.loadPoster(wn, source, item.findViewById(R.id.banner));

        TextView sublabel = item.findViewById(R.id.sublabel);
        sublabel.setText(source != null ? source.label : wn.packageName);
        sublabel.setVisibility(View.VISIBLE);

        float progress = wn.progress();
        if (progress > 0f) {
            LinearLayout bar = item.findViewById(R.id.progress);
            int h = LinearLayout.LayoutParams.MATCH_PARENT;
            bar.getChildAt(0).setLayoutParams(new LinearLayout.LayoutParams(0, h, progress));
            bar.getChildAt(1).setLayoutParams(new LinearLayout.LayoutParams(0, h, 1f - progress));
            bar.setVisibility(View.VISIBLE);
        }

        item.setOnClickListener(v -> launchWatchNext(wn));
        item.setOnLongClickListener(v -> {
            showWatchNextMenu(wn, source);
            return true;
        });
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
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(app.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (tryStart(intent)) return;
        // Component may have been disabled or removed after the list was built; fall back.
        Intent fallback = getPackageManager().getLeanbackLaunchIntentForPackage(app.packageName);
        if (fallback == null) fallback = getPackageManager().getLaunchIntentForPackage(app.packageName);
        if (fallback == null || !tryStart(fallback)) {
            Toast.makeText(this, R.string.err_launch, Toast.LENGTH_SHORT).show();
        }
    }

    private void launchWatchNext(WatchNextItem wn) {
        try {
            Intent intent = Intent.parseUri(wn.intentUri, Intent.URI_INTENT_SCHEME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (tryStart(intent)) return;
        } catch (Exception ignored) {
            // malformed intent URI, open the app instead
        }
        AppInfo app = appsByPackage.get(wn.packageName);
        if (app != null) launch(app);
        else Toast.makeText(this, R.string.err_launch, Toast.LENGTH_SHORT).show();
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

    @Override
    public void powerOff(final QuickActions.PowerAction action) {
        new AlertDialog.Builder(this)
                .setMessage(R.string.power_confirm)
                .setPositiveButton(R.string.qa_power, (d, w) -> {
                    if (!action.run()) {
                        Toast.makeText(this, R.string.power_failed, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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

    private void showWatchNextMenu(final WatchNextItem wn, final AppInfo source) {
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
                            launchWatchNext(wn);
                            break;
                        case 1:
                            if (source != null) launch(source);
                            break;
                        case 2:
                            watchNext.dismiss(wn.id);
                            watchSignature = "";
                            loadWatchNext();
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

        if (WatchNextRepository.supported()) {
            if (!watchNext.hasPermission()) {
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
                    watchSignature = "";
                    loadWatchNext();
                });
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
