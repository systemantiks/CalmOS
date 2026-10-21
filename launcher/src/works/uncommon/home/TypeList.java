package works.uncommon.home;

import android.animation.ArgbEvaluator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A big typographic list, optionally with a line icon before each row. It never
 * takes Android focus: the activity owns the selection and tells the list what
 * to highlight, which keeps D-pad handling predictable on TV remotes.
 */
final class TypeList extends ScrollView {

    private static final class Row {
        LinearLayout box;
        IconView icon;
        TextView text;
        int color;
        boolean faded;      // standby / unplugged: recedes further when not selected
        boolean header;     // a small section title, never selected
        ValueAnimator anim;
        IconView pin;       // badge after the label (pinned to Home)
        boolean pinned;
        ValueAnimator pinAnim;
    }

    private final LinearLayout column;
    private final List<Row> rows = new ArrayList<Row>();

    private Look look;
    private float textPx;
    private int itemH;
    private int fade;
    private int iconBox;
    private int iconGap;
    private int pinBox;

    private int turnGen;            // bumps on every turn/cancel so stale callbacks do nothing
    private boolean turning;
    private boolean unclipped;      // mid-turn: lines may swing past the sides, not over the header

    TypeList(Context c) {
        super(c);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setVerticalFadingEdgeEnabled(true);
        setSmoothScrollingEnabled(true);

        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    void configure(Look look, float textFrac, float pitchFrac, float fadeFrac) {
        this.look = look;
        this.textPx = look.h * textFrac;
        this.itemH = look.hp(pitchFrac);
        this.fade = look.hp(fadeFrac);
        this.iconBox = Math.round(textPx * 1.4f);
        this.iconGap = Math.round(textPx * 0.26f);
        this.pinBox = Math.round(textPx * 0.74f);
        setFadingEdgeLength(fade);
        column.setPadding(0, 0, 0, fade);
    }

    int size() {
        return rows.size();
    }

    void setItems(List<? extends CharSequence> labels) {
        setItems(labels, null);
    }

    void setItems(List<? extends CharSequence> labels, int[] icons) {
        setItems(labels, icons, null, null);
    }

    /**
     * @param icons IconView kind per row, or null / negative for no icon
     * @param faded rows to show receded when not selected, or null
     * @param images your own icon per row (wins over icons), or null
     */
    void setItems(List<? extends CharSequence> labels, int[] icons, boolean[] faded,
                  CustomIcons.Icon[] images) {
        setItems(labels, icons, faded, images, null);
    }

    /** True if row i is a section title (not selectable). */
    boolean isHeader(int i) {
        return i >= 0 && i < rows.size() && rows.get(i).header;
    }

    /**
     * @param headers rows that are small section titles ("From Apple TV"), or null
     */
    void setItems(List<? extends CharSequence> labels, int[] icons, boolean[] faded,
                  CustomIcons.Icon[] images, boolean[] headers) {
        // Reuse rows where possible so colours don't flash on refresh.
        while (rows.size() > labels.size()) {
            Row r = rows.remove(rows.size() - 1);
            if (r.anim != null) r.anim.cancel();
            if (r.pinAnim != null) r.pinAnim.cancel();
            column.removeView(r.box);
        }
        while (rows.size() < labels.size()) rows.add(newRow());
        for (int i = 0; i < labels.size(); i++) {
            Row r = rows.get(i);
            r.text.setText(labels.get(i));
            r.faded = faded != null && i < faded.length && faded[i];
            styleHeader(r, headers != null && i < headers.length && headers[i]);
            int kind = (icons != null && i < icons.length) ? icons[i] : -1;
            CustomIcons.Icon img = (images != null && i < images.length) ? images[i] : null;
            if (img != null) {
                r.icon.setImage(img, look.bg);
                r.icon.setVisibility(View.VISIBLE);
            } else if (kind >= 0) {
                r.icon.setKind(kind);
                r.icon.setVisibility(View.VISIBLE);
            } else {
                r.icon.setVisibility(View.GONE);
            }
        }
    }

    /** Section titles are small and sit low in a shorter row, just above their group. */
    private void styleHeader(Row r, boolean header) {
        if (r.header == header && r.box.getLayoutParams() != null) return;
        r.header = header;
        r.text.setTypeface(header ? look.text : look.display);
        r.text.setTextSize(TypedValue.COMPLEX_UNIT_PX, header ? look.h * 0.026f : textPx);
        r.text.setLetterSpacing(header ? 0f : -0.012f);
        r.box.setGravity(header ? (Gravity.BOTTOM | Gravity.START) : Gravity.CENTER_VERTICAL);
        r.box.setPadding(0, 0, 0, header ? Math.round(itemH * 0.12f) : 0);
        ViewGroup.LayoutParams lp = r.box.getLayoutParams();
        if (lp != null) {
            lp.height = header ? Math.round(itemH * 0.85f) : itemH;
            r.box.setLayoutParams(lp);
        }
        if (header) {
            r.icon.setVisibility(View.GONE);
            showPin(r, false, false);
        }
    }

    // ------------------------------------------------------------ pin badges

    /** Show a pin after the label of each row where {@code on[i]}; animate the ones that change. */
    void setPins(boolean[] on, boolean animate) {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            boolean want = !r.header && on != null && i < on.length && on[i];
            if (r.pinned != want) showPin(r, want, animate);
        }
    }

    private void showPin(final Row r, final boolean on, boolean animate) {
        r.pinned = on;
        if (r.pinAnim != null) {
            r.pinAnim.cancel();
            r.pinAnim = null;
        }
        final View p = r.pin;
        if (!animate) {
            p.setVisibility(on ? View.VISIBLE : View.GONE);
            p.setAlpha(1f);
            p.setTranslationY(0f);
            p.setScaleX(1f);
            p.setScaleY(1f);
            p.setRotation(0f);
            return;
        }
        final float lift = -pinBox * 0.7f;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        if (on) {
            // Drops in from just above, tilts upright as it lands, settles with a small bounce.
            p.setVisibility(View.VISIBLE);
            final TimeInterpolator land = new OvershootInterpolator(2.2f);
            a.setDuration(420);
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator anim) {
                    float t = anim.getAnimatedFraction();
                    float f = land.getInterpolation(t);
                    p.setAlpha(Math.min(1f, t * 3.5f));
                    p.setTranslationY(lift * (1f - f));
                    float k = 1.3f - 0.3f * f;
                    p.setScaleX(k);
                    p.setScaleY(k);
                    p.setRotation(-28f * (1f - f));
                }
            });
        } else {
            // Lifts out and fades.
            final TimeInterpolator ease = new AccelerateInterpolator(1.4f);
            a.setDuration(200);
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator anim) {
                    float f = ease.getInterpolation(anim.getAnimatedFraction());
                    p.setAlpha(1f - f);
                    p.setTranslationY(lift * 0.5f * f);
                    float k = 1f - 0.2f * f;
                    p.setScaleX(k);
                    p.setScaleY(k);
                    p.setRotation(-16f * f);
                }
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(android.animation.Animator anim) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(android.animation.Animator anim) {
                    if (!cancelled && !r.pinned) p.setVisibility(View.GONE);
                }
            });
        }
        r.pinAnim = a;
        a.start();
    }

    private Row newRow() {
        Context c = getContext();
        Row r = new Row();
        r.box = new LinearLayout(c);
        r.box.setOrientation(LinearLayout.HORIZONTAL);
        r.box.setGravity(Gravity.CENTER_VERTICAL);

        r.icon = new IconView(c, IconView.PORT);
        r.icon.setVisibility(View.GONE);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(iconBox, iconBox);
        ip.rightMargin = iconGap;
        r.box.addView(r.icon, ip);

        r.text = new TextView(c);
        r.text.setFocusable(false);
        r.text.setTypeface(look.display);
        r.text.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx);
        r.text.setIncludeFontPadding(false);
        r.text.setSingleLine(true);
        r.text.setEllipsize(TextUtils.TruncateAt.END);
        r.text.setLetterSpacing(-0.012f);
        r.text.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        look.shade(r.text);
        // Sized to the label so a badge can sit right after it.
        r.box.addView(r.text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        r.pin = new IconView(c, IconView.PIN);
        r.pin.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(pinBox, pinBox);
        pp.leftMargin = Math.round(textPx * 0.3f);
        r.box.addView(r.pin, pp);

        r.color = look.rest;
        apply(r, r.color);
        column.addView(r.box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemH));
        return r;
    }

    private static void apply(Row r, int color) {
        r.text.setTextColor(color);
        r.icon.setColor(color);
        r.pin.setColor(color);
    }

    /**
     * @param focus  highlighted row, or -1
     * @param moving row being reordered, or -1 (everything else recedes)
     */
    void render(int focus, int moving, boolean animate) {
        for (int i = 0; i < rows.size(); i++) {
            int target;
            if (moving >= 0) target = (i == moving) ? look.strong : look.faint;
            else if (rows.get(i).header) target = look.dim;
            else if (i == focus) target = rows.get(i).faded ? Look.withAlpha(look.strong, 0.6f) : look.strong;
            else target = rows.get(i).faded ? look.faint : look.rest;
            setColor(rows.get(i), target, animate);
        }
    }

    private void setColor(final Row r, int to, boolean animate) {
        if (r.anim != null) {
            r.anim.cancel();
            r.anim = null;
        }
        int from = r.color;
        r.color = to;
        if (from == to || !animate) {
            apply(r, to);
            return;
        }
        ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        a.setDuration(160);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator anim) {
                apply(r, (Integer) anim.getAnimatedValue());
            }
        });
        r.anim = a;
        a.start();
    }

    /** Scroll so row {@code i} sits clear of the faded edges. */
    void reveal(final int i, final boolean smooth) {
        if (i < 0 || i >= rows.size()) return;
        View row = rows.get(i).box;
        if (getHeight() == 0 || row.getHeight() == 0) {
            post(new Runnable() {
                @Override
                public void run() {
                    if (getHeight() > 0) reveal(i, false);
                }
            });
            return;
        }
        int y = getScrollY();
        int vh = getHeight();
        int topGuard = (i == 0) ? 0 : fade;
        int target = y;
        if (row.getTop() - topGuard < y) target = row.getTop() - topGuard;
        else if (row.getBottom() > y + vh - fade) target = row.getBottom() - vh + fade;
        if (target < 0) target = 0;
        if (target == y) return;
        if (smooth) smoothScrollTo(0, target);
        else scrollTo(0, target);
    }

    /** Right edge of row i's text, in this view's coordinates. */
    int textRight(int i) {
        if (i < 0 || i >= rows.size()) return 0;
        Row r = rows.get(i);
        CharSequence t = r.text.getText();
        float w = r.text.getPaint().measureText(t, 0, t.length());
        return Math.round(r.box.getLeft() + r.text.getLeft() + Math.min(w, r.text.getWidth()));
    }

    /** Vertical centre of row i, in this view's coordinates (scroll applied). */
    int centerY(int i) {
        if (i < 0 || i >= rows.size()) return 0;
        View b = rows.get(i).box;
        return b.getTop() + b.getHeight() / 2 - getScrollY();
    }

    /** Jump without animation, e.g. after switching tabs. */
    void jumpTo(int i) {
        scrollTo(0, 0);
        if (i > 0) reveal(i, false);
    }

    // ------------------------------------------------------------ turnstile
    //
    // Windows Phone's "turnstile feather" page transition: every line swings about
    // a vertical hinge on the screen's left edge, like a door, one after another
    // from top to bottom. Going forward, the old page swings out towards you and
    // the new one swings in from behind; going back, the reverse.

    private static final float OUT_TOWARD = 72f;   // degrees; right edge swings towards you
    private static final float OUT_AWAY = -60f;    // right edge swings into the screen
    private static final float IN_FROM_AWAY = -58f;
    private static final float IN_FROM_TOWARD = 52f;
    private static final long OUT_MS = 170, OUT_STAGGER = 28;
    private static final long IN_MS = 280, IN_STAGGER = 34;

    boolean isTurning() {
        return turning;
    }

    /** Swing the visible lines off the page, then run {@code done}. */
    void turnOut(boolean forward, final Runnable done) {
        cancelTurn();
        final int gen = ++turnGen;
        List<Row> visible = visibleRows();
        if (visible.isEmpty()) {
            if (done != null) done.run();
            return;
        }
        turning = true;
        unclip(true);
        for (Row r : rows) if (!visible.contains(r)) r.box.setAlpha(0f);
        float to = forward ? OUT_TOWARD : OUT_AWAY;
        final int[] left = {visible.size()};
        TimeInterpolator ease = new AccelerateInterpolator(1.6f);
        for (int k = 0; k < visible.size(); k++) {
            View b = visible.get(k).box;
            hinge(b);
            b.animate().rotationY(to).alpha(0f)
                    .setStartDelay(k * OUT_STAGGER).setDuration(OUT_MS).setInterpolator(ease)
                    .withLayer()
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != turnGen || --left[0] > 0) return;
                            // Hidden and flat, ready for whatever goes on the page next.
                            for (Row r : rows) {
                                r.box.setRotationY(0f);
                                r.box.setAlpha(0f);
                            }
                            turning = false;
                            if (done != null) done.run();
                        }
                    }).start();
        }
    }

    /**
     * Swing the lines onto the page. Waits for the next layout so rows that were
     * just added have a size, and scrolls so row {@code reveal} is in view first.
     */
    void turnIn(final boolean forward, final int reveal, final Runnable done) {
        cancelTurn();
        final int gen = ++turnGen;
        turning = true;
        for (Row r : rows) r.box.setAlpha(0f);      // nothing flashes up before it swings in
        getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                getViewTreeObserver().removeOnPreDrawListener(this);
                if (gen != turnGen) return true;
                jumpTo(reveal);
                List<Row> visible = visibleRows();
                if (visible.isEmpty()) {
                    settle();
                    if (done != null) done.run();
                    return true;
                }
                unclip(true);
                float from = forward ? IN_FROM_AWAY : IN_FROM_TOWARD;
                final int[] left = {visible.size()};
                TimeInterpolator ease = new DecelerateInterpolator(2.2f);
                for (int k = 0; k < visible.size(); k++) {
                    View b = visible.get(k).box;
                    hinge(b);
                    b.setRotationY(from);
                    b.setAlpha(0f);
                    b.animate().rotationY(0f).alpha(1f)
                            .setStartDelay(k * IN_STAGGER).setDuration(IN_MS).setInterpolator(ease)
                            .withLayer()
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    if (gen != turnGen || --left[0] > 0) return;
                                    settle();
                                    if (done != null) done.run();
                                }
                            }).start();
                }
                return true;
            }
        });
        invalidate();
    }

    /**
     * Stop any turn and leave every line flat but invisible: for when another
     * screen is about to cover this one and nothing should flash back first.
     */
    void hideAll() {
        turnGen++;
        for (Row r : rows) {
            r.box.animate().cancel();
            r.box.setRotationY(0f);
            r.box.setAlpha(0f);
        }
        unclip(false);
        turning = false;
    }

    /** Stop any turn and put every line back flat and visible. */
    void cancelTurn() {
        turnGen++;
        for (Row r : rows) r.box.animate().cancel();
        settle();
    }

    private void settle() {
        for (Row r : rows) {
            r.box.setRotationY(0f);
            r.box.setAlpha(1f);
        }
        unclip(false);
        turning = false;
    }

    /** Rows that are at least partly inside the visible part of the list, top to bottom. */
    private List<Row> visibleRows() {
        List<Row> out = new ArrayList<Row>();
        int top = getScrollY(), bottom = top + getHeight();
        if (getHeight() == 0) return out;
        for (Row r : rows) {
            View b = r.box;
            if (b.getHeight() > 0 && b.getBottom() > top && b.getTop() < bottom) out.add(r);
        }
        return out;
    }

    /** Hinge at the screen's left edge, with enough camera distance for a gentle perspective. */
    private void hinge(View b) {
        int[] at = new int[2];
        getLocationOnScreen(at);
        float screenX = at[0] + column.getLeft() + b.getLeft();
        b.setPivotX(-screenX);
        b.setPivotY(b.getHeight() / 2f);
        // setCameraDistance takes px scaled by dpi/72 (the renderer works in points).
        float dpi = getResources().getDisplayMetrics().densityDpi;
        b.setCameraDistance(look.w * 2.2f * dpi / 72f);
    }

    /**
     * While unclipped for a turn, still cut everything off at the list's top edge
     * (exactly where it's cut when still), so a half-scrolled line never shows over
     * the tabs or title. Sideways, and a little below, there's room to swing.
     */
    @Override
    public void draw(Canvas c) {
        if (!unclipped || look == null) {
            super.draw(c);
            return;
        }
        int save = c.save();
        int y = getScrollY();
        c.clipRect(-look.w, y, look.w * 2, y + getHeight() + itemH);
        super.draw(c);
        c.restoreToCount(save);
    }

    /**
     * While a turn runs, lines swing outside the list's own bounds, so let them draw
     * there. Off-page rows are hidden during the turn so nothing shows that shouldn't.
     */
    private void unclip(boolean on) {
        unclipped = on;
        invalidate();
        setClipChildren(!on);
        column.setClipChildren(!on);
        ViewParent p = getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).setClipChildren(!on);
    }
}
