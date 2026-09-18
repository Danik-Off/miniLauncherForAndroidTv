package com.minilauncher.tv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

/**
 * The row of round buttons in the top bar. Every entry is a plain Intent; entries the current
 * firmware cannot open are simply not added, so the bar adapts to whatever TV it runs on.
 */
final class QuickActions {
    interface Host {
        void open(Intent intent);
        /** Ask for confirmation, then run {@code action}; it returns false if the TV refused. */
        void powerOff(PowerAction action);
    }

    interface PowerAction {
        boolean run();
    }

    private static final String ACTION_REQUEST_SHUTDOWN = "android.intent.action.ACTION_REQUEST_SHUTDOWN";
    /**
     * Handled by AOSP TvSettings (DaydreamVoiceAction, an invisible activity): puts the TV into
     * standby exactly like the remote's power key. It is what "OK Google, turn off the TV" uses.
     */
    private static final String ACTION_PANO_SLEEP = "com.google.android.pano.action.SLEEP";

    static void build(Context context, ViewGroup container, Host host) {
        PackageManager pm = context.getPackageManager();
        LayoutInflater inflater = LayoutInflater.from(context);
        container.removeAllViews();

        addSettings(pm, inflater, container, host, Settings.ACTION_WIFI_SETTINGS, R.drawable.ic_wifi_3, R.string.qa_wifi);
        addSettings(pm, inflater, container, host, Settings.ACTION_BLUETOOTH_SETTINGS, R.drawable.ic_bluetooth, R.string.qa_bluetooth);
        addSettings(pm, inflater, container, host, Settings.ACTION_SOUND_SETTINGS, R.drawable.ic_sound, R.string.qa_sound);
        if (!addSettings(pm, inflater, container, host, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, R.drawable.ic_apps, R.string.qa_apps)) {
            addSettings(pm, inflater, container, host, Settings.ACTION_APPLICATION_SETTINGS, R.drawable.ic_apps, R.string.qa_apps);
        }
        addSettings(pm, inflater, container, host, Settings.ACTION_SETTINGS, R.drawable.ic_settings, R.string.qa_settings);

        final PowerAction power = findPowerAction(context);
        if (power != null) {
            add(inflater, container, R.drawable.ic_power, R.string.qa_power, v -> host.powerOff(power));
        }
    }

    private static boolean addSettings(PackageManager pm, LayoutInflater inflater, ViewGroup container,
                                       final Host host, String action, int icon, int caption) {
        final Intent intent = new Intent(action);
        if (intent.resolveActivity(pm) == null) return false;
        add(inflater, container, icon, caption, v -> host.open(intent));
        return true;
    }

    private static void add(LayoutInflater inflater, ViewGroup container, int icon, int caption, View.OnClickListener click) {
        View item = inflater.inflate(R.layout.quick_action, container, false);
        ((ImageView) item.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) item.findViewById(R.id.caption)).setText(caption);
        item.setOnClickListener(click);
        container.addView(item);
    }

    /**
     * A normal app can't shut the device down itself, so look for a system activity that will
     * do it for us, in order of preference:
     * 1. a vendor activity handling ACTION_REQUEST_SHUTDOWN without a permission guard
     *    (the framework's own handler in package "android" needs the SHUTDOWN permission);
     * 2. TvSettings' SLEEP activity (standby), present on every Android TV build.
     * Returns null when neither exists; the button is then simply not shown.
     */
    static PowerAction findPowerAction(final Context context) {
        final PackageManager pm = context.getPackageManager();

        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(ACTION_REQUEST_SHUTDOWN), 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || "android".equals(ai.packageName) || !ai.exported) continue;
            if (ai.permission != null && !ai.permission.isEmpty()) continue;
            return startAction(context, new Intent(ACTION_REQUEST_SHUTDOWN)
                    .setClassName(ai.packageName, ai.name)
                    .putExtra("android.intent.extra.KEY_CONFIRM", false));
        }

        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(ACTION_PANO_SLEEP), 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || !ai.exported) continue;
            if (ai.permission != null && !ai.permission.isEmpty()) continue;
            return startAction(context, new Intent(ACTION_PANO_SLEEP).setClassName(ai.packageName, ai.name));
        }
        return null;
    }

    private static PowerAction startAction(final Context context, final Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return () -> {
            try {
                context.startActivity(intent);
                return true;
            } catch (Exception e) {
                return false;
            }
        };
    }
}
