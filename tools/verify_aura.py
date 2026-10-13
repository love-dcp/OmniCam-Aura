from pathlib import Path
from zipfile import ZipFile
import subprocess
import json
import os

PROJECT = Path(__file__).resolve().parent.parent
MODULE = PROJECT / 'module'

# The version lives in exactly one place (module.prop); the APK name, the archive name and the
# gradle build file are all derived from it so a release can never be half-bumped.
def _prop(key):
    for line in (MODULE / 'module.prop').read_text(encoding='utf-8').splitlines():
        if line.startswith(key + '='):
            return line.partition('=')[2].strip()
    raise RuntimeError(f'module.prop has no {key}=')

VERSION = _prop('version')
GRADLE = (PROJECT / 'app/build.gradle.kts').read_text(encoding='utf-8')
if f'versionName = "{VERSION}"' not in GRADLE:
    raise RuntimeError(f'build.gradle.kts versionName does not match module.prop ({VERSION})')
if int(_prop('versionCode')) <= 0:
    raise RuntimeError('module.prop versionCode must be positive')

APK = MODULE / f'omnicam-aura-{VERSION}.apk'
ARCHIVE = PROJECT / f'OmniCam-Aura-{VERSION}-KSU.zip'
sdk_path = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk_path:
    properties = PROJECT / 'local.properties'
    if properties.exists():
        for line in properties.read_text(encoding='utf-8').splitlines():
            if line.startswith('sdk.dir='):
                sdk_path = line.partition('=')[2].replace('\\:', ':').replace('\\\\', '\\')
                break
if not sdk_path:
    raise RuntimeError('Set ANDROID_HOME or ANDROID_SDK_ROOT, or create local.properties with sdk.dir')
build_tools = sorted((Path(sdk_path) / 'build-tools').glob('*'), reverse=True)
BUILD_TOOLS = next((p for p in build_tools if (p / 'aapt2.exe').exists() and (p / 'apksigner.bat').exists()), None)
if BUILD_TOOLS is None:
    raise RuntimeError('Android SDK build-tools with aapt2 and apksigner are required')

def require(condition, message):
    if not condition:
        raise RuntimeError(message)

with ZipFile(ARCHIVE) as bundle, ZipFile(APK) as apk:
    require(bundle.testzip() is None, 'Module archive CRC failed')
    names = bundle.namelist()
    require([n for n in names if n.endswith('.apk')] == [APK.name], 'Expected exactly one Aura APK')
    require(bundle.read(APK.name) == APK.read_bytes(), 'Packaged APK differs from build')
    require('assets/xposed_init' in apk.namelist(), 'LSPosed Java entry missing')
    require(apk.read('assets/xposed_init').strip() == b'local.omnicam.aura.ModuleEntry', 'Wrong LSPosed entry')
    require(apk.read('assets/native_init').strip() == b'libaura_native.so', 'Wrong native entry')
    require('lib/arm64-v8a/libaura_native.so' in apk.namelist(), 'GR/POP native library missing')
    require(not any(b'local.jiege.camera' in apk.read(n) for n in apk.namelist() if n.endswith('.dex')), 'Old package reference in Aura hooks')
    for removed in ('master_hncs/', 'unlock25mp/', 'audio_policy_volumes.xml'):
        require(not any(n.startswith('payload/' + removed) for n in names), f'Unrelated payload: {removed}')
    require(not any('/meishe_lut/JG_' in n or '/meishe_lut/LUT65_' in n or '/meishe_lut/LeicaM9' in n for n in names), 'Muse LUTs leaked into Aura')
    # Layout invariant: KernelSU's metamount.sh overlays every module's system/ tree onto the
    # device unconditionally, so nothing device-specific may live there. The two X9/X10 backends
    # stay in system/ because the overlay is the only way to create those paths on X9U, and X8U's
    # own libraries reference neither name (verified: 0 occurrences). The three libraries that
    # replace stock X9U files ship as payload and are bound in post-mount.sh for PMA110 only.
    overlaid = ('libBasicTonePhotoX9.so', 'libBasicTonePhotoX10.so')
    require(all('system/odm/lib64/' + n in names for n in overlaid), 'X9/X10 overlay backends missing')
    bound = ('libAlgoInterface.so', 'libBasicTonePhoto.so', 'libmsnativefilter.so')
    require(all('payload/lib64/' + n in names for n in bound), 'PMA110 payload backends missing')
    require(not any('system/odm/lib64/' + n in names for n in bound),
            'X9U backend placed in system/: it would be overlaid onto PKJ110 as well')
    for name in ('qing_tou.bin', 'hu_po.bin', 'polaroid_sdr.mslut',
                 'positive_hdr_normal_a_1.bin', 'positive_hdr_normal_d_1.bin',
                 'positive_sdr_gen_a_1.bin', 'positive_sdr_gen_d_1.bin',
                 'positive_normal.bin', 'positive_master.bin',
                 'positive_hdr_master_a_1.bin', 'positive_hdr_master_d_1.bin'):
        require('payload/meishe_lut/' + name in names, f'Missing LUT: {name}')

manifest = subprocess.check_output([str(BUILD_TOOLS / 'aapt2.exe'), 'dump', 'xmltree', str(APK), '--file', 'AndroidManifest.xml'], text=True, encoding='utf-8')
badging = subprocess.check_output([str(BUILD_TOOLS / 'aapt2.exe'), 'dump', 'badging', str(APK)], text=True, encoding='utf-8')
require("name='local.omnicam.aura'" in badging and "application-label:'OmniCam Aura'" in badging, 'APK identity mismatch')
resources = subprocess.check_output([str(BUILD_TOOLS / 'aapt2.exe'), 'dump', 'resources', str(APK)], text=True, encoding='utf-8')
for name in ('snap_switch_on', 'snap_switch_off', 'snap_switch_1m', 'snap_switch_2_5m', 'snap_switch_5m'):
    require('drawable/' + name in resources, f'GR snap focus icon was stripped: {name}')
for component in ('E: activity ', 'E: activity-alias ', 'E: provider ', 'E: service ', 'E: receiver '):
    require(component not in manifest, f'Unexpected UI/component: {component}')
scope = (PROJECT / 'app/src/main/res/values/arrays.xml').read_text(encoding='utf-8')
require('com.oplus.camera' in scope and 'com.coloros.gallery3d' in scope and '<item>android</item>' not in scope, 'Scope mismatch')
algo = json.loads((MODULE / 'payload/oplus_camera_algo_switch_config').read_bytes())
for mode in ('common', 'portrait', 'aiHighPixel'):
    group = next(g for g in algo['aps_capture_configs'] if g['mode'] == mode)
    for camera in (0, 1):
        entity = next(e for e in group['entity'] if e['id'] == camera)
        require(entity['aps_algo_color_palette'] == 1, f'Palette capture node missing: {mode}/{camera}')
for script in ('post-fs-data.sh', 'service.sh'):
    text = (MODULE / script).read_text(encoding='utf-8')
    # Muse-only payloads that must never come back. 'InsensorZoom' used to be on this list; it is
    # not any more -- upstream 1.1.1 legitimately patches the [InsensorZoom] section of
    # CameraHWConfiguration.config (Pro video ISZ). It is now asserted POSITIVELY below, with its
    # PMA110-only gate, so the removal is still covered.
    for removed in ('master_hncs', 'unlock25mp', 'audio_policy_volumes'):
        require(removed not in text, f'Unrelated mount/staging: {removed}')

# Pro video ISZ (upstream 1.1.1): the patch must be present, must target [InsensorZoom], and must
# stay gated to PMA110 -- the awk requires exactly 16 matching rows of PMA110's file to bind, and
# the x8u profile has never been validated with the gate removed.
hwcfg = (MODULE / 'post-fs-data.sh').read_text(encoding='utf-8')
require('[InsensorZoom]' in hwcfg, 'Pro video ISZ patch lost its [InsensorZoom] section match')
require(hwcfg.count('iZoomSettins') >= 2, 'Pro video ISZ patch lost its row matcher')
require('disable_pro_video_isz' in hwcfg, 'Pro video ISZ lost its opt-out flag')
require('elif [ "$PROFILE" != PMA110 ]; then' in hwcfg,
        'Pro video ISZ is no longer gated away from the PKJ110 profile')
for name in ('PROFILE=$(detect_profile)', 'CONFIG_SRC=$MODDIR/payload_pkj110'):
    require(name in hwcfg, f'PKJ110 profile selection missing from post-fs-data.sh: {name}')
mount = (MODULE / 'post-mount.sh').read_text(encoding='utf-8')
require('PROFILE=$(detect_profile)' in mount, 'PKJ110 profile selection missing from post-mount.sh')
require('bind_library "$MODDIR/payload/lib64/' in mount,
        'PMA110 backends are no longer bound out of payload/lib64')
for lib in ('libAlgoInterface.so', 'libBasicTonePhoto.so', 'libmsnativefilter.so'):
    require(f'"$MODDIR/payload/lib64/{lib}"' in mount,
            f'PMA110 backend bind lost: {lib}')
subprocess.run(['cmd', '/c', str(BUILD_TOOLS / 'apksigner.bat'), 'verify', str(APK)], check=True)
print('PASS: signed headless Aura APK; camera/gallery scope; GR/POP backends and all six palette capture nodes present; two device profiles wired; pro video ISZ present and PMA110-gated')
