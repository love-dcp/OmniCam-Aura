package local.jiege.hook.gr;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import local.jiege.hook.common.Log;

/**
 * The GR palette: the same dial the master page uses, wired to whichever two GR parameters are
 * selected, with the axis readout and the parameters as horizontal rows above it. The camera keeps
 * its own style strip over the preview - that is where a style is chosen - and the block reads it
 * only for the dial's colour.
 *
 * <p>The dial reports master-scale tone and saturation values; each axis is mapped onto the step
 * range of the parameter it currently drives, so the same knob covers any of the eleven.
 */
public final class GrPanel {
    private static final String TAG = "GrPanel";
    private static final String PANEL_VIEW =
        "com.oplus.ocs.camera.ipuapi.process.filter.palette.PalettePanelView";
    private static final String LISTENER = PANEL_VIEW + "$OnToneSaturationChangeListener";
    /** The dial speaks master values; the GR rows are stepped, and step 4 is the centre. */
    private static final int DIAL_FULL = 100;
    private static final int STEP_ZERO = 4;
    /** The parameter strip above the dial. */
    private static final int CHIP_ROW_DP = 33;

    /** The camera's own styles, read off its strip and switched by clicking it. */
    public interface Styles {
        List<String> labels();
        /** Index of the style the camera currently has, or -1 when it cannot be told. */
        int current();
        void select(int index);
        /** The dial's colour recipe for a style, or null when the camera has none for it. */
        Object recipe(int index);
    }

    private static List<GrEffectValues.Spec> specs = new ArrayList<>();
    private static Styles styles;
    private static int styleIndex = -1;
    private static String xParam;
    private static String yParam;
    private static boolean armingY;
    private static LinearLayout paramStrip;
    private static Object dial;

    private GrPanel() {}

    public static boolean ready() {
        return !GrEffectValues.specs().isEmpty();
    }

    /** How tall the block is: the parameter strip, plus the dial. */
    public static int height(Activity activity, int dialPx) {
        return dp(activity, CHIP_ROW_DP) + dialPx;
    }

    /**
     * Builds the palette block: the parameters as a horizontal strip with the dial centred under it.
     * Neither the styles nor a text readout are part of it - the styles are chosen on the camera's
     * own strip over the preview (read here only for the dial's colour), and which parameter sits on
     * which axis is told by the chips' own colours.
     */
    public static View build(Activity activity, int dialPx, Styles source) {
        specs = GrEffectValues.specs();
        if (specs.isEmpty()) return null;
        styles = source;
        styleIndex = styles == null ? -1 : styles.current();
        if (xParam == null || !has(xParam)) xParam = pick("camera_gr_tone");
        if (yParam == null || !has(yParam) || yParam.equals(xParam)) {
            yParam = pick("camera_gr_saturation");
        }

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout params = new LinearLayout(activity);
        params.setOrientation(LinearLayout.HORIZONTAL);
        for (GrEffectValues.Spec spec : specs) {
            TextView chip = chip(activity, spec.label);
            chip.setTag(spec.name);
            chip.setOnClickListener(v -> {
                if (armingY) {
                    if (!spec.name.equals(xParam)) yParam = spec.name;
                    armingY = false;
                } else {
                    xParam = spec.name;
                    if (spec.name.equals(yParam)) yParam = other(spec.name);
                    armingY = true;
                }
                setDial();
                highlight();
            });
            params.addView(chip);
        }
        paramStrip = params;
        root.addView(scroller(activity, params), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, CHIP_ROW_DP)));

        dial = XposedHelpers.newInstance(
            XposedHelpers.findClass(PANEL_VIEW, activity.getClassLoader()), activity);
        applyRecipe(styles == null ? null : styles.recipe(styleIndex));
        XposedHelpers.callMethod(dial, "setCurrentToneAndSaturation", dialOf(xParam), dialOf(yParam));
        XposedHelpers.callMethod(dial, "setOnToneSaturationChangeListener",
            Proxy.newProxyInstance(activity.getClassLoader(), new Class<?>[] { listener(activity) },
                (proxy, method, args) -> {
                    if ("onToneSaturationChanged".equals(method.getName())) {
                        moved(((Number) args[0]).intValue(), ((Number) args[1]).intValue());
                        return null;
                    }
                    if ("toString".equals(method.getName())) return "AuraGrPalette";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    return null;
                }));
        LinearLayout.LayoutParams dialParams = new LinearLayout.LayoutParams(dialPx, dialPx);
        dialParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView((View) dial, dialParams);

        highlight();
        StringBuilder names = new StringBuilder();
        for (GrEffectValues.Spec spec : specs) names.append(' ').append(spec.name);
        Log.i(TAG, "built " + specs.size() + " parameters, "
            + (styles == null ? 0 : styles.labels().size()) + " styles, axes "
            + xParam + "/" + yParam + ", style " + (styleIndex < 0 ? "?" : "" + styleIndex)
            + ", dial " + dialPx + ", params" + names);
        return root;
    }

    public static void release() {
        dial = null;
        paramStrip = null;
        styles = null;
        styleIndex = -1;
    }

    private static View scroller(Activity activity, View content) {
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(content, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    private static TextView chip(Activity activity, String text) {
        TextView chip = new TextView(activity);
        chip.setText(text);
        chip.setSingleLine(true);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setTextColor(0xFFEDEDED);
        chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0x00FFFFFF);
        background.setCornerRadius(dp(activity, 10));
        background.setStroke(Math.max(1, dp(activity, 1)), 0x44FFFFFF);
        chip.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(activity, 6);
        params.topMargin = dp(activity, 4);
        params.bottomMargin = dp(activity, 5);
        chip.setLayoutParams(params);
        return chip;
    }

    private static void applyRecipe(Object recipe) {
        if (dial == null) return;
        try {
            if (recipe == null) {
                Log.i(TAG, "no colour recipe for style " + styleIndex + ": the dial stays flat");
                return;
            }
            XposedHelpers.callMethod(dial, "setColorRecipe", recipe);
        } catch (Throwable error) {
            Log.i(TAG, "colour recipe skipped " + error);
        }
    }

    private static void setDial() {
        if (dial == null) return;
        try {
            XposedHelpers.callMethod(dial, "setCurrentToneAndSaturation",
                dialOf(xParam), dialOf(yParam));
        } catch (Throwable error) {
            Log.i(TAG, "dial sync skipped " + error);
        }
    }

    /** The dial's own value for a parameter's current step. */
    private static int dialOf(String parameter) {
        int step = GrEffectValues.step(parameter);
        if (step == Integer.MIN_VALUE) step = STEP_ZERO;
        return (step - STEP_ZERO) * DIAL_FULL / STEP_ZERO;
    }

    private static void moved(int x, int y) {
        GrEffectValues.set(xParam, stepOf(x));
        GrEffectValues.set(yParam, stepOf(y));
    }

    private static int stepOf(int dialValue) {
        int step = Math.round(dialValue * (float) STEP_ZERO / DIAL_FULL) + STEP_ZERO;
        return Math.max(0, Math.min(STEP_ZERO * 2, step));
    }

    private static void highlight() {
        if (paramStrip != null) {
            for (int i = 0; i < paramStrip.getChildCount(); i++) {
                View chip = paramStrip.getChildAt(i);
                String name = String.valueOf(chip.getTag());
                boolean x = name.equals(xParam);
                boolean y = name.equals(yParam);
                chip.setAlpha(x || y ? 1f : 0.6f);
                tint(chip, x ? 0x66FFC24B : y ? 0x664BB1FF : 0x00FFFFFF);
            }
        }
    }

    private static void tint(View chip, int colour) {
        if (chip.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) chip.getBackground()).setColor(colour);
        }
    }

    private static boolean has(String name) {
        for (GrEffectValues.Spec spec : specs) if (spec.name.equals(name)) return true;
        return false;
    }

    private static String pick(String preferred) {
        if (has(preferred)) return preferred;
        return specs.isEmpty() ? preferred : specs.get(0).name;
    }

    private static String other(String name) {
        for (GrEffectValues.Spec spec : specs) if (!spec.name.equals(name)) return spec.name;
        return name;
    }

    private static Class<?> listener(Activity activity) {
        return XposedHelpers.findClass(LISTENER, activity.getClassLoader());
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
