package works.uncommon.home;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Rename an HDMI input and choose its icon. Opened from the source options menu.
 * Uses ordinary Android focus so the TV keyboard and D-pad work as usual.
 */
public class EditSourceActivity extends Activity {

    static final String EXTRA_PORT = "port";
    static final String EXTRA_PORT_NAME = "portName";
    static final String EXTRA_AUTO_NAME = "autoName";

    private static final int[] ICONS = {
            IconView.PLAYSTATION, IconView.XBOX, IconView.NINTENDO, IconView.APPLE, IconView.LAPTOP,
    };
    private static final String[] ICON_NAMES = {
            "PlayStation", "Xbox", "Nintendo", "Apple", "Laptop",
    };

    private static final String[] PRESET_NAMES = {
            "PlayStation 5", "PlayStation 4", "Xbox", "Nintendo Switch", "Apple TV", "Laptop",
    };
    private static final int[] PRESET_ICONS = {
            IconView.PLAYSTATION, IconView.PLAYSTATION, IconView.XBOX, IconView.NINTENDO, IconView.APPLE,
            IconView.LAPTOP,
    };

    private Look look;
    private String port;
    private String autoName;

    private EditText name;
    private View underline;
    // Picker options: your own image files first, then bundled logos and line icons.
    private final java.util.List<String> optFile = new java.util.ArrayList<String>();   // null for built-in
    private final java.util.List<Integer> optKind = new java.util.ArrayList<Integer>(); // -1 for files
    private final java.util.List<String> optName = new java.util.ArrayList<String>();
    private final java.util.List<IconView> iconViews = new java.util.ArrayList<IconView>();
    private TextView iconCaption;
    private TextView save;

    private int selectedIcon;       // built-in kind (used when selectedFile is null)
    private String selectedFile;    // your own image, or null
    private boolean iconChosen;     // picked by hand, so typing a name no longer changes it

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        port = getIntent().getStringExtra(EXTRA_PORT);
        if (port == null) {
            finish();
            return;
        }
        String portName = getIntent().getStringExtra(EXTRA_PORT_NAME);
        autoName = getIntent().getStringExtra(EXTRA_AUTO_NAME);
        if (portName == null) portName = "HDMI";
        if (autoName == null) autoName = portName;

        String savedName = Look.Prefs.sourceName(this, port);
        int savedIcon = Look.Prefs.sourceIcon(this, port);
        String savedFile = Look.Prefs.sourceIconFile(this, port);
        if (savedFile != null && CustomIcons.load(this, savedFile) == null) savedFile = null;
        String current = savedName != null ? savedName : autoName;
        iconChosen = savedIcon >= 0 || savedFile != null;
        selectedIcon = savedIcon >= 0 ? savedIcon : Sources.iconFor(current);
        selectedFile = iconChosen ? savedFile : autoFile(current);

        for (String f : CustomIcons.list(this)) {
            if (CustomIcons.load(this, f) == null) continue;
            optFile.add(f);
            optKind.add(-1);
            optName.add(CustomIcons.displayName(f));
        }
        for (int i = 0; i < ICONS.length; i++) {
            optFile.add(null);
            optKind.add(ICONS[i]);
            optName.add(ICON_NAMES[i]);
        }

        build(portName, savedName);
        renderIcons(-1);
    }

    private void build(String portName, String savedName) {
        look = Look.get(this);
        getWindow().setBackgroundDrawable(new ColorDrawable(look.bg));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(look.bg);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(look.wp(0.85f), ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = look.wp(0.074f);
        cp.topMargin = look.hp(0.104f);
        root.addView(col, cp);

        // Title: the TV's name for this port
        TextView title = text(look.h * 0.046f, look.dim);
        title.setText(portName);
        col.addView(title);
        space(col, 0.06f);

        // Name
        name = new EditText(this);
        name.setTypeface(look.display);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, look.h * 0.083f);
        name.setTextColor(look.strong);
        name.setHintTextColor(look.faint);
        name.setHint(autoName);
        name.setHighlightColor(Look.withAlpha(look.strong, 0.18f));
        name.setLetterSpacing(-0.012f);
        name.setIncludeFontPadding(false);
        name.setSingleLine(true);
        name.setBackground(null);
        name.setPadding(0, 0, 0, look.hp(0.01f));
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        name.setText(savedName != null ? savedName : "");
        name.setSelection(name.getText().length());
        tintCursor(name);
        name.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!iconChosen) {
                    String n = s.toString().trim();
                    if (n.length() == 0) n = autoName;
                    selectedIcon = Sources.iconFor(n);
                    selectedFile = autoFile(n);
                    renderIcons(-1);
                }
            }
        });
        name.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                    hideKeyboard();
                    save.requestFocus();
                    return true;
                }
                return false;
            }
        });
        col.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        underline = new View(this);
        underline.setBackgroundColor(look.faint);
        col.addView(underline, new LinearLayout.LayoutParams(look.wp(0.5f), Math.max(2, look.hp(0.003f))));
        name.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                underline.setBackgroundColor(has ? look.strong : look.faint);
                if (!has) hideKeyboard();
            }
        });

        space(col, 0.055f);

        // Icon picker
        col.addView(text(look.h * 0.024f, look.dim, "Icon"));
        space(col, 0.012f);
        HorizontalScrollView iconScroll = new HorizontalScrollView(this);
        iconScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout icons = new LinearLayout(this);
        icons.setOrientation(LinearLayout.HORIZONTAL);
        int box = look.hp(0.085f);
        for (int i = 0; i < optKind.size(); i++) {
            final int idx = i;
            final IconView v = new IconView(this, optKind.get(i) >= 0 ? optKind.get(i) : IconView.PORT);
            if (optFile.get(i) != null) v.setImage(CustomIcons.load(this, optFile.get(i)), look.bg);
            v.setFocusable(true);
            v.setClickable(true);
            v.setContentDescription(optName.get(i));
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    selectedFile = optFile.get(idx);
                    if (selectedFile == null) selectedIcon = optKind.get(idx);
                    iconChosen = true;
                    renderIcons(idx);
                }
            });
            v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean has) {
                    renderIcons(has ? idx : focusedIcon());
                }
            });
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(box, box);
            ip.rightMargin = look.wp(0.012f);
            icons.addView(v, ip);
            iconViews.add(v);
        }
        iconScroll.addView(icons);
        col.addView(iconScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        space(col, 0.008f);
        iconCaption = text(look.h * 0.024f, look.dim);
        col.addView(iconCaption);

        space(col, 0.045f);

        // Presets: name + icon in one press
        col.addView(text(look.h * 0.024f, look.dim, "Presets"));
        space(col, 0.012f);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < PRESET_NAMES.length; i++) {
            final int idx = i;
            TextView chip = chip(PRESET_NAMES[i]);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    name.setText(PRESET_NAMES[idx]);
                    name.setSelection(name.getText().length());
                    selectedIcon = PRESET_ICONS[idx];
                    selectedFile = autoFile(PRESET_NAMES[idx]);
                    iconChosen = true;
                    renderIcons(-1);
                    save.requestFocus();
                }
            });
            presets.addView(chip, chipParams(i == 0));
        }
        scroll.addView(presets);
        col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        space(col, 0.06f);

        // Actions
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        save = chip("Save");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String n = name.getText().toString().trim();
                // Leaving the name blank (or as the automatic one) keeps the automatic name.
                String keepName = n.equals(autoName) ? null : n;
                if (!iconChosen) {
                    Look.Prefs.setSource(EditSourceActivity.this, port, keepName, -1, null);
                } else if (selectedFile != null) {
                    Look.Prefs.setSource(EditSourceActivity.this, port, keepName, -1, selectedFile);
                } else {
                    Look.Prefs.setSource(EditSourceActivity.this, port, keepName, selectedIcon, null);
                }
                finish();
            }
        });
        TextView reset = chip("Reset");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Look.Prefs.clearSource(EditSourceActivity.this, port);
                finish();
            }
        });
        TextView cancel = chip("Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        actions.addView(save, chipParams(true));
        actions.addView(reset, chipParams(false));
        actions.addView(cancel, chipParams(false));
        col.addView(actions);

        setContentView(root);
        // Focus the name, but the keyboard only opens when you press OK on it.
        name.requestFocus();
    }

    private boolean sawOkDown;
    private long shownAt;

    @Override
    protected void onResume() {
        super.onResume();
        shownAt = android.os.SystemClock.uptimeMillis();
    }

    /**
     * This screen opens on a held OK. The rest of that press (repeats and the
     * key-up) still arrives here and would pop the keyboard straight away, so
     * ignore OK events until a fresh press starts on this screen.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (SelectKey.isSelect(e.getKeyCode())) {
            // Some remotes resend OK as fresh presses while it's held; ignore OK for a moment.
            if (android.os.SystemClock.uptimeMillis() - shownAt < 600) return true;
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) sawOkDown = true;
            if (!sawOkDown) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private int focusedIcon() {
        for (int i = 0; i < iconViews.size(); i++) if (iconViews.get(i).hasFocus()) return i;
        return -1;
    }

    private boolean isSelected(int i) {
        String f = optFile.get(i);
        if (f != null) return f.equals(selectedFile);
        return selectedFile == null && optKind.get(i) == selectedIcon;
    }

    /** Your image whose file name matches this name, if you've added one. */
    private String autoFile(String forName) {
        String f = CustomIcons.match(this, forName);
        return (f != null && CustomIcons.load(this, f) != null) ? f : null;
    }

    /** Chosen icon is drawn strong; the focused one gets a soft circle; the caption names it. */
    private void renderIcons(int focused) {
        int chosen = -1;
        for (int i = 0; i < iconViews.size(); i++) {
            IconView v = iconViews.get(i);
            boolean sel = isSelected(i);
            if (sel) chosen = i;
            v.setColor(sel ? look.strong : look.dim);
            v.setHalo(i == focused, Look.withAlpha(look.strong, 0.1f));
        }
        if (iconCaption != null) {
            int show = focused >= 0 ? focused : chosen;
            String cap = show >= 0 ? optName.get(show) : "";
            if (show >= 0 && show == chosen && !iconChosen) cap += "  \u00b7  picked from the name";
            iconCaption.setText(cap);
        }
    }

    private TextView chip(String label) {
        final TextView t = text(look.h * 0.03f, look.dim, label);
        t.setFocusable(true);
        t.setClickable(true);
        int ph = look.wp(0.012f), pv = look.hp(0.012f);
        t.setPadding(ph, pv, ph, pv);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(look.h);
        bg.setColor(0);
        t.setBackground(bg);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                bg.setColor(has ? Look.withAlpha(look.strong, 0.1f) : 0);
                t.setTextColor(has ? look.strong : look.dim);
            }
        });
        return t;
    }

    private LinearLayout.LayoutParams chipParams(boolean first) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // Pull the first chip left so its text lines up with everything else.
        if (first) p.leftMargin = -look.wp(0.012f);
        p.rightMargin = look.wp(0.006f);
        return p;
    }

    private TextView text(float px, int color) {
        TextView t = new TextView(this);
        t.setTypeface(look.text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView text(float px, int color, String s) {
        TextView t = text(px, color);
        t.setText(s);
        return t;
    }

    private void space(LinearLayout col, float frac) {
        col.addView(new View(this), new LinearLayout.LayoutParams(1, look.hp(frac)));
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && name != null) imm.hideSoftInputFromWindow(name.getWindowToken(), 0);
    }

    /** Cursor in the theme's ink colour (API 29+; older TVs keep the default). */
    private void tintCursor(EditText e) {
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            GradientDrawable d = new GradientDrawable();
            d.setColor(look.strong);
            d.setSize(Math.max(2, look.hp(0.003f)), look.hp(0.07f));
            TextView.class.getMethod("setTextCursorDrawable", android.graphics.drawable.Drawable.class).invoke(e, d);
        } catch (Exception ignored) {
        }
    }
}
