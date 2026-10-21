package works.uncommon.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The Blue Sky theme: a pixel-art sky over a Ghibli-ish field, with clouds that
 * drift in alternating directions, row by row. Drawn at a low resolution and
 * scaled up with no smoothing, so everything snaps to a grid of big pixels.
 *
 * The sky and the field are painted once; each cloud is its own little sprite
 * that moves a whole art pixel at a time. Positions come from the clock, so the
 * clouds carry on unbroken from Home into All apps and back.
 */
final class SkyView extends View {

    private static final float PIXEL_DP = 2f;      // same grid as the Flowers meadow
    private static final long TICK_MS = 50;         // check for a whole-pixel move this often
    private static final long SEED = new Random().nextLong();   // one sky per launch

    // Sky, top to horizon. Muted, late-afternoon blues: not too bright behind white type.
    private static final int[] SKY = {0xFF27518A, 0xFF2F5F9C, 0xFF3A6EAD, 0xFF4A80BC, 0xFF5E93C8, 0xFF79A8D3};
    // Clouds, as in the reference: a pale sky-blue body (not white), a near-white rim
    // along the tops, a slightly deeper blue where one puff tucks behind another and
    // along the flat base.
    private static final int CLOUD_RIM = 0xFFF4FAFF, CLOUD_LIGHT = 0xFFD3E7FA, CLOUD = 0xFFB7D5F3,
            CLOUD_SHADE = 0xFF97BCE6, CLOUD_BASE = 0xFF88B0DE;
    // Field: far blue-green hills, then meadow, then the grass up close
    private static final int HILL_FAR = 0xFF557F86, HILL_FAR_DARK = 0xFF4B7279;
    private static final int MEADOW_LIGHT = 0xFF7FAF5C, MEADOW = 0xFF6A9C4C, MEADOW_DARK = 0xFF588A40;
    private static final int GRASS = 0xFF4C7D38, GRASS_DARK = 0xFF3E6A2F, GRASS_LIGHT = 0xFF8DBE63;
    private static final int[] FLOWER_DOTS = {0xFFFFF4D6, 0xFFFFE07A, 0xFFF6B7C8};

    // 4x4 ordered dither, for the banded gradients
    private static final int[][] BAYER = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};

    private static final class Cloud {
        Bitmap sprite;
        int w, h;
        double x0, y, speed;   // art px; speed in art px per second (sign = direction)
        int lastX = Integer.MIN_VALUE;
    }

    private final Paint paint = new Paint();
    private final Rect src = new Rect(), dst = new Rect();
    private int px, cols, rows;
    private Bitmap sky, field;
    private final List<Cloud> clouds = new ArrayList<Cloud>();
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (moved()) invalidate();
            postOnAnimationDelayed(this, TICK_MS);
        }
    };

    SkyView(Context c) {
        super(c);
        setFocusable(false);
        setWillNotDraw(false);
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
        paint.setDither(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w == 0 || h == 0) return;
        px = Math.max(1, Math.round(PIXEL_DP * getResources().getDisplayMetrics().density));
        cols = (int) Math.ceil(w / (double) px);
        rows = (int) Math.ceil(h / (double) px);
        paint();
    }

    // ------------------------------------------------------------ painting, once

    private int horizon() {
        return Math.round(rows * 0.74f);
    }

    private void paint() {
        Random rand = new Random(SEED);
        int horizon = horizon();

        // Sky: bands from top to horizon, dithered where they meet
        int[] p = new int[cols * rows];
        for (int y = 0; y < rows; y++) {
            double t = Math.min(1, y / (double) horizon) * (SKY.length - 1);
            int band = Math.min(SKY.length - 2, (int) t);
            double f = t - band;
            for (int x = 0; x < cols; x++) {
                boolean upper = f * 16 > BAYER[y & 3][x & 3] + 0.5;
                p[y * cols + x] = upper ? SKY[band + 1] : SKY[band];
            }
        }
        sky = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
        sky.setPixels(p, 0, cols, 0, 0, cols, rows);

        // Field: transparent above, so it can sit in front of the lowest clouds
        int[] f = new int[cols * rows];
        double a1 = rand.nextDouble() * 6, a2 = rand.nextDouble() * 6, a3 = rand.nextDouble() * 6;
        for (int x = 0; x < cols; x++) {
            // far hills: long soft swells rising just above the horizon
            int far = horizon - (int) Math.round(7 + 5 * Math.sin(x * 0.018 + a1) + 3 * Math.sin(x * 0.047 + a2));
            // the meadow's near edge: gentler, lower
            int near = horizon + (int) Math.round(4 + 3 * Math.sin(x * 0.011 + a3) + 1.5 * Math.sin(x * 0.06 + a1));
            for (int y = Math.max(0, far); y < rows; y++) {
                int c;
                if (y < near) {
                    // far hills, a darker band towards their foot
                    c = (y - far) > (near - far) * 0.55 && ((x + y) & 1) == 0 ? HILL_FAR_DARK : HILL_FAR;
                    if (y - far > (near - far) * 0.8) c = HILL_FAR_DARK;
                } else {
                    // meadow lit at the top, darkening towards you, dithered between bands
                    double d = (y - near) / (double) Math.max(1, rows - near);
                    int th = BAYER[y & 3][x & 3];
                    if (d < 0.18) c = d * 16 / 0.18 > th ? MEADOW : MEADOW_LIGHT;
                    else if (d < 0.5) c = (d - 0.18) * 16 / 0.32 > th ? MEADOW_DARK : MEADOW;
                    else c = (d - 0.5) * 16 / 0.5 > th ? GRASS : MEADOW_DARK;
                }
                f[y * cols + x] = c;
            }
        }
        // grass tufts along the meadow, thicker and taller towards the bottom
        int tufts = cols * 3;
        for (int i = 0; i < tufts; i++) {
            int x = rand.nextInt(cols);
            int near = horizon + (int) Math.round(4 + 3 * Math.sin(x * 0.011 + a3) + 1.5 * Math.sin(x * 0.06 + a1));
            if (near >= rows) continue;
            int y = near + 2 + (int) Math.round(Math.pow(rand.nextDouble(), 0.7) * (rows - near - 2));
            double depth = (y - near) / (double) Math.max(1, rows - near);
            int tall = 1 + (int) Math.round(depth * 3 * rand.nextDouble());
            int c = depth > 0.45 ? GRASS_DARK : GRASS;
            for (int k = 0; k < tall; k++) set(f, x + (k == tall - 1 && rand.nextBoolean() ? (rand.nextBoolean() ? 1 : -1) : 0), y - k, c);
            if (depth < 0.6 && rand.nextInt(5) == 0) set(f, x, y - tall, GRASS_LIGHT);
        }
        // a scatter of tiny flowers in the meadow
        int dots = cols / 6;
        for (int i = 0; i < dots; i++) {
            int x = rand.nextInt(cols);
            int near = horizon + (int) Math.round(4 + 3 * Math.sin(x * 0.011 + a3) + 1.5 * Math.sin(x * 0.06 + a1));
            if (near + 3 >= rows) continue;
            int y = near + 3 + rand.nextInt(Math.max(1, rows - near - 3));
            set(f, x, y, FLOWER_DOTS[rand.nextInt(FLOWER_DOTS.length)]);
        }
        field = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
        field.setPixels(f, 0, cols, 0, 0, cols, rows);

        // Clouds: rows across the sky, alternating direction, bigger and quicker lower down
        clouds.clear();
        int lanes = 4;
        for (int lane = 0; lane < lanes; lane++) {
            double depth = lane / (double) (lanes - 1);            // 0 high and far .. 1 low and near
            double y = rows * (0.06 + 0.19 * lane);
            double dir = lane % 2 == 0 ? 1 : -1;
            double speed = dir * (1.2 + 2.6 * depth) * (0.85 + rand.nextDouble() * 0.3);
            int count = 2 + rand.nextInt(2);
            double span = cols + 160;
            for (int i = 0; i < count; i++) {
                Cloud cl = makeCloud(rand, 0.55 + 1.05 * depth);
                cl.y = y - cl.h * 0.5 + (rand.nextDouble() - 0.5) * rows * 0.05;
                cl.x0 = span * (i + rand.nextDouble() * 0.6) / count;
                cl.speed = speed;
                clouds.add(cl);
            }
        }
    }

    private void set(int[] a, int x, int y, int c) {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return;
        a[y * cols + x] = c;
    }

    /**
     * A puffy pixel cloud, built like the reference: a few dome-shaped puffs on a flat
     * base, layered back to front. Each puff's visible top edge gets a crisp light rim;
     * where a puff sits in front of another, the one behind darkens along the join; the
     * base is a flat, deeper band; and a few lone pixels catch the light near the tops.
     */
    private static Cloud makeCloud(Random rand, double size) {
        // {cx, cyFromBase(negative is up), rx, ry, wobble phase, wobble phase}; drawn in
        // list order, later = in front
        List<double[]> back = new ArrayList<double[]>(), shoulders = new ArrayList<double[]>(),
                front = new ArrayList<double[]>();
        int puffs = 3 + rand.nextInt(2);
        double x = 0;
        double biggest = 0;
        for (int i = 0; i < puffs; i++) {
            double mid = 1 - Math.abs((i + 0.5) / puffs - 0.5) * 1.6;    // 1 in the middle, ~0.2 at the ends
            double r = size * (6 + 9 * Math.max(0.2, mid) + rand.nextDouble() * 3);
            x += i == 0 ? r : r * (0.55 + rand.nextDouble() * 0.25);
            boolean isBack = mid > 0.5;
            // the big middle puffs tower at the back; the smaller outer ones sit low in front
            double lift = r * (isBack ? 0.3 + rand.nextDouble() * 0.2 : 0.06 + rand.nextDouble() * 0.14);
            double[] puff = {x, -lift, r, r * (1.0 + rand.nextDouble() * 0.15),
                    rand.nextDouble() * 6.3, rand.nextDouble() * 6.3};
            (isBack ? back : front).add(puff);
            // little puffs heaped on a big one's shoulder, so the top is lumpy, not one arc
            if (false && isBack && r > biggest && r > size * 7) {   // (heaped bumps: off, the wobble does it)
                biggest = r;
                shoulders.clear();
                int bumps = rand.nextDouble() < 0.6 ? 1 : 0;
                for (int b = 0; b < bumps; b++) {
                    double side = (b == 0) == rand.nextBoolean() ? 1 : -1;
                    double br = r * (0.38 + rand.nextDouble() * 0.14);
                    double ang = side * (0.55 + rand.nextDouble() * 0.45);      // radians from straight up
                    double bx = x + Math.sin(ang) * (r - br * 0.55);
                    double by = -lift - Math.cos(ang) * (r - br * 0.55) * 1.0;
                    shoulders.add(new double[]{bx, by, br, br * 1.05, rand.nextDouble() * 6.3, rand.nextDouble() * 6.3});
                }
            }
            x += r * 0.4;
        }
        // biggest at the back within each layer
        java.util.Comparator<double[]> bigFirst = new java.util.Comparator<double[]>() {
            @Override
            public int compare(double[] a, double[] b) {
                return Double.compare(b[2], a[2]);
            }
        };
        java.util.Collections.sort(back, bigFirst);
        java.util.Collections.sort(front, bigFirst);
        List<double[]> all = new ArrayList<double[]>(back);
        all.addAll(shoulders);
        all.addAll(front);

        double minY = 0, maxX = 0;
        for (double[] q : all) {
            minY = Math.min(minY, q[1] - q[3]);
            maxX = Math.max(maxX, q[0] + q[2]);
        }
        int w = (int) Math.ceil(maxX) + 3;
        int flat = (int) Math.ceil(-minY) + 1;           // first row below the cloud
        int h = flat;
        int n = all.size();

        // who owns each pixel: the front-most puff covering it (-1 = sky)
        int[] own = new int[w * h];
        java.util.Arrays.fill(own, -1);
        for (int k = 0; k < n; k++) {
            double[] q = all.get(k);
            double cx = q[0] + 1, cy = flat + q[1];
            for (int py = 0; py < h; py++) {
                for (int pxl = 0; pxl < w; pxl++) {
                    double dx = (pxl + 0.5 - cx) / q[2], dy = (py + 0.5 - cy) / q[3];
                    // a gentle wobble round the edge, so puffs are mounds rather than circles
                    double a = Math.atan2(dy, dx);
                    double edge = 1 + 0.06 * Math.sin(3 * a + q[4]) + 0.035 * Math.sin(5 * a + q[5]);
                    if (dx * dx + dy * dy <= edge * edge) own[py * w + pxl] = k;
                }
            }
        }
        // the flat base: fill under the puffs down to the bottom row
        for (int pxl = 0; pxl < w; pxl++) {
            int last = -1;
            for (int py = 0; py < h; py++) if (own[py * w + pxl] >= 0) last = own[py * w + pxl];
            if (last < 0) continue;
            for (int py = h - 1; py >= 0 && own[py * w + pxl] < 0; py--) {
                // only fill gaps that sit under cloud (between the puffs' lower edges)
                boolean under = false;
                for (int yy = py - 1; yy >= 0; yy--) if (own[yy * w + pxl] >= 0) { under = true; break; }
                if (!under) break;
                own[py * w + pxl] = last;
            }
        }
        // tidy the silhouette: drop lone pixels that stick out, so edges step cleanly
        for (int py = 0; py < h; py++) {
            for (int pxl = 0; pxl < w; pxl++) {
                if (own[py * w + pxl] < 0) continue;
                int nb = 0;
                if (pxl > 0 && own[py * w + pxl - 1] >= 0) nb++;
                if (pxl < w - 1 && own[py * w + pxl + 1] >= 0) nb++;
                if (py > 0 && own[(py - 1) * w + pxl] >= 0) nb++;
                if (py < h - 1 && own[(py + 1) * w + pxl] >= 0) nb++;
                if (nb <= 1) own[py * w + pxl] = -1;
            }
        }

        // Light comes from the top right, as in the reference: each puff's upper-right
        // edge carries a broken bright band with specks of light inside it; its left side
        // has no rim; a puff in front casts a soft shadow down and to the left onto the
        // puff behind; and the flat base is a deeper band.
        int[] p = new int[w * h];
        for (int py = 0; py < h; py++) {
            for (int pxl = 0; pxl < w; pxl++) {
                int k = own[py * w + pxl];
                if (k < 0) continue;
                double[] q = all.get(k);
                double nx = (pxl + 0.5 - (q[0] + 1)) / q[2], ny = (py + 0.5 - (flat + q[1])) / q[3];
                double len = Math.max(1e-6, Math.sqrt(nx * nx + ny * ny));
                double lit = (0.6 * nx - 0.8 * ny) / len;          // facing the light: 1, away: -1
                // how far in from this puff's visible edge, looking up and to the right
                int depth = 0;
                while (depth < 4) {
                    int ux = pxl + (depth + 1 + 1) / 2, uy = py - (depth + 1);
                    if (ux >= w || uy < 0) break;
                    int o = own[uy * w + ux];
                    if (o == k || o > k) depth++;
                    else break;
                }
                if (depth == 4) depth = 99;     // no edge close by: deep inside the puff
                boolean edgeUp = py == 0 || (own[(py - 1) * w + pxl] != k && own[(py - 1) * w + pxl] < k);
                boolean edgeRight = pxl == w - 1 || (own[py * w + pxl + 1] != k && own[py * w + pxl + 1] < k);
                int fromBase = h - 1 - py;
                int hash = pxl * 0x27d4eb2d + py * 0x165667b1 + k * 0x9E3779B9;
                hash ^= hash >>> 15;
                hash *= 0x2c1b3c6d;
                hash ^= hash >>> 12;
                hash *= 0x297a2d39;
                hash ^= hash >>> 15;
                double noise = (hash & 0xFFFFFF) / (double) 0xFFFFFF;

                int c = CLOUD;
                // is the edge against open sky, or against a puff behind this one?
                boolean againstSky = (py == 0 || own[(py - 1) * w + pxl] < 0)
                        || (pxl == w - 1 || own[py * w + pxl + 1] < 0);
                if ((edgeUp || edgeRight) && (againstSky ? lit > -0.15 : lit > 0.3)) {
                    c = CLOUD_RIM;                                          // the lit rim
                } else if ((edgeUp || edgeRight) && !againstSky && lit > -0.1) {
                    c = CLOUD_LIGHT;                                        // fading down the join
                } else if (lit > 0.35 && depth <= 1) {
                    c = CLOUD_RIM;                                          // the band thickens on the bright side
                } else if (lit > 0.5 && depth == 2) {
                    c = noise < 0.7 ? CLOUD_RIM : CLOUD_LIGHT;               // thickest at the shoulder
                } else if (lit > 0.5 && depth == 3) {
                    c = noise < 0.35 ? CLOUD_RIM : noise < 0.55 ? CLOUD_LIGHT : CLOUD;   // breaking up into dashes
                } else if (lit > 0.45 && depth <= 4) {
                    c = noise < 0.1 ? CLOUD_RIM : noise < 0.18 ? CLOUD_LIGHT : CLOUD;    // the odd speck of light
                } else if (edgeUp && lit <= -0.15) {
                    c = CLOUD_LIGHT;                                        // the top edge on the shady side, softer
                }
                // shadow cast by a puff in front, up and to the right of this pixel
                if (c == CLOUD || c == CLOUD_LIGHT) {
                    for (int sdist = 1; sdist <= 3; sdist++) {
                        int sx = pxl + sdist, sy = py - sdist + 1;
                        if (sx < w && sy >= 0 && own[sy * w + sx] > k) {
                            c = (sdist <= 2 || noise < 0.5) ? CLOUD_SHADE : c;
                            break;
                        }
                        sx = pxl + 1;
                        sy = py - sdist;
                        if (sx < w && sy >= 0 && own[sy * w + sx] > k && sdist <= 2) {
                            c = CLOUD_SHADE;
                            break;
                        }
                    }
                }
                // the lower left of each puff, turned away from the light, a touch deeper
                if (c == CLOUD && lit < -0.75 && noise < 0.5) c = CLOUD_SHADE;
                // the flat base band, ragged on top
                if (fromBase <= 1) c = CLOUD_BASE;
                else if (fromBase == 2 && noise < 0.6) c = CLOUD_SHADE;
                else if (fromBase == 3 && noise < 0.2) c = CLOUD_SHADE;
                p[py * w + pxl] = c;
            }
        }
        Cloud cl = new Cloud();
        cl.w = w;
        cl.h = h;
        cl.sprite = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        cl.sprite.setPixels(p, 0, w, 0, 0, w, h);
        return cl;
    }

    // ------------------------------------------------------------ drifting

    private int xOf(Cloud c, long now) {
        double span = cols + c.w + 40;
        double x = (c.x0 + c.speed * now / 1000.0) % span;
        if (x < 0) x += span;
        return (int) Math.floor(x) - c.w;
    }

    /** True if any cloud has moved on by a whole art pixel since it was last drawn. */
    private boolean moved() {
        long now = SystemClock.uptimeMillis();
        for (Cloud c : clouds) if (xOf(c, now) != c.lastX) return true;
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (sky == null) return;
        long now = SystemClock.uptimeMillis();
        src.set(0, 0, cols, rows);
        dst.set(0, 0, cols * px, rows * px);
        canvas.drawBitmap(sky, src, dst, paint);
        for (Cloud c : clouds) {
            int x = xOf(c, now);
            c.lastX = x;
            int y = (int) Math.round(c.y);
            src.set(0, 0, c.w, c.h);
            dst.set(x * px, y * px, (x + c.w) * px, (y + c.h) * px);
            canvas.drawBitmap(c.sprite, src, dst, paint);
        }
        src.set(0, 0, cols, rows);
        dst.set(0, 0, cols * px, rows * px);
        canvas.drawBitmap(field, src, dst, paint);
    }

    // ------------------------------------------------------------ only while on screen

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        removeCallbacks(tick);
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

    private void update() {
        boolean on = isAttachedToWindow() && getWindowVisibility() == VISIBLE && isShown();
        if (on == running) return;
        running = on;
        removeCallbacks(tick);
        if (on) postOnAnimation(tick);
    }
}
