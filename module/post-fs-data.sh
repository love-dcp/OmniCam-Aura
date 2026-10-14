#!/system/bin/sh
# Bind the tested POP camera directories and GR resources.
#
# One package serves two devices; the profile is detected here (see post-mount.sh for the full
# reasoning). Both profiles are marked [PMA110] / [PKJ110] below.
#
#   PMA110 (Find X9 Ultra) - payload/          , the full OmniCam config set, ISP + gamma bound.
#   PKJ110 (Find X8 Ultra) - payload_pkj110/   , the three regenerated config files; the other
#                           encrypted oplus_* configs and the per-sensor ISP/gamma files are
#                           deliberately left alone.
MODDIR=${0%/*}
LOG=$MODDIR/bind.log
# Scratch space for the merged trees that get bind-mounted over /odm/etc/camera/*. It must sit
# OUTSIDE $MODDIR: installing or updating the module through KernelSU deletes and re-extracts
# /data/adb/modules/<id> wholesale, which would take a bind source with it while the mount is
# still live. See bind_merged_dir().
MERGED_ROOT=/data/adb/omnicam_aura/merged
set -u

log() {
    echo "$1" >> "$LOG"
}

getprop_safe() {
    getprop "$1" 2>/dev/null
}

detect_profile() {
    # Keep this identical to post-mount.sh.  The model name is NOT in
    # ro.product.device on this platform: PKJ110 reports OP5DD3L1 there, and
    # ro.product.odm.vendor.device / ro.vendor.oplus.device are empty, so probing
    # *device* first silently falls through to the ISP fallback.  Verified on
    # PKJ110: ro.product.model=PKJ110, ro.product.device=OP5DD3L1.
    for P in ro.product.vendor.model ro.product.vendor.name \
             ro.product.model ro.product.name \
             ro.product.odm.model ro.product.odm.name \
             ro.product.bootimage.model \
             ro.build.product \
             ro.build.display.id \
             ro.oplus.version.my_manifest \
             ro.product.vendor.device ro.product.device ro.product.odm.device \
             ro.product.odm.vendor.device ro.vendor.oplus.device; do
        V=$(getprop_safe "$P")
        case "$V" in
            *PKJ110*) log "detect: $P=$V"; echo PKJ110; return ;;
            *PMA110*) log "detect: $P=$V"; echo PMA110; return ;;
        esac
    done
    if [ -f /odm/lib64/camera/com.qti.tuned.lighthousemain.bin ]; then
        log "detect: lighthouse ISP tuning present"
        echo PMA110
    elif [ -f /odm/lib64/camera/com.qti.tuned.zfmain.bin ]; then
        log "detect: zf ISP tuning present"
        echo PKJ110
    else
        log "detect: unknown device, assuming PKJ110 (stock resources kept)"
        echo PKJ110
    fi
}

bind_file() {
    local SOURCE="$1" TARGET="$2"
    if [ ! -f "$SOURCE" ]; then log "skip $TARGET: no payload"; return 0; fi
    if [ ! -f "$TARGET" ]; then log "skip $TARGET: target absent"; return 0; fi
    # Drop any earlier bind at this target first. Installing the module again through KernelSU
    # deletes /data/adb/modules/<id> and re-extracts it, so a bind from an earlier run keeps
    # serving the deleted inode (mountinfo shows the source with a "//deleted" suffix) and the
    # camera would keep reading the OLD file even though this phase has just run again.
    while grep -q " $TARGET " /proc/self/mounts 2>/dev/null; do
        umount "$TARGET" 2>>"$LOG" || break
    done
    chown root:root "$SOURCE"
    chmod 0644 "$SOURCE"
    chcon u:object_r:vendor_configs_file:s0 "$SOURCE" 2>/dev/null || true
    if mount --bind "$SOURCE" "$TARGET" >> "$LOG" 2>&1; then
        log "file bind ok $TARGET"
    else
        log "file bind FAIL $TARGET"
    fi
}

label_tree() {
    DIR=$1
    chown -R root:root "$DIR"
    find "$DIR" -type d -exec chmod 0755 {} \;
    find "$DIR" -type f -exec chmod 0644 {} \;
    find "$DIR" -exec chcon u:object_r:vendor_configs_file:s0 {} \; 2>>"$LOG" || true
}

bind_merged_dir() {
    # `local` (mksh supports it) matters here: the parameter NAME would otherwise collide with the
    # caller's `for NAME in camera_unit_config ...` loop variable and leak back into it.
    local LIVE=$1 EXTRA=$2 ROOT=$3 NAME=$4 TARGET=$5 DEST
    log "merge $TARGET from $LIVE"
    if [ ! -d "$LIVE" ]; then
        log "missing live $LIVE"
        return 0
    fi
    # Never build the merged tree under $MODDIR. KernelSU replaces the entire module directory
    # when the module is installed or updated in place, so a source living there is deleted while
    # the bind mount still holds it; the mount then serves the dead inode and the target reads as
    # an EMPTY directory (mountinfo renders it as ".../merged/<name>//deleted"). For
    # /odm/etc/camera/basictone that quietly strips BasicTone's tone tables, which stalls the
    # preview pipeline -- the same black frame the library overlay used to cause.
    mkdir -p "$ROOT"
    # A previous run in the same boot (KernelSU re-runs this phase after an in-place update) left
    # a bind behind. Drop it so its directory can be reclaimed. If the unmount does not stick,
    # fall back to a per-run directory rather than rm -rf a live bind source.
    umount "$TARGET" 2>/dev/null || true
    if grep -q " $TARGET " /proc/mounts 2>/dev/null; then
        DEST="$ROOT/$NAME.$$"
    else
        DEST="$ROOT/$NAME"
        rm -rf "$DEST"
    fi
    mkdir -p "$DEST"
    cp -a "$LIVE"/. "$DEST"/
    if [ -d "$EXTRA" ]; then
        cp -a "$EXTRA"/. "$DEST"/
    fi
    label_tree "$DEST"
    log "live_count=$(ls "$LIVE" | wc -l) merged_count=$(ls "$DEST" | wc -l)"
    if mount --bind "$DEST" "$TARGET" >> "$LOG" 2>&1; then
        log "dir bind ok $TARGET"
    else
        log "dir bind FAIL $TARGET"
    fi
}

: > "$LOG"

PROFILE=$(detect_profile)
if [ "$PROFILE" = PMA110 ]; then
    CONFIG_SRC=$MODDIR/payload
else
    CONFIG_SRC=$MODDIR/payload_pkj110
fi
log "begin profile=$PROFILE config=$CONFIG_SRC"

# [PMA110] payload/ carries the full set.
#
# [PKJ110] payload_pkj110/ carries ONLY the three regenerated files
#   (camera_unit_config, camera_unit_feature_config.protobuf, oplus_camera_algo_switch_config).
#   Of the other three encrypted oplus_* configs, X8U's stock copies stay in place: they are
#   opaque containers (header 01 01, decoded only by the native libOplusStringJNI.so) and
#   PMA110's plaintext copies are not interchangeable.
#
#   History (2026-10-13). Injection was briefly DISABLED on the theory that the port
#   base was older than the on-device /odm. That theory is FALSIFIED: the config that
#   x8u-config-revert.sh pulled back from the device is byte-identical to the build base
#       camera_unit_config                  6ff8bb9c9fed6e3054ae0bad64a05ac8
#       camera_unit_feature_config.protobuf d28f6cec50cbdb8d33d220904e99d7e6
#   and both carry a 296-entry field1 pool. The actual cause of
#       IndexOutOfBoundsException: Index 314 out of bounds for length 296
#         at LazyStringArrayList.get / CameraConfigure.initFeatureInfo
#   was that field2/field3 never store strings inline -- they store **indices into the
#   field1 string pool**, and X9U's pool (319 entries) diverges from X8U's (296) from
#   index 50 onward. Porting X9U entries verbatim therefore pointed at the wrong string
#   (semantic corruption) or past the end of the pool (the crash above).
#   build_x8u_port.py now remaps every pool index **by string identity** (appending to
#   X8U's pool when the string is absent) and rebuilds every enclosing length prefix.
#   Verified on the produced file: 16380 pool references, max index 306 < pool length
#   307, zero out of bounds; the three ported entries decode to exactly the same token
#   multiset as X9U, and all 20 original entries are byte-identical.
for NAME in oplus_camera_config oplus_camera_aps_config oplus_camera_preview_decision_config.json; do
    [ "$PROFILE" = PMA110 ] || continue
    bind_file "$CONFIG_SRC/$NAME" "/odm/etc/camera/config/$NAME"
done
# oplus_camera_algo_switch_config is the exception, and the reason this profile injects one more
# file than it used to. It is the only place APS records which modes exist: the camera looks
# "<mode>@<cameraId>" up in AlgoSwitchConfig.sPreviewConfigMap and, with no entry, gets null, so
# APS produces no preview result for that mode at all. POP is the one mode X8U's own copy does not
# list, and BaseMode.m8() then refuses every shutter press with bb.g.b still at its -1 default.
# X9U's copy does list it, but it is not a drop-in base (different mode set, different per-mode
# algorithm switches), so the PKJ110 payload is rebuilt from the device's own decoded text with
# the retroCamera block appended:
#   tools/build_x8u_algo_switch_config.py    port-x8u/README.md section 4.12
bind_file "$CONFIG_SRC/oplus_camera_algo_switch_config" \
    "/odm/etc/camera/config/oplus_camera_algo_switch_config"
# Both profiles bind the same two files. The config directory is deliberately NOT merged: 1.3.0
# merged it to carry a synthesised OCS "config parse version 2" container
# (oplus_camera_feature_config), which turned out to deadlock the camera on the first feature
# lookup -- see local.jiege.hook.x10.PaletteFeatureSwitch. The palette switch is answered in Java
# now, so nothing extra belongs in this directory.
for NAME in camera_unit_config camera_unit_feature_config.protobuf; do
    bind_file "$CONFIG_SRC/$NAME" "/odm/etc/camera/config/$NAME"
done

bind_merged_dir /odm/etc/camera/meishe_lut "$MODDIR/payload/meishe_lut" "$MERGED_ROOT" meishe_lut /odm/etc/camera/meishe_lut

BT_EXTRA=$MERGED_ROOT/basictone_extra
rm -rf "$BT_EXTRA"
mkdir -p "$BT_EXTRA/setting_Retro"
if [ -d "$MODDIR/payload/setting_Retro" ]; then
    cp -a "$MODDIR/payload/setting_Retro"/. "$BT_EXTRA/setting_Retro"/
fi
if [ "$PROFILE" = PMA110 ]; then
    cp -a "$MODDIR/payload/x10_basictone"/. "$BT_EXTRA"/
else
    # [PKJ110] Only the palette half of x10_basictone/ is merged. palette/{HDR,SDR}/SC16Lut0..8
    # are the colour cards CombineLutHelper reads from /odm/etc/camera/basictone/palette once
    # Lut3dDataManager.paletteMode() says yes (see the config comment above), and the four
    # SimTool_Palette_*.ini describe them; none of those names exist in X8U's stock tone
    # directory, so this is a pure addition and reverts by simply dropping the bind.
    #
    # lmt/{LMTPhotoLut*,SCLut*} is deliberately NOT merged: those are per-device tone tables read
    # straight by BasicTone/AlgoInterface, and X8U already has its own lmt/ (including SC16Lut0),
    # so dropping X10's set in risks the kind of pipeline stall the library overlay used to cause.
    # GR's own setting_Retro/ is merged above, as before.
    if [ -d "$MODDIR/payload/x10_basictone/palette" ]; then
        mkdir -p "$BT_EXTRA/palette" "$BT_EXTRA/setting"
        cp -a "$MODDIR/payload/x10_basictone/palette"/. "$BT_EXTRA/palette"/ 2>>"$LOG" \
            || log "palette copy FAIL"
        cp -a "$MODDIR/payload/x10_basictone/setting"/SimTool_Palette_*.ini "$BT_EXTRA/setting"/ 2>>"$LOG" \
            || log "palette ini copy FAIL"
        log "palette extra: $(find "$BT_EXTRA/palette" -type f | wc -l) cards"
    else
        log "palette skip: no payload"
    fi
fi
bind_merged_dir /odm/etc/camera/basictone "$BT_EXTRA" "$MERGED_ROOT" basictone /odm/etc/camera/basictone

log "polaroid=$(ls -l /odm/etc/camera/meishe_lut/polaroid_sdr.mslut 2>&1)"
log "host_film=$(ls -l /odm/etc/camera/meishe_lut/fuji-nc.bin 2>&1)"
log "retro_ini=$(ls -l /odm/etc/camera/basictone/setting_Retro/SimTool_Master.ini 2>&1)"
log "host_setting=$(ls -d /odm/etc/camera/basictone/setting 2>&1)"

if [ "$PROFILE" = PMA110 ]; then
    for LENS in main wide tele ultratele; do
        NAME="com.qti.tuned.lighthouse${LENS}.bin"
        SOURCE="$MODDIR/payload/isp/$NAME"
        TARGET="/odm/lib64/camera/$NAME"
        if [ ! -f "$SOURCE" ]; then log "isp skip $NAME: no payload"; continue; fi
        if [ ! -f "$TARGET" ]; then log "isp skip $NAME: target absent"; continue; fi
        chown root:root "$SOURCE"
        chmod 0644 "$SOURCE"
        chcon u:object_r:vendor_file:s0 "$SOURCE" 2>>"$LOG" || true
        if mount --bind "$SOURCE" "$TARGET" >>"$LOG" 2>&1; then
            log "isp bind ok $TARGET"
        else
            log "isp bind FAIL $TARGET"
        fi
    done
    for NAME in gamma_preview_sdr_conf.json gamma_quick_sdr_conf.json gamma_preview_hdr_conf.json; do
        bind_file "$MODDIR/payload/gamma/$NAME" "/odm/etc/camera/$NAME"
    done
else
    # [PKJ110] ISP tuning: DISABLED. PKJ110 /odm/lib64/camera holds
    #   com.qti.tuned.zf{main,ultrawide,tele,ultratele,front}.bin + com.qti.sensor.zf*.so
    # while the payload only carries PMA110's lighthouse{main,wide,tele,ultratele}.bin.
    # Cross-binding tuning between different sensors is not safe; regenerating the GR tuning for
    # the zf* files needs the vendor tuning tool and is tracked as follow-up work.
    log "isp skip: PKJ110 uses com.qti.tuned.zf* (per-sensor)"
    # [PKJ110] Gamma: DISABLED for the same reason. Both devices ship the same three file names,
    # but PKJ110's carry their own per-sensor tone curves (MainGammaSet/UWGammaSet/TeleGammaSet/
    # UteleGammaSet/FrontGammaSet) with different sample values, so the payload copies are not
    # interchangeable and would visibly corrupt tone response.
    log "gamma skip: PKJ110 per-sensor tone curves differ"
fi

# --- Pro video ISZ (upstream 1.1.1, PMA110) ---------------------------------
# Stock sets the ISO gate of rows 25-40 of [InsensorZoom] to 0/0, which
# DoIzoomStable() can never pass; -1 means "no ISO check", like the normal video
# rows (2x main, 6x tele, O-Log2 too). Patched from the live file on every boot;
# touch $MODDIR/disable_pro_video_isz to skip.
#
# [PKJ110] Deliberately skipped. The awk below is byte-precise against PMA110's
# CameraHWConfiguration.config -- it requires exactly 16 matching rows or it
# refuses to bind at all -- and the Find X8 Ultra build has never been validated
# with the gate removed. PKJ110 is the profile that was just signed off as fully
# working, so this release changes nothing about it. Enabling it there means
# re-running the same transform against the X8U file and flipping the gate below.
HWCFG=/odm/etc/camera/CameraHWConfiguration.config
HWOUT=$MERGED_ROOT/CameraHWConfiguration.config
if [ -f "$MODDIR/disable_pro_video_isz" ]; then
    log "pro video isz disabled"
elif [ "$PROFILE" != PMA110 ]; then
    log "pro video isz skip: only validated on PMA110"
elif [ -f "$HWCFG" ]; then
    mkdir -p "$MERGED_ROOT"
    awk -F';' -v OFS=';' '
        /^\[/ { section = $0 }
        section == "[InsensorZoom]" && match($0, /^[ \t]*iZoomSettins\[[0-9]+\]/) {
            row = $0; sub(/^[ \t]*iZoomSettins\[/, "", row); sub(/\].*/, "", row)
            if (row + 0 >= 25 && row + 0 <= 40 && NF == 14 && $12 + 0 == 0 && $13 + 0 == 0 && $12 ~ /^[ \t]*0[ \t]*$/ && $13 ~ /^[ \t]*0[ \t]*$/) {
                $12 = "     -1"; $13 = "     -1"; patched++
            }
        }
        { print }
        END { print patched + 0 > "/dev/stderr" }
    ' "$HWCFG" > "$HWOUT" 2> "$MERGED_ROOT/isz_count"
    COUNT=$(cat "$MERGED_ROOT/isz_count" 2>/dev/null)
    if [ "$COUNT" = "16" ]; then
        bind_file "$HWOUT" "$HWCFG"
    else
        log "pro video isz skipped, patched rows=$COUNT (expected 16)"
    fi
fi

log "end profile=$PROFILE"
