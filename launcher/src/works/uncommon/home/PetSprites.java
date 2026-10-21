package works.uncommon.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Load each separated silhouette once, then retain only tiny pixel sprites. */
final class PetSprites {
    private static PetSprites dog, cat;
    final Bitmap[] run = new Bitmap[4], sit = new Bitmap[4], walk = new Bitmap[4],
            sleep = new Bitmap[2], look = new Bitmap[2], jump = new Bitmap[4], ride = new Bitmap[2];
    private static final class Part {
        int id, count, left, top, right, bottom;
    }

    static PetSprites dog(Context c) {
        if (dog == null) dog = new PetSprites(c, true);
        return dog;
    }
    static PetSprites cat(Context c) {
        if (cat == null) cat = new PetSprites(c, false);
        return cat;
    }

    private PetSprites(Context context, boolean isDog) {
        float scale = isDog ? 0.14f : 0.084f;
        Bitmap[] base = load(context, isDog ? "themes/moose.png" : "themes/bengal.png", scale);
        System.arraycopy(base, 0, run, 0, 4);
        System.arraycopy(base, 4, sit, 0, 4);
        Bitmap[] actions = load(context,
                isDog ? "themes/moose-actions.png" : "themes/tofu-actions.png", scale);
        if (isDog) {
            System.arraycopy(actions, 0, walk, 0, 4);
            System.arraycopy(actions, 4, sleep, 0, 2);
            System.arraycopy(actions, 6, look, 0, 2);
        } else {
            System.arraycopy(actions, 0, jump, 0, 4);
            System.arraycopy(actions, 4, ride, 0, 2);
            System.arraycopy(actions, 6, sleep, 0, 2);
        }
    }

    private Bitmap[] load(Context context, String asset, float scale) {
        try (InputStream stream = context.getAssets().open(asset)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2; // tiny TV sprites need no full-resolution atlas in memory
            Bitmap sheet = BitmapFactory.decodeStream(stream, null, options);
            if (sheet == null) throw new IllegalStateException("Cannot decode " + asset);
            try { return extract(sheet, scale * options.inSampleSize); }
            finally { sheet.recycle(); }
        } catch (Exception error) {
            // A broken optional decoration must never prevent opening the launcher.
            Log.e("HomePetSprites", "Cannot load " + asset, error);
            return new Bitmap[8];
        }
    }

    private Bitmap[] extract(Bitmap sheet, float scale) {
        int w = sheet.getWidth(), h = sheet.getHeight();
        int[] pixels = new int[w * h], labels = new int[w * h], queue = new int[w * h];
        sheet.getPixels(pixels, 0, w, 0, 0, w, h);
        List<Part> parts = new ArrayList<Part>();
        int nextId = 0;
        // Generated poses can extend past nominal cell boundaries. Connected
        // silhouettes preserve tails/paws without including a neighbouring pose.
        for (int start = 0; start < pixels.length; start++) {
            if (labels[start] != 0 || (pixels[start] >>> 24) < 32) continue;
            Part p = new Part();
            p.id = ++nextId; p.left = w; p.top = h;
            int head = 0, tail = 1;
            queue[0] = start; labels[start] = p.id;
            while (head < tail) {
                int at = queue[head++], x = at % w, y = at / w;
                p.count++;
                p.left = Math.min(p.left, x); p.right = Math.max(p.right, x);
                p.top = Math.min(p.top, y); p.bottom = Math.max(p.bottom, y);
                for (int yy = Math.max(0, y - 1); yy <= Math.min(h - 1, y + 1); yy++) {
                    for (int xx = Math.max(0, x - 1); xx <= Math.min(w - 1, x + 1); xx++) {
                        int n = yy * w + xx;
                        if (labels[n] == 0 && (pixels[n] >>> 24) >= 32) {
                            labels[n] = p.id; queue[tail++] = n;
                        }
                    }
                }
            }
            if (p.count > 1000) parts.add(p);
        }
        if (parts.size() != 8) throw new IllegalStateException("Expected 8 poses, found " + parts.size());
        final int mid = h / 2;
        Collections.sort(parts, new Comparator<Part>() {
            @Override public int compare(Part a, Part b) {
                int rowA = a.top < mid ? 0 : 1, rowB = b.top < mid ? 0 : 1;
                return rowA != rowB ? rowA - rowB : a.left - b.left;
            }
        });
        Paint paint = new Paint();
        paint.setFilterBitmap(false);
        Bitmap[] frames = new Bitmap[8];
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            int pw = p.right - p.left + 1, ph = p.bottom - p.top + 1;
            int[] isolated = new int[pw * ph];
            for (int y = 0; y < ph; y++) {
                for (int x = 0; x < pw; x++) {
                    int at = (p.top + y) * w + p.left + x;
                    if (labels[at] == p.id) isolated[y * pw + x] = pixels[at];
                }
            }
            Bitmap original = Bitmap.createBitmap(isolated, pw, ph, Bitmap.Config.ARGB_8888);
            Bitmap sprite = Bitmap.createBitmap(Math.max(1, Math.round(pw * scale)),
                    Math.max(1, Math.round(ph * scale)), Bitmap.Config.ARGB_8888);
            new Canvas(sprite).drawBitmap(original, null,
                    new Rect(0, 0, sprite.getWidth(), sprite.getHeight()), paint);
            original.recycle();
            frames[i] = sprite;
        }
        return frames;
    }
}
