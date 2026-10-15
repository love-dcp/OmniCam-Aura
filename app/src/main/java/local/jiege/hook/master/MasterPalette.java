package local.jiege.hook.master;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * Palette entry for master ("professional") mode on PKJ110.
 *
 * <p>The stock filter menu only offers the palette in common/portrait/night: its gate is
 * {@code Lut3dDataManager.f(String mode)} = {@code CameraConfig.d("com.oplus.feature.color.palette.support")
 * && (mode is common|portrait|night)}. Master mode is "professional", so the gate is shut and it is
 * a hard-coded mode list - answering the feature key (what {@code PaletteFeatureSwitch} does for
 * photo) changes nothing here, and forcing {@code f()} for "professional" makes the native palette
 * backend run against master filters and fogs the preview. The built-in UI route is therefore a dead
 * end for master.
 *
 * <p>This class goes the other way round: it never touches the stock gate or the APS configuration.
 * It hangs its own small button on the camera window, and when tapped instantiates the app's own
 * {@code PalettePanelView}, fills it with the recipe from {@code PaletteColors.get(filterType)} and
 * pushes tone/saturation straight into the master presenter with {@code presenter.a(value, key)} -
 * keys {@code n9.f.P0} / {@code n9.f.O0}. The presenter already accepts palette writes, so only the
 * UI entry point was missing in master mode.
 *
 * <p>Once the panel is up the layout is arranged exactly like the camera's own palette: the control
 * panel is lifted by {@code dimen/palette_control_view_translation_4_3} (which is what moves the
 * shutter out of the way - the preview frame itself never moves), the mode strip, the zoom row and
 * the manual-parameter rows are hidden, the preview is masked into a rounded card, the app's own
 * 影调/饱和度 readout is inflated along the top edge, and the thumbnail is parked in the palette
 * row. Every number here was measured on the device with the layout snapshot in {@link #PROP_DUMP}.
 *
 * <p>The obfuscated names differ between camera builds: 7.006.94 (ROM 17.0.0.101/102) uses
 * {@code ef.m}/{@code rp.k0}/{@code em.p0}/{@code yg.a} and the methods {@code f0}/{@code R5}, while
 * 7.006.123X (ROM 17.0.0.104, the build this device runs) renames them to
 * {@code ff.n}/{@code tp.k0}/{@code fm.q0}/{@code zg.a} and {@code c0}/{@code Q5}. {@link #type}
 * and {@link #call} translate, and the newer build is detected by the presence of {@code ff.n}.
 *
 * <p>The feature is on by default: {@code persist.omnicam.aura.masterpalette=0} turns it off, and
 * with the property at 0 nothing is installed and the app behaves exactly as before.
 */
public final class MasterPalette {
    private static final String TAG = "MasterPalette";
    private static final String PROP = "persist.omnicam.aura.masterpalette";
    /** Changing this property's value makes the camera print one layout snapshot (calibration). */
    private static final String PROP_DUMP = "persist.omnicam.aura.paldump";
    /** The stock photo-mode palette container; its appearance is the layout we want to copy. */
    private static final String[] PHOTO_PALETTE_IDS = {
        "palette_menu_root", "palette_panel_container", "palette_panel_view",
        "palette_reset_button_group", "palette_soft_light_button_group",
    };
    /**
     * Views the camera itself hides while its own (photo mode) palette is up - measured with the
     * paldump tree dump: the mode strip, the zoom row over the preview and the manual-parameter
     * rows. Without hiding the last two they keep floating over the preview.
     */
    private static final String[] PALETTE_HIDE_IDS = {
        "headline_view", "headline_background",
        "zoom_seek_bar",
        "professional_params_bar_container", "professional_param_slide_bar_container",
    };
    /** How far the camera lifts the control panel while its palette is up: dimen/palette_control_view_translation_4_3. */
    private static final int PALETTE_PANEL_LIFT_DP = 95;
    /** Dial top relative to the lifted control panel top (photo mode: 1836 - 1485 = 351px at 3x). */
    private static final int PALETTE_DIAL_TOP_DP = 117;
    /**
     * The preview card photo mode draws while its palette is up: the image is inset 14dp on both
     * sides (42px at 3x) and rounded with a 16dp radius (48px) - measured off the screenshots.
     */
    private static final int PREVIEW_CARD_INSET_DP = 14;
    private static final int PREVIEW_CARD_RADIUS_DP = 16;
    /**
     * The app's own 影调/饱和度 readout, shown along the top edge while its palette is up. Inflating
     * the layout keeps the labels (string/tone, string/camera_master_saturation), font and spacing
     * identical; its two groups sit at x 291..639, i.e. clear of the top bar icons either side.
     */
    private static final String READOUT_LAYOUT = "palette_value_panel";
    private static final String READOUT_LAYOUT_ALT = "palette_value_panel_multi_language";
    /**
     * The stock filter UI (filter strip / style switch). None of these are visible in master mode on
     * their own, so seeing one while the palette is up means the user opened the filter page: the
     * palette has to go, otherwise its click-catcher sits over the preview and the page can no longer
     * be dragged to switch filters.
     */
    private static final String[] FILTER_PANEL_IDS = {
        "effect_style_bottom_control_view", "effect_style_indicator", "effect_style_back_icon",
        "switch_filter_view", "style_filter_view",
    };
    /** Bottom-bar ids to try, in order, when measuring the free strip under the shutter. */
    private static final String[] CANDIDATE_CONTROL_IDS = {
        "bottom_bar", "control_panel_layout", "effect_style_bottom_control_view",
        "control_panel_button_layout", "bottom_control_bar",
    };
    /** Ids reported in the one-shot geometry line, to keep calibrating against the device. */
    private static final String[] GEOMETRY_IDS = {
        "control_panel_layout", "control_panel_button_layout", "shutter_button", "thumbnail",
        "headline_view", "zoom_seek_bar", "professional_params_bar_container",
        "palette_panel_view", "palette_menu_root", "frame_layout",
    };

    private static ClassLoader loader;
    private static boolean camera123;
    private static boolean diagnostics;
    private static final List<WeakReference<Object>> presenters = new ArrayList<>();
    private static final List<WeakReference<Object>> containers = new ArrayList<>();
    private static WeakReference<Session> session = new WeakReference<>(null);

    private MasterPalette() {}

    public static void install(ClassLoader classLoader) {
        loader = classLoader;
        if ("0".equals(readProp())) {
            Log.i(TAG, "off (" + PROP + "=0)");
            return;
        }
        // The layout snapshots are a calibration aid, not something to write into every log: they
        // run when the paldump property carries a value, and stay out of the way otherwise.
        String dump = prop(PROP_DUMP);
        diagnostics = !dump.isEmpty() && !"0".equals(dump);
        camera123 = XposedHelpers.findClassIfExists("ff.n", classLoader) != null;
        try {
            XposedBridge.hookAllConstructors(cls("ef.m"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    presenters.add(new WeakReference<>(param.thisObject));
                }
            });
            XposedBridge.hookAllConstructors(cls("rp.k0"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    containers.add(new WeakReference<>(param.thisObject));
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (!"com.oplus.camera.Camera".equals(activity.getClass().getName())) return;
                    Session current = session.get();
                    if (current != null && current.activity == activity) return;
                    if (current != null) current.close();
                    Session created = new Session(activity);
                    session = new WeakReference<>(created);
                    created.start();
                }
            });
            // Leaving the camera (e.g. tapping the thumbnail to open the gallery) must take the
            // palette down with it; otherwise it hangs over the photo viewer.
            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Session current = session.get();
                    if (current != null && current.activity == param.thisObject) current.detach();
                }
            });
            // Anything the camera itself is about to do takes the palette down first. The palette
            // lifts the whole control panel while it is up, and a page built during that moment (the
            // filter page, above all) caches a touch area that stops short of the bottom half - so
            // the page can only be dragged from the top. Closing on the way into the click means the
            // camera builds its page against the untouched layout.
            XposedHelpers.findAndHookMethod(View.class, "performClick", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Session current = session.get();
                    if (current != null) current.onForeignClick((View) param.thisObject);
                }
            });
            Log.i(TAG, "installed independent button; original palette gates and APS configuration unchanged");
        } catch (Throwable error) {
            Log.e(TAG, "install failed", error);
        }
    }

    private static String readProp() {
        // Empty means "not set", which is the on state: only an explicit 0 turns the feature off.
        return prop(PROP);
    }

    private static String prop(String name) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Object value = type.getMethod("get", String.class, String.class).invoke(null, name, "");
            return String.valueOf(value);
        } catch (Throwable error) {
            return "";
        }
    }

    // --- reflection helpers ------------------------------------------------------------------

    private static Object stat(String className, String method, Object... args) {
        Class<?> target = cls(className);
        // 7.006.123X moved the SAU settings getter from com.oplusos.sau.common.utils.a#h to r1.d#l.
        if (camera123 && "com.oplusos.sau.common.utils.a".equals(className) && "h".equals(method)) {
            target = cls("r1.d");
            method = "l";
        }
        if (target == null) throw new IllegalStateException("class not found: " + className);
        // Older builds declare the trailing parameter as Integer, the newer one as Object; the JVM
        // lookup Xposed does is exact, so match by hand and fall back to Xposed only if nothing fits.
        for (Class<?> c = target; c != null; c = c.getSuperclass()) {
            for (Method candidate : c.getDeclaredMethods()) {
                if (!candidate.getName().equals(method)) continue;
                if (candidate.getParameterCount() != args.length) continue;
                if (!matches(candidate.getParameterTypes(), args)) continue;
                try {
                    candidate.setAccessible(true);
                    return candidate.invoke(null, args);
                } catch (Throwable ignored) {
                }
            }
        }
        return XposedHelpers.callStaticMethod(target, method, args);
    }

    /** True when every argument can be passed where the matching parameter type expects it. */
    private static boolean matches(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            Object arg = args[i];
            if (arg == null) {
                if (type.isPrimitive()) return false;
                continue;
            }
            Class<?> actual = arg.getClass();
            if (type.isAssignableFrom(actual)) continue;
            if (type == int.class && actual == Integer.class) continue;
            if (type == long.class && actual == Long.class) continue;
            if (type == boolean.class && actual == Boolean.class) continue;
            if (type == float.class && actual == Float.class) continue;
            return false;
        }
        return true;
    }

    private static Class<?> cls(String name) {
        return XposedHelpers.findClassIfExists(type(name), loader);
    }

    /**
     * Logical camera class name to this build's real name. 7.006.123X renamed every class the
     * palette path touches; the mapping is taken from the build where {@code ff.n} exists.
     */
    private static String type(String name) {
        if (!camera123) return name;
        switch (name) {
            case "ef.m": return "ff.n";
            case "ef.m$b": return "ff.n$b";
            case "rp.k0": return "tp.k0";
            case "com.oplus.camera.x6": return "com.oplus.camera.w6";
            case "em.p0": return "fm.q0";
            case "yg.a": return "zg.a";
            case "ap.d": return "bp.d";
            default: return name;
        }
    }

    private static Object call(Object target, String method, Object... args) {
        // 7.006.123X also renamed two methods on the presenter/container.
        if (camera123) {
            if ("f0".equals(method)) method = "c0";
            else if ("R5".equals(method)) method = "Q5";
        }
        return XposedHelpers.callMethod(target, method, args);
    }

    private static Object field(Object target, String name) {
        return XposedHelpers.getObjectField(target, name);
    }

    /** Value of the first declared field whose type name equals {@code type}, walking up. */
    private static Object byType(Object target, String type) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.getType().getName().equals(type)) continue;
                try {
                    f.setAccessible(true);
                    return f.get(target);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** The palette key constants live as static fields on n9.f (P0 = tone, O0 = saturation). */
    private static Object key(String name) {
        Class<?> type = cls("n9.f");
        return type == null ? null : XposedHelpers.getStaticObjectField(type, name);
    }

    /**
     * The master presenter: an ef.m instance whose palette object (a yg.a field) is the em.p0 found
     * through rp.k0 -> com.oplus.camera.x6 -> f0 -> a -> a, and whose i() reports "professional".
     */
    private static Object masterPresenter() {
        for (int i = containers.size() - 1; i >= 0; i--) {
            Object container = containers.get(i).get();
            if (container == null) {
                containers.remove(i);
                continue;
            }
            Object paletteObject = null;
            try {
                paletteObject = field(field(call(byType(container, type("com.oplus.camera.x6")), "f0"), "a"), "a");
            } catch (Throwable ignored) {
            }
            if (paletteObject == null || !type("em.p0").equals(paletteObject.getClass().getName())) continue;
            for (int j = presenters.size() - 1; j >= 0; j--) {
                Object presenter = presenters.get(j).get();
                if (presenter == null) {
                    presenters.remove(j);
                    continue;
                }
                try {
                    if (byType(presenter, type("yg.a")) == paletteObject
                        && "professional".equals(call(presenter, "i"))) {
                        return presenter;
                    }
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
        return null;
    }

    /** The right-hand menu button of the master UI, used both as anchor and as position source. */
    private static View anchor(Activity activity) {
        for (int i = containers.size() - 1; i >= 0; i--) {
            Object container = containers.get(i).get();
            if (container == null) {
                containers.remove(i);
                continue;
            }
            try {
                if (byType(container, "android.app.Activity") != activity) continue;
                View direct = (View) field(container, "o");
                if (direct != null && direct.isShown() && direct.getAlpha() > 0.1f) return direct;
                View fallback = null;
                for (Field f : container.getClass().getDeclaredFields()) {
                    if (!f.getType().getName().equals("com.oplus.camera.ui.effectcontainer.MenuRightButton")) {
                        continue;
                    }
                    f.setAccessible(true);
                    View candidate = (View) f.get(container);
                    if (candidate == null || !candidate.isShown() || candidate.getAlpha() <= 0.1f) continue;
                    int[] location = new int[2];
                    candidate.getLocationOnScreen(location);
                    if ((float) location[1] > activity.getResources().getDisplayMetrics().heightPixels * 0.40625f) {
                        fallback = candidate;
                    }
                }
                if (fallback != null) return fallback;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Reads a current palette value (P0 tone / O0 saturation) back from the presenter. */
    private static int value(Object presenter, String name) {
        Object result = stat("com.oplusos.sau.common.utils.a", "h",
            key(name), call(presenter, "M2"), Integer.class, 0);
        return ((Number) result).intValue();
    }

    // --- the floating session ----------------------------------------------------------------

    private static final class Session {
        final Activity activity;
        final FrameLayout decor;
        final ImageView button;
        final Runnable tick;
        View shelf;
        View scrim;
        View controlBar;
        View previewMask;
        View thumb;
        float thumbBase;
        float thumbShift;
        Readout readout;
        final Map<View, Integer> hidden = new HashMap<>();
        Pending pending;
        boolean stopped;
        boolean photoPaletteOpen;
        boolean filterPanelOpen;
        String lastTrigger = "";
        String lastError = "";

        Session(Activity activity) {
            this.activity = activity;
            this.decor = (FrameLayout) activity.getWindow().getDecorView();
            this.button = new ImageView(activity);
            this.button.setContentDescription("大师调色板");
            int icon = activity.getResources().getIdentifier("filter_palette", "drawable", activity.getPackageName());
            if (icon != 0) this.button.setImageResource(icon);
            this.button.setPadding(dp(8), dp(8), dp(8), dp(8));
            this.button.setScaleType(ImageView.ScaleType.FIT_CENTER);
            GradientDrawable background = new GradientDrawable();
            background.setColor(0x87606060);
            background.setCornerRadius(dp(16));
            background.setStroke(dp(1), 0x44888888);
            this.button.setBackground(background);
            this.button.setVisibility(View.GONE);
            this.button.setElevation(dp(12));
            this.button.setOnClickListener(v -> {
                if (shelf != null) {
                    hide();
                } else {
                    open();
                }
            });
            this.tick = new Runnable() {
                @Override public void run() {
                    if (stopped || activity.isDestroyed()) {
                        close();
                        return;
                    }
                    try {
                        // Calibration: snapshot the layout the moment the stock photo-mode palette
                        // opens (that is the arrangement the master palette has to match), and any
                        // time the paldump property changes value so a state can be captured on
                        // demand over adb without rebuilding.
                        String trigger = prop(PROP_DUMP);
                        if (!trigger.equals(lastTrigger)) {
                            lastTrigger = trigger;
                            if (!trigger.isEmpty() && !"0".equals(trigger)) {
                                postDump("trigger-" + trigger, true);
                            }
                        }
                        boolean photoOpen = findByIds(PHOTO_PALETTE_IDS) != null;
                        if (photoOpen != photoPaletteOpen) {
                            photoPaletteOpen = photoOpen;
                            if (photoOpen) postDump("photo-palette");
                        }
                        View anchor = MasterPalette.anchor(activity);
                        Object presenter = MasterPalette.masterPresenter();
                        // Hide the little button while the palette is docked at the bottom.
                        boolean paletteUp = shelf != null;
                        if (anchor != null && presenter != null && decor.hasWindowFocus() && !paletteUp) {
                            int[] anchorLoc = new int[2];
                            int[] decorLoc = new int[2];
                            anchor.getLocationOnScreen(anchorLoc);
                            decor.getLocationOnScreen(decorLoc);
                            int size = Math.max(dp(28), Math.min(dp(34), Math.min(anchor.getWidth(), anchor.getHeight())));
                            if (button.getWidth() != size) {
                                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) button.getLayoutParams();
                                params.width = size;
                                params.height = size;
                                button.setLayoutParams(params);
                            }
                            button.setX(Math.max(dp(8), Math.min((float) (decor.getWidth() - size - dp(8)),
                                (anchorLoc[0] - decorLoc[0]) + (anchor.getWidth() - size) / 2f)));
                            button.setY(Math.max(dp(80), (anchorLoc[1] - decorLoc[1]) - size - dp(10)));
                            button.setVisibility(View.VISIBLE);
                            button.setEnabled(anchor.isEnabled());
                            button.bringToFront();
                        } else {
                            button.setVisibility(View.GONE);
                        }
                        // The camera lays the thumbnail out on its own schedule; hold it in the
                        // palette row for as long as the dial is up.
                        if (shelf != null && thumb != null) {
                            thumb.setTranslationY(thumbBase + thumbShift);
                        }
                        // Opening the filter page has to take the palette down: the click-catcher
                        // covers the preview, so the filter pager would never see the drag.
                        boolean filterOpen = findByIds(FILTER_PANEL_IDS) != null;
                        if (filterOpen != filterPanelOpen) {
                            filterPanelOpen = filterOpen;
                            if (filterOpen) postDump("filter-panel");
                        }
                        if (filterOpen && shelf != null) {
                            Log.i(TAG, "closed: the camera's filter panel came up");
                            hide();
                        }
                        // Drop the palette only when the master presenter disappears (left master
                        // mode). Window focus is not a signal here: the bar is a plain child view.
                        if (presenter == null && shelf != null) hide();
                    } catch (Throwable error) {
                        if (!error.toString().equals(lastError)) {
                            lastError = error.toString();
                            Log.e(TAG, "layout", error);
                        }
                    }
                    decor.postDelayed(this, 250);
                }
            };
        }

        void start() {
            decor.addView(button, new FrameLayout.LayoutParams(dp(34), dp(34)));
            decor.post(tick);
            Log.i(TAG, "camera UI attached");
        }

        void close() {
            stopped = true;
            decor.removeCallbacks(tick);
            hide();
            decor.removeView(button);
        }

        int dp(float value) {
            return Math.round(value * activity.getResources().getDisplayMetrics().density);
        }

        /**
         * The palette dial size photo mode uses (dimen/palette_color_size, 96dp on this build) so the
         * master dial comes out the same size instead of the oversized first version.
         */
        int dial() {
            int res = activity.getResources().getIdentifier("palette_color_size", "dimen",
                activity.getPackageName());
            int size = res == 0 ? 0 : activity.getResources().getDimensionPixelSize(res);
            return size > 0 ? size : dp(96);
        }

        /** First of {@code ids} that exists in the decor and is actually laid out. */
        View findByIds(String[] ids) {
            for (String id : ids) {
                int res = activity.getResources().getIdentifier(id, "id", activity.getPackageName());
                if (res == 0) continue;
                View view = decor.findViewById(res);
                if (view != null && view.isShown() && view.getHeight() > 0) return view;
            }
            return null;
        }

        /** "id=[x,y,w,h]" in decor coordinates, or "id=n"/"id=?" when the view/id is missing. */
        String geometry(String id) {
            int res = activity.getResources().getIdentifier(id, "id", activity.getPackageName());
            if (res == 0) return id + "=?";
            View found = decor.findViewById(res);
            if (found == null) return id + "=n";
            int[] loc = new int[2];
            int[] decorLoc = new int[2];
            found.getLocationOnScreen(loc);
            decor.getLocationOnScreen(decorLoc);
            return id + "=[" + (loc[0] - decorLoc[0]) + "," + (loc[1] - decorLoc[1]) + ","
                + found.getWidth() + "," + found.getHeight() + "]"
                + (found.isShown() ? "" : "hidden");
        }

        String entryName(View view) {
            if (view == null) return "none";
            try {
                return activity.getResources().getResourceEntryName(view.getId());
            } catch (Throwable error) {
                return "(id 0x" + Integer.toHexString(view.getId()) + ")";
            }
        }

        /** Snapshots the decor tree now and again once the camera's own animation has settled. */
        void postDump(String why) {
            postDump(why, diagnostics);
        }

        void postDump(String why, boolean enabled) {
            if (!enabled) return;
            decor.postDelayed(() -> dumpTree(why), 600);
            decor.postDelayed(() -> dumpTree(why + "+1s"), 1400);
        }

        private void dumpTree(String why) {
            if (stopped || activity.isDestroyed()) return;
            StringBuilder sb = new StringBuilder();
            int[] count = new int[1];
            collect(decor, 0, sb, count);
            Log.i(TAG, "TREE " + why + " decor=" + decor.getWidth() + "x" + decor.getHeight()
                + " views=" + count[0]);
            for (int i = 0; i < sb.length(); i += 800) {
                Log.i(TAG, "TREE|" + sb.substring(i, Math.min(sb.length(), i + 800)));
            }
        }

        /** Ids plus anything the camera itself moved or scaled - ids are what we compare across modes. */
        private void collect(View view, int depth, StringBuilder sb, int[] count) {
            if (view == null) return;
            boolean tagged = view.getId() != View.NO_ID;
            boolean moved = view.getTranslationX() != 0f || view.getTranslationY() != 0f
                || view.getScaleX() != 1f || view.getScaleY() != 1f;
            if ((tagged || moved) && view.isShown() && count[0] < 320) {
                count[0]++;
                int[] loc = new int[2];
                int[] decorLoc = new int[2];
                view.getLocationOnScreen(loc);
                decor.getLocationOnScreen(decorLoc);
                sb.append(tagged ? idOf(view) : "~").append(' ')
                    .append(view.getClass().getSimpleName()).append(' ')
                    .append(view.getVisibility() == View.VISIBLE ? "V"
                        : (view.getVisibility() == View.INVISIBLE ? "I" : "G"))
                    .append('[').append(loc[0] - decorLoc[0]).append(',').append(loc[1] - decorLoc[1])
                    .append(' ').append(view.getWidth()).append('x').append(view.getHeight()).append(']')
                    .append(" ty=").append(Math.round(view.getTranslationY()))
                    .append(" sy=").append(view.getScaleY())
                    .append(" a=").append(view.getAlpha())
                    .append(" | ");
            }
            if (view instanceof ViewGroup && depth < 14) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    collect(group.getChildAt(i), depth + 1, sb, count);
                }
            }
        }

        String idOf(View view) {
            if (view.getId() == View.NO_ID) return "~";
            try {
                return activity.getResources().getResourceEntryName(view.getId());
            } catch (Throwable error) {
                return "0x" + Integer.toHexString(view.getId());
            }
        }

        /** Removes the bar/scrim and puts the control bar back where the camera left it. */
        void hide() {
            if (pending != null) {
                pending.flush();
                pending = null;
            }
            if (shelf != null) {
                decor.removeView(shelf);
                shelf = null;
            }
            if (scrim != null) {
                decor.removeView(scrim);
                scrim = null;
            }
            if (controlBar != null) {
                controlBar.setTranslationY(0f);
                controlBar = null;
            }
            if (thumb != null) {
                thumb.setTranslationY(thumbBase);
                thumb = null;
            }
            if (previewMask != null) {
                decor.removeView(previewMask);
                previewMask = null;
            }
            if (readout != null) {
                decor.removeView(readout.view);
                readout = null;
            }
            restore();
        }

        /**
         * Inflates the app's own 影调/饱和度 readout and hangs it on the decor. The 柔光 group is
         * dropped because master mode's palette writes tone and saturation only.
         */
        Readout buildReadout() {
            int layout = activity.getResources().getIdentifier(READOUT_LAYOUT, "layout",
                activity.getPackageName());
            if (layout == 0) {
                layout = activity.getResources().getIdentifier(READOUT_LAYOUT_ALT, "layout",
                    activity.getPackageName());
            }
            if (layout == 0) return null;
            View view = activity.getLayoutInflater().inflate(layout, decor, false);
            View softLight = id(view, "palette_soft_light_group");
            if (softLight != null) softLight.setVisibility(View.GONE);
            // Nothing here is interactive, and it sits over the top bar: let taps fall through.
            disableTouch(view);
            decor.addView(view);
            return new Readout(view, text(view, "palette_tone_value"),
                text(view, "palette_saturation_value"));
        }

        void disableTouch(View view) {
            view.setClickable(false);
            view.setFocusable(false);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    disableTouch(group.getChildAt(i));
                }
            }
        }

        View id(View root, String name) {
            int res = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
            return res == 0 ? null : root.findViewById(res);
        }

        TextView text(View root, String name) {
            View found = id(root, name);
            return found instanceof TextView ? (TextView) found : null;
        }

        /** Puts the views we borrowed (visibility) back the way the camera left them. */
        void restore() {
            for (Map.Entry<View, Integer> entry : hidden.entrySet()) {
                entry.getKey().setVisibility(entry.getValue());
            }
            hidden.clear();
        }

        int screenTop(View view) {
            int[] loc = new int[2];
            int[] decorLoc = new int[2];
            view.getLocationOnScreen(loc);
            decor.getLocationOnScreen(decorLoc);
            return loc[1] - decorLoc[1];
        }

        int screenLeft(View view) {
            int[] loc = new int[2];
            int[] decorLoc = new int[2];
            view.getLocationOnScreen(loc);
            decor.getLocationOnScreen(decorLoc);
            return loc[0] - decorLoc[0];
        }

        /** A view by id, whether or not it is currently visible. */
        View find(String id) {
            int res = activity.getResources().getIdentifier(id, "id", activity.getPackageName());
            return res == 0 ? null : decor.findViewById(res);
        }

        /** Camera paused (gallery, another app): take the palette down but keep the session. */
        void detach() {
            hide();
            button.setVisibility(View.GONE);
        }

        /**
         * A click landed on the camera's own UI. Take the palette down before the camera acts on it,
         * so whatever page it opens next is laid out against the untouched control panel. Clicks
         * inside our own button or dial are ignored.
         */
        void onForeignClick(View view) {
            if (shelf == null || view == null) return;
            for (View node = view; node != null; ) {
                if (node == shelf || node == button) return;
                ViewParent parent = node.getParent();
                node = parent instanceof View ? (View) parent : null;
            }
            Log.i(TAG, "closed: camera UI tapped (" + idOf(view) + ")");
            hide();
        }

        void open() {
            if (shelf != null) {
                hide();
                return;
            }
            Object presenter = masterPresenter();
            if (presenter == null) return;
            View panel;
            Object recipe;
            String filter;
            try {
                Object holder = XposedHelpers.newInstance(cls("ef.m$b"), presenter);
                panel = (View) XposedHelpers.newInstance(
                    cls("com.oplus.ocs.camera.ipuapi.process.filter.palette.PalettePanelView"), activity);
                filter = (String) call(byType(presenter, type("yg.a")), "R5",
                    ((Number) call(presenter, "L2")).intValue());
                recipe = stat("com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteColors", "get", filter);
                if (recipe == null) {
                    recipe = stat("com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteColors", "get", "default");
                }
                call(panel, "setColorRecipe", recipe);
                int tone = value(presenter, "P0");
                int saturation = value(presenter, "O0");
                call(panel, "setCurrentToneAndSaturation", tone, saturation);

                // Copy the stock arrangement instead of inventing one. Measured with the paldump tree
                // dump on this device, photo mode - with its own palette open - does exactly this:
                // lifts the whole control panel by dimen/palette_control_view_translation_4_3 (95dp),
                // hides the mode strip, the zoom row and the manual-parameter rows, and puts the 96dp
                // dial at control-panel-top + 351px. Its preview frame never moves (330..1770 in both
                // states), so "the preview shrinks and lifts" was only ever the hidden rows plus the
                // lifted panel. The dial therefore needs no background of its own: the area under the
                // shutter is already black.
                controlBar = findByIds(CANDIDATE_CONTROL_IDS);
                if (controlBar != null) controlBar.setTranslationY(-dp(PALETTE_PANEL_LIFT_DP));
                restore();
                for (String id : PALETTE_HIDE_IDS) {
                    View view = find(id);
                    if (view == null || view.getVisibility() != View.VISIBLE) continue;
                    hidden.put(view, view.getVisibility());
                    view.setVisibility(View.GONE);
                }
                int panelTop = controlBar != null ? screenTop(controlBar)
                    : decor.getHeight() - dp(202);
                int dialPx = dial();
                int dialTop = panelTop + dp(PALETTE_DIAL_TOP_DP);

                // Round the preview off the same way photo mode does while its palette is up. The
                // image comes from gl_root_view, which cannot be clipped by an outline, so the card
                // is painted as a mask: black everywhere outside the inset rounded rectangle.
                View frame = find("frame_layout");
                if (frame != null && frame.getWidth() > 0 && frame.getHeight() > 0) {
                    FrameLayout.LayoutParams maskParams =
                        new FrameLayout.LayoutParams(frame.getWidth(), frame.getHeight());
                    maskParams.leftMargin = screenLeft(frame);
                    maskParams.topMargin = screenTop(frame);
                    previewMask = new PreviewCardMask(activity,
                        dp(PREVIEW_CARD_INSET_DP), dp(PREVIEW_CARD_RADIUS_DP));
                    decor.addView(previewMask, maskParams);
                }

                // The thumbnail rides with the control panel, which would park it next to the shutter
                // over the preview. Photo mode puts its 重置 button in the bottom-left corner of the
                // palette row (1932..2039), so the thumbnail goes there - centred on the dial row.
                thumb = find("thumbnail");
                if (thumb != null && thumb.getHeight() > 0) {
                    thumbBase = thumb.getTranslationY();
                    thumbShift = (dialTop + dialPx / 2f) - (screenTop(thumb) + thumb.getHeight() / 2f);
                    thumb.setTranslationY(thumbBase + thumbShift);
                }

                FrameLayout.LayoutParams dialParams = new FrameLayout.LayoutParams(
                    dialPx, dialPx, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                dialParams.topMargin = dialTop;
                decor.addView(panel, dialParams);
                shelf = panel;
                decor.bringChildToFront(panel);

                readout = buildReadout();
                if (readout != null) readout.show(tone, saturation);

                // Anything tapped over the preview closes the palette, as in photo mode. The scrim
                // stops at the lifted control panel so the shutter keeps working while it is open.
                int scrimBottom = Math.max(dp(80), Math.min(panelTop, decor.getHeight()));
                View scrimView = new View(activity);
                scrimView.setBackgroundColor(0x01000000);
                scrimView.setClickable(true);
                scrimView.setOnClickListener(v -> hide());
                decor.addView(scrimView, new FrameLayout.LayoutParams(-1, Math.max(dp(1), scrimBottom), Gravity.TOP));
                scrim = scrimView;
                postDump("master-palette");
                // Two later snapshots: if the user goes on to open the filter page, the state that
                // broke the drag is captured even when the auto-close above does not fire.
                decor.postDelayed(() -> dumpTree("master-palette+3s"), 3000);
                decor.postDelayed(() -> dumpTree("master-palette+6s"), 6000);

                Pending pending = new Pending(presenter, holder, panel, readout, tone, saturation);
                this.pending = pending;
                panel.setOnTouchListener((v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        v.post(pending.update);
                    }
                    return false;
                });
                call(panel, "setOnToneSaturationChangeListener", Proxy.newProxyInstance(loader,
                    new Class<?>[] { cls("com.oplus.ocs.camera.ipuapi.process.filter.palette."
                        + "PalettePanelView$OnToneSaturationChangeListener") },
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "onToneSaturationChanged":
                                pending.change(((Number) args[0]).intValue(), ((Number) args[1]).intValue());
                                return null;
                            case "toString":
                                return "AuraMasterPaletteListener";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                return null;
                        }
                    }));

                StringBuilder ids = new StringBuilder();
                for (String id : GEOMETRY_IDS) ids.append(geometry(id)).append(' ');
                Log.i(TAG, "opened filter=" + filter + " tone=" + tone + " saturation=" + saturation
                    + " dial=" + dialPx + " dialTop=" + dialTop + " panelTop=" + panelTop
                    + " scrim=" + scrimBottom + " hidden=" + hidden.size()
                    + " card=" + (previewMask != null) + " thumbShift=" + thumbShift
                    + " readout=" + (readout != null)
                    + (readout == null ? "" : "(tone=" + (readout.tone != null)
                        + ",sat=" + (readout.saturation != null) + ")")
                    + " controlBar=" + entryName(controlBar)
                    + " geom[" + ids.toString().trim() + "]");
            } catch (Throwable error) {
                hide();
                Log.e(TAG, "open failed", error);
            }
        }
    }

    /**
     * Paints everything outside an inset rounded rectangle black, i.e. a rounded "card" the live
     * preview shows through. Used because the preview lives in gl_root_view, a GL surface that no
     * outline/clipToOutline can round - photo mode gets the same look from its own renderer.
     */
    private static final class PreviewCardMask extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float inset;
        private final float radius;

        PreviewCardMask(Context context, float inset, float radius) {
            super(context);
            this.inset = inset;
            this.radius = radius;
            paint.setColor(0xFF000000);
            setClickable(false);
            setFocusable(false);
        }

        @Override protected void onDraw(Canvas canvas) {
            Path path = new Path();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0f, 0f, getWidth(), getHeight(), Path.Direction.CW);
            path.addRoundRect(inset, 0f, getWidth() - inset, getHeight(), radius, radius,
                Path.Direction.CW);
            canvas.drawPath(path, paint);
        }
    }

    /**
     * The app's 影调/饱和度 readout views. Written with setText, which the rolling-number view
     * handles itself, so no internal API is needed.
     */
    private static final class Readout {
        final View view;
        private final TextView tone;
        private final TextView saturation;

        Readout(View view, TextView tone, TextView saturation) {
            this.view = view;
            this.tone = tone;
            this.saturation = saturation;
        }

        void show(int toneValue, int saturationValue) {
            set(tone, toneValue);
            set(saturation, saturationValue);
        }

        private static void set(TextView view, int value) {
            if (view == null) return;
            String text = String.valueOf(value);
            if (!text.contentEquals(view.getText())) view.setText(text);
        }
    }

    /** Debounced palette value push: 32 ms coalescing, then presenter.a(value, key). */
    private static final class Pending {
        private final Object presenter;
        private final Object callback;
        private final View panel;
        private final Readout readout;
        private final Object toneKey = key("P0");
        private final Object saturationKey = key("O0");
        private final Runnable update = this::flush;
        private int tone;
        private int saturation;
        private int appliedTone;
        private int appliedSaturation;
        private int lastLoggedTone = Integer.MIN_VALUE;
        private int lastLoggedSaturation = Integer.MIN_VALUE;
        private boolean queued;

        Pending(Object presenter, Object callback, View panel, Readout readout, int tone, int saturation) {
            this.presenter = presenter;
            this.callback = callback;
            this.panel = panel;
            this.readout = readout;
            this.tone = tone;
            this.appliedTone = tone;
            this.saturation = saturation;
            this.appliedSaturation = saturation;
        }

        void change(int tone, int saturation) {
            this.tone = tone;
            this.saturation = saturation;
            if (!queued) {
                queued = true;
                panel.postDelayed(update, 32);
            }
        }

        void flush() {
            panel.removeCallbacks(update);
            queued = false;
            if (masterPresenter() != presenter) return;
            if (readout != null) readout.show(tone, saturation);
            if (tone != appliedTone) {
                call(callback, "a", tone, toneKey);
                appliedTone = tone;
            }
            if (saturation != appliedSaturation) {
                call(callback, "a", saturation, saturationKey);
                appliedSaturation = saturation;
            }
            if (tone != lastLoggedTone || saturation != lastLoggedSaturation) {
                lastLoggedTone = tone;
                lastLoggedSaturation = saturation;
                Log.i(TAG, "apply tone=" + tone + " saturation=" + saturation);
            }
        }
    }
}
