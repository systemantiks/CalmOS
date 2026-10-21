package works.uncommon.home;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Links the Apple TV on an HDMI input: find it on the network, type the code it
 * shows, done. Afterwards its apps appear in All apps and open straight on it.
 */
public class AtvPairActivity extends Activity {

    static final String EXTRA_PORT = "port";

    private Look look;
    private String port;
    private LinearLayout col;
    private TextView title, body;
    private LinearLayout choices;
    private EditText code;
    private View underline;

    private final List<AppleTv.Device> found = new ArrayList<AppleTv.Device>();
    private Runnable stopSearch;
    private AppleTv.Pairing pairing;
    private AppleTv.Device chosen;
    private boolean submitting;
    private long shownAt;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        port = getIntent().getStringExtra(EXTRA_PORT);
        build();
        AppleTv.Pending p = AppleTv.pending();
        if (p != null && (port == null || port.equals(p.port))) {
            // Back from reading the code on the Apple TV: carry on where we were.
            port = p.port;
            chosen = p.device;
            pairing = p.pairing;
            askForCode(null);
        } else {
            if (p != null) AppleTv.clearPending(true);
            if (port == null) {
                finish();
                return;
            }
            search();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        shownAt = SystemClock.uptimeMillis();
    }

    @Override
    protected void onDestroy() {
        if (stopSearch != null) stopSearch.run();
        // A pairing waiting for its code is kept alive (see AppleTv.Pending); anything else stops here.
        AppleTv.Pending p = AppleTv.pending();
        if (pairing != null && (p == null || p.pairing != pairing)) pairing.cancel();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        AppleTv.clearPending(true);
        super.onBackPressed();
    }

    /** Ignore the tail of the OK press that opened this screen. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (SelectKey.isSelect(e.getKeyCode()) && SystemClock.uptimeMillis() - shownAt < 600) return true;
        int k = e.getKeyCode();
        if (code != null && code.getVisibility() == View.VISIBLE && !code.hasFocus()
                && k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
            code.requestFocus();
            return code.dispatchKeyEvent(e);
        }
        return super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------ steps

    private void search() {
        found.clear();
        showMessage("Link Apple TV", "Looking for Apple TVs on your network…", null);
        stopSearch = AppleTv.discover(this, 8000, new AppleTv.Done<AppleTv.Device>() {
            @Override
            public void done(AppleTv.Device d, String error) {
                if (isFinishing() || chosen != null) return;
                found.add(d);
                showDevices();
            }
        }, new Runnable() {
            @Override
            public void run() {
                stopSearch = null;
                if (isFinishing() || chosen != null) return;
                if (found.isEmpty()) {
                    showMessage("No Apple TV found",
                            "Check it's switched on and on the same network as the TV.",
                            new String[]{"Search again", "Cancel"});
                }
            }
        });
    }

    private void showDevices() {
        int focused = choices.indexOfChild(choices.getFocusedChild());
        showMessage("Link Apple TV", found.size() == 1 ? "Found one Apple TV. Choose it to link."
                : "Choose the Apple TV plugged into this input.", null);
        choices.removeAllViews();
        for (int i = 0; i < found.size(); i++) {
            final AppleTv.Device d = found.get(i);
            TextView t = chip(d.name);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    choose(d);
                }
            });
            choices.addView(t, chipParams(i == 0));
        }
        addChip("Cancel", choices.getChildCount() == 0);
        choices.getChildAt(focused >= 0 && focused < choices.getChildCount() ? focused : 0).requestFocus();
    }

    private void choose(final AppleTv.Device d) {
        chosen = d;
        if (stopSearch != null) {
            stopSearch.run();
            stopSearch = null;
        }
        showMessage(d.name, "Connecting…", new String[]{"Cancel"});
        AppleTv.clearPending(true);
        final AppleTv.Pairing attempt = new AppleTv.Pairing(this, d, port);
        pairing = attempt;
        attempt.start(new AppleTv.Done<Boolean>() {
            @Override
            public void done(Boolean ok, String error) {
                if (ok != null && ok) AppleTv.setPending(new AppleTv.Pending(d, port, attempt));
                if (isFinishing()) return;
                if (ok != null && ok) askForCode(null);
                else showMessage(d.name, "Couldn't start linking: " + error + ".",
                        new String[]{"Try again", "Cancel"});
            }
        });
    }

    private void askForCode(String problem) {
        showMessage(chosen.name, (problem == null ? "" : problem + ". ")
                + "Type the code shown on the Apple TV. To see it, choose Show code, "
                + "then press Home to come back here.", null);
        code.setText("");
        code.setVisibility(View.VISIBLE);
        underline.setVisibility(View.VISIBLE);
        choices.removeAllViews();
        addChip("Link", true);
        addChip("Show code", false);
        addChip("Cancel", false);
        code.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(code, 0);
    }

    private void submit() {
        final String pin = code.getText().toString().trim();
        if (pin.length() < 4 || submitting) return;
        submitting = true;
        hideKeyboard();
        code.setVisibility(View.GONE);
        underline.setVisibility(View.GONE);
        showMessage(chosen.name, "Linking…", new String[]{"Cancel"});
        pairing.finish(pin, new AppleTv.Done<Integer>() {
            @Override
            public void done(Integer apps, String error) {
                submitting = false;
                boolean retrying = apps == null && error != null && error.startsWith("That code");
                if (!retrying) AppleTv.clearPending(false);
                if (isFinishing()) return;
                if (apps != null) {
                    showMessage("Linked",
                            apps > 0 ? apps + " apps from " + chosen.name + " are now in All apps. "
                                    + "Hold OK on one to pin it to Home."
                                    : chosen.name + " is linked. Its apps will appear in All apps once it's awake.",
                            new String[]{"Done"});
                } else if (error != null && error.startsWith("That code")) {
                    // The Apple TV shows a new code for each attempt.
                    showMessage(chosen.name, "That code didn't match. Getting a new one…", new String[]{"Cancel"});
                    pairing.start(new AppleTv.Done<Boolean>() {
                        @Override
                        public void done(Boolean ok, String err) {
                            if (ok == null || !ok) AppleTv.clearPending(false);
                            if (isFinishing()) return;
                            if (ok != null && ok) {
                                askForCode("That code didn't match");
                            } else {
                                AppleTv.clearPending(false);
                                showMessage(chosen.name, "Couldn't link: " + err + ".", new String[]{"Try again", "Cancel"});
                            }
                        }
                    });
                } else {
                    showMessage(chosen.name, "Couldn't link: " + error + ".", new String[]{"Try again", "Cancel"});
                }
            }
        });
    }

    private void onChoice(String label) {
        if (label.equals("Cancel") || label.equals("Done")) {
            AppleTv.clearPending(label.equals("Cancel"));
            finish();
        } else if (label.equals("Show code")) {
            showCode();
        } else if (label.equals("Search again")) {
            chosen = null;
            search();
        } else if (label.equals("Try again")) {
            if (chosen != null) choose(chosen);
            else search();
        } else if (label.equals("Link")) {
            submit();
        }
    }

    /** Switch the TV to the Apple TV so the code can be read; Home brings this screen back. */
    private void showCode() {
        for (Sources.Entry s : Sources.list(this)) {
            if (s.portId.equals(port)) {
                Sources.open(this, s);
                return;
            }
        }
        android.widget.Toast.makeText(this, "Switch the TV to the Apple TV's input, then press Home",
                android.widget.Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------ layout

    private void build() {
        look = Look.get(this);
        getWindow().setBackgroundDrawable(new ColorDrawable(look.bg));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(look.bg);

        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(look.wp(0.8f), ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = look.wp(0.074f);
        cp.topMargin = look.hp(0.104f);
        root.addView(col, cp);

        TextView kicker = text(look.h * 0.046f, look.dim, "Apple TV apps");
        col.addView(kicker);
        space(0.06f);

        title = new TextView(this);
        title.setTypeface(look.display);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, look.h * 0.083f);
        title.setTextColor(look.strong);
        title.setIncludeFontPadding(false);
        title.setLetterSpacing(-0.012f);
        title.setSingleLine(true);
        col.addView(title);
        space(0.03f);

        body = text(look.h * 0.032f, look.dim, "");
        body.setLineSpacing(look.h * 0.01f, 1f);
        col.addView(body);
        space(0.05f);

        code = new EditText(this);
        code.setTypeface(look.display);
        code.setTextSize(TypedValue.COMPLEX_UNIT_PX, look.h * 0.083f);
        code.setTextColor(look.strong);
        code.setHintTextColor(look.faint);
        code.setHint("0000");
        code.setLetterSpacing(0.2f);
        code.setIncludeFontPadding(false);
        code.setSingleLine(true);
        code.setBackground(null);
        code.setPadding(0, 0, 0, look.hp(0.01f));
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        code.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        code.setVisibility(View.GONE);
        tintCursor(code);
        code.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() == 4) submit();
            }
        });
        code.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                submit();
                return true;
            }
        });
        col.addView(code, new LinearLayout.LayoutParams(look.wp(0.3f), ViewGroup.LayoutParams.WRAP_CONTENT));
        underline = new View(this);
        underline.setBackgroundColor(look.strong);
        underline.setVisibility(View.GONE);
        col.addView(underline, new LinearLayout.LayoutParams(look.wp(0.18f), Math.max(2, look.hp(0.003f))));
        space(0.05f);

        choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(choices);

        setContentView(root);
    }

    private void showMessage(String t, String b, String[] buttons) {
        title.setText(t);
        body.setText(b);
        if (code != null && buttons != null) {
            code.setVisibility(View.GONE);
            underline.setVisibility(View.GONE);
        }
        choices.removeAllViews();
        if (buttons != null) {
            for (int i = 0; i < buttons.length; i++) addChip(buttons[i], i == 0);
            choices.getChildAt(0).requestFocus();
        }
    }

    private void addChip(final String label, boolean first) {
        TextView t = chip(label);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onChoice(label);
            }
        });
        choices.addView(t, chipParams(first));
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
        if (first) p.leftMargin = -look.wp(0.012f);
        p.rightMargin = look.wp(0.006f);
        return p;
    }

    private TextView text(float px, int color, String s) {
        TextView t = new TextView(this);
        t.setTypeface(look.text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setText(s);
        return t;
    }

    private void space(float frac) {
        col.addView(new View(this), new LinearLayout.LayoutParams(1, look.hp(frac)));
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(code.getWindowToken(), 0);
    }

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
