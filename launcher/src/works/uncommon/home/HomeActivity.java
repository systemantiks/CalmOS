package works.uncommon.home;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Home: two tabs (Apps, Sources) switched with left/right, a clock, and corner
 * controls (theme, all apps, settings) one more press to the right.
 */
public class HomeActivity extends Activity implements SelectKey.Listener {

    private static final int APPS = 0, SOURCES = 1;
    // Top right, left to right, just before the clock
    private static final int ICON_ALL = 0, ICON_THEME = 1, ICON_SETTINGS = 2, ICON_COUNT = 3;

    private static final int MENU_SWITCH = 0, MENU_EDIT = 1, MENU_RESET = 2;
    private static final int MENU_ATV_LINK = 3, MENU_ATV_MOVE = 4, MENU_ATV_UNLINK = 5, MENU_ATV_REFRESH = 6;

    private Look look;
    private FrameLayout root;
    private TypeList list;
    private LinearLayout menu;                      // options for the selected source
    private final List<TextView> menuItems = new ArrayList<TextView>();
    private final List<Integer> menuActions = new ArrayList<Integer>();
    private int menuIndex;
    private final TextView[] tabs = new TextView[2];
    private final IconView[] icons = new IconView[ICON_COUNT];
    private final TextView[] iconLabels = new TextView[ICON_COUNT];

    private List<Apps.Entry> pinned = new ArrayList<Apps.Entry>();
    private List<Sources.Entry> sources = new ArrayList<Sources.Entry>();
    private boolean empty;          // nothing pinned & installed: Apps shows a single "All apps" row

    private int tab = APPS;
    private int appIndex = 0;
    private int srcIndex = 0;
    private int iconIndex = ICON_ALL;
    private boolean inIcons = false;
    private int moving = -1;        // Apps row being reordered, or -1

    private boolean stopped;        // another screen covered Home since it was last shown
    private boolean toTop;          // Home pressed while on Home; handled in onResume
    private boolean pendingReload;  // apps/inputs changed while reordering
    private long lastSelect;
    private boolean menuOpen;
    private boolean turning;        // a tab change is swinging the old page out
    private int turnGen;            // stale turn callbacks check this and do nothing
    private boolean firstShow = true;
    private boolean leftHome;       // we opened an app, input or screen since Home was last shown
    private long pausedAt;          // when Home last lost the screen (uptime ms)
    private boolean handedOff;      // turned out for All apps: stays blank underneath it
    private final List<View> chrome = new ArrayList<View>();   // tabs, clock, corner controls
    private boolean themeMenu;      // the open menu is the theme picker (else source options)

    private final SelectKey select = new SelectKey();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Apps.invalidate();
            changed();
        }
    };

    private TvInputManager inputs;
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String inputId, int state) {
            changed();
        }

        @Override
        public void onInputAdded(String inputId) {
            changed();
        }

        @Override
        public void onInputRemoved(String inputId) {
            changed();
        }

        // API 24+ (not in the API 23 stubs we compile against, still called at runtime)
        public void onInputUpdated(String inputId) {
            changed();
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        CustomIcons.dir(this);   // create the icons folder so `adb push` has somewhere to go
        build();
        reload();
        render(false);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packages, f);

        inputs = Sources.manager(this);
        if (inputs != null) {
            try {
                inputs.registerCallback(inputCallback, handler);
            } catch (RuntimeException e) {
                inputs = null;
            }
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(packages);
        } catch (IllegalArgumentException ignored) {
        }
        if (inputs != null) {
            try {
                inputs.unregisterCallback(inputCallback);
            } catch (RuntimeException ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Animate in when launched, or when coming back from anything else: an app,
        // an HDMI input (the TV app doesn't fully hide Home, so it isn't "stopped"),
        // or anything that kept Home in the background for more than a moment.
        long away = SystemClock.uptimeMillis() - pausedAt;
        boolean back = handedOff;       // back from All apps
        handedOff = false;
        boolean enter = back || firstShow || stopped || leftHome || (pausedAt > 0 && away > 1500);
        leftHome = false;
        firstShow = false;
        stopped = false;
        if (toTop) {
            // Nothing changed while Home was on screen, so skip the reload.
            // First press: glide to the top of the list you're on. On Apps, a press
            // when already at the top goes over to Sources (at its top).
            toTop = false;
            moving = -1;
            boolean atTop = !inIcons && index() == 0 && list.getScrollY() == 0;
            inIcons = false;
            if (tab == APPS && atTop) {
                srcIndex = 0;
                switchTab(SOURCES);
                return;
            }
            if (tab == SOURCES) srcIndex = 0;
            else appIndex = 0;
            list.smoothScrollTo(0, 0);
            render(true);
            return;
        }
        if (look == null || look.stale(this)) build();
        reload(enter);      // coming on screen: a list from the last few seconds is fine, and quicker
        render(false);
        if (AppleTv.pending() != null) {
            // Back from reading an Apple TV pairing code: straight back to typing it in.
            startActivity(new Intent(this, AtvPairActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));
            return;
        }
        if (enter) animateIn(back);
        else list.reveal(index(), false);
    }

    @Override
    protected void onPause() {
        pausedAt = SystemClock.uptimeMillis();
        if (handedOff) {
            // All apps is taking over: stay blank underneath it, so nothing flashes
            // back up before it appears or when it closes.
            list.hideAll();
            for (View v : chrome) {
                v.animate().cancel();
                v.setAlpha(0f);
            }
        } else {
            if (turning || list.isTurning()) settleTurn();
            settleChrome();
        }
        if (moving >= 0) finishMoving();
        if (menuOpen) closeMenu(false);
        super.onPause();
    }

    @Override
    protected void onStop() {
        stopped = true;
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);   // so Back knows we're now running as Home
        // Home pressed while Home was already on screen: back to the top of Apps.
        // (Coming back from an app keeps the last selection.)
        // Only counts as "Home pressed on Home" if nothing else was on screen.
        if (!stopped && !leftHome && SystemClock.uptimeMillis() - pausedAt < 1500) toTop = true;
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        leftHome = true;
        super.startActivity(intent, options);
    }

    // ---------------------------------------------------------------- layout

    private void build() {
        look = Look.get(this);
        getWindow().setBackgroundDrawable(new ColorDrawable(look.bg));

        menuOpen = false;
        themeMenu = false;
        menu = null;
        root = new FrameLayout(this);
        root.setBackgroundColor(look.bg);
        turnGen++;
        turning = false;
        chrome.clear();

        ThemeDecor.addTo(root, look);

        // Tabs, top left
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        // Left to right: Sources, then Apps (tabs[] stays indexed by SOURCES / APPS).
        int[] order = {SOURCES, APPS};
        String[] names = new String[2];
        names[SOURCES] = "Sources";
        names[APPS] = "Apps";
        for (int k = 0; k < 2; k++) {
            int i = order[k];
            TextView t = text(look.h * 0.046f, i == tab ? look.strong : look.dim);
            t.setText(names[i]);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (k == 0) tl.rightMargin = look.wp(0.028f);
            tabRow.addView(t, tl);
            tabs[i] = t;
        }
        FrameLayout.LayoutParams rp = wrap(Gravity.TOP | Gravity.START);
        rp.leftMargin = look.wp(0.074f);
        rp.topMargin = look.hp(0.104f);
        root.addView(tabRow, rp);
        chrome.add(tabRow);

        // The list
        list = new TypeList(this);
        list.configure(look, 0.083f, 0.148f, 0.11f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(look.wp(0.66f), look.hp(0.715f));
        lp.leftMargin = look.wp(0.072f);
        lp.topMargin = look.hp(0.203f);
        root.addView(list, lp);

        // Top right: All apps, Theme and Settings, then the clock. A control's label
        // shows underneath it while it's selected.
        int box = look.hp(0.05f);
        int labelW = look.wp(0.2f), labelH = look.hp(0.04f), labelGap = look.hp(0.008f);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setClipChildren(false);
        top.setClipToPadding(false);
        // room inside its own bounds for the labels hanging below (and wider than) the icons
        top.setPadding(labelW / 2, 0, 0, labelGap + labelH);
        for (int i = 0; i < ICON_COUNT; i++) {
            FrameLayout cell = new FrameLayout(this);
            cell.setClipChildren(false);
            IconView icon = new IconView(this, IconView.GRID);
            icon.setColor(look.dim);
            cell.addView(icon, new FrameLayout.LayoutParams(box, box));
            icons[i] = icon;

            TextView label = text(look.h * 0.024f, look.strong);
            label.setAlpha(0f);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(labelW, labelH,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            llp.topMargin = box + labelGap;
            cell.addView(label, llp);
            iconLabels[i] = label;

            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(box, box);
            cl.rightMargin = look.wp(0.016f);
            top.addView(cell, cl);
        }
        icons[ICON_THEME].setKind(IconView.THEME);
        icons[ICON_ALL].setKind(IconView.GRID);
        icons[ICON_SETTINGS].setKind(IconView.SETTINGS);
        iconLabels[ICON_THEME].setText("Theme  \u00b7  " + look.name);
        iconLabels[ICON_ALL].setText("All apps");
        iconLabels[ICON_SETTINGS].setText("Settings");

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTypeface(look.text);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, look.h * 0.046f);
        clock.setTextColor(Look.withAlpha(look.strong, 0.72f));
        clock.setIncludeFontPadding(false);
        clock.setFocusable(false);
        look.shade(clock);
        LinearLayout.LayoutParams ckp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ckp.leftMargin = look.wp(0.006f);
        top.addView(clock, ckp);

        FrameLayout.LayoutParams tpp = wrap(Gravity.TOP | Gravity.END);
        tpp.rightMargin = look.wp(0.075f);
        tpp.topMargin = look.hp(0.104f);
        root.addView(top, tpp);
        chrome.add(top);

        setContentView(root);
    }

    private TextView text(float px, int color) {
        TextView t = new TextView(this);
        t.setTypeface(look.text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setFocusable(false);
        look.shade(t);
        return t;
    }

    private static FrameLayout.LayoutParams wrap(int gravity) {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
    }

    // ---------------------------------------------------------------- data

    private void changed() {
        if (moving >= 0 || menuOpen || turning) {
            pendingReload = true;
            return;
        }
        reload();
        render(false);
    }

    /** Re-read apps and inputs, keeping each tab's highlight on the same item. */
    private void reload() {
        reload(false);
    }

    /** @param recent a list of apps from the last few seconds will do (screen coming on) */
    private void reload(boolean recent) {
        String selApp = (appIndex >= 0 && appIndex < pinned.size()) ? pinned.get(appIndex).pkg : null;
        pinned = Apps.pinned(this, recent ? Apps.recent(this, 4000) : Apps.all(this));
        empty = pinned.isEmpty();
        if (selApp != null) {
            for (int i = 0; i < pinned.size(); i++) {
                if (pinned.get(i).pkg.equals(selApp)) {
                    appIndex = i;
                    break;
                }
            }
        }
        appIndex = clamp(appIndex, empty ? 1 : pinned.size());
        if (moving >= pinned.size()) moving = -1;

        String selSrc = (srcIndex >= 0 && srcIndex < sources.size()) ? sources.get(srcIndex).portId : null;
        sources = Sources.list(this);
        if (selSrc != null) {
            for (int i = 0; i < sources.size(); i++) {
                if (sources.get(i).portId.equals(selSrc)) {
                    srcIndex = i;
                    break;
                }
            }
        }
        srcIndex = clamp(srcIndex, Math.max(1, sources.size()));

        showTab(false);
    }

    private static int clamp(int i, int n) {
        return Math.max(0, Math.min(n - 1, i));
    }

    /** Put the current tab's rows into the list. */
    private void showTab(boolean fadeIn) {
        if (tab == APPS) {
            List<CharSequence> labels = new ArrayList<CharSequence>();
            if (empty) labels.add("All apps");
            else for (Apps.Entry e : pinned) labels.add(Apps.display(e, null));
            list.setItems(labels);
        } else {
            List<CharSequence> labels = new ArrayList<CharSequence>();
            int[] kinds = null;
            boolean[] faded = null;
            CustomIcons.Icon[] images = null;
            if (sources.isEmpty()) {
                labels.add("No sources found");
            } else {
                kinds = new int[sources.size()];
                faded = new boolean[sources.size()];
                images = new CustomIcons.Icon[sources.size()];
                for (int i = 0; i < sources.size(); i++) {
                    Sources.Entry src = sources.get(i);
                    labels.add(src.label);
                    kinds[i] = src.icon;
                    faded[i] = src.faded;
                    images[i] = CustomIcons.load(this, src.iconFile);
                }
            }
            list.setItems(labels, kinds, faded, images);
        }
        if (fadeIn) {
            list.setAlpha(0f);
            list.animate().alpha(1f).setDuration(180).start();
        }
    }

    private int index() {
        return tab == APPS ? appIndex : srcIndex;
    }

    private void render(boolean animate) {
        // With the menu open, the chosen source stays strong and the rest recede.
        int emphasise = tab == APPS ? moving : (menuOpen && !themeMenu ? srcIndex : -1);
        list.render(inIcons ? -1 : index(), emphasise, animate);
        for (int i = 0; i < 2; i++) tabs[i].setTextColor(i == tab ? look.strong : look.dim);
        for (int i = 0; i < ICON_COUNT; i++) {
            boolean on = inIcons && i == iconIndex;
            icons[i].setColor(on ? look.strong : look.dim);
            icons[i].setHalo(on, Look.withAlpha(look.strong, 0.08f));
            float a = on ? 1f : 0f;
            if (animate) iconLabels[i].animate().alpha(a).setDuration(160).start();
            else iconLabels[i].setAlpha(a);
        }
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (turning) {
            int k = e.getKeyCode();
            // The old page is on its way out: wait for the new one (a few hundred ms).
            if (SelectKey.isSelect(k) || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                    || k == KeyEvent.KEYCODE_MENU) return true;
        }
        if (select.handle(e, this)) return true;

        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (menuOpen) return menuKey(code, down, e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) step(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Left to right: Sources -> Apps -> the controls by the clock.
                if (down && moving < 0) {
                    if (inIcons) {
                        if (iconIndex < ICON_COUNT - 1) {
                            iconIndex++;
                            render(true);
                        }
                    } else if (tab == SOURCES) {
                        switchTab(APPS);
                    } else {
                        enterIcons();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && moving < 0) {
                    if (inIcons) {
                        if (iconIndex > 0) iconIndex--;
                        else inIcons = false;      // off the left end: back to the list
                        render(true);
                    } else if (tab == APPS) {
                        switchTab(SOURCES);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (down && e.getRepeatCount() == 0) {
                    if (moving >= 0) finishMoving();
                    else if (tab == SOURCES && !inIcons && !sources.isEmpty()) openMenu();
                    else onHold();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (down && e.getRepeatCount() == 0 && moving >= 0) {
                    finishMoving();
                    return true;
                }
                // Opened as an ordinary app (not as Home): let Back close it.
                Intent launch = getIntent();
                if (moving < 0 && (launch == null || !launch.hasCategory(Intent.CATEGORY_HOME))) {
                    return super.dispatchKeyEvent(e);
                }
                // As Home, Back steps back towards the Apps list: corner controls -> list,
                // Sources -> Apps (with the page turn), then back to the top of Apps.
                if (down && e.getRepeatCount() == 0) {
                    if (inIcons) {
                        inIcons = false;
                        render(true);
                    } else if (tab == SOURCES) {
                        switchTab(APPS);
                    } else if (appIndex > 0) {
                        appIndex = 0;
                        render(true);
                        list.smoothScrollTo(0, 0);
                    }
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    /** Change tab with a Windows Phone style turnstile: old lines swing out, new ones swing in. */
    private void switchTab(int t) {
        if (tab == t) return;
        if (turning || list.isTurning()) settleTurn();
        final boolean forward = t == APPS;     // Apps is to the right of Sources
        tab = t;
        for (int i = 0; i < 2; i++) tabs[i].setTextColor(i == tab ? look.strong : look.dim);
        turning = true;
        final int gen = ++turnGen;
        list.turnOut(forward, new Runnable() {
            @Override
            public void run() {
                if (gen != turnGen) return;
                turning = false;
                if (pendingReload && moving < 0 && !menuOpen) {
                    pendingReload = false;
                    reload();
                } else {
                    showTab(false);
                }
                render(false);
                list.turnIn(forward, index(), null);
            }
        });
    }

    /** Skip to the end of any tab change: new page shown, flat and still. */
    private void settleTurn() {
        turnGen++;
        boolean wasOut = turning;
        turning = false;
        list.cancelTurn();
        if (wasOut) {
            if (pendingReload && moving < 0 && !menuOpen) {
                pendingReload = false;
                reload();
            } else {
                showTab(false);
            }
            list.jumpTo(index());
            render(false);
            settleChrome();     // in case All apps was opening and slid it away
        }
    }

    /**
     * Home coming on screen: lines swing back in, the tabs, clock and controls drift in
     * (from the left when coming back from All apps, which pushed them that way).
     */
    private void animateIn(boolean fromLeft) {
        // A fresh meadow grows in, as on the site when it comes into view (not when
        // coming back from All apps: that's the same meadow carrying on).
        if (look.flowers && !fromLeft) MeadowView.replant();
        list.turnIn(false, index(), null);
        float dx = look.wp(0.012f) * (fromLeft ? -1f : 1f);
        for (int i = 0; i < chrome.size(); i++) {
            View v = chrome.get(i);
            v.animate().cancel();
            v.setAlpha(0f);
            v.setTranslationX(dx);
            v.animate().alpha(1f).translationX(0f)
                    .setStartDelay(60 + i * 50L).setDuration(360)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f))
                    .start();
        }
    }

    private void settleChrome() {
        for (View v : chrome) {
            v.animate().cancel();
            v.setAlpha(1f);
            v.setTranslationX(0f);
        }
    }

    /** Onto the controls by the clock, starting with All apps. */
    private void enterIcons() {
        inIcons = true;
        iconIndex = ICON_ALL;
        render(true);
    }

    private void step(int d) {
        if (inIcons) {
            // The controls sit in a row above the list: down drops back into it.
            if (d > 0) {
                inIcons = false;
                render(true);
            }
            return;
        }
        int n = list.size();
        if (n == 0) return;
        if (d < 0 && index() == 0 && moving < 0) {
            enterIcons();     // up from the first row reaches the controls
            return;
        }
        if (tab == APPS && moving >= 0) {
            int to = moving + d;
            if (to < 0 || to >= pinned.size()) return;
            Apps.Entry e = pinned.remove(moving);
            pinned.add(to, e);
            moving = to;
            appIndex = to;
            savePinnedOrder();
            showTab(false);
            render(false);
            list.reveal(appIndex, true);
            return;
        }
        int next = clamp(index() + d, n);
        if (next == index()) return;
        if (tab == APPS) appIndex = next;
        else srcIndex = next;
        render(true);
        list.reveal(next, true);
    }

    @Override
    public void onSelect() {
        // Ignore a quick second OK while the first launch is still opening.
        long now = SystemClock.uptimeMillis();
        if (now - lastSelect < 700) return;
        lastSelect = now;

        if (menuOpen) {
            chooseMenu();
            return;
        }
        if (inIcons) {
            switch (iconIndex) {
                case ICON_THEME:
                    openThemeMenu();
                    break;
                case ICON_ALL:
                    openAllApps();
                    break;
                case ICON_SETTINGS:
                    Apps.openSettings(this);
                    break;
            }
            return;
        }
        if (tab == APPS) {
            if (moving >= 0) {
                finishMoving();
                return;
            }
            if (empty) {
                openAllApps();
                return;
            }
            if (appIndex < pinned.size()) Apps.launch(this, pinned.get(appIndex).pkg);
        } else if (srcIndex < sources.size()) {
            Sources.open(this, sources.get(srcIndex));
        }
    }

    @Override
    public void onHold() {
        if (inIcons || moving >= 0 || menuOpen) return;
        if (tab == SOURCES) return;   // source options use the remote's Menu button
        if (empty || pinned.size() < 2) return;
        moving = appIndex;
        render(true);
    }

    private void finishMoving() {
        moving = -1;
        if (pendingReload) {
            pendingReload = false;
            reload();
        }
        render(true);
    }

    // ---------------------------------------------------------------- source menu

    private void openMenu() {
        if (srcIndex >= sources.size()) return;
        Sources.Entry src = sources.get(srcIndex);
        menuActions.clear();
        menuActions.add(MENU_SWITCH);
        menuActions.add(MENU_EDIT);
        if (Look.Prefs.sourceName(this, src.portId) != null || Look.Prefs.sourceIcon(this, src.portId) >= 0
                || Look.Prefs.sourceIconFile(this, src.portId) != null) {
            menuActions.add(MENU_RESET);
        }
        // Apple TV apps: pair with the Apple TV on this input so its apps can be opened from Home.
        String atvPort = AppleTv.isLinked(this) ? AppleTv.inputPort(this) : null;
        if (atvPort == null) menuActions.add(MENU_ATV_LINK);
        else if (atvPort.equals(src.portId)) {
            menuActions.add(MENU_ATV_REFRESH);
            menuActions.add(MENU_ATV_UNLINK);
        }
        else menuActions.add(MENU_ATV_MOVE);
        String atvName = AppleTv.name(this);
        String[] all = {"Switch to " + src.label, "Rename and icon", "Reset to " + src.autoName,
                "Link Apple TV apps", atvName + " is on this input", "Unlink " + atvName,
                "Refresh Apple TV apps"};
        List<CharSequence> labels = new ArrayList<CharSequence>();
        for (int a : menuActions) labels.add(all[a]);
        themeMenu = false;
        showMenu(labels, 0);
        list.reveal(srcIndex, false);
    }

    /** The theme picker, opening up and to the left of the theme icon. */
    private void openThemeMenu() {
        menuActions.clear();
        List<CharSequence> labels = new ArrayList<CharSequence>();
        int selected = 0;
        for (int k = 0; k < Look.MENU_ORDER.length; k++) {
            int i = Look.MENU_ORDER[k];
            menuActions.add(i);
            labels.add(Look.NAMES[i] + (i == look.index ? "   \u2713" : ""));
            if (i == look.index) selected = k;
        }
        themeMenu = true;
        showMenu(labels, selected);
    }

    private void showMenu(List<CharSequence> labels, int selected) {
        if (menu != null) root.removeView(menu);
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        int pad = look.hp(0.014f);
        menu.setPadding(pad, pad, pad, pad);
        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(look.hp(0.03f));
        card.setColor(mix(look.bg, look.strong, 0.06f));
        menu.setBackground(card);
        menuItems.clear();
        for (CharSequence l : labels) {
            TextView t = text(look.h * 0.032f, look.dim);
            t.setText(l);
            t.setSingleLine(true);
            int ph = look.wp(0.012f), pv = look.hp(0.012f);
            t.setPadding(ph, pv, ph, pv);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(look.h);
            pill.setColor(0);
            t.setBackground(pill);
            menu.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            menuItems.add(t);
        }
        menu.setAlpha(0f);
        root.addView(menu, wrap(Gravity.TOP | Gravity.START));
        menuIndex = clamp(selected, menuItems.size());
        menuOpen = true;
        renderMenu();
        render(true);
        // Place it once it has been measured.
        menu.post(new Runnable() {
            @Override
            public void run() {
                if (menu == null || !menuOpen) return;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menu.getLayoutParams();
                float slide;
                if (themeMenu) {
                    // Drops down below the theme icon and its label, centred under it
                    int[] r = new int[2], ic = new int[2];
                    root.getLocationInWindow(r);
                    icons[ICON_THEME].getLocationInWindow(ic);
                    int iconMid = ic[0] - r[0] + icons[ICON_THEME].getWidth() / 2;
                    int iconBottom = ic[1] - r[1] + icons[ICON_THEME].getHeight();
                    lp.leftMargin = Math.max(look.wp(0.04f), Math.min(iconMid - menu.getWidth() / 2,
                            root.getWidth() - menu.getWidth() - look.wp(0.04f)));
                    lp.topMargin = iconBottom + look.hp(0.06f);
                    slide = -look.wp(0.008f);
                } else {
                    int x = list.getLeft() + list.textRight(srcIndex) + look.wp(0.025f);
                    int maxX = root.getWidth() - menu.getWidth() - look.wp(0.12f);
                    lp.leftMargin = Math.max(list.getLeft(), Math.min(x, maxX));
                    int y = list.getTop() + list.centerY(srcIndex) - menu.getHeight() / 2;
                    lp.topMargin = Math.max(look.hp(0.18f),
                            Math.min(y, root.getHeight() - menu.getHeight() - look.hp(0.1f)));
                    slide = -look.wp(0.008f);
                }
                menu.setLayoutParams(lp);
                menu.setTranslationX(slide);
                menu.animate().alpha(1f).translationX(0).setDuration(150).start();
            }
        });
    }

    private void renderMenu() {
        for (int i = 0; i < menuItems.size(); i++) {
            TextView t = menuItems.get(i);
            boolean on = i == menuIndex;
            t.setTextColor(on ? look.strong : look.dim);
            ((GradientDrawable) t.getBackground()).setColor(on ? Look.withAlpha(look.strong, 0.1f) : 0);
        }
    }

    private boolean menuKey(int code, boolean down, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && menuIndex > 0) {
                    menuIndex--;
                    renderMenu();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && menuIndex < menuItems.size() - 1) {
                    menuIndex++;
                    renderMenu();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_MENU:
                if (down && e.getRepeatCount() == 0) closeMenu(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && e.getRepeatCount() == 0) {
                    if (themeMenu) {
                        closeMenu(true);          // back to the theme icon
                    } else {
                        closeMenu(false);         // on to the Apps tab
                        switchTab(APPS);
                    }
                }
                return true;
            default:
                // Volume, input, home and the like still work while the menu is open.
                return super.dispatchKeyEvent(e);
        }
    }

    private void chooseMenu() {
        if (themeMenu) {
            int pick = (menuIndex >= 0 && menuIndex < menuActions.size()) ? menuActions.get(menuIndex) : look.index;
            closeMenu(false);
            if (pick != look.index) {
                Look.Prefs.setTheme(this, pick);
                if (pick == Look.IDIOTS) IdiotsView.restart();
                restyle();
                if (look.flowers) MeadowView.replant();   // grows in as you pick it
            }
            return;
        }
        if (menuIndex < 0 || menuIndex >= menuActions.size() || srcIndex >= sources.size()) {
            closeMenu(true);
            return;
        }
        Sources.Entry src = sources.get(srcIndex);
        int action = menuActions.get(menuIndex);
        closeMenu(false);
        switch (action) {
            case MENU_SWITCH:
                Sources.open(this, src);
                break;
            case MENU_EDIT:
                startActivity(new Intent(this, EditSourceActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(EditSourceActivity.EXTRA_PORT, src.portId)
                        .putExtra(EditSourceActivity.EXTRA_PORT_NAME, src.portName)
                        .putExtra(EditSourceActivity.EXTRA_AUTO_NAME, src.autoName));
                break;
            case MENU_RESET:
                Look.Prefs.clearSource(this, src.portId);
                reload();
                break;
            case MENU_ATV_LINK:
                startActivity(new Intent(this, AtvPairActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(AtvPairActivity.EXTRA_PORT, src.portId));
                break;
            case MENU_ATV_MOVE:
                AppleTv.setInputPort(this, src.portId);
                android.widget.Toast.makeText(this, AppleTv.name(this) + " set to " + src.label,
                        android.widget.Toast.LENGTH_SHORT).show();
                break;
            case MENU_ATV_REFRESH: {
                final String who = AppleTv.name(this);
                android.widget.Toast.makeText(this, "Checking " + who + "\u2026", android.widget.Toast.LENGTH_SHORT).show();
                AppleTv.refreshNow(this, new AppleTv.Done<Integer>() {
                    @Override
                    public void done(Integer count, String error) {
                        String msg = count != null ? count + " apps from " + who + " are in All apps"
                                : "Couldn't get " + who + "'s apps: " + error;
                        android.widget.Toast.makeText(HomeActivity.this, msg, android.widget.Toast.LENGTH_LONG).show();
                        if (count != null) changed();
                    }
                });
                break;
            }
            case MENU_ATV_UNLINK:
                AppleTv.unlink(this);
                reload();
                android.widget.Toast.makeText(this, "Apple TV apps removed", android.widget.Toast.LENGTH_SHORT).show();
                break;
        }
        render(true);
    }

    private void closeMenu(boolean animate) {
        menuOpen = false;
        themeMenu = false;
        if (menu != null) {
            final View m = menu;
            menu = null;
            if (animate) {
                m.animate().alpha(0f).setDuration(120).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        root.removeView(m);
                    }
                }).start();
            } else {
                root.removeView(m);
            }
        }
        if (pendingReload) {
            pendingReload = false;
            reload();
        }
        render(animate);
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /**
     * Home's lines swing out and the tabs, clock and controls slide off to the left,
     * then All apps swings in (no system transition in between). The app list is
     * gathered in the background meanwhile, so All apps can show it straight away.
     */
    private void openAllApps() {
        if (turning) return;
        turning = true;
        final int gen = ++turnGen;
        inIcons = false;
        render(true);
        Apps.prefetch(this);
        float dx = look.wp(0.012f);
        for (int i = 0; i < chrome.size(); i++) {
            View v = chrome.get(i);
            v.animate().cancel();
            v.animate().alpha(0f).translationX(-dx)
                    .setStartDelay(i * 30L).setDuration(200)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f))
                    .start();
        }
        list.turnOut(true, new Runnable() {
            @Override
            public void run() {
                if (gen != turnGen) return;
                turning = false;
                handedOff = true;
                startActivity(new Intent(HomeActivity.this, AppsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));
                overridePendingTransition(0, 0);
            }
        });
    }

    /** Rebuild with the new theme, keeping the selection where it was. */
    private void restyle() {
        boolean wasInIcons = inIcons;
        build();
        inIcons = wasInIcons;
        showTab(false);
        render(false);
        list.jumpTo(index());
    }

    /** Write the on-screen order back, leaving not-installed pins where they were. */
    private void savePinnedOrder() {
        List<String> full = Look.Prefs.pinned(this);
        Set<String> shown = new HashSet<String>();
        for (Apps.Entry e : pinned) shown.add(e.pkg);
        int k = 0;
        for (int i = 0; i < full.size() && k < pinned.size(); i++) {
            if (shown.contains(full.get(i))) full.set(i, pinned.get(k++).pkg);
        }
        Look.Prefs.setPinned(this, full);
    }
}
