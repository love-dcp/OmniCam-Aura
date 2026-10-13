#!/system/bin/sh
# =============================================================================
# x8u-camera-unblock.sh — 解除 ColorOS 对相机的「不稳定应用」启动限制
#
# 背景（2026-10-13 实测）：
#   ColorOS 17 国行存在一条启动拦截路径 OplusStartupStrategy.isAppClassifyRestricted
#   （国际版因 isExpVersionVIP 直接跳过，国行才查限制表）。相机因反复启动异常被记进
#   「不稳定应用」名单后，AMS 会在创建进程之前直接拒绝启动：
#       W/OplusAppStartupManager: prevent start by classifyResrict com.oplus.camera,
#           cmp com.oplus.camera.Camera by activity  callingUid 2000
#       D/OplusAppStartupMonitor: notifyUnstableAppInfo: ... packageName=com.oplus.camera
#           unstable_restrict_switch=true
#   实测：显式 / LAUNCHER / STILL_IMAGE_CAMERA / IMAGE_CAPTURE 四种意图全部被拦，
#   且全设备只有 com.oplus.camera 一个包被拦（其它 OPPO 自家应用拦截数=0）。
#
# 记录存放：com.oplus.battery（进程 com.oplus.athena）内的
#   com.oplus.startupapp.data.StartupProvider（authority com.oplus.startup.provider），
#   表名 UnstableRecord / Record / AppToShow。解除接口名为 derestrictUnstableApp。
#   设备上没有 sqlite3 / busybox，所以「改库」路线走「导出→本地改→推回」。
#
# ---------------------------------------------------------------------------
# 用法（必须 root）：
#     sh x8u-camera-unblock.sh          # 【只读】探查 + 把相关 DB 导出到 /sdcard
#     sh x8u-camera-unblock.sh fix      # 探查 + 尝试解除（会清 com.oplus.battery 数据，
#                                       #   清之前先整目录备份到 /sdcard）
#
# 安全边界（与 x8u-repair.sh 同一套承诺）：
#   * 只读写 /data（userdata）与 /sdcard 上的普通文件；
#   * 不打开任何 /dev/block/*，无 dd / flash / mkfs，从不 remount rw；
#   * 不碰 boot / super / vbmeta / 分区表；不 setenforce、不 insmod。
#   因此与「变砖」之间没有通路。清 App 数据是可逆操作（脚本会先备份）。
# =============================================================================

MODE="${1:-probe}"

UID_NOW=$(id -u 2>/dev/null)
if [ "$UID_NOW" != "0" ]; then
    echo "=============================================================="
    echo " !! 当前不是 root（uid=${UID_NOW:-?}），本脚本需要 root。"
    echo "    请在临时 root shell 里执行（先 su，或用 rootapp 的终端）。"
    echo "=============================================================="
    echo " 已中止，未做任何修改。"
    exit 1
fi

BATT=/data/data/com.oplus.battery
BATT_DE=/data/user_de/0/com.oplus.battery
OUT=/sdcard/x8u-startup-db
say() { echo "[cam-unblock] $*"; }

echo "=============================================================="
echo " A. 取证（只读）"
echo "=============================================================="
say "mode   : $MODE"
say "date   : $(date)"
say "uptime : $(cat /proc/uptime)"

echo
say "--- 被限制的包（现场复现一次，看日志）---"
logcat -c 2>/dev/null
am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.oplus.camera >/dev/null 2>&1
sleep 2
logcat -d 2>/dev/null | grep -oE "prevent start by classifyResrict [a-zA-Z0-9._]+" | sort -u
say "（若上面为空，说明当前没有被拦）"

echo
say "--- com.oplus.battery 数据目录 ---"
ls -la "$BATT" 2>&1 | head -20
echo "  ----"
ls -la "$BATT/databases" 2>&1 | head -20

echo
say "--- 哪些 DB 里含 com.oplus.camera ---"
mkdir -p "$OUT" 2>/dev/null
FOUND=""
for D in "$BATT/databases" "$BATT_DE/databases" "$BATT" "$BATT_DE"; do
    [ -d "$D" ] || continue
    for F in "$D"/*.db "$D"/*.db-journal "$D"/*.db-wal; do
        [ -f "$F" ] || continue
        N=$(grep -ac "com.oplus.camera" "$F" 2>/dev/null || echo 0)
        if [ "$N" -gt 0 ] 2>/dev/null; then
            say "  ★ 命中 $N 处: $F  ($(stat -c %s "$F") B)"
            FOUND="$FOUND $F"
        fi
    done
done
[ -z "$FOUND" ] && say "  （未在 *.db 中找到，稍后看全目录 grep）"

echo
say "--- 全目录兜底：含相机字样的文件 ---"
grep -rl "com.oplus.camera" "$BATT" "$BATT_DE" 2>/dev/null | head -20

echo
say "--- 导出到 $OUT（供本地分析）---"
for F in $FOUND; do
    B=$(basename "$F")
    cp -f "$F" "$OUT/$B" 2>/dev/null && say "  exported $B ($(stat -c %s "$OUT/$B") B)"
done
# 顺带把整个 databases 目录打包，便于完整分析
if [ -d "$BATT/databases" ]; then
    tar -czf "$OUT/battery-databases.tar.gz" -C "$BATT" databases 2>/dev/null \
        && say "  tar: $OUT/battery-databases.tar.gz ($(stat -c %s "$OUT/battery-databases.tar.gz") B)"
fi
ls -la "$OUT" 2>&1

echo
say "--- 当前相关 secure 键 ---"
settings list secure 2>/dev/null | grep -aiE "camera|unstable|startup|prevent|crashbox"

echo
say "--- 允许 shell 读取 /data/adb 的模块日志（若存在）---"
ls -la /data/adb/modules/omnicam_aura/bind.log /data/adb/modules/omnicam_aura/mount.log 2>&1

if [ "$MODE" != "fix" ]; then
    echo
    echo "=============================================================="
    echo " 探查完毕，未做任何修改。"
    echo " 请把上面 A 段完整输出发回，并把 $OUT 里的文件 adb pull 出来："
    echo "   adb pull /sdcard/x8u-startup-db  G:/OmniCam-Aura-main/port-x8u/_startup/device"
    echo " 若确认要用兜底方案，再执行： sh $0 fix"
    echo "=============================================================="
    exit 0
fi

echo
echo "=============================================================="
echo " B. 尝试解除（兜底方案：清 com.oplus.battery 数据）"
echo "=============================================================="
BK="$OUT/FULLBATTERYBACKUP.tar.gz"
say "先整目录备份（可回滚）..."
tar -czf "$BK" -C /data/data com.oplus.battery -C /data/user_de/0 com.oplus.battery 2>/dev/null \
    && say "  backup ok: $BK ($(stat -c %s "$BK") B)" \
    || say "  !! 备份失败，已中止（不执行清理）"

if [ -s "$BK" ]; then
    say "执行 pm clear com.oplus.battery ..."
    pm clear com.oplus.battery 2>&1
    say "已清理。记录可能仍被 system_server 缓存，建议再软重启一次。"
    sleep 2

    echo
    say "--- 复测 ---"
    logcat -c 2>/dev/null
    am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.oplus.camera >/dev/null 2>&1
    sleep 3
    CNT=$(logcat -d 2>/dev/null | grep -c "prevent start by classifyResrict")
    say "classifyResrict 拦截次数 = $CNT   （0 = 已解除）"
    ps -A -o PID,USER,NAME 2>/dev/null | grep -i "oplus.camera" || say "相机进程仍未起来"
fi

echo
say "done。全程未写入任何块设备 / 未改动任何分区。"
say "若仍被拦，请软重启后再看，并把输出发回。"
