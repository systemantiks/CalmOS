package works.uncommon.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;

/** Tofu hides on Moose, races him, then curls up on his tree while Moose naps. */
final class IdiotsView extends PixelSceneView {
    private static long start = SystemClock.uptimeMillis();
    private final PetSprites dog, cat;
    private final PetChase story = new PetChase();
    private Bitmap backdrop;

    static void restart() { start = SystemClock.uptimeMillis(); }

    IdiotsView(Context context) {
        super(context);
        dog = PetSprites.dog(context);
        cat = PetSprites.cat(context);
    }

    @Override void prepare() {
        backdrop = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(backdrop);
        c.drawColor(0xFF202A2B);
        box(c, 0, rows - 17, cols, 17, 0xFF263132);
        box(c, 0, rows - 18, cols, 1, 0xFF34403E);
        // A little woven rug beneath Moose's bed and Tofu's climbing tree.
        int left = (int) (cols * 0.57), right = (int) (cols * 0.96);
        box(c, left, rows - 16, right - left, 12, 0xFF394743);
        for (int x = left + 2; x < right; x += 5) {
            box(c, x, rows - 15, 2, 1, 0xFF536054);
            box(c, x, rows - 6, 2, 1, 0xFF536054);
        }
        bed(c, Math.round(PetChase.bedX(cols)), 70, 0xFF80616A, 0xFFB39791);
        catTree(c, Math.round(PetChase.treeX(cols)));
    }

    private void bed(Canvas c, int x, int width, int rim, int cushion) {
        int y = rows - 15;
        box(c, x - width / 2 + 5, y - 4, width - 10, 13, rim);
        box(c, x - width / 2 + 2, y - 2, width - 4, 10, rim);
        box(c, x - width / 2, y, width, 5, rim);
        box(c, x - width / 2 + 7, y - 2, width - 14, 7, cushion);
        box(c, x - width / 2 + 11, y - 3, width - 22, 1, cushion);
        box(c, x - width / 2 + 5, y + 7, width - 10, 2, 0xFF33403E);
    }

    private void catTree(Canvas c, int x) {
        int floor = rows - 14, top = floor - (int) PetChase.TREE_HEIGHT;
        // Wide weighted base, sisal posts, a lower step and a padded top nest.
        box(c, x - 24, floor + 4, 48, 5, 0xFF5E7880);
        box(c, x - 21, floor + 3, 42, 2, 0xFF9AADB1);
        box(c, x - 4, top + 4, 9, floor - top, 0xFF9E8870);
        box(c, x - 3, top + 4, 3, floor - top, 0xFFC2AA88);
        for (int y = top + 7; y < floor + 3; y += 4)
            line(c, x - 4, y + 1, x + 4, y - 1, 0xFF746757);
        box(c, x - 17, top + 28, 7, 26, 0xFF8A7965);
        box(c, x - 26, top + 25, 29, 5, 0xFF5E7880);
        box(c, x - 23, top + 24, 23, 2, 0xFF9AADB1);
        box(c, x - 24, top - 1, 48, 6, 0xFF5E7880);
        box(c, x - 21, top - 3, 42, 4, 0xFF9AADB1);
        box(c, x - 17, top - 2, 34, 3, 0xFFBCC7BF);
        box(c, x - 24, top - 5, 5, 8, 0xFF718D92);
        box(c, x + 19, top - 5, 5, 8, 0xFF718D92);
        // Small hanging pom-pom makes the silhouette read as a cat tree.
        line(c, x + 17, top + 5, x + 17, top + 17, 0xFFB8AA8B);
        box(c, x + 15, top + 17, 5, 4, 0xFFB39791);
        box(c, x + 16, top + 16, 3, 6, 0xFFB39791);
    }

    @Override void drawScene(Canvas c, long now) {
        c.drawBitmap(backdrop, 0, 0, ink);
        long elapsed = Math.max(0, now - start);
        story.at(elapsed, cols);
        boolean dogSleeping = story.phase == PetChase.REST;
        boolean catSleeping = story.catSleeping();
        int breath = (int) (elapsed / 1800 % 2);
        int baseline = rows - 14;
        Bitmap dogFrame;
        switch (story.phase) {
            case PetChase.REST: dogFrame = dog.sleep[breath]; break;
            case PetChase.WAKE: dogFrame = dog.sit[0]; break;
            case PetChase.CAT_CHASES:
            case PetChase.DOG_CHASES:
            case PetChase.RETURN: dogFrame = dog.run[(int) (elapsed / 110 % 4)]; break;
            case PetChase.WALK_LEFT:
            case PetChase.WALK_RIGHT:
            case PetChase.DOG_HOME: dogFrame = dog.walk[(int) (elapsed / 220 % 4)]; break;
            case PetChase.LOOK_LEFT: dogFrame = dog.look[0]; break;
            case PetChase.LOOK_RIGHT: dogFrame = dog.look[1]; break;
            default: dogFrame = dog.walk[0]; break;
        }
        Bitmap catFrame;
        if (catSleeping) catFrame = cat.sleep[breath];
        else if (story.passenger()) catFrame = cat.ride[elapsed % 7000 < 500 ? 1 : 0];
        else if (story.phase == PetChase.LEAVE_TREE || story.phase == PetChase.TREE_JUMP) {
            int frame = story.progress < 0.15f ? 0 : story.progress < 0.55f ? 1 :
                    story.progress < 0.85f ? 2 : 3;
            catFrame = cat.jump[frame];
        } else if (story.phase == PetChase.SETTLE) {
            catFrame = story.progress < 0.25f ? cat.jump[3] : cat.ride[1];
        } else if (story.phase == PetChase.WAKE) catFrame = cat.sit[0];
        else catFrame = cat.run[(int) (elapsed / 110 + 1) % 4];
        pet(c, dogFrame, story.dogX, baseline, !story.dogRight);
        pet(c, catFrame, story.catX, baseline - Math.round(story.catLift), !story.catRight);
        if (dogSleeping || story.phase == PetChase.WAKE) {
            // The near cushion lip puts Moose's paws inside his bed.
            box(c, Math.round(story.dogX) - 29, rows - 12, 58, 3, 0xFF80616A);
        }
        if (dogSleeping)
            thought(c, Math.round(story.dogX) + 14, baseline - height(dogFrame) - 31, now, false);
        if (catSleeping)
            thought(c, Math.round(story.catX) + 3,
                    baseline - (int) PetChase.TREE_HEIGHT - height(catFrame) - 31, now + 650, false);
        if (story.phase == PetChase.LOOK_LEFT || story.phase == PetChase.LOOK_RIGHT)
            thought(c, Math.round(story.dogX) + (story.dogRight ? 39 : -39),
                    baseline - 57, now, true);
    }

    private static int height(Bitmap sprite) { return sprite == null ? 0 : sprite.getHeight(); }

    private static final int[] LITTLE_Z = {7, 2, 7}, BIG_Z = {31, 2, 4, 8, 31},
            QUESTION = {6, 9, 2, 0, 2};

    /** A pixel thought bubble, with z -> zZ -> zZz and gently rising letters. */
    private void thought(Canvas c, int center, int top, long now, boolean question) {
        int rim = 0xFF71837C, fill = 0xFF2D3937, text = 0xFFDDE2D3;
        box(c, center - 18, top + 3, 36, 14, rim);
        box(c, center - 15, top, 30, 20, rim);
        box(c, center - 17, top + 4, 34, 12, fill);
        box(c, center - 14, top + 1, 28, 18, fill);
        box(c, center - 8, top + 22, 3, 3, rim);
        box(c, center - 11, top + 27, 2, 2, rim);
        if (question) {
            glyph(c, QUESTION, 4, center - 2, top + 6, text);
        } else {
            int beat = (int) (now / 650 % 4), rise = (int) (now / 325 % 2);
            glyph(c, LITTLE_Z, 3, center - 11, top + 11 - rise, text);
            if (beat >= 1) glyph(c, BIG_Z, 5, center - 3, top + 6 - rise, text);
            if (beat >= 2) glyph(c, LITTLE_Z, 3, center + 8, top + 4 - rise, text);
        }
    }

    private void glyph(Canvas c, int[] pixels, int width, int x, int y, int color) {
        for (int row = 0; row < pixels.length; row++)
            for (int col = 0; col < width; col++)
                if ((pixels[row] & (1 << (width - col - 1))) != 0)
                    box(c, x + col, y + row, 1, 1, color);
    }

    private void pet(Canvas c, Bitmap sprite, float center, int floor, boolean flip) {
        if (sprite == null) return;
        int x = Math.round(center) - sprite.getWidth() / 2;
        int y = floor - sprite.getHeight();
        c.save();
        if (flip) c.scale(-1, 1, Math.round(center), 0);
        c.drawBitmap(sprite, x, y, ink);
        c.restore();
    }

    @Override long frameDelay(long now) {
        long t = Math.max(0, now - start) % PetChase.CYCLE_MS;
        return t >= 6000 && t < 36000 ? 50 : 250;
    }
}
