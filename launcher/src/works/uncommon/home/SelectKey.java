package works.uncommon.home;

import android.view.KeyEvent;

/**
 * Turns the remote's OK button into "press" and "hold" without relying on
 * view focus. Remotes vary in which key code OK sends, so accept them all.
 */
final class SelectKey {

    interface Listener {
        void onSelect();

        void onHold();
    }

    private boolean held;
    private boolean down;   // saw this press start, so a stray key-up can't trigger

    static boolean isSelect(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    /** Returns true if the event was an OK key (always consumed). */
    boolean handle(KeyEvent e, Listener l) {
        if (!isSelect(e.getKeyCode())) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                down = true;
                held = false;
            } else if (down && !held && (e.isLongPress() || e.getRepeatCount() >= 12)) {
                held = true;
                l.onHold();
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            if (down && !held && !e.isCanceled()) l.onSelect();
            held = false;
            down = false;
        }
        return true;
    }
}
