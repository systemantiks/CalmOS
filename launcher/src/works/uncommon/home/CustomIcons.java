package works.uncommon.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.LruCache;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Icons you add yourself: image files pushed to
 * /sdcard/Android/data/works.uncommon.home/files/icons/ (PNG, JPG or WebP).
 * Single-colour images with transparency are tinted to the theme; anything
 * else is drawn as it is.
 */
final class CustomIcons {

    static final class Icon {
        final Bitmap bitmap;
        final boolean tint;

        Icon(Bitmap bitmap, boolean tint) {
            this.bitmap = bitmap;
            this.tint = tint;
        }
    }

    private static final int MAX_PX = 256;
    private static final LruCache<String, Icon> CACHE = new LruCache<String, Icon>(32);

    /** The folder, created if needed so `adb push` lands in a folder the app owns. */
    static File dir(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) return null;
        File d = new File(base, "icons");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** File names of usable images, sorted. */
    static List<String> list(Context c) {
        List<String> out = new ArrayList<String>();
        File d = dir(c);
        if (d == null) return out;
        String[] names = d.list();
        if (names == null) return out;
        Arrays.sort(names, String.CASE_INSENSITIVE_ORDER);
        for (String n : names) {
            String l = n.toLowerCase(Locale.ROOT);
            if (l.startsWith(".")) continue;
            if (l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".webp")) out.add(n);
        }
        return out;
    }

    /** "playstation-5.png" -> "playstation 5" */
    static String displayName(String file) {
        String s = file;
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        return s.replaceAll("[_\\-]+", " ").trim();
    }

    /** Loaded (and cached) icon, or null if the file is missing or unreadable. */
    static Icon load(Context c, String file) {
        if (file == null) return null;
        File d = dir(c);
        if (d == null) return null;
        File f = new File(d, file);
        if (!f.isFile()) return null;
        String key = f.getAbsolutePath() + ":" + f.lastModified() + ":" + f.length();
        Icon hit = CACHE.get(key);
        if (hit != null) return hit;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_PX) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b == null) return null;
            Icon icon = new Icon(b, isSingleColour(b));
            CACHE.put(key, icon);
            return icon;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** True if the image has transparency and its visible pixels are all roughly one colour. */
    private static boolean isSingleColour(Bitmap b) {
        if (!b.hasAlpha()) return false;
        int w = b.getWidth(), h = b.getHeight();
        int step = Math.max(1, Math.max(w, h) / 64);
        int minR = 255, minG = 255, minB = 255, maxR = 0, maxG = 0, maxB = 0;
        int opaque = 0, clear = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int p = b.getPixel(x, y);
                int a = Color.alpha(p);
                if (a < 40) {
                    clear++;
                    continue;
                }
                if (a < 200) continue;   // anti-aliased edges
                opaque++;
                int r = Color.red(p), g = Color.green(p), bl = Color.blue(p);
                minR = Math.min(minR, r); maxR = Math.max(maxR, r);
                minG = Math.min(minG, g); maxG = Math.max(maxG, g);
                minB = Math.min(minB, bl); maxB = Math.max(maxB, bl);
            }
        }
        if (opaque == 0 || clear == 0) return false;
        int spread = Math.max(maxR - minR, Math.max(maxG - minG, maxB - minB));
        return spread < 48;
    }

    /**
     * A file whose name matches the source's name, e.g. "ps5.png" for "PS5" or
     * "playstation.png" for "PlayStation 5". Null if none.
     */
    static String match(Context c, String sourceName) {
        String name = norm(sourceName);
        if (name.length() == 0) return null;
        String best = null;
        int bestLen = 0;
        for (String f : list(c)) {
            String n = norm(displayName(f));
            if (n.length() < 2) continue;
            boolean hit = name.equals(n) || name.startsWith(n) || name.contains(n);
            if (hit && n.length() > bestLen) {
                best = f;
                bestLen = n.length();
            }
        }
        return best;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }
}
