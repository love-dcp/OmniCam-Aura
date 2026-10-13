#!/system/bin/sh
# 免重启地把修好的相机配置挂到 /odm/etc/camera/config/ 下，用于快速验证。
# 只操作挂载表（bind mount），不写任何分区；revert 用 x8u-config-revert.sh。
#
#   sh /data/local/tmp/x8u-apply-config.sh check    # 只读：看现状
#   sh /data/local/tmp/x8u-apply-config.sh apply    # 绑上（幂等，先 umount）
#   sh /data/local/tmp/x8u-apply-config.sh revert   # 撤回原厂
set -u

SRC=/data/local/tmp/x8u-cfg
DST=/odm/etc/camera/config
FILES="camera_unit_config camera_unit_feature_config.protobuf"
LOG=/data/local/tmp/x8u-apply-config.log

uid=$(id -u 2>/dev/null || echo 0)
[ "$uid" = 0 ] || { echo "[cfg] 需要 root：请用 su -c 或 root shell 运行（当前 uid=$uid）"; exit 1; }

do_check() {
    echo "[cfg] --- 现状取证 ---"
    for F in $FILES; do
        if [ -f "$SRC/$F" ]; then
            SZ=$(stat -c '%s' "$SRC/$F"); MD=$(md5sum "$SRC/$F" | cut -d' ' -f1)
            echo "[cfg] 源   $F  ${SZ} B  md5=${MD}"
        else
            echo "[cfg] 源   $F  (缺失)"
        fi
        echo "[cfg] 目标 $F  $(stat -c '%s B' "$DST/$F" 2>/dev/null || echo '(缺失)')"
    done
    echo "[cfg] --- 挂载表 ---"
    grep "$DST" /proc/self/mountinfo || echo "[cfg] (无 bind，正在用原厂)"
}

do_revert() {
    for F in $FILES; do
        while grep -q " $DST/$F " /proc/self/mounts 2>/dev/null; do
            umount "$DST/$F" 2>>"$LOG" || break
        done
    done
    echo "[cfg] 已撤回原厂配置"
    do_check
}

do_apply() {
    for F in $FILES; do
        [ -f "$SRC/$F" ] || { echo "[cfg] 跳过 $F：源缺失"; continue; }
        [ -f "$DST/$F" ] || { echo "[cfg] 跳过 $F：目标不存在"; continue; }
        while grep -q " $DST/$F " /proc/self/mounts 2>/dev/null; do
            umount "$DST/$F" 2>>"$LOG" || break
        done
        chown root:root "$SRC/$F"; chmod 0644 "$SRC/$F"
        chcon u:object_r:vendor_configs_file:s0 "$SRC/$F" 2>>"$LOG" || true
        if mount --bind "$SRC/$F" "$DST/$F" >>"$LOG" 2>&1; then
            echo "[cfg] bind ok  $F"
        else
            echo "[cfg] bind FAIL $F（见 $LOG）"
        fi
    done
    echo "[cfg] --- 复核 ---"
    do_check
    echo "[cfg] 强制停止相机，下次启动即读到新配置"
    am force-stop com.oplus.camera
    echo "[cfg] 完成。现在可以打开相机，并切换到 POP / GR / 移轴界面测试。"
}

case "${1:-check}" in
    check) do_check ;;
    apply) do_apply ;;
    revert) do_revert ;;
    *) echo "用法: $0 {check|apply|revert}"; exit 2 ;;
esac
