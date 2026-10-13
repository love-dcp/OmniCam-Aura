package local.jiege.hook.x10;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * X10 port (PKJ110): supplies the palette feature switch the platform cannot answer for itself.
 *
 * <p>{@code Lut3dDataManager.paletteMode()} asks
 * {@code CameraConfig.d("com.oplus.feature.color.palette.support", false, false)}, which ends up in
 * {@code com.oplus.ocs.camera.configure.CameraConfigure.getProjectSupportFeatureValue(String)}:
 *
 * <pre>
 *   if (!ProtobufConfigureHelper.isFeatureConfigureFileExist()) return null;      // line 319
 *   if (ProtobufConfigureHelper.getConfigParseVersion() != 1)
 *       CameraConfigure.PROJECT_FEATURES_READY_CONDITION.block();                 // line 323
 *   return sVendorTagMap.get(key);                                                // line 325
 * </pre>
 *
 * <p>The container this reads is {@code /odm/etc/camera/config/oplus_camera_feature_config}, the
 * OCS "config parse version 2" file. X8U does not ship one, so the first check returns null for
 * every {@code com.oplus.feature.*} key and {@code paletteMode()} is permanently false -- even
 * though the ipuapi palette stack and this module's palette hooks are all in place.
 *
 * <p>Shipping a synthesised container was tried and reverted: it makes
 * {@code !isFeatureConfigureFileExist()} pass and {@code getConfigParseVersion()} return 2, and
 * {@code PROJECT_FEATURES_READY_CONDITION} -- a plain {@link android.os.ConditionVariable}, closed
 * at class-init and opened only by {@code decodeConfigFileVersion2()} via
 * {@code CameraConfigure.initialize()} -- then blocks the main thread. The first feature lookup of
 * the process is {@code com.oplus.camera.preset.MasterPresetDeferredImportProvider.onCreate()},
 * which runs during {@code installContentProviders} and therefore before {@code FeatureApi
 * .initialize()} is ever reached; nobody opens the condition, the window is never drawn, and the
 * app dies with "ANR Input dispatching timed out (Application does not have a focused window)".
 *
 * <p>So the one switch is answered here instead, in front of {@code CameraConfigure}, which also
 * means that condition variable is never touched. Only the palette key is answered; every other
 * lookup falls through to the stock path, which on this platform returns null exactly as before.
 * The palette colour cards are supplied by the module payload, not here.
 */
public final class PaletteFeatureSwitch {
    private static final String TAG = "X10Filter";
    private static final String PALETTE_SUPPORT = "com.oplus.feature.color.palette.support";
    private static final String CONFIGURE_HELPER =
            "com.oplus.ocs.camera.consumer.apsAdapter.adapter.ProtobufConfigureHelper";
    private static final String FEATURE_API = "com.oplus.ocs.camera.config.FeatureApi";

    private PaletteFeatureSwitch() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "palette feature switch", () -> install(classLoader));
    }

    private static void install(ClassLoader classLoader) throws Throwable {
        if (hasOwnContainer(classLoader)) {
            Log.i(TAG, "palette switch: platform has its own config container, left alone");
            return;
        }
        XposedHelpers.findAndHookMethod(FEATURE_API, classLoader, "getProjectSupportFeatureValue",
                String.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam x) {
                        if (PALETTE_SUPPORT.equals(x.args[0])) x.setResult("true");
                    }
                });
        Log.i(TAG, "palette switch installed: " + PALETTE_SUPPORT + "=true");
    }

    /**
     * True when the device ships the parse-version-2 container itself (X10 and friends). In that
     * case the platform already has the switch and, more importantly, already has a working
     * {@code CameraConfigure.initialize()} ordering, so the stock path is left untouched.
     */
    private static boolean hasOwnContainer(ClassLoader classLoader) {
        try {
            Class<?> helper = XposedHelpers.findClass(CONFIGURE_HELPER, classLoader);
            return Boolean.TRUE.equals(XposedHelpers.callStaticMethod(helper, "isFeatureConfigureFileExist"));
        } catch (Throwable t) {
            // Not being able to ask is not a reason to leave the palette dark: the file is absent
            // on every device that lacks the helper's class in the first place.
            Log.e(TAG, "palette switch: container probe failed, assuming none", t);
            return false;
        }
    }
}
