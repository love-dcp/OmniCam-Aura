# OmniCam Aura 1.3.0

## 适用组合

| 机型 | 代号 | 相机版本 | 状态 |
|---|---|---|---|
| OPPO Find X9 Ultra | PMA110 | 7.006.77 | 已验证 |
| OPPO Find X8 Ultra | PKJ110 | 移植依据 7.006.94；实机 7.006.123 | 已验证 |

> PKJ110 的两个版本号都经过核对：原生指纹键控的是 `libAlgoInterface.so`（ODM 固件，不随相机
> APK 版本走），`symbols.json` 在实机 7.006.123 上 190/191 命中。详见 `../port-x8u/README.md` §5、§7。

模块启动时会自行判定机型，同一个包可直接在两台设备上使用，判断结果显示在模块目录下的
`mount.log` 与 `bind.log` 里（`profile=PMA110` 或 `profile=PKJ110`）。

安装此 KernelSU 模块后，安装同版配套 APK `omnicam-aura-1.3.0.apk`，在 LSPosed 中启用并勾选相机
`com.oplus.camera` 和相册 `com.coloros.gallery3d`，然后重启。Aura 与 Muse 只能启用其一。

## 两个机型档案的差别

- **PMA110**：与以往版本行为完全一致。`payload/` 提供完整配置，`/odm/lib64` 下的
  `libBasicTonePhotoX9/X10.so` 由系统 overlay 创建，`libBasicTonePhoto.so`、`libAlgoInterface.so`、
  `libmsnativefilter.so` 由 OmniCam 自己的副本 bind 覆盖，ISP 调谐（`com.qti.tuned.lighthouse*.bin`）
  与 gamma 表也会挂载；专业视频 ISZ 补丁在此档案上生效。
- **PKJ110**：不改任何后端库（"只 hook"路线）。PKJ110 没有 `X9/X10` 后端，其
  `/odm/lib64/libBasicTonePhoto.so` 就是真实后端，而 OmniCam 那份是只负责 dlopen 的 8 KB 路由桩，
  挂上去会让 BasicTone 直接失败。配置文件改从 `payload_pkj110/` 取（两份按 PKJ110 原厂数据重新
  生成的 GR 条目，外加一份 1.3.0 新增的 **OCS config parse version 2 容器**
  `oplus_camera_feature_config`，用来打开调色盘开关 —— X8U 原厂没有这个容器，缺它时
  `CameraConfigure.getProjectSupportFeatureValue()` 对所有 `com.oplus.feature.*` 键返回 null，
  调色盘恒不可用）。ISP 与 gamma 因属逐传感器数据而**不下发**；
  `x10_basictone/` **只 merge 调色盘那一半**（`palette/{HDR,SDR}` 共 18 张色卡 +
  4 个 `SimTool_Palette_*.ini`），逐机型的 `lmt/` tone 表保持 X8U 原厂；专业视频 ISZ 补丁**跳过**
  （它逐字节匹配 PMA110 的 `CameraHWConfiguration.config`，且未在 X8U 上验证过）。

> **注意**：凡是设备相关的后端库都**不能**放进模块的 `system/` 目录。KernelSU 的 `metamount.sh`
> 会把 `system/` 下的树无条件叠加到系统分区，脚本没有机会按机型拦截。三个 X9U 后端因此放在
> `payload/lib64/`，由 `post-mount.sh` 在 PMA110 上 bind；`system/` 只保留 `libBasicTonePhotoX9/X10.so`
> 两个惰性文件（overlay 是唯一能"凭空创建"路径的机制，X9U 需要它们做 bind 目标）。
> `tools/verify_aura.py` 会对这条布局加硬断言。

## 随包的修复脚本（都不在开机时运行）

| 脚本 | 用途 |
|---|---|
| `x8u-fix-pop.sh` | 重新下发 `camera_unit_config` 并解开悬空 bind（POP 起不来的现场修复） |
| `x8u-fix-vibrant.sh` | 补齐 POP「浓郁」滤镜的 8 份 `positive_*.bin` 色卡 |
| `x8u-pop-diag.sh` | POP 故障取证（挂载、md5、日志抓取） |
| `x8u-config-revert.sh` | 把相机配置退回原厂 |
| `x8u-repair.sh` | 综合修复入口 |

细节见 `../port-x8u/README.md`。

源码、构建说明和授权范围见仓库根目录的 README.md 与 LICENSE。
