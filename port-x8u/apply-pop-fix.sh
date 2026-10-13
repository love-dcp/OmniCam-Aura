#!/usr/bin/env bash
# 主机侧入口：把修好的配置与设备侧脚本推到手机，然后调用 x8u-fix-pop.sh。
#
#   ./port-x8u/apply-pop-fix.sh check     # 只读：看两边 md5 / 挂载 / bind 源
#   ./port-x8u/apply-pop-fix.sh apply     # 推包 → bind → 写回 payload → 复核 → 重启相机
#   ./port-x8u/apply-pop-fix.sh revert    # 撤回原厂配置（设备侧 umount）
#   ./port-x8u/apply-pop-fix.sh diag      # 采诊断包并 pull 回本地
#
# 环境：ADB 可指定 adb 路径（默认自动查找 SDK / PATH）。
# 前提：手机已 root（su 可用）、KernelSU 模块 omnicam_aura 已安装。
set -uo pipefail

# Git Bash / MSYS 会把 "/data/local/tmp/x" 这类设备路径改写成 Windows 路径，
# 传给 adb 就成了 C:/Program Files/...。这里一律关闭路径转换（其它平台上是空操作）。
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/.." && pwd)
PAYLOAD="$REPO/module/payload_pkj110"
# The device-side scripts live in module/ so that they also ship inside the KernelSU archive:
# with them installed, the same fix can be applied from the phone alone, no PC needed.
DEVICE_SH="$REPO/module"
STAGE=/data/local/tmp/x8u-fixcfg
LOCAL_TMP=/data/local/tmp
PULL_DIR="$HERE/_diag"

MD_UNIT=6b6c77aa8694bd55d2eaaacaec5a659a
MD_PROTO=b0ee50c1a3bbc9e1dcc20f2391a490c2

find_adb() {
    if [ -n "${ADB:-}" ] && [ -x "${ADB}" ]; then echo "$ADB"; return; fi
    if command -v adb >/dev/null 2>&1; then command -v adb; return; fi
    for c in \
        "/c/Users/${USER:-${USERNAME:-}}/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
        "${HOME:-}/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
        "${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe" \
        "/c/Android/Sdk/platform-tools/adb.exe"; do
        [ -n "$c" ] && [ -x "$c" ] && { echo "$c"; return; }
    done
    echo ""
}

ADB=$(find_adb)
if [ -z "$ADB" ]; then
    echo "找不到 adb。请设置 ADB=/path/to/adb 后重试。" >&2
    exit 2
fi
echo "[host] ADB=$ADB"

adb_do() { "$ADB" "$@"; }
sh_root() { "$ADB" shell "su -c '$*'"; }

# adb.exe 是原生 Windows 程序：设备侧路径必须保持 `/data/...` 原样（靠上面关掉转换），
# 而本地被 push 的文件必须是 Windows 形式，否则它 stat 不到 `/g/...`。
to_win() { cygpath -w "$1" 2>/dev/null || printf '%s' "$1"; }
PAYLOAD_WIN=$(to_win "$PAYLOAD")
DEVICE_SH_WIN=$(to_win "$DEVICE_SH")

require_device() {
    local n
    n=$(adb_do devices | awk 'NR>1 && $2=="device"' | wc -l)
    if [ "$n" -eq 0 ]; then
        echo "[host] 没有已授权的设备。接上 USB、解锁、允许调试后重试。" >&2
        "$ADB" devices >&2
        exit 3
    fi
    echo "[host] 设备："
    adb_do devices | sed 's/^/[host]   /'
}

require_root() {
    if ! sh_root id | grep -q 'uid=0'; then
        echo "[host] 设备侧 su 不可用（或未授权）。本流程需要 root。" >&2
        exit 4
    fi
    echo "[host] su 可用"
}

# 本地产物完整性自检：推之前先确认推的就是修好的那份
verify_local() {
    local rc=0 f want got
    for pair in "camera_unit_config:$MD_UNIT" "camera_unit_feature_config.protobuf:$MD_PROTO"; do
        f=${pair%%:*}; want=${pair#*:}
        if [ ! -f "$PAYLOAD/$f" ]; then
            echo "[host] 缺文件 $PAYLOAD/$f" >&2; rc=1; continue
        fi
        got=$(md5sum "$PAYLOAD/$f" | cut -d' ' -f1)
        if [ "$got" = "$want" ]; then
            echo "[host]   $f  ok  $got"
        else
            echo "[host]   $f  **md5 不符**  $got  期望 $want" >&2; rc=1
        fi
    done
    if [ "$rc" != 0 ]; then
        echo "[host] 本地产物不对，先跑 python tools/verify_port_artifacts.py 排查。" >&2
        exit 5
    fi
}

push_all() {
    verify_local
    adb_do shell "mkdir -p $STAGE"
    adb_do push "$PAYLOAD_WIN/camera_unit_config" "$STAGE/camera_unit_config" >/dev/null
    adb_do push "$PAYLOAD_WIN/camera_unit_feature_config.protobuf" "$STAGE/camera_unit_feature_config.protobuf" >/dev/null
    adb_do push "$DEVICE_SH_WIN/x8u-fix-pop.sh" "$LOCAL_TMP/x8u-fix-pop.sh" >/dev/null
    adb_do push "$DEVICE_SH_WIN/x8u-pop-diag.sh" "$LOCAL_TMP/x8u-pop-diag.sh" >/dev/null
    adb_do shell "chmod 0755 $LOCAL_TMP/x8u-fix-pop.sh $LOCAL_TMP/x8u-pop-diag.sh; chmod 0644 $STAGE/*"
    echo "[host] 推入完成：$STAGE（两份配置）、$LOCAL_TMP/x8u-fix-pop.sh、x8u-pop-diag.sh"
}

case "${1:-check}" in
    check)
        require_device
        push_all
        sh_root "sh $LOCAL_TMP/x8u-fix-pop.sh check"
        ;;
    apply)
        require_device
        require_root
        push_all
        sh_root "sh $LOCAL_TMP/x8u-fix-pop.sh apply"
        ;;
    revert)
        require_device
        require_root
        sh_root "sh $LOCAL_TMP/x8u-fix-pop.sh revert"
        ;;
    diag)
        require_device
        require_root
        adb_do push "$DEVICE_SH_WIN/x8u-pop-diag.sh" "$LOCAL_TMP/x8u-pop-diag.sh" >/dev/null
        adb_do shell "chmod 0755 $LOCAL_TMP/x8u-pop-diag.sh"
        sh_root "sh $LOCAL_TMP/x8u-pop-diag.sh pack"
        mkdir -p "$PULL_DIR"
        adb_do shell "ls -d /sdcard/omnicam-diag-* 2>/dev/null | tail -1" | tr -d '\r' > "$PULL_DIR/.last_dir"
        LAST=$(cat "$PULL_DIR/.last_dir")
        if [ -n "$LAST" ]; then
            echo "[host] pull $LAST"
            adb_do pull "$LAST" "$(to_win "$PULL_DIR")/" >/dev/null && echo "[host] 已保存到 $PULL_DIR/"
        fi
        ;;
    *)
        cat <<EOF
用法: $0 {check|apply|revert|diag}

  check    只读取证（源 / live / payload 的 md5、bind 源是否 //deleted）
  apply    绑定修好的配置 + 写回模块 payload + 复核 + am force-stop com.oplus.camera
  revert   撤回原厂配置
  diag     自动重启相机并采样日志/线程转储，pull 到 port-x8u/_diag/

前提：设备在线且已 root。产物取自 $PAYLOAD。
EOF
        exit 2
        ;;
esac
