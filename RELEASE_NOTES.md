# OmniCam Aura 1.3.5

无界面相机模块。1.3.5 把**调色盘在 PKJ110 上真正做完**：预览与成片两侧都生效。

事情分两步。1.3.4 修好了**预览**侧（缺的 `[PaletteSCLut]` ini），但同一份操作里按快门成片
**不带色**。1.3.5 查明原因：预览和成片在 X8U 上用的是**两个完全不同的库** —— 预览走相机 APK
自带的 `libCombineLut.so`，成片走 ODM 的 `libBasicTonePhoto.so`，而后者**一个 palette 字串都没有**。
补法是把 X9U 的那个后端按原厂文件名 bind 上去；同时修掉一个被这次换库顶出来的 native hook 缺陷
（重复 hook 导致 `pc=0` 崩溃），并把调色盘开关正式下发给 PKJ110。

## 本次变更（1.3.5 · 成片侧调色盘 + 修掉换库顶出来的 GR hook 崩溃）

> 承接 1.3.4：那一版让**预览**里的调色盘活了，但成片仍然不变色。本节是成片侧的收口，
> 完整推理与取证见 `port-x8u/README.md` §4.14。

### 一、成片侧为什么不变色：两条实现，各管一半

- **预览**：`/my_product/lib64/libCombineLut.so`（**相机 APK 自带**）——
  有 `SCLutMallocPalette` / `PaletteSCLut` / `m_iPaletteFlag`，读
  `SimTool_Preview_Palette_Normal.ini`。这就是 1.3.4 修好之后预览生效的原因。
- **成片**：`/odm/lib64/libBasicTonePhoto.so`（**ODM 固件**）——**全库 0 个 `Palette` 字串**，
  只认 `SimTool_Photo_{Normal,Master}.ini` / `SimTool_Dolby.ini`。
- 由此还说明：1.3.4 摆进 `basictone/setting/` 的三个 `SimTool_Palette_Photo_*.ini` 是**死载荷**
  ——那些名字只出现在 X9U 后端里，X8U 原厂库永远不会打开它们。
- 「有一点区别但很不明显」来自 `libAlgoInterface.so` 的降级分支
  （`finalizePaletteFilterInfoBasicToneOnly`），不是调色盘真的生效。meishe 路也断：
  两台的 `libmsnativefilter.so` 都没有 palette 字串。
- 另：`libAlgoInterface.so`（X8U 原厂）里 palette 能力**其实是齐的**
  （`ipuColorPaletteProcess` / `mapCapModeToPaletteCamMode` / `palette_routing` …），
  缺的只有 basic-tone 那一步的实现。

### 二、补法：只换 `libBasicTonePhoto.so` 这一个库

换之前先把两个库对开，确认它**是**可替换的（而不是像上次那样黑屏）：

- `DT_NEEDED` 完全一致（`libsharebuffer/liblog/libGLESv3/libEGL/libdl/libc/libm`），无 X9U 特有依赖。
- 两库导出**同样 39 个未修饰 C 符号**，入口是 `getAlgoAPI` + `OGLBasicToneProcess`。
- `libAlgoInterface.so` 全文只有 1 处 `libBasicTonePhoto` 字串、且**没有任何 `BasicTone*` C++ 符号**
  ⇒ 它是 `dlopen` 按名加载，库内部的签名变化（`BasicToneRender::init` 多一个 bool、
  `loadLut` 多两个参数）碰不到调用方。
- X10 新增的正是缺的能力：`BasicTone::PaletteSCLutMalloc()` / `PaletteSCLutFrameMalloc()` 等。

`module/post-mount.sh` 的 PKJ110 分支因此从 `no library replacement` 改为
**只 bind 这一个库**（先 `umount` 掉可能的陈旧 bind）。`libAlgoInterface.so` 与
`libmsnativefilter.so` **保持原厂不动** —— 当年黑屏的是 X9U 一整对，本次不涉及。

### 三、顺带修掉一个被顶出来的 native 崩溃

换库后第一次打开相机崩了一次（`signal 11`，`pc=0`，线程 `RefFrameProc`）：

```
#00 pc 0  <unknown>
#01 libaura_native.so +0x22b94
#02 libBasicTonePhoto.so (OGLBasicToneProcess+36)
#03 libBasicTonePhoto.so (basictoneOcce::ThreadWorker::run+240)
```

根因是 `gr_basictone.cpp` 的 `hookBasicTone()` 用
`if (originalProcessCore && originalLoadLut) return;` 做一次性守卫，而 X10 后端把经典
`loadLut(CamMode)` 改了名 ⇒ `dlsym` 失败 ⇒ `originalLoadLut` 永远是 NULL ⇒ **守卫永远不生效**
⇒ 每次 dlopen 都重复 hook 同一个已打补丁的 `processCore`；第二次 hook 失败时 LSPosed 会清空
backup，于是 `originalProcessCore` 成了 NULL，下一次调用即 `pc=0`。
X8U 原厂库撞不到（经典 `loadLut` 在，守卫生效）。

修法：改成独立的一次性标志 `basicToneHooked`；`loadLut` 只在经典符号存在时才挂；
两个 shim 各加 `if (!originalXxx) return 0;` 兜底。复测连续 3 轮启停相机，
`hook_failed=0`、`sigsegv=0`。

### 四、配置侧：补上 `aps_algo_color_palette`

`AlgoSwitchConfig.getSupportCaptureAlgo(mode, cameraId, "aps_algo_color_palette")` 决定成片请求
要不要带 palette 算法，答案**完全取自** `oplus_camera_algo_switch_config`；X8U 原厂表里没有这个键。
现按 X9U 的做法，往 `common` / `portrait` / `aiHighPixel` 的每个 capture entity **纯插入**一行
（文本级 splice，共 5 处；`aiHighPixel` 只有 cam 0）。配置 141,474 → 141,729 B /
`593dbc79…` → `10a2532d…`，生成器 `tools/build_x8u_algo_switch_config.py` 同步扩了插入逻辑与校验。

### 五、开关正式下发

`local.jiege.hook.x10.PaletteFeatureSwitch`（1.3.2 曾因崩溃撤回、1.3.4 查明真因后重测通过）
**正式装回** `ModuleEntry`。反向断言同步反转：`tools/verify_aura.py` 现在断言
**它必须在场**，并新增「PKJ110 必须 bind 上面那个库」的断言。

### 实机验证（2026-10-14 19:23）

照片模式拍照，`libBasicTonePhoto.so` 侧日志：

```
BasicToneProcessNext() iPaletteFlag = 1
BasicTonePaletteProcessNext() Palette mode!
read3DlutSet() PaletteSCSize: 16 / PaletteSCLutNum: 9
SCLutMallocPalette()      reading palette lut .../palette/HDR/SC16Lut4
SCLutFrameMallocPalette() reading palette lut .../palette/HDR/SC16Lut0 … SC16Lut8
fuseSCLut() SC_coeff_0.000000_0.790000
BasicTonePaletteProcessNext() Palette process success!
```

9 张色卡全部加载成功，无 `Failed to open lut file` / `Unkown lut file`；
`SC_coeff` 随色盘滑动变化，用户确认**成片颜色变化明显**。

### 发布闸门

`python tools/verify_port_artifacts.py` 与 `python tools/verify_aura.py` 均 PASS。
同步的两个定值：`post-mount.sh` 9,505 B / `2512f1bb1acd1fcb8f4c798c932a0e89`；
`oplus_camera_algo_switch_config` 141,729 B / `10a2532d483d3c3357eb266f35b3142e`。

## 历史（1.3.4 · 调色盘崩机：`[PaletteSCLut]` 那个 ini 从来没被合并过）

> **本节三条结论已被 1.3.5 推翻或作废**（2026-10-14）：①「调色盘开关仍未下发、要真看效果必须
> 重启设备一次」—— 开关已在 1.3.5 正式下发，且**不需要重启手机**（`am force-stop` 重启相机进程
> 就够，实测有效）；②「钩子文件现停在 `.workbuddy/pal/pending/`」—— 已转正回 `app/src`；
> ③「免重启的旁路已查到底、都不通」—— 成片侧缺的从来不是旁路，而是后端库，见 `§4.14`。
> 下面的现场记录（两枚 tombstone、`SC0Lut4` 那条因果链、五个后端件逐字节相同）仍然成立。

> 本节推翻 1.3.2 的两条结论：「X10 调色盘不能在 Java 层开启」与「X8U 自己的原生 LUT 合成器
> 处理不了调色盘组合、缺 `/product/lib64` 那两个原生件」。两条都不成立，详见 `port-x8u/README.md` §4.13。

- **真正的根因（第五个独立根因）**：`module/post-fs-data.sh` 用 `SimTool_Palette_*.ini` 做 glob
  合并 `setting/`，而 `libCombineLut.so` 里写死要打开的**只有一个**文件：
  `SimTool_Preview_Palette_Normal.ini`。它的前缀是 `Preview_` 不是 `Palette_`，**不匹配这个 glob**，
  所以从来没进过 `/odm/etc/camera/basictone/setting/`。
- **缺文件为什么是崩机，而不是「没效果」**：`read3DlutSet()` 读 `[PaletteSCLut]` 的
  `lut_size` / `SCLutNum`，但 `readIniKeyInt()` **恒返回 0**（`readIniKeyStr()` 打不开就把缓冲留空、
  `atoi("") == 0`），于是 `PaletteSCSize` / `PaletteSCLutNum` 被**静默写成 0**，
  源码里那条 `if (rc) log "read3DlutSet failed"` 是死代码。接着 `SCLutMallocPalette()` 去找
  `palette/HDR/SC0Lut4`，打不开 ⇒ `lutMallocPalette failed!` ⇒ `BasicTonePaletteProcessNext`
  走失败出口 `b 0x2a188`，**跳过**唯一那条 `OutParam[0] = 64`。
- **放大器在 JNI 侧**：`processV3`（`libCombineLutJni.so @0x14d8`）**不检查** `CombineLut_Process()`
  的返回值，直接拿**从未被写过的栈上 `OutParam[0]`** 去算 `new float[3·n³]`：
  `csinv x0, x21, xzr, eq` 在 32 位溢出时把请求改成 `SIZE_MAX` ⇒ 必抛 ⇒ `bad_alloc`；
  不溢出就轮到下一条 `memcpy` 读野指针。两条指令相邻（`0x1644` / `0x1654`），
  正好对上两枚 tombstone：`processV3+364` 的 SIGBUS 与 `processV3+380` 的 SIGSEGV。
- **决定性证据**：崩溃那次（pid 23662，07:36:42.880）的日志是完整的因果链——
  `readIniKeyStr() can't open ini file!` → `PaletteSCSize: 0` →
  `SCLutMallocPalette() reading palette lut …/palette/HDR/SC0Lut4` → `can't open SC0 file!` →
  `lutMallocPalette failed!`；而全日志里 `Process failed!` 出现 **0 次**
  （`BasicToneProcessNext` 返回的是"成功"，`processV3` 更无从察觉）。
- **推翻「缺 X9U 原生件」**：X8U 实机与 X9U 固件的五个后端件**逐字节相同**——
  `libCombineLut.so`（429,512 B / `34001cde1397d3f3e4f5a2420fef45da`）、
  `libCombineLutJni.so`（9,928 B / `6a1d3134fe3d697bd9981b501f58c369`）、
  `libPalette-Jni.so`（370,440 B / `ec315c5027ff78d23fb75cd4fb8ca2d6`）、
  `com.oplus.camera.unit.sdk.jar`（5,319,483 B / `f705b12945cae6b7aab6ca6115099f28`）、
  `com.oplus.camera.unit.sdk.adapter.jar`（2,939,239 B / `7a225bfbe824af118312ee67102f8c7d`）。
  唯一真正的差异是相机 APK，而且是 **X8U 更新**（7.006.123 vs 7.006.119）。
- **顺带再证 1.3.1 的钩子本身没问题**：崩溃现场 `reserved[5]: 1`、`reserved[6]: 1`、
  `iPaletteFlag = 1` 都正常——钩子把开关喂进去了，坏的是它下游的数据。
- **修复**：`module/post-fs-data.sh` 的 PKJ110 palette 分支把 glob 改成**具名拷贝**
  （glob 那行留着，另加一行具名 `…/setting/SimTool_Preview_Palette_Normal.ini`），
  并记一条 `palette extra: N cards, M inis` 日志。脚本 16,430 B /
  md5 `42e919f6c420d000d4b6099066e8755d`。
- **补上闸门缺口**：`tools/verify_aura.py` 原先只断言 payload **里有**这个 ini，
  没断言**合并脚本会拷它**——这正是它溜过去的原因；现加正向断言。
  `tools/verify_port_artifacts.py` 的 `MODULE_SCRIPTS["post-fs-data.sh"]` 定值同步。
  `python tools/verify_port_artifacts.py` 与 `python tools/verify_aura.py` 均 PASS。
- **⚠️ 本次对设备行为零变化**：调色盘开关（`local.jiege.hook.x10.PaletteFeatureSwitch`）**仍未下发**，
  所以 `iPaletteFlag` 仍是 0、palette 分支根本不进。**要真看到效果必须两件事一起做**：
  (1) 把 `symbols.json` 的 `CameraConfig.flag` 指纹与那个钩子加回来（钩子文件现停在
  `.workbuddy/pal/pending/`，已从 `app/src` 移出）；(2) **重启设备一次**加载新模块 APK
  ——LSPosed 缓存模块 APK，热替换 `/data/adb/modules/omnicam_aura/*.apk` 实测完全无效。
  重启是本工程红线（README §4.12.6），**必须由人点头**。免重启的旁路已查到底、**都不通**：
  `CameraConfig.d → w → b → y` 的兜底最终落在 vendor tag（V2 容器死锁路），
  既不是可写文件、也不是可设属性。

## 历史（1.3.3 · PKJ110 的 POP 快门修复）

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

> **本节结论已被 1.3.4 推翻。** 真正的根因是模块漏合并 `SimTool_Preview_Palette_Normal.ini`
> （见 §4.13），原生件与 X9U **逐字节相同**，不是「X8U 处理不了调色盘」。保留原文是为了记录
> 当时的判断，以及「没做 A/B md5 就断言缺件」的代价。

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
- **要真正做成，缺的是 X8U 的原生件**（**已被 1.3.4 推翻**，见 `port-x8u/README.md` §4.13.2：
  五个后端件逐字节相同，X8U 跑的就是同一份文件）：`/product/lib64/libCombineLut.so` +
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
  （开关生效即原生崩溃，见上「1.3.2」段），撤回后重装实测 **0 次原生崩溃**；
  **1.3.4 定位到该崩溃的真正原因是模块漏合并 `SimTool_Preview_Palette_Normal.ini`**
  （原是数据缺失，不是能力缺失，见 §4.13），已修并补上发布闸门，实机行为与 1.3.3 相同（开关仍未下发）。
- 待验证 / 已知限制：X8U 上的移轴、滑杆与前置成片尚未逐项走查；**X8U 的 X10 调色盘仍未开启**
  ——[2026-10-14 更新] 此前记的「不可用」原因已查清并修掉（模块漏合并 `SimTool_Preview_Palette_Normal.ini`，
  见 §4.13），**不再是"X8U 原生件处理不了"**：五个后端件与 X9U 逐字节相同。1.3.4 只把数据铺平、
  行为零变化，开关仍未下发；要真开需「重开钩子 + 重启设备一次」，重启是本工程红线，尚未执行。
  X8U 的 ISP GR 调谐与 gamma 表（厂商调优工具才能生成）不在此次范围内；Pro video
  ISZ 故意未在 X8U 上启用。完整的未决清单见 `port-x8u/README.md` 第 7 节。
