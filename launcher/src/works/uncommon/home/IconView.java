package works.uncommon.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Line icons, bundled brand logos, or an image you supplied (see CustomIcons). */
final class IconView extends View {

    // Corner controls
    static final int THEME = 0, GRID = 3, SETTINGS = 4;
    // Source device types
    static final int CONTROLLER = 10, STREAMER = 11, DISC = 12, LAPTOP = 13, SPEAKER = 14, TV = 15, PORT = 16, HANDHELD = 17;
    // Keep these IDs stable: the chosen kind is saved per HDMI port.
    static final int PLAYSTATION = 18, XBOX = 19, NINTENDO = 20, APPLE = 21;
    // Badges
    static final int PIN = 40;

    private int kind;
    private Drawable logo;
    private int color = 0xFF888888;
    private int haloColor;
    private boolean halo;

    private CustomIcons.Icon image;   // when set, drawn instead of the line icon
    private int backdrop = 0xFFFFFFFF; // background colour, to fade full-colour images
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();

    IconView(Context c, int kind) {
        super(c);
        this.kind = kind;
        logo = loadLogo(kind);
        setFocusable(false);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    void setKind(int k) {
        if (kind != k || image != null) {
            kind = k;
            logo = loadLogo(k);
            image = null;
            invalidate();
        }
    }

    private Drawable loadLogo(int k) {
        int res;
        switch (k) {
            case PLAYSTATION: res = R.drawable.logo_playstation; break;
            case XBOX: res = R.drawable.logo_xbox; break;
            case APPLE: res = R.drawable.logo_apple; break;
            default: return null;
        }
        // Each view has its own tint, including the faded icons in the picker.
        return getContext().getDrawable(res).mutate();
    }

    /** Show your own image instead of a line icon. */
    void setImage(CustomIcons.Icon icon, int backdropColor) {
        image = icon;
        backdrop = backdropColor;
        invalidate();
    }

    void setColor(int c) {
        if (color != c) {
            color = c;
            invalidate();
        }
    }

    /** Soft circle behind the icon (corner controls when selected). */
    void setHalo(boolean on, int c) {
        if (halo != on || haloColor != c) {
            halo = on;
            haloColor = c;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = w / 2f, cy = h / 2f;
        float u = Math.min(w, h) * 0.56f;      // drawing size
        float sw = Math.max(1.5f, u * 0.075f);  // stroke width

        if (halo) {
            fill.setColor(haloColor);
            c.drawCircle(cx, cy, Math.min(w, h) / 2f, fill);
        }
        if (image != null) {
            drawImage(c, w, h);
            return;
        }
        if (logo != null) {
            float scale = Math.min(w * 0.9f / logo.getIntrinsicWidth(),
                    h * 0.66f / logo.getIntrinsicHeight());
            int dw = Math.round(logo.getIntrinsicWidth() * scale);
            int dh = Math.round(logo.getIntrinsicHeight() * scale);
            int left = Math.round((w - dw) / 2f), top = Math.round((h - dh) / 2f);
            logo.setBounds(left, top, left + dw, top + dh);
            logo.setTint(color);
            logo.draw(c);
            return;
        }
        stroke.setColor(color);
        stroke.setStrokeWidth(sw);
        stroke.setPathEffect(null);
        fill.setColor(color);

        float l = cx - u / 2f, t = cy - u / 2f;
        if (kind == PIN) {
            drawPin(c, cx, cy, Math.min(w, h) * 0.94f);
            return;
        }
        switch (kind) {
            case THEME: {
                // Paintbrush: rounded handle running up to the right, a solid metal band,
                // and a full tuft of bristles ending in a painted flick (on a 24-unit grid)
                float k = Math.min(w, h) * 0.8f / 24f;
                float ox = cx - 12 * k, oy = cy - 12 * k;
                c.save();
                c.rotate(45f, ox + 16.2f * k, oy + 7.8f * k);
                r.set(ox + (16.2f - 1.8f) * k, oy + (7.8f - 6.6f) * k, ox + (16.2f + 1.8f) * k, oy + (7.8f + 6.6f) * k);
                c.drawRoundRect(r, 1.8f * k, 1.8f * k, stroke);
                c.restore();
                c.save();
                c.rotate(45f, ox + 10.6f * k, oy + 13.4f * k);
                r.set(ox + (10.6f - 2.7f) * k, oy + (13.4f - 1.3f) * k, ox + (10.6f + 2.7f) * k, oy + (13.4f + 1.3f) * k);
                c.drawRoundRect(r, 0.6f * k, 0.6f * k, fill);
                c.restore();
                path.reset();
                path.moveTo(ox + 10.2f * k, oy + 15.0f * k);
                path.cubicTo(ox + 11.4f * k, oy + 16.8f * k, ox + 10.6f * k, oy + 19.6f * k, ox + 8.2f * k, oy + 20.8f * k);
                path.cubicTo(ox + 6.2f * k, oy + 21.8f * k, ox + 3.8f * k, oy + 21.7f * k, ox + 2.2f * k, oy + 21.0f * k);
                path.cubicTo(ox + 3.6f * k, oy + 20.4f * k, ox + 4.2f * k, oy + 19.4f * k, ox + 4.5f * k, oy + 18.0f * k);
                path.cubicTo(ox + 4.8f * k, oy + 16.0f * k, ox + 6.2f * k, oy + 14.4f * k, ox + 8.0f * k, oy + 14.1f * k);
                path.cubicTo(ox + 8.9f * k, oy + 13.95f * k, ox + 9.6f * k, oy + 14.3f * k, ox + 10.2f * k, oy + 15.0f * k);
                path.close();
                c.drawPath(path, fill);
                break;
            }
            case GRID: {
                float gap = u * 0.16f, cell = (u - gap) / 2f, rad = u * 0.08f;
                for (int row = 0; row < 2; row++) {
                    for (int col = 0; col < 2; col++) {
                        float x = l + col * (cell + gap), y = t + row * (cell + gap);
                        r.set(x, y, x + cell, y + cell);
                        c.drawRoundRect(r, rad, rad, stroke);
                    }
                }
                break;
            }
            case SETTINGS: {
                int teeth = 8;
                float outer = u * 0.50f, inner = u * 0.39f;
                path.reset();
                for (int i = 0; i < teeth * 4; i++) {
                    double a = Math.PI * 2 * i / (teeth * 4) - Math.PI / 2 + Math.PI / (teeth * 4);
                    float rad = (i % 4 < 2) ? outer : inner;
                    float x = cx + (float) (Math.cos(a) * rad), y = cy + (float) (Math.sin(a) * rad);
                    if (i == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                path.close();
                stroke.setPathEffect(new CornerPathEffect(u * 0.05f));
                c.drawPath(path, stroke);
                stroke.setPathEffect(null);
                c.drawCircle(cx, cy, u * 0.15f, stroke);
                break;
            }
            case CONTROLLER: {
                // Body
                r.set(l, t + u * 0.24f, l + u, t + u * 0.78f);
                c.drawRoundRect(r, u * 0.27f, u * 0.27f, stroke);
                // D-pad
                float dx = l + u * 0.28f, dy = t + u * 0.51f, a = u * 0.1f;
                c.drawLine(dx - a, dy, dx + a, dy, stroke);
                c.drawLine(dx, dy - a, dx, dy + a, stroke);
                // Buttons
                float br = u * 0.045f;
                c.drawCircle(l + u * 0.68f, t + u * 0.45f, br, fill);
                c.drawCircle(l + u * 0.78f, t + u * 0.57f, br, fill);
                break;
            }
            case HANDHELD:
            case NINTENDO: {
                // Wide hybrid console: a screen with a controller on each side
                float top = t + u * 0.26f, bot = t + u * 0.74f;
                r.set(l, top, l + u, bot);
                c.drawRoundRect(r, u * 0.12f, u * 0.12f, stroke);
                c.drawLine(l + u * 0.22f, top, l + u * 0.22f, bot, stroke);
                c.drawLine(l + u * 0.78f, top, l + u * 0.78f, bot, stroke);
                float br = u * 0.04f;
                c.drawCircle(l + u * 0.11f, top + u * 0.15f, br, fill);   // stick
                c.drawCircle(l + u * 0.89f, bot - u * 0.15f, br, fill);   // stick
                c.drawCircle(l + u * 0.89f, top + u * 0.13f, br * 0.8f, fill);
                c.drawCircle(l + u * 0.11f, bot - u * 0.13f, br * 0.8f, fill);
                break;
            }
            case STREAMER: {
                // Flat box with a status light
                r.set(l + u * 0.04f, t + u * 0.36f, l + u * 0.96f, t + u * 0.72f);
                c.drawRoundRect(r, u * 0.12f, u * 0.12f, stroke);
                c.drawCircle(l + u * 0.78f, t + u * 0.54f, u * 0.045f, fill);
                c.drawLine(l + u * 0.18f, t + u * 0.82f, l + u * 0.82f, t + u * 0.82f, stroke);
                break;
            }
            case DISC: {
                c.drawCircle(cx, cy, u * 0.46f, stroke);
                c.drawCircle(cx, cy, u * 0.12f, stroke);
                break;
            }
            case LAPTOP: {
                // Screen and a tapered keyboard base.
                r.set(l + u * 0.12f, t + u * 0.12f, l + u * 0.88f, t + u * 0.66f);
                c.drawRoundRect(r, u * 0.05f, u * 0.05f, stroke);
                path.reset();
                path.moveTo(l + u * 0.12f, t + u * 0.66f);
                path.lineTo(l, t + u * 0.84f);
                path.lineTo(l + u, t + u * 0.84f);
                path.lineTo(l + u * 0.88f, t + u * 0.66f);
                path.close();
                c.drawPath(path, stroke);
                c.drawLine(l + u * 0.4f, t + u * 0.75f, l + u * 0.6f, t + u * 0.75f, stroke);
                break;
            }
            case SPEAKER: {
                r.set(l, t + u * 0.36f, l + u, t + u * 0.64f);
                c.drawRoundRect(r, u * 0.14f, u * 0.14f, stroke);
                float dr = u * 0.04f;
                c.drawCircle(l + u * 0.25f, cy, dr, fill);
                c.drawCircle(cx, cy, dr, fill);
                c.drawCircle(l + u * 0.75f, cy, dr, fill);
                break;
            }
            case TV: {
                // Screen with rabbit-ear antenna: live TV
                r.set(l, t + u * 0.34f, l + u, t + u * 0.94f);
                c.drawRoundRect(r, u * 0.1f, u * 0.1f, stroke);
                c.drawLine(cx, t + u * 0.34f, l + u * 0.3f, t + u * 0.06f, stroke);
                c.drawLine(cx, t + u * 0.34f, l + u * 0.7f, t + u * 0.06f, stroke);
                break;
            }
            case PORT:
            default: {
                // Generic HDMI connector outline
                path.reset();
                path.moveTo(l + u * 0.02f, t + u * 0.32f);
                path.lineTo(l + u * 0.98f, t + u * 0.32f);
                path.lineTo(l + u * 0.98f, t + u * 0.52f);
                path.lineTo(l + u * 0.82f, t + u * 0.7f);
                path.lineTo(l + u * 0.18f, t + u * 0.7f);
                path.lineTo(l + u * 0.02f, t + u * 0.52f);
                path.close();
                stroke.setPathEffect(new CornerPathEffect(u * 0.05f));
                c.drawPath(path, stroke);
                stroke.setPathEffect(null);
                c.drawLine(l + u * 0.24f, t + u * 0.47f, l + u * 0.76f, t + u * 0.47f, stroke);
                break;
            }
        }
    }

    /** A push pin, tilted as if just pressed in: solid head, fine needle. */
    private void drawPin(Canvas c, float cx, float cy, float s) {
        float l = cx - s / 2f, t = cy - s / 2f;
        c.save();
        c.rotate(38f, cx, cy);
        stroke.setStrokeWidth(Math.max(1.5f, s * 0.075f));
        path.reset();
        path.moveTo(l + s * 0.31f, t + s * 0.07f);
        path.lineTo(l + s * 0.69f, t + s * 0.07f);
        path.lineTo(l + s * 0.62f, t + s * 0.15f);
        path.lineTo(l + s * 0.62f, t + s * 0.41f);
        path.lineTo(l + s * 0.77f, t + s * 0.58f);
        path.lineTo(l + s * 0.23f, t + s * 0.58f);
        path.lineTo(l + s * 0.38f, t + s * 0.41f);
        path.lineTo(l + s * 0.38f, t + s * 0.15f);
        path.close();
        c.drawPath(path, fill);
        c.drawPath(path, stroke);   // round joins soften the corners
        c.drawLine(cx, t + s * 0.58f, cx, t + s * 0.93f, stroke);
        c.restore();
    }

    private void drawImage(Canvas c, float w, float h) {
        int bw = image.bitmap.getWidth(), bh = image.bitmap.getHeight();
        if (bw <= 0 || bh <= 0) return;
        float maxW = w * 0.9f, maxH = h * 0.66f;
        float k = Math.min(maxW / bw, maxH / bh);
        float dw = bw * k, dh = bh * k;
        dst.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
        src.set(0, 0, bw, bh);
        if (image.tint) {
            // Single-colour logo: paint it in the current text colour
            imagePaint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            imagePaint.setAlpha(255);
        } else {
            // Full-colour image: keep its colours, fade it as far as the text is faded
            imagePaint.setColorFilter(null);
            imagePaint.setAlpha(Math.round(255 * emphasis()));
        }
        c.drawBitmap(image.bitmap, src, dst, imagePaint);
    }

    /** How far the current colour stands out from the background: ~1 selected, ~0.55 normal, ~0.25 faded. */
    private float emphasis() {
        float a = Color.alpha(color) / 255f;
        int d = Math.abs(Color.red(color) - Color.red(backdrop))
                + Math.abs(Color.green(color) - Color.green(backdrop))
                + Math.abs(Color.blue(color) - Color.blue(backdrop));
        return Math.max(0.2f, Math.min(1f, a * d / 600f));
    }
}
