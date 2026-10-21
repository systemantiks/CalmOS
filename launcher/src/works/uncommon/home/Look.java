package works.uncommon.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.util.DisplayMetrics;

/** Palettes, type and screen-relative sizes, shared by every screen. */
final class Look {

    /** Theme names. The index is what's saved, so new themes go on the end. */
    static final String[] NAMES = {"Paper", "Night", "Light", "Pink", "Midnight", "Blue Sky", "Flowers", "Halloween", "Idiots"};
    static final int LIGHT = 2, NIGHT = 1, SKY = 5, FLOWERS = 6, HALLOWEEN = 7, IDIOTS = 8;
    /** Keep saved indices stable. Retired Paper and Midnight open as Light and Night. */
    static final int[] MENU_ORDER = {LIGHT, NIGHT, 3, SKY, FLOWERS, HALLOWEEN, IDIOTS};

    //                                  background   strong      dim         faint
    private static final int[][] PALETTES = {
            {0xFFF2EFE9, 0xFF141413, 0xFF73716B, 0xFFB9B5AD},   // Paper: warm white, near-black
            {0xFF121211, 0xFFF2EFE9, 0xFF7A7873, 0xFF3E3D3A},   // Night: the dark version of Paper
            {0xFFE6DDD0, 0xFF1C1915, 0xFF7A7064, 0xFFB3A897},   // Light: sand and umber
            {0xFF8E3A5C, 0xFFFFE1EC, 0xFFF0A3C0, 0xFFB86689},   // Pink: deep rose ground, pale and mid pink type
            {0xFF000000, 0xFFF5F5F5, 0xFF7C7C7C, 0xFF363636},   // Midnight: pitch black, neutral white
            {0xFF3A69A3, 0xFFFFFFFF, 0xFFD4E4F5, 0xFF86A9D2},   // Blue Sky: pixel field and sky (see SkyView), white type
            {0xFF181D27, 0xFFEEF2FA, 0xFFACB8CF, 0xFF5B6375},   // Flowers: the site's slate (oklch .23/.96/.78), pixel meadow below
            {0xFF211A2C, 0xFFFFE6BD, 0xFFD4B6A0, 0xFF806C80},   // Halloween: plum dusk and candlelight
            {0xFF202A2B, 0xFFF3EBDD, 0xFFB6C5BC, 0xFF677D76},   // Idiots: cosy green and warm linen
    };

    final int bg, strong, dim, faint;
    final int rest;           // list rows that aren't selected: a step quieter than dim
    final int index;
    final String name;
    final boolean flowers;    // draw the pixel meadow along the bottom
    final boolean sky;        // draw the pixel field and drifting clouds behind everything
    final int shadow;         // text shadow colour (0 for none): keeps type readable over pictures

    final Typeface display;   // big list type
    final Typeface text;      // clock, tabs, labels, hints

    final float w, h;         // screen size in px

    private static Typeface sDisplay, sText;

    private Look(Context c, int index) {
        this.index = index;
        int[] p = PALETTES[index];
        bg = p[0]; strong = p[1]; dim = p[2]; faint = p[3];
        rest = mix(dim, bg, 0.25f);
        name = NAMES[index];
        flowers = index == FLOWERS;
        sky = index == SKY;
        shadow = sky ? 0x8C123A6B : 0;

        if (sDisplay == null) {
            sDisplay = load(c, "fonts/InterDisplay-Medium.otf", Typeface.DEFAULT);
            sText = load(c, "fonts/Inter-Regular.otf", Typeface.DEFAULT);
        }
        display = sDisplay;
        text = sText;

        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        w = Math.max(dm.widthPixels, dm.heightPixels);
        h = Math.min(dm.widthPixels, dm.heightPixels);
    }

    private static Typeface load(Context c, String path, Typeface fallback) {
        try {
            return Typeface.createFromAsset(c.getAssets(), path);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static Look get(Context c) {
        return new Look(c, Prefs.theme(c));
    }

    static int next(int i) {
        return (i + 1) % PALETTES.length;
    }

    /** True if the saved theme differs from this one (another screen changed it). */
    boolean stale(Context c) {
        return index != Prefs.theme(c);
    }

    /** Fraction of screen height, in px. */
    int hp(float f) { return Math.round(h * f); }

    /** Fraction of screen width, in px. */
    int wp(float f) { return Math.round(w * f); }

    /** Blend from colour a towards b by t (0..1), opaque. */
    static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Soft shadow under type on picture themes; nothing on plain ones. */
    void shade(android.widget.TextView t) {
        if (shadow != 0) t.setShadowLayer(h * 0.006f, 0, h * 0.0018f, shadow);
        else t.setShadowLayer(0, 0, 0, 0);
    }

    static int withAlpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0x00FFFFFF);
    }

    /** Simple persisted state: pinned apps (ordered) and theme. */
    static final class Prefs {
        private static final String FILE = "home";
        private static final String KEY_PINNED = "pinned";
        private static final String KEY_THEME = "look";        // index into NAMES
        private static final String KEY_OLD_THEME = "theme";   // v1.0/1.1: 0 Paper, 1 Clay, 2 Night

        /** Shown on first run, in this order, if installed. */
        static final String DEFAULT_PINNED =
                "com.stremio.one,"
                + "com.netflix.ninja,"
                + "com.disney.disneyplus,"
                + "com.google.android.youtube.tv,"
                + "com.apple.atve.androidtv.appletv,"
                + "com.amazon.amazonvideo.livingroom";

        private static SharedPreferences sp(Context c) {
            SharedPreferences s = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            if (s.contains(KEY_OLD_THEME)) {
                int old = s.getInt(KEY_OLD_THEME, 0);
                int now = old == 1 ? 2 : old == 2 ? 1 : 0;   // Clay and Night swapped places
                s.edit().putInt(KEY_THEME, now).remove(KEY_OLD_THEME).apply();
            }
            return s;
        }

        static java.util.List<String> pinned(Context c) {
            String raw = sp(c).getString(KEY_PINNED, DEFAULT_PINNED);
            java.util.ArrayList<String> out = new java.util.ArrayList<String>();
            for (String s : raw.split(",")) {
                s = s.trim();
                if (s.length() > 0 && !out.contains(s)) out.add(s);
            }
            return out;
        }

        static void setPinned(Context c, java.util.List<String> pkgs) {
            StringBuilder b = new StringBuilder();
            for (String p : pkgs) {
                if (b.length() > 0) b.append(',');
                b.append(p);
            }
            sp(c).edit().putString(KEY_PINNED, b.toString()).apply();
        }

        static int theme(Context c) {
            int t = sp(c).getInt(KEY_THEME, LIGHT);
            if (t == 0) return LIGHT;        // Paper, retired
            if (t == 4) return NIGHT;       // Midnight, retired
            return (t < 0 || t >= NAMES.length) ? LIGHT : t;
        }

        static void setTheme(Context c, int t) {
            sp(c).edit().putInt(KEY_THEME, t).apply();
        }

        // Per-HDMI-port name and icon you set through the source options menu.

        static String sourceName(Context c, String portId) {
            String s = sp(c).getString("src.name." + portId, null);
            return (s == null || s.trim().length() == 0) ? null : s.trim();
        }

        /** IconView kind, or -1 to pick one automatically from the name. */
        static int sourceIcon(Context c, String portId) {
            int kind = sp(c).getInt("src.icon." + portId, -1);
            // The old handheld choice and Nintendo share the same picker option.
            return kind == IconView.HANDHELD ? IconView.NINTENDO : kind;
        }

        /** File name of your own icon (in CustomIcons.dir), or null. Wins over the line icon. */
        static String sourceIconFile(Context c, String portId) {
            return sp(c).getString("src.file." + portId, null);
        }

        static void setSource(Context c, String portId, String name, int icon, String iconFile) {
            SharedPreferences.Editor e = sp(c).edit();
            if (iconFile == null) e.remove("src.file." + portId);
            else e.putString("src.file." + portId, iconFile);
            if (name == null || name.trim().length() == 0) e.remove("src.name." + portId);
            else e.putString("src.name." + portId, name.trim());
            if (icon < 0) e.remove("src.icon." + portId);
            else e.putInt("src.icon." + portId, icon);
            e.apply();
        }

        static void clearSource(Context c, String portId) {
            sp(c).edit().remove("src.name." + portId).remove("src.icon." + portId)
                    .remove("src.file." + portId).apply();
        }
    }
}
