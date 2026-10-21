package works.uncommon.home;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finds launchable apps and opens them. */
final class Apps {

    static final int NORMAL = 0, APPLE_TV = 1, SYSTEM = 2;

    static final class Entry {
        final String pkg;
        final String label;
        final int kind;     // NORMAL, APPLE_TV or SYSTEM (the TV's own utilities)

        Entry(String pkg, String label) {
            this(pkg, label, NORMAL);
        }

        Entry(String pkg, String label, int kind) {
            this.pkg = pkg;
            this.label = label;
            this.kind = kind;
        }
    }

    /** The TV's own bits that are launchable but nobody chose to install. */
    private static final String[] SYSTEM_PREFIXES = {
            "com.mediatek.", "mediatek.", "com.mstar.", "com.vestel.", "com.android.", "com.google.android.tv.",
    };
    private static final String[] SYSTEM_EXTRA = {
            "com.android.vending", "com.google.android.katniss", "com.google.android.apps.mediashell",
    };

    private static int kindOf(PackageManager pm, String pkg) {
        for (String p : SYSTEM_EXTRA) if (p.equals(pkg)) return SYSTEM;
        for (String p : SYSTEM_PREFIXES) if (pkg.startsWith(p)) return SYSTEM;
        try {
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            boolean system = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean updated = (ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            // Baked into the firmware and never updated from the Play Store: the TV's own utility.
            if (system && !updated) return SYSTEM;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        return NORMAL;
    }

    // The last full list, so a screen coming on can show it at once instead of
    // asking the package manager again (slow on the TV) mid-transition.
    private static List<Entry> cache;
    private static long cacheAt;

    /** The list from the last few moments if there is one, else a fresh one. */
    static List<Entry> recent(Context c, long maxAgeMs) {
        synchronized (Apps.class) {
            if (cache != null && android.os.SystemClock.uptimeMillis() - cacheAt < maxAgeMs) {
                return new ArrayList<Entry>(cache);
            }
        }
        return all(c);
    }

    /** Forget the remembered list (an app was installed or removed). */
    static void invalidate() {
        synchronized (Apps.class) {
            cache = null;
        }
    }

    /** Build the list in the background, ready for the next screen. */
    static void prefetch(Context c) {
        final Context app = c.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    all(app);
                } catch (RuntimeException ignored) {
                }
            }
        }, "apps-prefetch");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    /** Every launchable app except this one, sorted by name. TV (leanback) entries win over phone ones. */
    static List<Entry> all(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Entry> byPkg = new LinkedHashMap<String, Entry>();
        collect(c, pm, Intent.CATEGORY_LEANBACK_LAUNCHER, byPkg);
        collect(c, pm, Intent.CATEGORY_LAUNCHER, byPkg);
        // Apps on the linked Apple TV, as last seen over the network.
        for (Map.Entry<String, String> e : AppleTv.apps(c).entrySet()) {
            String id = AppleTv.PREFIX + e.getKey();
            byPkg.put(id, new Entry(id, e.getValue(), APPLE_TV));
        }

        // The Apple TV's apps, then apps installed on the TV, then the TV's own utilities; A-Z within each.
        List<Entry> out = new ArrayList<Entry>(byPkg.values());
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.kind != b.kind) return rank(a.kind) - rank(b.kind);
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        synchronized (Apps.class) {
            cache = new ArrayList<Entry>(out);
            cacheAt = android.os.SystemClock.uptimeMillis();
        }
        return out;
    }

    static int rank(int kind) {
        return kind == APPLE_TV ? 0 : kind == NORMAL ? 1 : 2;
    }

    private static void collect(Context c, PackageManager pm, String category, Map<String, Entry> into) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentActivities(q, 0);
        } catch (RuntimeException e) {
            return;
        }
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || into.containsKey(pkg)) continue;
            CharSequence l = ri.loadLabel(pm);
            into.put(pkg, new Entry(pkg, clean(l == null ? pkg : l.toString()), kindOf(pm, pkg)));
        }
    }

    private static String clean(String label) {
        String s = label.trim();
        String[] suffixes = {" for Android TV", " (Android TV)", " - Android TV", " – Android TV"};
        for (String suf : suffixes) {
            if (s.endsWith(suf)) s = s.substring(0, s.length() - suf.length()).trim();
        }
        return s;
    }

    /** Pinned packages that are actually installed, in pinned order. */
    /** Android streaming apps and the same app on the Apple TV, for moving pins across. */
    private static final String[][] TWINS = {
            {"com.netflix.ninja", "com.netflix.Netflix"},
            {"com.disney.disneyplus", "com.disney.disneyplus"},
            {"com.amazon.amazonvideo.livingroom", "com.amazon.aiv.AIVApp"},
            {"com.google.android.youtube.tv", "com.google.ios.youtube"},
            {"com.apple.atve.androidtv.appletv", "com.apple.TVWatchList"},
    };

    static List<Entry> pinned(Context c, List<Entry> all) {
        Map<String, Entry> byPkg = new LinkedHashMap<String, Entry>();
        for (Entry e : all) byPkg.put(e.pkg, e);
        // A pinned Android app that's been removed, but which the Apple TV has: pin that instead.
        List<String> pins = Look.Prefs.pinned(c);
        boolean moved = false;
        for (int i = 0; i < pins.size(); i++) {
            String p = pins.get(i);
            if (byPkg.containsKey(p) || onAppleTv(p)) continue;
            for (String[] t : TWINS) {
                String atv = AppleTv.PREFIX + t[1];
                if (t[0].equals(p) && byPkg.containsKey(atv) && !pins.contains(atv)) {
                    pins.set(i, atv);
                    moved = true;
                }
            }
        }
        if (moved) Look.Prefs.setPinned(c, pins);
        List<Entry> out = new ArrayList<Entry>();
        for (String p : pins) {
            Entry e = byPkg.get(p);
            if (e != null) out.add(e);
        }
        return out;
    }

    static boolean onAppleTv(String pkg) {
        return pkg.startsWith(AppleTv.PREFIX);
    }

    /** The label as shown in lists, with an optional small tag after it ("pinned"). */
    static CharSequence display(Entry e, String extra) {
        String tag = extra;
        if (tag == null) return e.label;
        SpannableString s = new SpannableString(e.label + "   " + tag);
        s.setSpan(new RelativeSizeSpan(0.36f), e.label.length(), s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    static void launch(Context c, String pkg) {
        if (onAppleTv(pkg)) {
            launchOnAppleTv(c, pkg.substring(AppleTv.PREFIX.length()));
            return;
        }
        PackageManager pm = c.getPackageManager();
        Intent i = null;
        try {
            i = pm.getLeanbackLaunchIntentForPackage(pkg);
        } catch (RuntimeException ignored) {
        }
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(c, "Can't open that app", Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(c, "Can't open that app", Toast.LENGTH_SHORT).show();
        }
    }

    /** Switch the TV to the Apple TV's input, and open the app on the Apple TV. */
    private static void launchOnAppleTv(Context c, String bundleId) {
        String port = AppleTv.inputPort(c);
        List<Sources.Entry> inputs = Sources.list(c);
        Sources.Entry target = null;
        for (Sources.Entry s : inputs) {
            if (s.portId.equals(port)) target = s;
        }
        if (target == null) {
            // Saved input not listed (hidden, or renumbered): fall back to one that looks like an Apple TV.
            for (Sources.Entry s : inputs) {
                if (s.icon == IconView.APPLE) {
                    target = s;
                    AppleTv.setInputPort(c, s.portId);
                    break;
                }
            }
        }
        if (target != null) Sources.open(c, target);   // shows its own message if the TV refuses
        if (target == null) {
            Toast.makeText(c, "Opening on " + AppleTv.name(c) + ". Switch the TV to its input.", Toast.LENGTH_LONG).show();
        }
        AppleTv.launch(c, bundleId);
    }

    static void openSettings(Context c) {
        Intent[] tries = {
                new Intent(android.provider.Settings.ACTION_SETTINGS),
                new Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings"),
        };
        for (Intent i : tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                c.startActivity(i);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        Toast.makeText(c, "Can't open Settings", Toast.LENGTH_SHORT).show();
    }
}
