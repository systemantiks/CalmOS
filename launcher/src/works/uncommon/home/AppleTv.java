package works.uncommon.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Apple TV behind one of the HDMI inputs, reached over the network with the
 * Companion protocol: find it, pair once, remember its apps, and launch them.
 * Network work runs on one background thread; callbacks come back on the main thread.
 */
final class AppleTv {

    static final String PREFIX = "atv:";            // pinned-app ids for Apple TV apps
    private static final String SERVICE = "_companion-link._tcp";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String NAME = "Home";      // how we appear in the Apple TV's Remote list

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    static final String TAG = "CalmOS-ATV";     // adb logcat -s CalmOS-ATV
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Done<T> {
        void done(T result, String error);
    }

    /** An Apple TV found on the network. */
    static final class Device {
        final String name, host;
        final int port;

        Device(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }

    private AppleTv() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("appletv", Context.MODE_PRIVATE);
    }

    static boolean isLinked(Context c) {
        return Companion.Credentials.parse(sp(c).getString("creds", null)) != null;
    }

    static String name(Context c) {
        return sp(c).getString("name", "Apple TV");
    }

    /** The HDMI input (port id) the Apple TV is plugged into, or null. */
    static String inputPort(Context c) {
        return sp(c).getString("input", null);
    }

    static void setInputPort(Context c, String port) {
        sp(c).edit().putString("input", port).apply();
    }

    static void unlink(Context c) {
        sp(c).edit().clear().apply();
    }

    /** Apps last seen on the Apple TV: bundle id -> name. */
    static Map<String, String> apps(Context c) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (!isLinked(c)) return out;
        String raw = sp(c).getString("apps", "");
        for (String line : raw.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) out.put(line.substring(0, tab), line.substring(tab + 1));
        }
        return out;
    }

    private static void saveApps(Context c, Map<String, String> apps) {
        if (apps.isEmpty()) return;     // an asleep Apple TV can answer with nothing; keep what we had
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : apps.entrySet()) {
            String name = e.getValue().replace('\n', ' ').replace('\t', ' ');
            b.append(e.getKey()).append('\t').append(name).append('\n');
        }
        sp(c).edit().putString("apps", b.toString()).apply();
    }

    /** Stable id the Apple TV knows us by (12 hex chars). */
    private static String rpId(Context c) {
        String id = sp(c).getString("rpid", null);
        if (id == null) {
            id = Companion.hex(Nacl.random(6));
            sp(c).edit().putString("rpid", id).apply();
        }
        return id;
    }

    // ------------------------------------------------------------ discovery

    /** Looks for Apple TVs for {@code ms} milliseconds; results arrive as they're found. */
    static Runnable discover(Context context, final long ms, final Done<Device> found, final Runnable finished) {
        final NsdManager nsd = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        final boolean[] stopped = {false};
        final Runnable[] stop = new Runnable[1];
        if (nsd == null) {
            MAIN.post(finished);
            return new Runnable() {
                @Override
                public void run() {
                }
            };
        }
        final List<String> seen = Collections.synchronizedList(new ArrayList<String>());
        final NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String type, int code) {
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (stopped[0]) return;
                        stopped[0] = true;
                        finished.run();
                    }
                });
            }

            @Override
            public void onStopDiscoveryFailed(String type, int code) {
            }

            @Override
            public void onDiscoveryStarted(String type) {
            }

            @Override
            public void onDiscoveryStopped(String type) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                if (seen.contains(info.getServiceName())) return;
                seen.add(info.getServiceName());
                Resolver.add(nsd, new Resolver.Job(info, stopped, found));
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
            }
        };
        stop[0] = new Runnable() {
            @Override
            public void run() {
                if (stopped[0]) return;
                stopped[0] = true;
                try {
                    nsd.stopServiceDiscovery(listener);
                } catch (RuntimeException ignored) {
                }
                MAIN.post(finished);
            }
        };
        try {
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException e) {
            stopped[0] = true;
            MAIN.post(finished);
            return stop[0];
        }
        MAIN.postDelayed(stop[0], ms);
        return stop[0];
    }

    /**
     * Android before 14 resolves one service at a time per app, so every search shares
     * this queue. Jobs from searches that have stopped are dropped; a resolve that never
     * answers is given up on after a few seconds.
     */
    private static final class Resolver {
        static final class Job {
            final NsdServiceInfo info;
            final boolean[] stopped;
            final Done<Device> found;
            int tries;

            Job(NsdServiceInfo info, boolean[] stopped, Done<Device> found) {
                this.info = info;
                this.stopped = stopped;
                this.found = found;
            }
        }

        private static final ArrayDeque<Job> QUEUE = new ArrayDeque<Job>();
        private static Job current;
        private static int token;

        static void add(NsdManager nsd, Job job) {
            synchronized (QUEUE) {
                QUEUE.add(job);
            }
            next(nsd);
        }

        private static void next(final NsdManager nsd) {
            final Job job;
            final int mine;
            synchronized (QUEUE) {
                if (current != null) return;
                Job j = QUEUE.poll();
                while (j != null && j.stopped[0]) j = QUEUE.poll();
                if (j == null) return;
                current = j;
                job = j;
                mine = ++token;
            }
            // Watchdog: some Android versions never call back for a stuck resolve.
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    finish(nsd, mine);
                }
            }, 5000);
            try {
                nsd.resolveService(job.info, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo info, int code) {
                        if (code == NsdManager.FAILURE_ALREADY_ACTIVE && job.tries++ < 5 && !job.stopped[0]) {
                            synchronized (QUEUE) {
                                QUEUE.addFirst(job);
                            }
                            MAIN.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    finish(nsd, mine);
                                }
                            }, 300);
                            return;
                        }
                        finish(nsd, mine);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo info) {
                        if (!job.stopped[0] && isAppleTv(info) && info.getHost() != null) {
                            final Device d = new Device(info.getServiceName(), info.getHost().getHostAddress(), info.getPort());
                            MAIN.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (!job.stopped[0]) job.found.done(d, null);
                                }
                            });
                        }
                        finish(nsd, mine);
                    }
                });
            } catch (RuntimeException e) {
                finish(nsd, mine);
            }
        }

        /** Marks resolve {@code which} as over (once) and starts the next. */
        private static void finish(NsdManager nsd, int which) {
            synchronized (QUEUE) {
                if (current == null || which != token) return;
                current = null;
            }
            next(nsd);
        }
    }

    /** Macs, iPads and HomePods advertise Companion too; their model says so. */
    private static boolean isAppleTv(NsdServiceInfo info) {
        try {
            Map<String, byte[]> attrs = info.getAttributes();
            byte[] model = attrs == null ? null : attrs.get("rpMd");
            return model == null || new String(model, UTF8).startsWith("AppleTV");
        } catch (RuntimeException e) {
            return true;
        }
    }

    // ------------------------------------------------------------ pairing

    /** One pairing attempt: start() makes the Apple TV show a code, finish() checks it. */
    static final class Pairing {
        private final Context context;
        private final Device device;
        private final String port;
        private Companion conn;

        Pairing(Context c, Device device, String port) {
            this.context = c.getApplicationContext();
            this.device = device;
            this.port = port;
        }

        void start(final Done<Boolean> done) {
            WORK.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        closeQuietly();
                        conn = new Companion(device.host, device.port, 5000, 10000);
                        conn.pairStart();
                        conn.setReadTimeout(15000);
                        post(done, true, null);
                    } catch (IOException e) {
                        closeQuietly();
                        post(done, false, describe(e));
                    } catch (RuntimeException e) {
                        closeQuietly();
                        post(done, false, "unexpected reply");
                    }
                }
            });
        }

        /** Result is the number of apps found on the Apple TV. */
        void finish(final String pin, final Done<Integer> done) {
            WORK.execute(new Runnable() {
                @Override
                public void run() {
                    Companion c = conn;
                    if (c == null) {
                        post(done, null, "Start again");
                        return;
                    }
                    try {
                        Companion.Credentials creds = c.pairFinish(pin, NAME);
                        c.close();
                        conn = null;
                        sp(context).edit()
                                .putString("creds", creds.toString())
                                .putString("name", device.name)
                                .putString("host", device.host)
                                .putInt("port", device.port)
                                .putString("input", port)
                                .remove("apps")
                                .apply();
                        int count = 0;
                        try {
                            count = fetchApps(context, false);
                        } catch (IOException | RuntimeException e) {
                            // Linked anyway; the list fills in next time All apps opens.
                            android.util.Log.w(TAG, "app list after linking failed", e);
                        }
                        post(done, count, null);
                    } catch (Companion.RefusedException e) {
                        closeQuietly();
                        post(done, null, e.code == 2 ? "That code didn't match" : describe(e));
                    } catch (IOException e) {
                        closeQuietly();
                        post(done, null, describe(e));
                    } catch (RuntimeException e) {
                        closeQuietly();
                        post(done, null, "unexpected reply");
                    }
                }
            });
        }

        void cancel() {
            WORK.execute(new Runnable() {
                @Override
                public void run() {
                    closeQuietly();
                }
            });
        }

        private void closeQuietly() {
            if (conn != null) conn.close();
            conn = null;
        }
    }

    /**
     * A pairing that's waiting for its code. Kept here rather than in the pairing
     * screen, because reading the code means switching the TV to the Apple TV and
     * pressing Home to come back, which closes the screen. The connection (and so
     * the code on the Apple TV) has to outlive that.
     */
    static final class Pending {
        final Device device;
        final String port;
        final Pairing pairing;
        final long since = System.currentTimeMillis();

        Pending(Device device, String port, Pairing pairing) {
            this.device = device;
            this.port = port;
            this.pairing = pairing;
        }
    }

    private static Pending pending;

    /** The pairing waiting for a code, or null. Gives up after 5 minutes. */
    static Pending pending() {
        if (pending != null && System.currentTimeMillis() - pending.since > 5 * 60 * 1000) clearPending(true);
        return pending;
    }

    static void setPending(Pending p) {
        if (pending != null && pending != p && pending.pairing != p.pairing) pending.pairing.cancel();
        pending = p;
    }

    /** Forget the waiting pairing; {@code cancel} also closes its connection. */
    static void clearPending(boolean cancel) {
        if (pending != null && cancel) pending.pairing.cancel();
        pending = null;
    }

    // ------------------------------------------------------------ using it

    private static final java.util.concurrent.atomic.AtomicBoolean REFRESHING =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile long lastRefresh;

    /**
     * Refreshes the cached app list in the background, at most every 30 seconds.
     * Doesn't wake the Apple TV or search the network for it, so it never holds up a launch.
     */
    static void refreshApps(Context c, final Done<Integer> done) {
        final Context app = c.getApplicationContext();
        if (!isLinked(app)) return;
        if (System.currentTimeMillis() - lastRefresh < 30000 || REFRESHING.getAndSet(true)) return;
        WORK.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    post(done, fetchApps(app, false), null);
                } catch (IOException e) {
                    android.util.Log.w(TAG, "background refresh failed", e);
                    post(done, null, describe(e));
                } catch (RuntimeException e) {
                    android.util.Log.e(TAG, "background refresh crashed", e);
                    post(done, null, "unexpected reply");
                } finally {
                    lastRefresh = System.currentTimeMillis();
                    REFRESHING.set(false);
                }
            }
        });
    }

    /** Wakes the Apple TV and opens an app on it. */
    static void launch(Context c, final String bundleId) {
        final Context app = c.getApplicationContext();
        WORK.execute(new Runnable() {
            @Override
            public void run() {
                Companion conn = null;
                try {
                    conn = session(app, true);
                    try {
                        conn.wake();
                    } catch (IOException ignored) {
                        // Already awake, or this tvOS doesn't take it: launching still works.
                    }
                    conn.launch(bundleId);
                    try {
                        saveApps(app, conn.appList());     // it's awake now, so the list is fresh
                    } catch (IOException ignored) {
                    }
                } catch (final IOException e) {
                    toast(app, name(app) + ": " + describe(e));
                } catch (RuntimeException e) {
                    toast(app, name(app) + ": unexpected reply");
                } finally {
                    if (conn != null) conn.close();
                }
            }
        });
    }

    private static int fetchApps(Context app, boolean rediscover) throws IOException {
        Companion conn = session(app, rediscover);
        try {
            Map<String, String> apps;
            try {
                apps = conn.appList();
            } catch (IOException e) {
                throw step("app list", e);
            }
            android.util.Log.i(TAG, "app list: " + apps.size() + " apps");
            if (apps.isEmpty()) {
                android.util.Log.w(TAG, "empty app list reply: " + summary(conn.lastAppListReply));
            }
            saveApps(app, apps);
            return apps.size();
        } finally {
            conn.close();
        }
    }

    /** Fetch the app list now, searching the network if needed; for the "Refresh" menu item. */
    static void refreshNow(Context c, final Done<Integer> done) {
        final Context app = c.getApplicationContext();
        WORK.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    int n = fetchApps(app, true);
                    if (n == 0) post(done, null, "it sent an empty list (is it asleep?)");
                    else post(done, n, null);
                } catch (IOException e) {
                    android.util.Log.w(TAG, "refresh failed", e);
                    post(done, null, describe(e));
                } catch (RuntimeException e) {
                    android.util.Log.e(TAG, "refresh crashed", e);
                    post(done, null, "unexpected reply (" + e.getClass().getSimpleName() + ")");
                } finally {
                    lastRefresh = System.currentTimeMillis();
                }
            }
        });
    }

    /** Labels where a failure happened ("verify: didn't answer"), keeping refusals as they are. */
    private static IOException step(String where, IOException e) {
        android.util.Log.w(TAG, where + " failed", e);
        if (e instanceof Companion.RefusedException) return e;
        IOException wrapped = new IOException(where + ": " + describe(e));
        wrapped.initCause(e);
        return wrapped;
    }

    private static String summary(Object o) {
        if (o == null) return "null";
        if (o instanceof Map) {
            StringBuilder b = new StringBuilder("{");
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (b.length() > 300) {
                    b.append("…");
                    break;
                }
                b.append(e.getKey()).append('=').append(summary(e.getValue())).append(", ");
            }
            return b.append('}').toString();
        }
        if (o instanceof byte[]) return "bytes[" + ((byte[]) o).length + "]";
        String s = String.valueOf(o);
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    /** Connected, verified and introduced. Optionally finds the Apple TV again if its address changed. */
    private static Companion session(Context app, boolean rediscover) throws IOException {
        SharedPreferences s = sp(app);
        Companion.Credentials creds = Companion.Credentials.parse(s.getString("creds", null));
        if (creds == null) throw new IOException("not linked");
        Companion conn;
        String host = s.getString("host", "");
        int port = s.getInt("port", 0);
        android.util.Log.i(TAG, "connecting to " + host + ":" + port);
        try {
            conn = new Companion(host, port, 2500, 8000);
        } catch (IOException first) {
            if (!rediscover) throw step("connect", first);
            Device d = findAgain(app, s.getString("name", ""));
            if (d == null) throw step("connect", new IOException("not found on the network"));
            android.util.Log.i(TAG, "found again at " + d.host + ":" + d.port);
            s.edit().putString("host", d.host).putInt("port", d.port).apply();
            try {
                conn = new Companion(d.host, d.port, 4000, 8000);
            } catch (IOException e) {
                throw step("connect", e);
            }
        }
        try {
            try {
                conn.verify(creds);
            } catch (IOException e) {
                throw step("verify", e);
            }
            try {
                conn.hello(creds, rpId(app), NAME);
            } catch (IOException e) {
                throw step("hello", e);
            }
            android.util.Log.i(TAG, "session ready");
            return conn;
        } catch (IOException e) {
            conn.close();
            throw e;
        }
    }

    /** Blocking re-discovery by name (Apple TVs pick a new port when they restart). */
    private static Device findAgain(Context app, final String name) {
        final Device[] hit = {null};
        final Object lock = new Object();
        final boolean[] over = {false};
        final Runnable[] stopper = {null};
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                stopper[0] = discover(app, 6000, new Done<Device>() {
                    @Override
                    public void done(Device d, String error) {
                        if (d.name.equals(name)) {
                            synchronized (lock) {
                                hit[0] = d;
                                lock.notifyAll();
                            }
                        }
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        synchronized (lock) {
                            over[0] = true;
                            lock.notifyAll();
                        }
                    }
                });
            }
        });
        synchronized (lock) {
            long until = System.currentTimeMillis() + 7000;
            while (hit[0] == null && !over[0]) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (stopper[0] != null) stopper[0].run();
            }
        });
        return hit[0];
    }

    private static void toast(final Context app, final String msg) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    static String describe(IOException e) {
        if (e instanceof Companion.RefusedException) {
            Companion.RefusedException r = (Companion.RefusedException) e;
            if (r.code == 0) return "needs linking again";
            return e.getMessage();
        }
        if (e instanceof java.net.SocketTimeoutException) return "didn't answer";
        if (e instanceof java.net.ConnectException || e instanceof java.net.NoRouteToHostException) return "couldn't connect";
        String m = e.getMessage();
        return m == null ? "couldn't connect" : m;
    }

    private static <T> void post(final Done<T> done, final T result, final String error) {
        if (done == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                done.done(result, error);
            }
        });
    }
}
