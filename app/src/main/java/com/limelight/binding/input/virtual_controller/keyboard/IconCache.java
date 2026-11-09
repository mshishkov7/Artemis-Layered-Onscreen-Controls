package com.limelight.binding.input.virtual_controller.keyboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.limelight.LimeLog;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Decodes imported button icons once per size. A missing or unreadable icon yields null (the
 * button then draws its text label) and is logged a single time.
 */
public final class IconCache {
    private static final Map<String, Bitmap> BITMAPS = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();

    private IconCache() {}

    /** Forget everything, e.g. after a new layout was loaded or imported. */
    public static synchronized void clear() {
        BITMAPS.clear();
        FAILED.clear();
    }

    /** Returns the icon scaled to fit width x height, or null if it is unavailable. */
    public static synchronized Bitmap get(Context context, String name, int width, int height) {
        if (width <= 0 || height <= 0) return null;
        String key = name + "@" + width + "x" + height;
        Bitmap cached = BITMAPS.get(key);
        if (cached != null) return cached;
        if (FAILED.contains(name)) return null;

        Bitmap result = null;
        try {
            File f = new File(LayeredLayoutStore.iconsDir(context), name);
            if (!f.isFile()) {
                throw new Exception("file not found");
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new Exception("not a readable image");
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) {
                sample *= 2;
            }
            opts.inSampleSize = sample;
            Bitmap full = BitmapFactory.decodeFile(f.getPath(), opts);
            if (full == null) {
                throw new Exception("decode failed");
            }

            float scale = Math.min((float) width / full.getWidth(), (float) height / full.getHeight());
            int w = Math.max(1, Math.round(full.getWidth() * scale));
            int h = Math.max(1, Math.round(full.getHeight() * scale));
            result = (w == full.getWidth() && h == full.getHeight())
                    ? full : Bitmap.createScaledBitmap(full, w, h, true);
        } catch (Throwable t) {
            LimeLog.warning("Layered layout: icon \"" + name + "\" unavailable (" + t.getMessage() + "), using text label");
        }

        if (result == null) {
            FAILED.add(name);
        } else {
            BITMAPS.put(key, result);
        }
        return result;
    }
}
