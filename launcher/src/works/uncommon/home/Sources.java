package works.uncommon.home;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HDMI inputs from Android TV's input framework, one row per port in port
 * order. A port is named, in order of preference: the name you gave it, the
 * name its device announces over HDMI-CEC ("PS5"), the name set in
 * Settings > Inputs, or the TV's own port name ("HDMI 2").
 */
final class Sources {

    static final class Entry {
        final String portId;      // stable id of the HDMI port; overrides are saved against this
        final String openId;      // what to switch to: the CEC device if there is one, else the port
        final String portName;    // the TV's own name for the port, e.g. "HDMI 2"
        final String autoName;    // what it would be called without a saved name
        final String label;       // what's shown
        final int icon;
        final String iconFile;    // your own image, or null
        final boolean faded;      // standby or nothing plugged in

        Entry(String portId, String openId, String portName, String autoName, String label, int icon,
              String iconFile, boolean faded) {
            this.portId = portId;
            this.openId = openId;
            this.portName = portName;
            this.autoName = autoName;
            this.label = label;
            this.icon = icon;
            this.iconFile = iconFile;
            this.faded = faded;
        }
    }

    /** The TV's own app, which handles switching inputs. */
    private static final String[] TV_APPS = {
            "com.mediatek.wwtv.tvcenter",   // MediaTek / Vestel
            "com.google.android.tv",        // Live Channels
            "com.android.tv",
    };

    static TvInputManager manager(Context c) {
        try {
            return (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static List<Entry> list(Context c) {
        final List<Entry> out = new ArrayList<Entry>();
        TvInputManager tim = manager(c);
        if (tim == null) return out;
        List<TvInputInfo> inputs;
        try {
            inputs = tim.getTvInputList();
        } catch (RuntimeException e) {
            return out;
        }
        if (inputs == null) return out;

        // CEC devices, grouped under the HDMI port they're plugged into.
        Map<String, List<TvInputInfo>> children = new HashMap<String, List<TvInputInfo>>();
        for (TvInputInfo in : inputs) {
            String parent = in.getParentId();
            if (parent == null || in.getType() != TvInputInfo.TYPE_HDMI || hidden(c, in)) continue;
            List<TvInputInfo> l = children.get(parent);
            if (l == null) children.put(parent, l = new ArrayList<TvInputInfo>());
            l.add(in);
        }

        final Map<Entry, Integer> order = new HashMap<Entry, Integer>();
        int seen = 0;
        for (TvInputInfo port : inputs) {
            if (port.getParentId() != null || port.getType() != TvInputInfo.TYPE_HDMI) continue;
            if (!port.isPassthroughInput() || hidden(c, port)) continue;

            String portName = baseLabel(c, port);

            // A connected CEC device on this port, if any
            TvInputInfo dev = null;
            int devState = TvInputManager.INPUT_STATE_DISCONNECTED;
            List<TvInputInfo> devs = children.get(port.getId());
            if (devs != null) {
                for (TvInputInfo d : devs) {
                    int st = state(tim, d.getId());
                    if (st == TvInputManager.INPUT_STATE_DISCONNECTED) continue;
                    if (dev == null || (st == TvInputManager.INPUT_STATE_CONNECTED
                            && devState != TvInputManager.INPUT_STATE_CONNECTED)) {
                        dev = d;
                        devState = st;
                    }
                }
            }

            String autoName = (dev != null) ? label(c, dev) : label(c, port);
            String saved = Look.Prefs.sourceName(c, port.getId());
            String name = (saved != null) ? saved : autoName;
            int savedIcon = Look.Prefs.sourceIcon(c, port.getId());
            int icon = (savedIcon >= 0) ? savedIcon : iconFor(name);
            // Your own image: the one you picked, else one whose file name matches,
            // unless you picked a built-in icon for this input.
            String file = Look.Prefs.sourceIconFile(c, port.getId());
            if (file != null && CustomIcons.load(c, file) == null) file = null;
            if (file == null && savedIcon < 0) file = CustomIcons.match(c, name);
            int st = (dev != null) ? devState : state(tim, port.getId());

            Entry e = new Entry(port.getId(), dev != null ? dev.getId() : port.getId(), portName, autoName,
                    name, icon, file, st != TvInputManager.INPUT_STATE_CONNECTED);
            out.add(e);
            // Sort key: port number from the TV's own name, then the order the TV listed them
            order.put(e, portNumber(portName) * 1000 + seen++);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return order.get(a) - order.get(b);
            }
        });
        return out;
    }

    /** "HDMI 2" -> 2; anything without a number sorts last. */
    private static int portNumber(String name) {
        Matcher m = Pattern.compile("(\\d+)").matcher(name);
        if (m.find()) {
            try {
                return Math.min(999, Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return 999;
    }

    /** The TV's own name for an input, ignoring any custom label. */
    private static String baseLabel(Context c, TvInputInfo in) {
        CharSequence l = null;
        try {
            l = in.loadLabel(c);
        } catch (RuntimeException ignored) {
        }
        String s = (l == null) ? "" : l.toString().trim();
        return s.length() == 0 ? "HDMI" : s;
    }

    private static boolean hidden(Context c, TvInputInfo in) {
        // isHidden() is API 24+: inputs the user hid in Settings > Inputs.
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            Method m = TvInputInfo.class.getMethod("isHidden", Context.class);
            return Boolean.TRUE.equals(m.invoke(in, c));
        } catch (Exception e) {
            return false;
        }
    }

    private static int state(TvInputManager tim, String id) {
        try {
            return tim.getInputState(id);
        } catch (RuntimeException e) {
            return TvInputManager.INPUT_STATE_CONNECTED;
        }
    }

    /** The name set in Settings > Inputs if there is one, else the device or port name. */
    private static String label(Context c, TvInputInfo in) {
        CharSequence l = null;
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                Method m = TvInputInfo.class.getMethod("loadCustomLabel", Context.class);
                l = (CharSequence) m.invoke(in, c);
            } catch (Exception ignored) {
            }
        }
        if (l == null || l.toString().trim().length() == 0) {
            try {
                l = in.loadLabel(c);
            } catch (RuntimeException ignored) {
            }
        }
        String s = (l == null) ? "" : l.toString().trim();
        return s.length() == 0 ? "Input" : s;
    }

    /** Pick a device-type icon from the name the device announces. */
    static int iconFor(String label) {
        String s = " " + label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ") + " ";
        if (has(s, "playstation") || s.matches(".* ps ?[2-5] .*")) return IconView.PLAYSTATION;
        if (has(s, "xbox")) return IconView.XBOX;
        if (has(s, "nintendo", "switch")) return IconView.NINTENDO;
        if (has(s, "apple tv", "appletv")) return IconView.APPLE;
        if (has(s, "steam deck", "handheld")) return IconView.NINTENDO;
        if (has(s, "steam", "console", "game", "gaming")) return IconView.CONTROLLER;
        if (has(s, "chromecast", "google tv", "fire tv", "firestick", "fire stick", "roku",
                "shield", "sky", "virgin", "now tv", "youview", "freesat", "freeview", "tivo",
                "set top", "stb", "box", "stream")) return IconView.STREAMER;
        if (has(s, "blu ray", "bluray", "dvd", "bd player", "disc", "uhd player")) return IconView.DISC;
        if (has(s, " pc ", " mac ", "macbook", "laptop", "computer", "windows", "desktop", "nuc ")) return IconView.LAPTOP;
        if (has(s, "sonos", "soundbar", "sound bar", " av ", "receiver", "denon", "yamaha", "onkyo",
                "marantz", "bose", "amp")) return IconView.SPEAKER;
        return IconView.PORT;
    }

    private static boolean has(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private static Intent tvAppLaunchIntent(Context c) {
        PackageManager pm = c.getPackageManager();
        for (String pkg : TV_APPS) {
            Intent i = null;
            try {
                i = pm.getLeanbackLaunchIntentForPackage(pkg);
            } catch (RuntimeException ignored) {
            }
            if (i == null) {
                try {
                    i = pm.getLaunchIntentForPackage(pkg);
                } catch (RuntimeException ignored) {
                }
            }
            if (i != null) return i;
        }
        return null;
    }

    /** Switches the TV to this input; false (after a toast) if the TV wouldn't. */
    static boolean open(Context c, Entry e) {
        // The standard way to switch to an HDMI/CEC input; the TV's own app handles it.
        Uri uri = TvContract.buildChannelUriForPassthroughInput(e.openId);
        // 1. The TV app directly, so there's never a chooser
        for (String pkg : TV_APPS) {
            if (tryStart(c, view(uri).setPackage(pkg))) return true;
        }
        // 2. Whatever app claims passthrough URIs
        if (tryStart(c, view(uri))) return true;
        // 3. The TV app's own launcher activity by exact component, which skips filter matching
        Intent launch = tvAppLaunchIntent(c);
        if (launch != null && launch.getComponent() != null
                && tryStart(c, view(uri).setComponent(launch.getComponent()))) return true;
        fail(c, e);
        return false;
    }

    private static Intent view(Uri uri) {
        return new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static boolean tryStart(Context c, Intent i) {
        try {
            c.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }

    private static void fail(Context c, Entry e) {
        Toast.makeText(c, "Can't switch to " + e.label, Toast.LENGTH_SHORT).show();
    }
}
