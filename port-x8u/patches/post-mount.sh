#!/system/bin/sh
#
# OmniCam Aura - post-mount phase (library replacement).
#
# KernelSU runs the stages in this order inside post-fs-data:
#     post-fs-data.sh  ->  load system.prop  ->  metamount.sh (mounts every module's system/ tree)
#         ->  post-mount.sh
# so this script runs AFTER the overlayfs mount and still before zygote.
#
# That timing is why the three X9U algorithm libraries no longer sit in system/odm/lib64/.
# Anything there is applied by metamount.sh unconditionally, with no chance to key on the device,
# which is exactly what broke Find X8 Ultra. Only libBasicTonePhotoX9/X10.so stay in system/,
# because the overlay is the one mechanism able to *create* a path that does not exist yet.
#
#   PMA110 (Find X9 Ultra) - ISP tuning is com.qti.tuned.lighthouse*.bin.
#       Installs OmniCam's own backends over /odm/lib64, as before.
#
#   PKJ110 (Find X8 Ultra) - ISP tuning is com.qti.tuned.zf*.bin.
#       Replaces NOTHING. X8U's own libAlgoInterface.so (60 MB) and libBasicTonePhoto.so
#       (538 KB) are the real backend, and libAlgoInterface loads libBasicTonePhoto.so by name.
#       Overlaying the X9U pair put OmniCam's 8 KB router stub in that slot, so the stub
#       dlopen'd X10 - an X9U backend, created by the very same overlay - for X8U hardware.
#       The preview pipeline stalled there and the camera came up on a black frame with a
#       frozen UI. The X8U libraries have to stay untouched.
#       The two X9/X10 files still arrive through the overlay and are inert: X8U's libraries
#       reference neither name (0 occurrences in libBasicTonePhoto.so and libAlgoInterface.so).
#
# Do NOT detect with `[ -f /odm/lib64/libBasicTonePhotoX9.so ]`: the module itself ships that file
# and metamount.sh has already overlaid it by the time this phase runs, so it exists on both
# devices. The ISP tuning files are device-only and safe to test.
set -u
MODDIR=${0%/*}
LOG=$MODDIR/mount.log
: > "$LOG"
log() { echo "$1" >> "$LOG"; }

getprop_safe() {
    getprop "$1" 2>/dev/null
}

detect_profile() {
    # The model name is NOT in ro.product.device on this platform: PKJ110 reports
    # OP5DD3L1 there, and ro.product.odm.vendor.device / ro.vendor.oplus.device are
    # empty, so a device-based probe silently falls through to the ISP fallback.
    # Verified on PKJ110: ro.product.model=PKJ110, ro.product.device=OP5DD3L1,
    # ro.build.display.id=PKJ110_15.0.1.502(CN01).  Probe model/name first.
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
        # Conservative fallback: leaving backends stock only loses features, binding the wrong
        # backend aborts BasicTone for the whole camera.
        log "detect: unknown device, assuming PKJ110 (backends left stock)"
        echo PKJ110
    fi
}

bind_library() {
    SOURCE=$1
    NAME=$2
    TARGET="/odm/lib64/$NAME"
    if [ ! -f "$SOURCE" ]; then log "lib skip $NAME: no source"; return 0; fi
    if [ ! -f "$TARGET" ]; then log "lib skip $NAME: target absent"; return 0; fi
    chown root:root "$SOURCE"
    chmod 0644 "$SOURCE"
    chcon u:object_r:same_process_hal_file:s0 "$SOURCE" 2>>"$LOG" || true
    if mount --bind "$SOURCE" "$TARGET" >>"$LOG" 2>&1; then
        log "lib bind ok $TARGET ($(stat -c %s "$SOURCE") B)"
    else
        log "lib bind FAIL $TARGET"
    fi
}

PROFILE=$(detect_profile)
log "begin profile=$PROFILE"

if [ "$PROFILE" = PMA110 ]; then
    # X9/X10 first: metamount.sh created both paths from system/odm/lib64/ a moment ago.
    bind_library "$MODDIR/system/odm/lib64/libBasicTonePhotoX9.so"  libBasicTonePhotoX9.so
    bind_library "$MODDIR/system/odm/lib64/libBasicTonePhotoX10.so" libBasicTonePhotoX10.so
    # These three replace stock files that already exist on X9U, so each bind has a target.
    bind_library "$MODDIR/payload/lib64/libBasicTonePhoto.so" libBasicTonePhoto.so
    bind_library "$MODDIR/payload/lib64/libAlgoInterface.so"  libAlgoInterface.so
    bind_library "$MODDIR/payload/lib64/libmsnativefilter.so" libmsnativefilter.so
else
    log "PKJ110: no library replacement (X8U keeps its own backend)"
    # metamount.sh has ALREADY overlaid /odm/lib64 from /mnt/vendor/mountify/odm/lib64, and that
    # workspace copy is where the X9U backends come from. No module script can stop the overlay
    # from being created -- but this phase runs immediately afterwards as root, so it can be
    # detached again here, and the workspace purged.
    #
    # This is not cosmetic. The device only supports a SOFT reboot: root comes from a CVE exploit
    # (com.pkj110.rootapp) that dies on a hard reboot. A soft reboot restarts userspace but NOT
    # the kernel, so these scripts run again while metamount leaves the existing overlay alone --
    # it stays frozen on whatever /mnt/vendor/mountify/odm/lib64 held the first time it was built,
    # long before the module dropped the X9U libraries from system/. Detaching it here is what
    # makes the fix reach the device at all, with no hard reboot.
    n=0
    while grep -q " /odm/lib64 " /proc/self/mounts 2>/dev/null; do
        umount -l /odm/lib64 2>>"$LOG" || umount /odm/lib64 2>>"$LOG" || break
        n=$((n + 1))
        [ "$n" -ge 4 ] && break
    done
    log "PKJ110: detached $n /odm/lib64 overlay(s)"
    for L in libBasicTonePhoto.so libAlgoInterface.so libmsnativefilter.so; do
        if [ -f "/mnt/vendor/mountify/odm/lib64/$L" ]; then
            rm -f "/mnt/vendor/mountify/odm/lib64/$L" 2>>"$LOG" && log "purge workspace $L"
        fi
    done
    for L in libBasicTonePhoto.so libAlgoInterface.so libmsnativefilter.so; do
        log "  $L = $(stat -c %s "/odm/lib64/$L" 2>/dev/null) B  (X8U stock 538400 / 60867488 / 7992584)"
    done
fi

log "end profile=$PROFILE"
