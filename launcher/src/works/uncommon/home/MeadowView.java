package works.uncommon.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Pixel asterisk flowers poking up from the bottom edge, ported from the Meadow
 * on Shad's site (src/components/Meadow.astro): the same plants, drawn into a
 * low-res bitmap that's scaled up with no smoothing so every bend snaps to the
 * grid, swaying on the same springs. The TV has no pointer, so there's only
 * the wind. The meadow is shared by every screen, so it carries on unbroken
 * from Home into All apps and back.
 */
final class MeadowView extends View {

    /** Height of the meadow, and size of one art pixel (the site's css px, as dp). */
    static final float HEIGHT_DP = 96f;
    private static final float PIXEL_DP = 2f;
    /**
     * Plant sizes as on the site at its full 132px height (66 art pixels), whatever
     * this view's height: flowers, leaves and pixels stay the site's size...
     */
    private static final double SCALE = 1.0;
    /** ...but the stems are shorter, so the meadow sits lower on the TV. */
    private static final double STEM = 0.6;
    /** Stems are rooted below the bottom edge so they run off the screen. */
    private static final int SINK = 4;
    private static final long FRAME_MS = 33;   // ~30 fps; the physics steps at 60

    private static final class Leaf {
        double s, length, width, lift;
        int side;
    }

    private static final class Plant {
        double x, h, radius, armWidth, spin, delay, duration, rest, phase, k, c, angle, vel;
        int color, arms;
        List<Leaf> leaves = new ArrayList<Leaf>();
    }

    private static final class Point {
        double x, y, phi;
        int px, py;
    }

    // ------------------------------------------------------------ shared meadow

    private static long seed = freshSeed();
    private static long growStart = Long.MIN_VALUE / 4;   // fully grown until replanted
    private static List<Plant> plants = new ArrayList<Plant>();
    private static int plantedCols, plantedRows;
    private static long plantedSeed;
    private static long lastStep;

    private static long freshSeed() {
        return (long) Math.floor(Math.random() * 4294967296.0);
    }

    /** A new meadow that grows in from nothing, as on the site when it scrolls into view. */
    static void replant() {
        seed = freshSeed();
        growStart = SystemClock.uptimeMillis();
    }

    // ------------------------------------------------------------ view

    private final Look look;
    private final int leafInk, stemInk;
    private final Paint paint = new Paint();
    private final Rect src = new Rect(), dst = new Rect();
    private int cols, rows, px;
    private Bitmap bitmap;
    private int[] pixels = new int[0];
    private boolean running;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (running) {
                invalidate();
                postOnAnimationDelayed(this, FRAME_MS);
            }
        }
    };

    MeadowView(Context c, Look look) {
        super(c);
        this.look = look;
        setFocusable(false);
        setWillNotDraw(false);
        paint.setFilterBitmap(false);   // keep the pixels hard-edged
        paint.setAntiAlias(false);
        paint.setDither(false);
        leafInk = 0xFF000000 | (look.dim & 0xFFFFFF);
        // Stems sit halfway between the leaves and the page so they recede.
        stemInk = average(look.dim, look.bg);
    }

    private static int average(int a, int b) {
        int r = Math.round((((a >> 16) & 0xFF) + ((b >> 16) & 0xFF)) / 2f);
        int g = Math.round((((a >> 8) & 0xFF) + ((b >> 8) & 0xFF)) / 2f);
        int bl = Math.round(((a & 0xFF) + (b & 0xFF)) / 2f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Height to give the view, in px. */
    static int heightPx(Context c) {
        return Math.round(HEIGHT_DP * c.getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w == 0 || h == 0) return;
        float density = getResources().getDisplayMetrics().density;
        // px per art pixel, snapped so each one is whole device pixels
        px = Math.max(1, Math.round(PIXEL_DP * density));
        cols = (int) Math.ceil(w / (double) px);
        rows = (int) Math.ceil(h / (double) px);
        bitmap = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
        pixels = new int[cols * rows];
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        removeCallbacks(frame);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        update();
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        update();
    }

    /** Sway only while on screen. */
    private void update() {
        boolean on = isAttachedToWindow() && getWindowVisibility() == VISIBLE && isShown();
        if (on == running) return;
        running = on;
        removeCallbacks(frame);
        if (on) {
            lastStep = SystemClock.uptimeMillis();
            postOnAnimation(frame);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap == null) return;
        if (plantedCols != cols || plantedRows != rows || plantedSeed != seed) {
            grow(cols, rows, seed);
        }
        long now = SystemClock.uptimeMillis();
        step(now);
        render(now);
        bitmap.setPixels(pixels, 0, cols, 0, 0, cols, rows);
        src.set(0, 0, cols, rows);
        // Anchored bottom-left like the site's canvas; any spare row is cut off the top.
        dst.set(0, getHeight() - rows * px, cols * px, getHeight());
        canvas.drawBitmap(bitmap, src, dst, paint);
    }

    // ------------------------------------------------------------ planting

    private static double clamp01(double n) {
        return Math.min(1, Math.max(0, n));
    }

    private static double easeOut(double t) {
        return 1 - Math.pow(1 - t, 3);
    }

    private static double easeOutBack(double t) {
        return 1 + 2.70158 * Math.pow(t - 1, 3) + 1.70158 * Math.pow(t - 1, 2);
    }

    /** mulberry32, as on the site. */
    private static final class Rand {
        private int seed;

        Rand(long seed) {
            this.seed = (int) seed;
        }

        double next() {
            seed = seed + 0x6d2b79f5;
            int t = (seed ^ (seed >>> 15)) * (1 | seed);
            t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
            return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
        }

        double between(double min, double max) {
            return min + next() * (max - min);
        }
    }

    private static double hue;

    private static void grow(int cols, int rows, long seed) {
        Rand rand = new Rand(seed);
        double scale = SCALE;
        List<Plant> next = new ArrayList<Plant>();
        // golden-angle hue steps so neighbouring flowers never match
        hue = rand.next() * 360;

        double x = rand.between(4, 14);
        while (x < cols + 8) {
            double kind = rand.next();
            if (kind < 0.36) {
                plant(next, rand, scale, x, rand.between(34, 50), rand.between(6, 8), 2);
                if (rand.next() < 0.5) {
                    double px = x + (rand.next() < 0.5 ? -1 : 1) * rand.between(8, 11);
                    double h = rand.between(18, 26);
                    double r = rand.between(3.5, 5);
                    plant(next, rand, scale, px, h, r, rand.next() < 0.5 ? 1 : 0);
                }
            } else if (kind < 0.62) {
                int count = rand.next() < 0.5 ? 2 : 3;
                for (int i = 0; i < count; i++) {
                    double px = x + i * rand.between(7, 9);
                    double h = rand.between(16, 32);
                    double r = rand.between(3, 5);
                    plant(next, rand, scale, px, h, r, rand.next() < 0.4 ? 1 : 0);
                }
            } else {
                double h = rand.between(24, 36);
                double r = rand.between(4.5, 6);
                plant(next, rand, scale, x, h, r, rand.next() < 0.5 ? 1 : 2);
            }
            x += rand.between(22, 38);
        }

        // tallest drawn first so shorter ones sit in front
        Collections.sort(next, new Comparator<Plant>() {
            @Override
            public int compare(Plant a, Plant b) {
                return Double.compare(b.h, a.h);
            }
        });
        plants = next;
        plantedCols = cols;
        plantedRows = rows;
        plantedSeed = seed;
    }

    private static void plant(List<Plant> into, Rand rand, double scale, double x, double h, double radius,
                              int leafCount) {
        Plant p = new Plant();
        int side = rand.next() < 0.5 ? 1 : -1;
        for (int i = 0; i < leafCount; i++) {
            Leaf l = new Leaf();
            l.s = rand.between(0.22, 0.34) + i * 0.2;
            l.side = side;
            l.length = rand.between(7, 11) * scale;
            l.width = rand.between(1.6, 2.1);
            l.lift = rand.between(0.55, 0.95);
            p.leaves.add(l);
            side = side == 1 ? -1 : 1;
        }
        double pickArms = rand.next();
        int arms = pickArms < 0.4 ? 5 : pickArms < 0.75 ? 6 : 4;
        double armWidth = Math.min(1.5, Math.max(0.75, radius * scale * 0.19));
        double stemLength = h * scale * STEM + SINK;
        double freq = 1.5 + rand.next() * 0.7 - (h / 50) * 0.5;
        double k = Math.pow(Math.PI * 2 * freq, 2);
        hue = (hue + 137.508) % 360;
        // pastel lightness and chroma, in step with the site's tag colours
        double L = round(rand.between(0.82, 0.9), 3);
        double C = round(rand.between(0.08, 0.13), 3);
        p.x = Math.round(x);
        p.h = stemLength;
        // four-petal heads need a few pixels before the notches read
        p.radius = arms == 4 ? Math.max(4.6, radius * scale) : radius * scale;
        p.color = oklch(L, C, round(hue, 1));
        p.arms = arms;
        p.armWidth = armWidth;
        // mostly upright like the favicon asterisk, with a little wobble
        p.spin = (arms == 6 && rand.next() < 0.5 ? Math.PI / 6 : 0) + (rand.next() - 0.5) * 0.4;
        p.delay = rand.between(0, 550);
        p.duration = rand.between(900, 1400);
        p.rest = (rand.next() - 0.5) * 0.12;
        p.phase = rand.next() * Math.PI * 2;
        p.k = k;
        p.c = 2 * (0.1 + rand.next() * 0.05) * Math.sqrt(k);
        into.add(p);
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    /** CSS oklch() to an opaque sRGB colour, clipped to the gamut as a canvas does. */
    static int oklch(double L, double C, double hDeg) {
        double h = Math.toRadians(hDeg);
        double a = C * Math.cos(h), b = C * Math.sin(h);
        double l_ = L + 0.3963377774 * a + 0.2158037573 * b;
        double m_ = L - 0.1055613458 * a - 0.0638541728 * b;
        double s_ = L - 0.0894841775 * a - 1.2914855480 * b;
        double l = l_ * l_ * l_, m = m_ * m_ * m_, s = s_ * s_ * s_;
        double r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
        double g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
        double bl = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;
        return 0xFF000000 | (channel(r) << 16) | (channel(g) << 8) | channel(bl);
    }

    private static int channel(double v) {
        v = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
    }

    // ------------------------------------------------------------ wind

    /** Spring each stem towards a gently moving rest angle (the site's idle sway). */
    private static void step(long now) {
        double total = Math.min(1 / 30.0, Math.max(0.001, (now - lastStep) / 1000.0));
        // the frame can run slower than the site's 60 fps; step the springs at 60 regardless
        int n = Math.max(1, (int) Math.ceil(total / (1 / 60.0)));
        double dt = total / n;
        lastStep = now;
        for (int i = n - 1; i >= 0; i--) {
            double t = (now - i * dt * 1000) / 1000.0;
            for (Plant p : plants) {
                double target = p.rest + 0.035 * Math.sin(t * 1.3 + p.x * 0.0225)
                        + 0.018 * Math.sin(t * 2.6 + p.phase);
                double acc = -p.k * (p.angle - target) - p.c * p.vel;
                p.vel = Math.max(-9, Math.min(9, p.vel + acc * dt));
                p.angle = Math.max(-0.75, Math.min(0.75, p.angle + p.vel * dt));
            }
        }
    }

    // ------------------------------------------------------------ drawing

    private void put(int x, int y, int color) {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return;
        pixels[y * cols + x] = color;
    }

    /** Walk the stem along its arc, curling harder toward the tip. */
    private List<Point> stem(Plant p, double length) {
        List<Point> points = new ArrayList<Point>();
        double x = p.x + 0.5;
        double y = rows + SINK;
        long steps = Math.round(length * 2);
        for (int i = 1; i <= steps; i++) {
            double phi = (2 * p.angle * i) / (p.h * 2);
            x += Math.sin(phi) * 0.5;
            y -= Math.cos(phi) * 0.5;
            Point point = new Point();
            point.x = x;
            point.y = y;
            point.px = (int) Math.floor(x);
            point.py = (int) Math.round(y);
            point.phi = phi;
            int n = points.size();
            Point last = n > 0 ? points.get(n - 1) : null;
            if (last != null && last.px == point.px && last.py == point.py) continue;
            // drop elbow pixels so diagonals stay one pixel thin
            Point prev = n > 1 ? points.get(n - 2) : null;
            if (prev != null && last != null && Math.abs(point.px - prev.px) == 1 && Math.abs(point.py - prev.py) == 1) {
                points.remove(n - 1);
            }
            points.add(point);
        }
        return points;
    }

    private void leaf(Point base, Leaf l, double grown) {
        double length = l.length * grown;
        if (length < 1) return;
        double width = l.width * (0.4 + 0.6 * grown);
        double turn = base.phi + l.side * l.lift;
        double dx = Math.sin(turn);
        double dy = -Math.cos(turn);
        double ox = base.px + 0.5;
        double oy = base.py + 0.5;
        int reach = (int) Math.ceil(length + 1);
        for (int y = base.py - reach; y <= base.py + reach; y++) {
            for (int x = base.px - reach; x <= base.px + reach; x++) {
                double rx = x + 0.5 - ox;
                double ry = y + 0.5 - oy;
                double t = rx * dx + ry * dy;
                if (t < 0.5 || t > length) continue;
                if (Math.abs(rx * dy - ry * dx) <= width * Math.pow(Math.sin((Math.PI * t) / length), 0.6)) {
                    put(x, y, leafInk);
                }
            }
        }
    }

    private void head(double cx, double cy, Plant p, double bloom, double turn) {
        double radius = p.radius * easeOutBack(bloom);
        if (radius < 0.75) return;
        double armWidth = p.armWidth * Math.min(1, 0.6 + 0.4 * bloom);
        double core = p.arms == 4 ? Math.min(1.2, radius * 0.3) : Math.min(armWidth * 1.4, radius * 0.4);
        // quantised so idle sway doesn't make the edges shimmer
        double rotation = Math.round((p.spin + turn + (1 - bloom) * 1.4) / 0.06) * 0.06;
        // four-petal heads: a scalloped disc split by notches between the petals
        double notchX = Math.sin(rotation + Math.PI / 4);
        double notchY = -Math.cos(rotation + Math.PI / 4);
        int reach = (int) Math.ceil(radius + 1);
        int left = (int) Math.floor(cx);
        int top = (int) Math.floor(cy);
        for (int y = top - reach; y <= top + reach; y++) {
            for (int x = left - reach; x <= left + reach; x++) {
                double rx = x + 0.5 - cx;
                double ry = y + 0.5 - cy;
                boolean inside = rx * rx + ry * ry <= core * core;
                if (p.arms == 4) {
                    double edge = radius * (0.7 + 0.3 * Math.sqrt(Math.abs(Math.cos(2 * (Math.atan2(rx, -ry) - rotation)))));
                    double notch = Math.min(Math.abs(rx * notchY - ry * notchX), Math.abs(rx * notchX + ry * notchY));
                    inside = inside || (Math.hypot(rx, ry) <= edge && notch >= 0.7);
                } else {
                    for (int arm = 0; !inside && arm < p.arms; arm++) {
                        double a = rotation + (arm * Math.PI * 2) / p.arms;
                        double dx = Math.sin(a);
                        double dy = -Math.cos(a);
                        double t = rx * dx + ry * dy;
                        inside = t >= 0 && t <= radius && Math.abs(rx * dy - ry * dx) <= armWidth;
                    }
                }
                if (inside) put(x, y, p.color);
            }
        }
    }

    private void render(long now) {
        java.util.Arrays.fill(pixels, 0);
        for (Plant p : plants) {
            double g = clamp01((now - growStart - p.delay) / p.duration);
            if (g <= 0) continue;
            double reach = easeOut(clamp01(g / 0.7));
            List<Point> points = stem(p, p.h * reach);
            if (points.isEmpty()) continue;
            for (Point point : points) put(point.px, point.py, stemInk);
            for (Leaf l : p.leaves) {
                double grown = easeOut(clamp01((reach - l.s) / 0.2));
                if (grown > 0) {
                    int at = (int) Math.min(points.size() - 1, Math.round(l.s * (points.size() - 1) / reach));
                    leaf(points.get(at), l, grown);
                }
            }
            Point tip = points.get(points.size() - 1);
            head(tip.px + 1, tip.py, p, clamp01((g - 0.5) / 0.5), tip.phi * 0.7);
        }
    }
}
