package works.uncommon.home;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Every installed app, same typographic style. OK opens, hold OK (or Menu) pins/unpins. */
public class AppsActivity extends Activity implements SelectKey.Listener {

    private Look look;
    private TypeList list;
    private List<Apps.Entry> apps = new ArrayList<Apps.Entry>();   // one per row; null for section titles
    private int index = 0;
    private long lastSelect;
    private boolean firstShow = true, stopped, leaving;
    private final List<View> chrome = new ArrayList<View>();     // title, clock

    private final SelectKey select = new SelectKey();

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Apps.invalidate();
            reload(true, false);
            list.render(index, -1, false);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        build();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(packages);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        stopped = true;
        super.onStop();
    }

    @Override
    protected void onPause() {
        // Never leave lines half-turned behind another screen.
        if (!leaving) {
            list.cancelTurn();
            for (View v : chrome) {
                v.animate().cancel();
                v.setAlpha(1f);
                v.setTranslationX(0f);
            }
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean enter = firstShow || stopped;
        firstShow = false;
        stopped = false;
        leaving = false;
        // Pick up apps installed on the Apple TV since last time (if it's reachable).
        AppleTv.refreshApps(this, new AppleTv.Done<Integer>() {
            @Override
            public void done(Integer count, String error) {
                if (count != null && !isFinishing()) {
                    reload(true, false);
                    list.render(index, -1, false);
                    list.reveal(index, false);
                }
            }
        });
        // Coming on screen: Home already gathered the list as it turned out, so use that.
        reload(!enter, false);
        list.render(index, -1, false);
        if (enter) animateIn();
        else list.reveal(index, false);
    }

    /** Lines swing in from behind (Windows Phone turnstile), the title and clock drift in. */
    private void animateIn() {
        list.turnIn(true, index, null);
        float dx = look.wp(0.012f);
        for (int i = 0; i < chrome.size(); i++) {
            View v = chrome.get(i);
            v.animate().cancel();
            v.setAlpha(0f);
            v.setTranslationX(dx);
            v.animate().alpha(1f).translationX(0f)
                    .setStartDelay(40 + i * 50L).setDuration(340)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f))
                    .start();
        }
    }

    /** Back: lines swing away and the title slides off to the right, then Home swings back in. */
    @Override
    public void onBackPressed() {
        if (leaving) return;
        leaving = true;
        float dx = look.wp(0.012f);
        for (int i = 0; i < chrome.size(); i++) {
            chrome.get(i).animate().alpha(0f).translationX(dx)
                    .setStartDelay(i * 30L).setDuration(200)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f))
                    .start();
        }
        list.turnOut(false, new Runnable() {
            @Override
            public void run() {
                finish();
                overridePendingTransition(0, 0);
            }
        });
    }

    private void build() {
        look = Look.get(this);
        getWindow().setBackgroundDrawable(new ColorDrawable(look.bg));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(look.bg);

        ThemeDecor.addTo(root, look);

        TextView title = new TextView(this);
        title.setText("All apps");
        title.setTypeface(look.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, look.h * 0.05f);
        title.setTextColor(look.dim);
        title.setIncludeFontPadding(false);
        look.shade(title);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        tp.leftMargin = look.wp(0.074f);
        tp.topMargin = look.hp(0.104f);
        root.addView(title, tp);
        chrome.clear();
        chrome.add(title);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTypeface(look.text);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, look.h * 0.046f);
        clock.setTextColor(Look.withAlpha(look.strong, 0.72f));
        clock.setIncludeFontPadding(false);
        look.shade(clock);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        cp.rightMargin = look.wp(0.075f);
        cp.topMargin = look.hp(0.104f);
        root.addView(clock, cp);
        chrome.add(clock);

        list = new TypeList(this);
        list.configure(look, 0.062f, 0.11f, 0.10f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(look.wp(0.7f), look.hp(0.72f));
        lp.leftMargin = look.wp(0.072f);
        lp.topMargin = look.hp(0.205f);
        root.addView(list, lp);

        setContentView(root);
    }


    /**
     * @param fresh       ask the package manager again (else a list from the last few seconds will do)
     * @param animatePins pins that appear or disappear animate (after pinning from here)
     */
    private void reload(boolean fresh, boolean animatePins) {
        Apps.Entry current = (index >= 0 && index < apps.size()) ? apps.get(index) : null;
        String sel = current == null ? null : current.pkg;
        // Already grouped: Apple TV, then this TV's apps, then the TV's own
        List<Apps.Entry> all = fresh ? Apps.all(this) : Apps.recent(this, 4000);
        List<String> pins = Look.Prefs.pinned(this);
        List<CharSequence> labels = new ArrayList<CharSequence>();
        List<Boolean> heads = new ArrayList<Boolean>();
        List<Boolean> pinnedRows = new ArrayList<Boolean>();
        apps = new ArrayList<Apps.Entry>();
        int lastKind = -1;
        for (Apps.Entry e : all) {
            if (e.kind != lastKind) {
                // From Apple TV first, then the TV's installed apps (titled only if there's
                // something above them), then the TV's own utilities.
                String title = e.kind == Apps.APPLE_TV ? "From Apple TV"
                        : e.kind == Apps.SYSTEM ? "ShiteWare"
                        : lastKind == -1 ? null : "On this TV";
                if (title != null) {
                    apps.add(null);
                    labels.add(title);
                    heads.add(true);
                    pinnedRows.add(false);
                }
            }
            lastKind = e.kind;
            apps.add(e);
            labels.add(Apps.display(e, null));
            heads.add(false);
            pinnedRows.add(pins.contains(e.pkg));
        }
        boolean[] headers = new boolean[heads.size()];
        boolean[] pinMarks = new boolean[heads.size()];
        for (int i = 0; i < headers.length; i++) {
            headers[i] = heads.get(i);
            pinMarks[i] = pinnedRows.get(i);
        }
        list.setItems(labels, null, null, null, headers);
        list.setPins(pinMarks, animatePins);
        if (sel != null) {
            for (int i = 0; i < apps.size(); i++) {
                if (apps.get(i) != null && apps.get(i).pkg.equals(sel)) {
                    index = i;
                    break;
                }
            }
        }
        if (index >= apps.size()) index = Math.max(0, apps.size() - 1);
        index = selectable(index, 1);
    }

    /** Nearest row from {@code i} (moving in direction {@code d}) that isn't a section title. */
    private int selectable(int i, int d) {
        int n = apps.size();
        if (n == 0) return 0;
        for (int j = i; j >= 0 && j < n; j += d) if (apps.get(j) != null) return j;
        for (int j = i; j >= 0 && j < n; j -= d) if (apps.get(j) != null) return j;
        return 0;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (leaving && e.getKeyCode() != KeyEvent.KEYCODE_HOME) return true;   // on the way out
        if (select.handle(e, this)) return true;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) step(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // Left goes back to Home, the way it came
                if (down && e.getRepeatCount() == 0) onBackPressed();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (down && e.getRepeatCount() == 0) onHold();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void step(int d) {
        int n = list.size();
        if (n == 0) return;
        int next = Math.max(0, Math.min(n - 1, index + d));
        while (next > 0 && next < n - 1 && apps.get(next) == null) next += d;   // hop over section titles
        if (apps.get(next) == null) next = index;
        if (next != index) {
            index = next;
            list.render(index, -1, true);
            list.reveal(index, true);
        }
    }

    @Override
    public void onSelect() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSelect < 700) return;
        lastSelect = now;
        if (index >= 0 && index < apps.size() && apps.get(index) != null) Apps.launch(this, apps.get(index).pkg);
    }

    @Override
    public void onHold() {
        if (index < 0 || index >= apps.size() || apps.get(index) == null) return;
        Apps.Entry e = apps.get(index);
        List<String> pins = Look.Prefs.pinned(this);
        boolean pinning = !pins.contains(e.pkg);
        if (pinning) pins.add(e.pkg);
        else pins.remove(e.pkg);
        Look.Prefs.setPinned(this, pins);
        reload(false, true);
        list.render(index, -1, false);
    }
}
