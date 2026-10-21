package works.uncommon.home;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** The same decoration and animation timeline on Home and All apps. */
final class ThemeDecor {
    static void addTo(FrameLayout root, Look look) {
        View view;
        int height = ViewGroup.LayoutParams.MATCH_PARENT;
        if (look.sky) view = new SkyView(root.getContext());
        else if (look.flowers) {
            view = new MeadowView(root.getContext(), look);
            height = MeadowView.heightPx(root.getContext());
        } else if (look.index == Look.HALLOWEEN) view = new HalloweenView(root.getContext());
        else if (look.index == Look.IDIOTS) view = new IdiotsView(root.getContext());
        else return;
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM));
    }
}
