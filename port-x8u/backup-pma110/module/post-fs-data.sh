#!/system/bin/sh
# Bind the tested POP camera directories and GR ISP files.
MODDIR=${0%/*}
LOG=$MODDIR/bind.log
set -u

log() {
    echo "$1" >> "$LOG"
}

bind_file() {
    SOURCE="$1"
    TARGET="$2"
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
    LIVE=$1
    EXTRA=$2
    DEST=$3
    TARGET=$4
    log "merge $TARGET from $LIVE"
    if [ ! -d "$LIVE" ]; then
        log "missing live $LIVE"
        return 0
    fi
    rm -rf "$DEST"
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
log "begin"

for NAME in camera_unit_config camera_unit_feature_config.protobuf oplus_camera_config oplus_camera_algo_switch_config oplus_camera_aps_config oplus_camera_preview_decision_config.json; do
    bind_file "$MODDIR/payload/$NAME" "/odm/etc/camera/config/$NAME"
done

bind_merged_dir /odm/etc/camera/meishe_lut "$MODDIR/payload/meishe_lut" "$MODDIR/merged/meishe_lut" /odm/etc/camera/meishe_lut

BT_EXTRA=$MODDIR/merged/basictone_extra
rm -rf "$BT_EXTRA"
mkdir -p "$BT_EXTRA/setting_Retro"
if [ -d "$MODDIR/payload/setting_Retro" ]; then
    cp -a "$MODDIR/payload/setting_Retro"/. "$BT_EXTRA/setting_Retro"/
fi
cp -a "$MODDIR/payload/x10_basictone"/. "$BT_EXTRA"/
bind_merged_dir /odm/etc/camera/basictone "$BT_EXTRA" "$MODDIR/merged/basictone" /odm/etc/camera/basictone

log "polaroid=$(ls -l /odm/etc/camera/meishe_lut/polaroid_sdr.mslut 2>&1)"
log "host_film=$(ls -l /odm/etc/camera/meishe_lut/fuji-nc.bin 2>&1)"
log "retro_ini=$(ls -l /odm/etc/camera/basictone/setting_Retro/SimTool_Master.ini 2>&1)"
log "host_setting=$(ls -d /odm/etc/camera/basictone/setting 2>&1)"

for LENS in main wide tele ultratele; do
    NAME="com.qti.tuned.lighthouse${LENS}.bin"
    SOURCE="$MODDIR/payload/isp/$NAME"
    TARGET="/odm/lib64/camera/$NAME"
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
log "end"
