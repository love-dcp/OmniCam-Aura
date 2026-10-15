package local.jiege.hook.diag;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.concurrent.atomic.AtomicInteger;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Diagnostic probe for the palette-in-master-mode question: the palette UI and capture work in the
 * photo modes but not in master (professional) mode, while the X9U build offers it there. Off
 * unless the system property below is set; when off it changes nothing.
 *
 * What it watches, all in the camera app process:
 *   - Lut3dData.paletteMode(): the global palette switch lookup (who asks, what it gets);
 *   - PaletteSeekbarColors / PaletteColors (OCS SDK): which filter types ask for palette colours,
 *     and whether master mode ever asks;
 *   - AlgoSwitchConfig.getSupportCaptureAlgo(mode, cameraId, algo): the per-mode capture gate for
 *     aps_algo_color_palette (the switch config has palette only under common/portrait/aiHighPixel);
 *   - DataManager writes/reads of any entry whose key text mentions palette (key_support_palette).
 *
 * Enable with: setprop persist.omnicam.aura.palprobe 1   (then force-stop the camera).
 */
public final class PaletteProbe {
    private static final String TAG = "PalProbe";
    private static final String PROP = "persist.omnicam.aura.palprobe";
    private static final String FORCE_PROP = "persist.omnicam.aura.palforce";
    private static final String UI_FORCE_PROP = "persist.omnicam.aura.paluiforce";
    private static final AtomicInteger calls = new AtomicInteger();
    private static final int BURST = 15;
    private static final int SAMPLE = 50;

    private PaletteProbe() {}

    public static void install(ClassLoader classLoader) {
        if (!"1".equals(readProp())) {
            Log.i(TAG, "off (" + PROP + " not 1)");
            return;
        }
        Log.i(TAG, "on: installing palette probes (force=" + readProp(FORCE_PROP) + ")");
        Log.guard(TAG, "paletteMode", () -> hookPaletteMode(classLoader));
        Log.guard(TAG, "screen ui gate", () -> hookScreenUiGate(classLoader));
        Log.guard(TAG, "cameraConfig.d", () -> hookCameraConfigD(classLoader));
        Log.guard(TAG, "sdk palette colors", () -> hookSdkPalette(classLoader));
        Log.guard(TAG, "algo switch", () -> hookAlgoSwitch(classLoader));
        Log.guard(TAG, "data manager", () -> hookDataManager(classLoader));
        Log.guard(TAG, "filter effect menu log", () -> hookFilterEffectMenuLog(classLoader));
        Log.guard(TAG, "palette key writes", () -> hookPaletteKeyWrites(classLoader));
    }

    /**
     * Watches the camera data bus for the palette DataKeys n9.f.P0 (tone) and n9.f.O0 (saturation).
     * In photo mode the palette works, so whoever carries those keys through DataManager in photo
     * mode is the palette data path we need to replicate for master mode. Logs the key, the value
     * and the full caller stack.
     */
    private static void hookPaletteKeyWrites(ClassLoader loader) {
        Class<?> keys = find(loader, "n9.f");
        Class<?> dataManager = find(loader, "com.oplus.camera.data.DataManager");
        if (keys == null || dataManager == null) {
            Log.i(TAG, "absent: n9.f=" + keys + " DataManager=" + dataManager);
            return;
        }
        final Object toneKey;
        final Object saturationKey;
        try {
            toneKey = XposedHelpers.getStaticObjectField(keys, "P0");
            saturationKey = XposedHelpers.getStaticObjectField(keys, "O0");
        } catch (Throwable error) {
            Log.e(TAG, "palette keys unreadable", error);
            return;
        }
        if (toneKey == null || saturationKey == null) {
            Log.i(TAG, "palette keys null");
            return;
        }
        int hooked = 0;
        // Only the two named DataKey-carrying methods are hooked. Hooking every method of the
        // camera data bus (a core hot path) black-screened the preview - never do that again.
        for (String name : new String[] {"b", "g"}) {
            try {
                XposedBridge.hookAllMethods(dataManager, name, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0) return;
                        for (int i = 0; i < param.args.length; i++) {
                            Object arg = param.args[i];
                            String which = arg == toneKey ? "P0(tone)"
                                : arg == saturationKey ? "O0(saturation)" : null;
                            if (which == null) continue;
                            Log.i(TAG, "DataManager." + name + " key=" + which
                                + (param.args.length > 1 ? " value=" + param.args[1] : ""));
                            Log.i(TAG, "  caller: " + caller());
                            break;
                        }
                    }
                });
                hooked++;
            } catch (Throwable ignored) {
            }
        }
        Log.i(TAG, "palette key writes hooked on DataManager." + hooked + " named methods (b,g)");
    }

    /**
     * Hook android.util.Log and capture the call stack whenever the filter-effect-menu class logs
     * a palette show/cancel line. The method that logs "cancelPaletteMenuRootShowAnim" or
     * "showPaletteFilterPanel, menu not ready" is the seekbar visibility decision point - the stack
     * names the gate method and its caller (the actual mode check we need to force).
     */
    private static void hookFilterEffectMenuLog(ClassLoader loader) {
        Class<?> log = find(loader, "android.util.Log");
        if (log == null) {
            Log.i(TAG, "absent: android.util.Log");
            return;
        }
        for (String m : new String[] {"w", "d", "i", "e"}) {
            try {
                XposedBridge.hookAllMethods(log, m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length < 2) return;
                        Object tagObj = param.args[0];
                        Object msgObj = param.args[1];
                        if (tagObj == null || msgObj == null) return;
                        String tag = String.valueOf(tagObj);
                        String msg = String.valueOf(msgObj);
                        if (!tag.contains("FilterEffectMenu") && !tag.contains("Palette")) return;
                        if (!msg.toLowerCase().contains("palette")) return;
                        Log.i(TAG, "Log." + m + "(" + tag + "): " + msg + "   [stack]");
                        Log.i(TAG, "  " + caller());
                    }
                });
            } catch (Throwable ignored) {
            }
        }
        Log.i(TAG, "filter effect menu log hooked");
    }

    private static String readProp() {
        return readProp(PROP);
    }

    private static String readProp(String name) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Object value = type.getMethod("get", String.class, String.class).invoke(null, name, "0");
            return String.valueOf(value);
        } catch (Throwable error) {
            return "0";
        }
    }

    /**
     * com.oplus.camera.common.screen.a.f2(): the per-mode palette UI gate. The base returns false;
     * photo-mode screen subclasses override it to true, master ("professional") does not, so the
     * filter menu hides the palette seekbar (bg.v.l5 calls b0.W4() when f2() is false). With the UI
     * force flag set the base method is answered true - master inherits the base, so it flips;
     * photo already overrides, so it is unaffected. This does NOT touch paletteMode(), so the native
     * palette LUT branch stays off and the preview does not fog.
     */
    private static void hookScreenUiGate(ClassLoader loader) {
        Class<?> type = find(loader, "com.oplus.camera.common.screen.a");
        if (type == null) {
            Log.i(TAG, "absent: common.screen.a");
            return;
        }
        final boolean force = "1".equals(readProp(UI_FORCE_PROP));
        XposedBridge.hookAllMethods(type, "f2", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!force) return;
                param.setResult(Boolean.TRUE);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!force) return;
                int n = calls.incrementAndGet();
                if (n <= 5 || n % 200 == 0) {
                    Log.i(TAG, "screen.a.f2() forced = " + param.getResult()
                        + (masterContext() ? "  [MASTER]" : "") + "   [call " + n + "]");
                }
            }
        });
        Log.i(TAG, "screen.a.f2 hooked (force=" + force + ")");
    }

    /** Lut3dDataManager.paletteMode(String modeName): the per-mode native palette switch. */
    private static void hookPaletteMode(ClassLoader loader) throws Throwable {
        final Symbols symbols = Symbols.get();
        XposedBridge.hookMethod(symbols.method("Lut3dData.paletteMode"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!"1".equals(readProp(FORCE_PROP))) return;
                if (param.args.length == 0 || param.args[0] == null) return;
                String mode = String.valueOf(param.args[0]);
                // Force only master/professional mode - leave photo and every other mode alone.
                if (!"professional".equals(mode)) return;
                param.setResult(Boolean.TRUE);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                int n = calls.incrementAndGet();
                String mode = param.args.length > 0 ? String.valueOf(param.args[0]) : "?";
                Object result = param.getResult();
                boolean master = masterContext();
                if (master || !Boolean.TRUE.equals(result)) {
                    Log.i(TAG, "paletteMode(\"" + mode + "\") = " + result
                        + (master ? "  [MASTER]" : "") + "   [call " + n + "]");
                    Log.i(TAG, "  caller: " + caller());
                    return;
                }
                if (n > BURST && n % SAMPLE != 0) return;
                Log.i(TAG, "paletteMode(\"" + mode + "\") = true   [call " + n + "]");
            }
        });
        Log.i(TAG, "paletteMode hooked");
    }

    /**
     * CameraConfig.d(key, ...): the feature-switch lookup paletteMode() passes through. The
     * installed switch answers only com.oplus.feature.color.palette.support; logging every
     * palette-ish key asked (with its raw result and caller) reveals the master-mode key, if the
     * master path consults a different one.
     */
    private static void hookCameraConfigD(ClassLoader loader) {
        Class<?> type = find(loader, "com.oplus.camera.configure.CameraConfig");
        if (type == null) {
            Log.i(TAG, "absent: CameraConfig");
            return;
        }
        XposedBridge.hookAllMethods(type, "d", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                String key = String.valueOf(param.args[0]);
                if (!key.toLowerCase().contains("palette")) return;
                Log.i(TAG, "CameraConfig.d(" + key + ") = " + param.getResult()
                    + (masterContext() ? "  [MASTER]" : ""));
                Log.i(TAG, "  d-caller: " + caller());
            }
        });
        Log.i(TAG, "cameraConfig.d hooked");
    }

    /**
     * The OCS SDK palette colour providers. X10FilterPort already hooks these to supply values;
     * this probe wraps the same entry points to see who asks (and whether master mode asks at all).
     */
    private static void hookSdkPalette(ClassLoader loader) {
        String[] classes = {
            "com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteSeekbarColors",
            "com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteColors",
        };
        for (String name : classes) {
            Class<?> type = find(loader, name);
            if (type == null) {
                Log.i(TAG, "absent: " + name);
                continue;
            }
            for (String method : new String[] {"get", "hasSeekbarColors", "hasPalette"}) {
                try {
                    XposedBridge.hookAllMethods(type, method, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            int n = calls.incrementAndGet();
                            if (n > BURST && n % SAMPLE != 0) return;
                            String type0 = param.args.length > 0 ? String.valueOf(param.args[0]) : "?";
                            Log.i(TAG, shortName(name) + "." + method + "(" + type0 + ") = "
                                + param.getResult() + (masterContext() ? "  [MASTER]" : "") + "   [call " + n + "]");
                        }
                    });
                } catch (Throwable ignored) {
                    // that overload set is not on this build
                }
            }
        }
        Log.i(TAG, "sdk palette hooked");
    }

    /**
     * AlgoSwitchConfig.getSupportCaptureAlgo(mode, cameraId, algo): the per-mode capture gate.
     * The switch config ships aps_algo_color_palette only under common/portrait/aiHighPixel; the
     * master block has none. Watching this tells us whether master even reaches the gate.
     */
    private static void hookAlgoSwitch(ClassLoader loader) {
        Class<?> type = find(loader, "com.oplus.ocs.camera.consumer.apsAdapter.config.AlgoSwitchConfig");
        if (type == null) {
            Log.i(TAG, "absent: AlgoSwitchConfig");
            return;
        }
        try {
            XposedBridge.hookAllMethods(type, "getSupportCaptureAlgo", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    String algo = param.args.length > 2 ? String.valueOf(param.args[2]) : "?";
                    if (!algo.contains("palette")) return;
                    int n = calls.incrementAndGet();
                    Log.i(TAG, "getSupportCaptureAlgo(" + param.args[0] + ", " + param.args[1] + ", "
                        + algo + ") = " + param.getResult() + "   [call " + n + "]");
                }
            });
            Log.i(TAG, "algo switch hooked");
        } catch (Throwable error) {
            Log.e(TAG, "algo switch hook failed", error);
        }
    }

    /** DataManager traffic for any entry whose key text mentions palette (key_support_palette). */
    private static void hookDataManager(ClassLoader loader) throws Throwable {
        final Symbols symbols = Symbols.get();
        for (String name : new String[] {"DataManager.put", "DataManager.get"}) {
            try {
                XposedBridge.hookMethod(symbols.method(name), new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length == 0 || param.args[0] == null) return;
                        String text = String.valueOf(param.args[0]);
                        if (!text.toLowerCase().contains("palette")) return;
                        int n = calls.incrementAndGet();
                        if (n > BURST && n % SAMPLE != 0) return;
                        Log.i(TAG, shortName(name) + "(" + text + ")"
                            + (param.args.length > 1 ? " = " + param.args[1] : "")
                            + (masterContext() ? "  [MASTER]" : "") + "   [call " + n + "]");
                    }
                });
            } catch (Throwable error) {
                Log.e(TAG, name + " hook failed", error);
            }
        }
        Log.i(TAG, "data manager hooked");
    }

    /** True when the calling thread is inside master/professional mode code. */
    private static boolean masterContext() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String name = frame.getClassName() + "." + frame.getMethodName();
            if (name.toLowerCase().contains("master") || name.toLowerCase().contains("professional")) return true;
        }
        return false;
    }

    private static String caller() {
        StringBuilder out = new StringBuilder();
        int kept = 0;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String name = frame.getClassName();
            if (name.startsWith("de.robv") || name.startsWith("local.jiege") || name.startsWith("java.lang")
                || name.contains("XC_MethodHook") || name.contains("XposedBridge")) continue;
            out.append(name).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber()).append(" <- ");
            if (++kept == 8) break;
        }
        return out.toString();
    }

    private static String shortName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static Class<?> find(ClassLoader loader, String name) {
        try {
            return XposedHelpers.findClassIfExists(name, loader);
        } catch (Throwable error) {
            return null;
        }
    }
}
