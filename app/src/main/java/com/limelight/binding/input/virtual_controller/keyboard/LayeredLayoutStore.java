package com.limelight.binding.input.virtual_controller.keyboard;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Stores the imported layered layout in app-private storage, one per keyboard-controls profile.
 */
public final class LayeredLayoutStore {
    public static final String FORMAT = "artemis-layers";
    public static final int MAX_VERSION = 1;

    private static final int MAX_LAYOUT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ICON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ICONS = 300;

    private LayeredLayoutStore() {}

    private static String profileName(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getString(
                KeyBoardControllerConfigurationLoader.OSC_PREFERENCE,
                KeyBoardControllerConfigurationLoader.OSC_PREFERENCE_VALUE);
    }

    private static File layoutFile(Context context) {
        String safe = profileName(context).replaceAll("[^A-Za-z0-9_.-]", "_");
        return new File(new File(context.getFilesDir(), "layered/" + safe), "layout.json");
    }

    public static File iconsDir(Context context) {
        return new File(layoutFile(context).getParentFile(), "icons");
    }

    /** True if the name is a plain file name (no folders), safe to join onto the icons folder. */
    public static boolean isPlainFileName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > limit) {
                throw new IOException("File is too large");
            }
        }
        return out.toByteArray();
    }

    /**
     * Imports either a bare layout.json or a .zip holding layout.json and PNG icons (any folder,
     * only the file names are used). Returns the number of icons imported.
     */
    public static int importStream(Context context, InputStream in) throws JSONException, IOException {
        byte[] data = readLimited(in, 64 * 1024 * 1024);
        boolean zip = data.length > 4 && data[0] == 'P' && data[1] == 'K';
        if (!zip) {
            importLayout(context, new String(data, StandardCharsets.UTF_8));
            return 0;
        }

        String layoutText = null;
        java.util.Map<String, byte[]> icons = new java.util.HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            ZipEntry entry;
            int layoutDepth = Integer.MAX_VALUE;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String path = entry.getName().replace('\\', '/');
                String base = path.substring(path.lastIndexOf('/') + 1);
                String lower = base.toLowerCase(java.util.Locale.ROOT);
                if (lower.equals("layout.json")) {
                    int depth = path.split("/").length;
                    if (depth < layoutDepth) {
                        layoutDepth = depth;
                        layoutText = new String(readLimited(zin, MAX_LAYOUT_BYTES), StandardCharsets.UTF_8);
                    }
                } else if (lower.endsWith(".png") && !base.startsWith(".") && isPlainFileName(base)) {
                    if (icons.size() >= MAX_ICONS) {
                        throw new IOException("Too many icons (max " + MAX_ICONS + ")");
                    }
                    icons.put(base, readLimited(zin, MAX_ICON_BYTES));
                }
            }
        }
        if (layoutText == null) {
            throw new IOException("layout.json not found in the zip");
        }

        // Validate and store the layout first, so a bad layout leaves existing icons alone
        importLayout(context, layoutText);

        File dir = iconsDir(context);
        File[] old = dir.listFiles();
        for (int i = 0; old != null && i < old.length; i++) {
            if (old[i].isFile()) old[i].delete();
        }
        if (!icons.isEmpty() && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        for (java.util.Map.Entry<String, byte[]> e : icons.entrySet()) {
            try (FileOutputStream out = new FileOutputStream(new File(dir, e.getKey()))) {
                out.write(e.getValue());
            }
        }
        IconCache.clear();
        return icons.size();
    }

    /** Returns the stored layout text, or null if none is imported (or it cannot be read). */
    public static String read(Context context) {
        File f = layoutFile(context);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Validates and stores a layout. Throws with a readable message if the file is not a valid
     * layered layout. Also drops saved in-app position tweaks for the file's elements so the
     * file's positions take effect.
     */
    public static void importLayout(Context context, String json) throws JSONException, IOException {
        JSONObject root = new JSONObject(json);
        if (!FORMAT.equals(root.optString("format"))) {
            throw new JSONException("Not an \"" + FORMAT + "\" layout file");
        }
        if (root.optInt("version", 1) > MAX_VERSION) {
            throw new JSONException("Layout version " + root.optInt("version") + " is newer than this app supports");
        }

        File f = layoutFile(context);
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }

        SharedPreferences.Editor edit = context.getSharedPreferences(profileName(context), Activity.MODE_PRIVATE).edit();
        JSONArray buttons = root.optJSONArray("buttons");
        for (int i = 0; buttons != null && i < buttons.length(); i++) {
            JSONObject b = buttons.optJSONObject(i);
            if (b != null) edit.remove(LayeredLayoutLoader.buttonElementId(b.optString("id")));
        }
        JSONArray sticks = root.optJSONArray("sticks");
        for (int i = 0; sticks != null && i < sticks.length(); i++) {
            JSONObject s = sticks.optJSONObject(i);
            if (s != null) edit.remove(LayeredLayoutLoader.stickElementId(s.optString("id")));
        }
        edit.apply();
    }

    public static void clear(Context context) {
        File f = layoutFile(context);
        if (f.isFile() && !f.delete()) {
            f.deleteOnExit();
        }
        File[] icons = iconsDir(context).listFiles();
        for (int i = 0; icons != null && i < icons.length; i++) {
            icons[i].delete();
        }
        IconCache.clear();
    }
}
