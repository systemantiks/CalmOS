package works.uncommon.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

/** Candlelit pumpkins, quiet corner webs and an occasional curious spider. */
final class HalloweenView extends PixelSceneView {
    private Bitmap backdrop;
    private static final int BG = 0xFF211A2C, WEB = 0xFF66526F, THREAD = 0xFF9C869F;
    private static final String[] PUMPKIN = {
        "........gg........", ".......gg.........", "....dddggdddd.....",
        "..ddooollloooodd..", ".dooooolllooooood.", "dooooolllllooooood",
        "dooeeoolllooeeood", "doeeeoolllooeeood",
        "dooooollelloooood", "dooeeoolllooeeood",
        "doooeeeeeeeeeood", ".doooeeoeoeeoood.", "..dooooollooood..",
        "...dddddddddd...."
    };

    HalloweenView(Context context) { super(context); }

    @Override void prepare() {
        backdrop = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(backdrop);
        c.drawColor(BG);
        // Only the outer corners: tabs, clock and lists retain a quiet ground.
        web(c, false, 32);
        web(c, true, 29);
        box(c, 0, rows - 7, cols, 7, 0xFF191521);
        for (int x = 3; x < cols; x += 11) {
            int y = rows - 8 - (x * 7 % 4);
            box(c, x, y, 2, 3, 0xFF3F3042);
            if (x % 3 == 0) box(c, x + 2, y - 1, 2, 1, 0xFF71503E);
        }
    }

    private void web(Canvas c, boolean right, int size) {
        int edge = right ? cols - 1 : 0, sign = right ? -1 : 1;
        for (int spoke = 0; spoke <= 4; spoke++) {
            double a = spoke * Math.PI / 8;
            line(c, edge, 0, edge + sign * (int) (Math.cos(a) * size),
                    (int) (Math.sin(a) * size), WEB);
        }
        for (int r = 8; r <= size; r += 7) {
            for (int spoke = 0; spoke < 4; spoke++) {
                double a = spoke * Math.PI / 8, b = (spoke + 1) * Math.PI / 8;
                int x1 = edge + sign * (int) (Math.cos(a) * r), y1 = (int) (Math.sin(a) * r);
                int x2 = edge + sign * (int) (Math.cos(b) * r), y2 = (int) (Math.sin(b) * r);
                int midX = edge + (x1 + x2 - 2 * edge) * 4 / 9, midY = (y1 + y2) * 4 / 9;
                line(c, x1, y1, midX, midY, WEB);
                line(c, midX, midY, x2, y2, WEB);
            }
        }
    }

    @Override void drawScene(Canvas c, long now) {
        c.drawBitmap(backdrop, 0, 0, ink);
        pumpkin(c, 12, rows - 36, 2, now, 0);
        pumpkin(c, 49, rows - 22, 1, now, 2);
        pumpkin(c, cols - 65, rows - 22, 1, now, 4);
        pumpkin(c, cols - 43, rows - 36, 2, now, 6);
        // Slow 32-second excursion, with most of the time tucked into the web.
        double t = (now % 32000) / 1000.0;
        double drop = t < 6 ? 0 : t < 12 ? (t - 6) / 6 :
                t < 18 ? 1 : t < 24 ? (24 - t) / 6 : 0;
        int x = cols - 15, y = 19 + (int) (drop * rows * 0.24);
        line(c, x, 9, x, y, THREAD);
        int flex = (int) (now / 550 % 2);
        for (int side = -1; side <= 1; side += 2) {
            for (int leg = 0; leg < 4; leg++) {
                int ly = y + 2 + leg * 2;
                line(c, x + side * 2, ly, x + side * (6 + flex), ly - 3 + leg, THREAD);
                line(c, x + side * (6 + flex), ly - 3 + leg, x + side * 8, ly + leg - 1, THREAD);
            }
        }
        box(c, x - 3, y + 1, 7, 8, 0xFF17121D);
        box(c, x - 2, y, 5, 10, 0xFF17121D);
        box(c, x - 1, y + 2, 1, 1, 0xFFE9C491);
        box(c, x + 2, y + 2, 1, 1, 0xFFE9C491);
    }

    private void pumpkin(Canvas c, int x, int y, int scale, long now, int phase) {
        int glow = Look.mix(0xFFF2A943, 0xFFFFDF87,
                (float) (0.5 + 0.5 * Math.sin(now / 950.0 + phase)));
        for (int row = 0; row < PUMPKIN.length; row++) {
            String pixels = PUMPKIN[row];
            for (int col = 0; col < pixels.length(); col++) {
                int color;
                switch (pixels.charAt(col)) {
                    case 'g': color = 0xFF7C8B50; break;
                    case 'd': color = 0xFF6A362E; break;
                    case 'o': color = 0xFFBA582F; break;
                    case 'l': color = 0xFFE0873D; break;
                    case 'e': color = glow; break;
                    default: continue;
                }
                box(c, x + col * scale, y + row * scale, scale, scale, color);
            }
        }
    }
}
