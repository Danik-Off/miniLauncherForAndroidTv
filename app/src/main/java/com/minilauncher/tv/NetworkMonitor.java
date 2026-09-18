package com.minilauncher.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Shows the active network in the top bar. Purely event driven: the system tells us when the
 * connection or the RSSI changes, and we only listen while the launcher is on screen.
 */
final class NetworkMonitor {
    private final Context context;
    private final ImageView icon;
    private final TextView text;
    private final ConnectivityManager connectivity;
    private final WifiManager wifi;
    private boolean started;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            refresh();
        }
    };

    NetworkMonitor(Context context, ImageView icon, TextView text) {
        this.context = context.getApplicationContext();
        this.icon = icon;
        this.text = text;
        connectivity = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        wifi = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
    }

    void start() {
        if (started) return;
        started = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        context.registerReceiver(receiver, filter);
        refresh();
    }

    void stop() {
        if (!started) return;
        started = false;
        context.unregisterReceiver(receiver);
    }

    @SuppressWarnings("deprecation")
    void refresh() {
        NetworkInfo info = connectivity != null ? connectivity.getActiveNetworkInfo() : null;
        if (info == null || !info.isConnected()) {
            icon.setImageResource(wifi != null && wifi.isWifiEnabled() ? R.drawable.ic_wifi_0 : R.drawable.ic_wifi_off);
            text.setText(R.string.net_none);
            return;
        }
        if (info.getType() == ConnectivityManager.TYPE_WIFI && wifi != null) {
            WifiInfo wi = wifi.getConnectionInfo();
            int level = wi != null ? WifiManager.calculateSignalLevel(wi.getRssi(), 4) : 3;
            icon.setImageResource(levelIcon(level));
            String ssid = wi != null ? wi.getSSID() : null;
            // Without location permission Android hands back a placeholder; don't show that.
            if (ssid == null || ssid.isEmpty() || ssid.contains("unknown") || ssid.equals("0x")) {
                text.setText(R.string.net_wifi);
            } else {
                text.setText(ssid.replace("\"", ""));
            }
            return;
        }
        if (info.getType() == ConnectivityManager.TYPE_ETHERNET) {
            icon.setImageResource(R.drawable.ic_ethernet);
            text.setText(R.string.net_ethernet);
            return;
        }
        icon.setImageResource(R.drawable.ic_ethernet);
        CharSequence name = info.getTypeName();
        text.setText(name != null ? name : "");
    }

    private static int levelIcon(int level) {
        switch (level) {
            case 0: return R.drawable.ic_wifi_0;
            case 1: return R.drawable.ic_wifi_1;
            case 2: return R.drawable.ic_wifi_2;
            default: return R.drawable.ic_wifi_3;
        }
    }
}
