package com.minilauncher.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A tiny D-pad friendly file browser built from AlertDialogs, because old TV firmwares ship no
 * system image picker. Starts at the list of mounted volumes (USB drives, internal storage).
 */
final class ImagePicker {
    interface Callback {
        void onPicked(File file);
    }

    private static final String[] EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"};

    private final Activity activity;
    private final Callback callback;

    ImagePicker(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    void show() {
        List<File> roots = roots();
        if (roots.isEmpty()) {
            Toast.makeText(activity, R.string.picker_no_storage, Toast.LENGTH_LONG).show();
            return;
        }
        if (roots.size() == 1) {
            browse(roots.get(0), null);
            return;
        }
        showList(activity.getString(R.string.picker_title), roots, null, null);
    }

    private void browse(final File dir, final File parent) {
        File[] listed = dir.listFiles();
        List<File> dirs = new ArrayList<>();
        List<File> images = new ArrayList<>();
        if (listed != null) {
            for (File f : listed) {
                if (f.isHidden()) continue;
                if (f.isDirectory()) dirs.add(f);
                else if (isImage(f)) images.add(f);
            }
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(dirs, (a, b) -> collator.compare(a.getName(), b.getName()));
        Collections.sort(images, (a, b) -> collator.compare(a.getName(), b.getName()));
        List<File> entries = new ArrayList<>(dirs);
        entries.addAll(images);
        if (entries.isEmpty()) {
            Toast.makeText(activity, R.string.picker_empty, Toast.LENGTH_SHORT).show();
        }
        showList(dir.getName(), entries, dir, parent);
    }

    private void showList(String title, final List<File> entries, final File current, final File parent) {
        final boolean hasUp = current != null;
        List<CharSequence> labels = new ArrayList<>();
        if (hasUp) labels.add(activity.getString(R.string.picker_up));
        for (File f : entries) labels.add(f.isDirectory() ? f.getName() + "/" : f.getName());
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    if (hasUp && which == 0) {
                        if (parent != null) browse(parent, grandParent(parent));
                        else show();
                        return;
                    }
                    File chosen = entries.get(hasUp ? which - 1 : which);
                    if (chosen.isDirectory()) browse(chosen, current);
                    else callback.onPicked(chosen);
                })
                .show();
    }

    private File grandParent(File dir) {
        for (File root : roots()) if (root.equals(dir)) return null;
        return dir.getParentFile();
    }

    private static boolean isImage(File f) {
        String name = f.getName().toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) if (name.endsWith(ext)) return true;
        return false;
    }

    /** Mounted volumes: USB sticks show up under /storage/XXXX-XXXX or /mnt/media_rw. */
    private static List<File> roots() {
        List<File> roots = new ArrayList<>();
        File internal = Environment.getExternalStorageDirectory();
        if (internal != null && internal.canRead()) roots.add(internal);
        for (String base : new String[]{"/storage", "/mnt/media_rw", "/mnt/usb"}) {
            File[] volumes = new File(base).listFiles();
            if (volumes == null) continue;
            for (File v : volumes) {
                String n = v.getName();
                if (n.equals("self") || n.equals("emulated") || !v.isDirectory() || !v.canRead()) continue;
                if (!roots.contains(v)) roots.add(v);
            }
        }
        return roots;
    }
}
