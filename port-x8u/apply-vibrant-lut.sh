#!/usr/bin/env bash
# 主机侧入口：把「POP 浓郁(正片)色卡」替身推上手机，再调用设备侧 x8u-fix-vibrant.sh。
#
#   ./port-x8u/apply-vibrant-lut.sh check     # 只读：看源 / payload / live 三处 md5
#   ./port-x8u/apply-vibrant-lut.sh apply     # 推 8 份色卡 → 写 payload → 写合并树 → 复核
#   ./port-x8u/apply-vibrant-lut.sh revert    # 撤掉这 8 份
#
# 背景：X8U 的 ODM 里整个 positive_* 色卡家族都不存在（出厂 F.21 odm.img 的
# meishe_lut 111 个条目无一条 positive_*），而 APK 的 POP「浓郁」= Positive
# 会去 /odm/etc/camera/meishe_lut/positive_hdr_normal_a_1.bin 取色卡，取不到 ⇒
# 滤镜选了没反应。这里用本机自带的「正片」色卡 gr.posi.rgba.bin 按 8 个
# positive_* 名字补齐，让它能出画面。详见 module/x8u-fix-vibrant.sh 顶部注释。
#
# 环境：ADB 可指定 adb 路径（默认自动查找 SDK / PATH）。前提：手机已 root、模块已装。
set -uo pipefail

# Git Bash / MSYS 会把 "/data/local/tmp/x" 改写成 Windows 路径，传给 adb 就成了
# C:/Program Files/...。这里一律关闭路径转换（其它平台上是空操作）。
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/.." && pwd)
LUTDIR="$REPO/module/payload/meishe_lut"
DEVICE_SH="$REPO/module"
STAGE=/data/local/tmp/x8u-fixlut
LOCAL_TMP=/data/local/tmp

MD_LUT=ec7eee635f70dbf299d6c49e750739b7
SZ_LUT=24128
LUTS="positive_normal.bin positive_master.bin positive_sdr_gen_a_1.bin positive_sdr_gen_d_1.bin positive_hdr_normal_a_1.bin positive_hdr_normal_d_1.bin positive_hdr_master_a_1.bin positive_hdr_master_d_1.bin"

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

# adb.exe 是原生 Windows 程序：设备侧路径必须保持 /data/... 原样（上面已关转换），
# 而被 push 的本地文件必须是 Windows 形式，否则它 stat 不到 /g/...。
to_win() { cygpath -w "$1" 2>/dev/null || printf '%s' "$1"; }
LUTDIR_WIN=$(to_win "$LUTDIR")
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

# 推之前先确认本地这 8 份就是预期的替身，避免把半成品写进手机。
verify_local() {
    local rc=0 f got sz
    for f in $LUTS; do
        if [ ! -f "$LUTDIR/$f" ]; then
            echo "[host] 缺文件 $LUTDIR/$f" >&2; rc=1; continue
        fi
        sz=$(stat -c %s "$LUTDIR/$f" 2>/dev/null)
        got=$(md5sum "$LUTDIR/$f" | cut -d' ' -f1)
        if [ "$sz" = "$SZ_LUT" ] && [ "$got" = "$MD_LUT" ]; then
            echo "[host]   $f  ok  $sz B  $got"
        else
            echo "[host]   $f  **不符**  $sz B  $got  期望 $SZ_LUT B / $MD_LUT" >&2; rc=1
        fi
    done
    if [ "$rc" != 0 ]; then
        echo "[host] 本地产物不对，先跑 python tools/verify_aura.py 排查。" >&2
        exit 5
    fi
}

push_all() {
    verify_local
    adb_do shell "mkdir -p $STAGE"
    for f in $LUTS; do
        adb_do push "$LUTDIR_WIN/$f" "$STAGE/$f" >/dev/null
    done
    adb_do push "$DEVICE_SH_WIN/x8u-fix-vibrant.sh" "$LOCAL_TMP/x8u-fix-vibrant.sh" >/dev/null
    adb_do shell "chmod 0755 $LOCAL_TMP/x8u-fix-vibrant.sh; chmod 0644 $STAGE/*"
    echo "[host] 推入完成：$STAGE（8 份色卡）、$LOCAL_TMP/x8u-fix-vibrant.sh"
}

case "${1:-check}" in
    check)
        require_device
        push_all
        sh_root "sh $LOCAL_TMP/x8u-fix-vibrant.sh check"
        ;;
    apply)
        require_device
        require_root
        push_all
        sh_root "sh $LOCAL_TMP/x8u-fix-vibrant.sh apply"
        ;;
    revert)
        require_device
        require_root
        sh_root "sh $LOCAL_TMP/x8u-fix-vibrant.sh revert"
        ;;
    *)
        cat <<EOF
用法: $0 {check|apply|revert}

  check    只读取证（源 / payload / live(omd) 三处 md5 与合并树 bind 源）
  apply    补齐 8 份 positive_* 色卡 + 写回模块 payload + 复核 + am force-stop com.oplus.camera
  revert   撤掉这 8 份（回到"选了浓郁没反应"）

前提：设备在线且已 root。色卡取自 $LUTDIR。
EOF
        exit 2
        ;;
esac
