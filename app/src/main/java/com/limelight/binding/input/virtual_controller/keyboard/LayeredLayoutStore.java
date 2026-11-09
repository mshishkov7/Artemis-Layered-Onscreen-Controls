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
import java.nio.charset.StandardCharsets;

/**
 * Stores the imported layered layout in app-private storage, one per keyboard-controls profile.
 */
public final class LayeredLayoutStore {
    public static final String FORMAT = "artemis-layers";
    public static final int MAX_VERSION = 1;

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
    }
}
