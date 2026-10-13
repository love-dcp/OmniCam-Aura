# OmniCam Aura 1.3.1

无界面相机模块。在既有的 Find X9 Ultra（PMA110，相机 7.006.77）与 Find X8 Ultra
（PKJ110，相机 7.006.94 / 实机 7.006.123）支持之上，**1.3.1 修复 X8U 的 X10 调色盘（Palette）**，
两台设备共用同一个包，机型开机自动判定。

## 本次新增（1.3.1 · PKJ110 调色盘）

- **X8U 调色盘可用**。根因是 X8U 取不到特性开关
  `com.oplus.feature.color.palette.support`：X8U 的 ODM 是 OCS「config parse version 1」，
  `CameraConfigure.getProjectSupportFeatureValue()` 在 v1 下对**所有** `com.oplus.feature.*`
  键返回 null，于是 `Lut3dDataManager.paletteMode()` 恒为 false，`CombineLutHelper.init()`
  永远走不到 `/odm/etc/camera/basictone/palette/*`。既不是缺后端库，也不是 LSPosed 钩子没装。
- **修复方式：在 Java 侧回答这一个开关**。新增 `local.jiege.hook.x10.PaletteFeatureSwitch`
  （由 `ModuleEntry` 在相机进程里装上），前置 hook `FeatureApi.getProjectSupportFeatureValue`，
  只对 `com.oplus.feature.color.palette.support` 返回 `"true"`；其它 key 落到原路径，
  与原厂 v1 行为逐字节相同。装挂前先探 `ProtobufConfigureHelper.isFeatureConfigureFileExist()`，
  设备若自带配置容器则主动让路。
- **踩坑记录（务必不要重犯）**：1.3.0 曾用设备自己的 FeatureTable 合成一份 OCS「config parse
  version 2」容器 `oplus_camera_feature_config`，试图从盘上补这个开关。**实测直接把相机锁死**：
  `getProjectSupportFeatureValue()` 在 `parseVersion != 1` 时会
  `PROJECT_FEATURES_READY_CONDITION.block()`，而这个条件只由 `CameraConfigure.initialize()`
  打开、且该 `initialize()` 是"晚"调用的；进程内第一次取特性值发生得更早
  （`MasterPresetDeferredImportProvider.onCreate`，`installContentProviders` 阶段，
  **早于** `Application.onCreate()`）⇒ 有人等、没人唤醒，主线程 ANR。原厂 X8U 永远撞不到，
  因为它在 `!isFeatureConfigureFileExist()` 那一步就 `return null` 了。1.3.1 已删除该容器、
  设备侧的整目录合并、以及生成它的 `tools/build_feature_config_v2.py`，并让
  `tools/verify_aura.py` 反向断言"容器不得重现、调色盘钩子必须在场"。
- **X8U 只合并 `x10_basictone/` 的调色盘那一半**：`palette/{HDR,SDR}/SC16Lut0..8` 共 18 张
  色卡 + 4 个 `SimTool_Palette_*.ini`；`lmt/`（逐机型 tone 表）保持 X8U 原厂不下发。
  纯追加、可回退。
- **配置目录不再合并**：两个机型都**逐文件** `bind_file` 那两份配置，
  `/odm/etc/camera/config` 不做整目录合并（1.3.0 的合并只为携带那份容器，容器既废，合并也撤）。

## 本次新增（1.2.0 · PKJ110）

- **POP 模式可用**。两条独立故障都修掉了：配置层是 `camera_unit_config` 的 `usecase_info`
  写成畸形配对表（每条本该是「单键对象列表」），native 层是模块自己的 LSPosed 原生钩子在
  `dlopen(NULL)` 时对空指针调 `strstr` 直接 SIGSEGV（`libfusioninterpolation.arcsoft.so` 在
  FRC 管线起来时会合法地这么调）。前者按 X8U 原厂数据重新生成配置，后者补了空指针保护。
- **POP「浓郁」滤镜可用**。它的色卡名（`FilterConstant.STYLE_FILTER_TYPE_POSITIVE_HDR_PATH`
  展开的 8 个 `positive_*.bin`）在 X8U 的 ODM 里**整个家族都不存在**（出厂 F.21 `odm.img` 的
  `meishe_lut` 共 111 条，零条 positive），于是每次预览都在刷
  `meicam: Failed to open lut file`。用本机自带的「正片」色卡 `gr.posi.rgba.bin` 复制成 8 个
  名字补齐；因为 `filter_type` 就是文件名，无需改代码或重编 APK。
- **GR 模式**：`getSettingInt` 改为按前 4 条指令指纹在两张表里选偏移（PMA110 `0x150cdd0` /
  PKJ110 `0x13f46d4`），一个 APK 同时服务两个机型，且表项不匹配时安全跳过（相机完全不受影响）。
- **焦距条**：X8U 7.006.94 把 `Zoom.labelBitmap` 换成了 `Path`，固定的字段名解析不到；改用
  多值符号 `Zoom.labelBitmaps` 一次清空 ZoomSeekBar 的全部 Bitmap 字段，两台设备都成立。
- **未改 X8U 的任何后端库**。X9U 的 `libAlgoInterface.so` / `libBasicTonePhoto.so` /
  `libmsnativefilter.so` 从模块 `system/` 移到 `payload/lib64/`，只在 PMA110 上 bind。

## 本次新增（1.1.1）

- 专业视频（含 O-Log2）强制启用 ISZ：主摄 2x、长焦 6x 去掉 ISO 门限（模块目录放
  `disable_pro_video_isz` 可关闭）。**仅 PMA110 生效**：该补丁对 PMA110 的
  `CameraHWConfiguration.config` 是逐字节匹配（要求恰好 16 行）的，且从未在 X8U 上验证过，
  所以不在刚签核过的 X8U 路径上引入未测试的改动。
- 大师模式：相机异常重启后预设列表补全，滤镜不再成片显示为“默认”；当前预设按预设记录而非
  列表位置保持（`MasterPresetListSync` / `PresetSelection`，来自 Muse）。

## 其他

- 版本号收敛到单一来源 `module/module.prop`：`build.ps1` 与 `tools/verify_aura.py` 都从它读取，
  并断言 `app/build.gradle.kts` 的 `versionName` 与之一致，避免只升一半的版本。
- 新增移植校验闸 `tools/verify_port_artifacts.py`（载荷定值、`port-x8u/payload` 镜像一致性、
  JSON 畸形检测、对 X8U 原厂配置的语义 diff、原生指纹、归档一致性）。
- 模块 `system/` 不再存放任何设备相关后端库——KernelSU 的 `metamount.sh` 会无条件把
  `system/` 叠加到系统分区，脚本没有机会按机型拦截。

## 已验证 / 待验证

- 已验证（PMA110）：APK 编译、签名、无 UI Manifest、模块结构、GR/POP 后端与六组调色盘捕获配置；
  已安装模块与 APK 并逐项使用。
- 已验证（PKJ110）：模块与 APK 已实机安装；相机正常启动并稳定运行；**POP 模式可用，切到 POP
  不再闪退，POP 的全部滤镜（含此前失效的「浓郁」）均生效**；GR 的符号层与原生 `getSettingInt`
  指纹已按设备上的 `libAlgoInterface.so` 复核一致；**1.3.0 的 V2 容器方案被实机证伪并已回滚**
  （叠加容器后相机主线程 ANR，见上「踩坑记录」）。
- 待验证 / 已知限制：X8U 上的移轴、滑杆与前置成片尚未逐项走查；X8U 的 X10 调色盘**已改为
  Java 侧回答开关（1.3.1）并随包下发色卡**，但**面板 UI 与成片效果仍需上机逐项确认**；
  X8U 的 ISP GR 调谐与 gamma 表（厂商调优工具才能生成）不在此次范围内；Pro video
  ISZ 故意未在 X8U 上启用。完整的未决清单见 `port-x8u/README.md` 第 7 节。
