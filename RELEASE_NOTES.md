# OmniCam Aura 1.3.3

无界面相机模块。在既有的 Find X9 Ultra（PMA110，相机 7.006.77）与 Find X8 Ultra
（PKJ110，相机 7.006.94 / 实机 7.006.123）支持之上，**1.3.3 修好 POP（复古相机）模式的快门**
——此前要点快门毫无反应（预览正常、滤镜正常，就是不出片）。

## 本次变更（1.3.3 · PKJ110 的 POP 快门修复）

- **症状**：PKJ110 上进入 POP（`retroCamera`）后点快门（屏幕快门与硬件相机键都是）**毫无反应**。
  预览正常、色卡正常，`is_export,true` 从不出现。照片模式一切正常。
- **根因（APS 配置缺口，不是模式类钩子）**：相机把 `<mode>@<cameraId>` 拿去
  `AlgoSwitchConfig.sPreviewConfigMap` 里查，查不到就让 APS 不给该模式任何预览结果。
  PKJ110 的 `/odm/etc/camera/config/oplus_camera_algo_switch_config` 用的是 X8U 原厂那份，
  全表 **60 个键里没有 `retroCamera`**（也没有 `grmode`）。于是 `getPreviewConfig("retroCamera",0,…)`
  返回 `null`（`ApsAdapterLog.e "getPreviewConfig, preview config is null"`，POP 模式 33 次、
  照片 0 次），`bb.g` 全部停在构造默认值（相机 ID `-1`、特性类型 `0`、滤镜名 `null`），
  最后 `BaseMode.m8` 的尾句 `return gVar.b == getCameraId()` 落到 `-1 == 0` ⇒ 恒 false，快门被拦。
- **为什么 GR 没事**：`GrModeHooks` 让 GR 冒充 `professional`，走的是 `master@0` 那套现成配置；
  POP 的移植只做了 UI / 色卡 / mask，**没做"让 POP 落到一个 APS 认识的 mode 上"这一步**。
- **修复方式：从设备原厂明文重建这份配置，纯追加 `retroCamera` 块**（不是照搬 X9U 那份全模式表）。
  - 用临时探针 dump 出设备原厂配置**解密后的明文**（132,973 B，**JSONC**，带两处 `//` 注释）。
  - `tools/build_x8u_algo_switch_config.py` 在这份明文上做**文本级 splice**，
    追加的 `retroCamera` 条目按 X8U 的词汇表**裁剪**（去掉 `aps_algo_colorsensor` /
    `aps_algo_dcg_fusion` / `aps_algo_rsp` 等 X8U 没有的算法开关，保留 `aps_algo_color_palette`），
    并还原原厂排版。
  - 产物 **141,474 B / md5 `593dbc7913682da175663d708b005120`**，同时写入
    `module/payload_pkj110/`（下发）与 `port-x8u/payload/`（交付镜像，逐字节相同）。
  - `module/post-fs-data.sh` 对 PKJ110 **无条件** bind 这个文件（它原先只在 PMA110 分支里被跳过）。
- **发布闸门**：`tools/verify_port_artifacts.py` 新增一组（4b）——动态载入生成器重建、两个镜像等值、
  产物与**原厂文本前缀逐字节一致**、尾部一致、`"mode"` 恰好多 2 个。`python tools/verify_port_artifacts.py`
  与 `python tools/verify_aura.py` 均 PASS。
- **实机验证**（PKJ110，相机 7.006.123）：POP 出片 ✅、照片 ✅、大师 ✅ 三模式回归通过，
  `preview config is null` **33 → 0**。终验是在**模块自身完成 bind**的情况下做的（非手工 mount），
  即装包开机后自动生效。详见 `port-x8u/README.md` §4.12。

## 历史（1.3.2 · PKJ110 调色盘开关撤回）

> 1.3.2 的结论仍然有效（调色盘不能在 Java 层开启），本节保留原样。

无界面相机模块。在既有的 Find X9 Ultra（PMA110，相机 7.006.77）与 Find X8 Ultra
（PKJ110，相机 7.006.94 / 实机 7.006.123）支持之上，**1.3.2 撤回 X8U 的调色盘开关**：
在 Java 侧回答那个特性开关确实能让 `paletteMode()` 变成 true，但相机随即在 basic tone 初始化时
**原生崩溃**，实机不可用。原因在 X8U 自己的原生 LUT 合成器，不在 Java 层。详见下面「1.3.1/1.3.2」段。

## 本次变更（1.3.2 · PKJ110 调色盘开关撤回）

- **结论：X10 调色盘不能在 Java 层开启**。1.3.1 的钩子（hook `CameraConfig.d`，只回答
  `com.oplus.feature.color.palette.support`）实测生效——设备日志
  `X10Filter: palette switch answered com.oplus.feature.color.palette.support = true`，
  `Lut3dDataManager.f("common")` 随之返回 true。**但相机随后必崩**：
  `SIGSEGV`/`SIGBUS`，`libCombineLutJni.so Java_..._processV3` 读野指针，进程反复重启。
- **决定性证据（dex 级）**：`Lut3dDataManager.e(scene)` 里
  `v13 = f(scene)`（0x0064），`if-eqz v13, 0x0163`（0x0126）——**调色盘关闭走 0x0163 那条安全分支**
  （只会调 `process(IIII[I)`），开启才落到 `0x015c: CombineLutHelper.process(IIIIFFF String[] int[])`。
  tombstone 里的 `Lut3dDataManager.e+696` = 字节偏移 696 = 348 code unit = **0x15C**（该方法总共只有
  545 unit，所以 `+N` 只能是字节）；崩溃现场的
  `OCAM_Lut3dDataManager: initializeBasicToneEnv, combineType: 1, capMode: 2 capModeName common`
  也正好是 `f("common") == true` 的那一次调用。⇒ **走到崩溃点必须调色盘为开，而它变开只因这个钩子。**
- **修复方式：不装这个钩子**。删掉 `local.jiege.hook.x10.PaletteFeatureSwitch`、`ModuleEntry` 的装挂、
  以及符号表里的 `CameraConfig.flag` 指纹；`tools/verify_aura.py` 反过来断言"它不得再被装挂"。
  色卡（`palette/{HDR,SDR}/SC16Lut0..8` 共 18 张 + 4 个 `SimTool_Palette_*.ini`）**仍然随包下发**：
  纯追加、无开关指向它们、可删。
- **要真正做成，缺的是 X8U 的原生件**：`/product/lib64/libCombineLut.so` +
  `/product/lib64/libCombineLutJni.so`（与相机 APK 同分区）。X8U 这份 `processV3` 处理不了调色盘
  组合，而 X9U 的对应件不在这份移植素材里。这与 §4.9 的 `positive_*` 色卡缺口是同一类问题：
  功能开关背后需要设备侧既有实现，不是多一个 flag。
- **为什么不退而求其次**：把 `getSupportFunction("key_support_palette")` 单独改成 true，面板会出现，
  但 `Lut3dDataManager.f` 仍为 false ⇒ 调色盘 LUT 根本不会被合成，选色**毫无效果**。
  面板"显示但无效"比不显示更糟，因此不做。

## 历史（1.3.1 · 已被 1.3.2 推翻）

> **本节结论已被 1.3.2 推翻。** 1.3.1 的代码会让相机原生崩溃，**从未发布**（未推送远端）。
> 保留是为了记录"当时为什么这么想"。

- 根因判断（**这一层是对的**）：X8U 取不到 `com.oplus.feature.color.palette.support`，因为 X8U 的 ODM
  是 OCS「config parse version 1」，`CameraConfigure.getProjectSupportFeatureValue()` 在 v1 下对
  **所有** `com.oplus.feature.*` 键返回 null。
- 但真正的闸门比"取值链"更深两层，1.3.1 首版两处都踩了：
  1. `CameraConfig.y` 里 `oa.k.b ? FeatureApi.getProjectSupportFeatureValue(key) : null` ——
     `oa.k.b` = "盘上有没有 v2 容器 `oplus_camera_feature_config`"，X8U 没有 ⇒ **FeatureApi 从来没被调用过**，
     钩它是死代码；
  2. `CameraConfig.d` **自带 `ConcurrentHashMap` 缓存**，每个键只算一次，之后直接 return ⇒
     `w/b/y` 整条兜底链（含 `CameraUnitUtils.getVendorTagConfig`）**每次进程只跑一次**，
     钩兜底链在实机上"零调用"。落点只能是 `CameraConfig.d` 本身。
- **踩坑记录（务必不要重犯）**：1.3.0 曾用设备自己的 FeatureTable 合成一份 OCS「config parse
  version 2」容器 `oplus_camera_feature_config`，试图从盘上补这个开关。**实测直接把相机锁死**：
  `getProjectSupportFeatureValue()` 在 `parseVersion != 1` 时会
  `PROJECT_FEATURES_READY_CONDITION.block()`，而这个条件只由 `CameraConfigure.initialize()`
  打开、且该 `initialize()` 是"晚"调用的；进程内第一次取特性值发生得更早
  （`MasterPresetDeferredImportProvider.onCreate`，`installContentProviders` 阶段，
  **早于** `Application.onCreate()`）⇒ 有人等、没人唤醒，主线程 ANR。原厂 X8U 永远撞不到，
  因为它在 `!isFeatureConfigureFileExist()` 那一步就 `return null` 了。1.3.2 保持删除该容器、
  设备侧的整目录合并、以及生成它的 `tools/build_feature_config_v2.py`。
- 另一个只值一行字的坑：判断"这台机器是不是 v2"要问 **`getConfigParseVersion()`**，
  不能问 `isFeatureConfigureFileExist()` —— 后者只读缓存字段，在**任何**带 v1 配置文件的机器上
  都为 true（含 X8U），会把钩子在最需要它的地方跳过。
- 1.3.1 附带并保留到 1.3.2 的两条：X8U 只合并 `x10_basictone/` 的调色盘那一半
  （`palette/{HDR,SDR}/SC16Lut0..8` + 4 个 `SimTool_Palette_*.ini`，`lmt/` 不下发）；
  两个机型都**逐文件** `bind_file` 配置，`/odm/etc/camera/config` 不做整目录合并。

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
  （叠加容器后相机主线程 ANR，见上「踩坑记录」）；**1.3.1 的调色盘开关被实机证伪并已回滚**
  （开关生效即原生崩溃，见上「1.3.2」段），撤回后重装实测 **0 次原生崩溃**。
- 待验证 / 已知限制：X8U 上的移轴、滑杆与前置成片尚未逐项走查；**X8U 的 X10 调色盘目前不可用**
  ——Java 层能开、但 X8U 自己的原生 LUT 合成器处理不了调色盘组合，缺的是 `/product/lib64` 那两个
  原生件（X9U 的对应件不在移植素材里）。把面板单独显示出来是可以的，但选色不会有任何效果，
  故不做。X8U 的 ISP GR 调谐与 gamma 表（厂商调优工具才能生成）不在此次范围内；Pro video
  ISZ 故意未在 X8U 上启用。完整的未决清单见 `port-x8u/README.md` 第 7 节。
