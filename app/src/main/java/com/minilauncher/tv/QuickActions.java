package com.minilauncher.tv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * The row of round buttons in the top bar. Every entry is a plain Intent; entries the current
 * firmware cannot open are simply not added, so the bar adapts to whatever TV it runs on.
 */
final class QuickActions {
    interface Host {
        void open(Intent intent);
    }

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
}
