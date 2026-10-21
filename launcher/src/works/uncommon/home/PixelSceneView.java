package works.uncommon.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;

/** Small reusable canvas, enlarged on whole pixels; no animation when hidden. */
abstract class PixelSceneView extends View {
    final Paint ink = new Paint();
    int cols, rows;
    private int pixel;
    private Bitmap buffer;
    private Canvas scene;
    private final Rect destination = new Rect();
    private boolean running;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, frameDelay(SystemClock.uptimeMillis()));
        }
    };

    PixelSceneView(Context context) {
        super(context);
        setFocusable(false);
        setClickable(false);
        ink.setAntiAlias(false);
        ink.setFilterBitmap(false);
        ink.setDither(false);
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        if (w <= 0 || h <= 0) return;
        pixel = Math.max(1, Math.round(h / 270f));
        cols = (w + pixel - 1) / pixel;
        rows = (h + pixel - 1) / pixel;
        buffer = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
        scene = new Canvas(buffer);
        destination.set(0, 0, cols * pixel, rows * pixel);
        prepare();
    }

    void prepare() {}
    abstract void drawScene(Canvas canvas, long now);
    long frameDelay(long now) { return 100; }

    @Override protected void onDraw(Canvas canvas) {
        if (scene == null) return;
        drawScene(scene, SystemClock.uptimeMillis());
        canvas.drawBitmap(buffer, null, destination, ink);
    }

    final void box(Canvas c, int x, int y, int w, int h, int color) {
        ink.setColor(color);
        c.drawRect(x, y, x + w, y + h, ink);
    }

    /** Integer lines keep webs and bed stitching on the same pixel grid. */
    final void line(Canvas c, int x, int y, int endX, int endY, int color) {
        int dx = Math.abs(endX - x), dy = -Math.abs(endY - y);
        int sx = x < endX ? 1 : -1, sy = y < endY ? 1 : -1, error = dx + dy;
        while (true) {
            box(c, x, y, 1, 1, color);
            if (x == endX && y == endY) break;
            int twice = error * 2;
            if (twice >= dy) { error += dy; x += sx; }
            if (twice <= dx) { error += dx; y += sy; }
        }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); update(); }
    @Override protected void onDetachedFromWindow() {
        running = false;
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        update();
    }
    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        update();
    }
    private void update() {
        boolean visible = isAttachedToWindow() && getWindowVisibility() == VISIBLE && isShown();
        if (visible == running) return;
        running = visible;
        removeCallbacks(tick);
        if (visible) { invalidate(); post(tick); }
    }
}
