#!/system/bin/sh
# Bind the tested POP camera directories and GR resources.
#
# One package serves two devices; the profile is detected here (see post-mount.sh for the full
# reasoning). Both profiles are marked [PMA110] / [PKJ110] below.
#
#   PMA110 (Find X9 Ultra) - payload/          , the full OmniCam config set, ISP + gamma bound.
#   PKJ110 (Find X8 Ultra) - payload_pkj110/   , only the two regenerated config files; the
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
# [PKJ110] config injection is DISABLED (2026-10-13) -- see port-x8u/README.md section 4.4.
#   The two regenerated files were built on top of an X8U odm.img that is OLDER than the one
#   actually on the device (device camera is 7.006.123, /odm built 2026-10-07 23:15). The
#   stale protobuf carries only 296 top-level field1 entries (the repeated string the camera
#   walks), while com.oplus.camera 7.006.123 reads index 314 of that list:
#       java.lang.IndexOutOfBoundsException: Index 314 out of bounds for length 296
#         at com.google.oplus.protobuf.LazyStringArrayList.get(LazyStringArrayList.java:97)
#         at com.oplus.ocs.camera.configure.CameraConfigure.initFeatureInfo(CameraConfigure.java:556)
#   Consequence: the camera crashes on every launch, and ColorOS then blacklists it as an
#   unstable app ("prevent start by classifyResrict com.oplus.camera"), so it will not even
#   start. The crash is the cause, the startup block is the symptom.
#   Until the ported files are regenerated from the device's OWN config (x8u-config-revert.sh
#   exports them to /sdcard/x8u-pristine-config), PKJ110 keeps its stock config.
for NAME in oplus_camera_config oplus_camera_algo_switch_config oplus_camera_aps_config oplus_camera_preview_decision_config.json; do
    [ "$PROFILE" = PMA110 ] || continue
    bind_file "$CONFIG_SRC/$NAME" "/odm/etc/camera/config/$NAME"
done
if [ "$PROFILE" = PMA110 ]; then
    for NAME in camera_unit_config camera_unit_feature_config.protobuf; do
        bind_file "$CONFIG_SRC/$NAME" "/odm/etc/camera/config/$NAME"
    done
else
    log "PKJ110: camera_unit_config / protobuf injection DISABLED (stock config is newer than the port base)"
    log "PKJ110: run port-x8u/x8u-config-revert.sh on device to drop the stale live binds"
fi

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
    # [PKJ110] The X10 palette tuning is deliberately NOT merged here. x10_basictone/ carries
    # lmt/{LMTPhotoLut*,SCLut*}, palette/{HDR,SDR} and four SimTool_Palette_*.ini that drive the
    # Find X10 palette on PMA110's engine. Those are per-device tone tables read straight by
    # BasicTone/AlgoInterface, so dropping the X9U set into X8U's tone directory risks the same
    # kind of pipeline stall the libraries used to cause. GR's own setting_Retro/ is still merged.
    log "x10_basictone skip: PKJ110 keeps its own tone tables"
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
log "end profile=$PROFILE"
