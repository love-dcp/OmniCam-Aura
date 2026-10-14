# OmniCam Aura → OPPO Find X8 Ultra (PKJ110 / ColorOS 17 F.21) 移植包

目标机：**PKJ110**，相机 `com.oplus.camera` **7.006.94**，相册 `com.coloros.gallery3d` **17.9.24**。
基准机：PMA110，相机 7.006.77（本仓库原始目标）。

> 上游项目 OmniCam Aura 由 **苯宝宝是个醚**（GitHub: [benbaobaoshigemi](https://github.com/benbaobaoshigemi)）
> 原创并开源；本移植包在其基础上做 PKJ110 适配。

> **当前状态：已随 1.2.0 稳定版发布。补丁已合并进仓库主树，采用「双机型单包」方案。**
> 模块启动时自行判定机型，同一个 APK + 同一个 KSU 包可同时用于 PMA110 与 PKJ110，
> PMA110 的行为与改动前**逐字节一致**（见第 5 节）。
> PKJ110 实机验收（2026-10-13 晚）：相机正常启动并稳定运行；**POP 可用、切过去不再闪退，
> POP 全部滤镜（含此前失效的「浓郁」）均生效**。其余功能项在 X8U 上的逐项走查见第 7 节。

> **合并说明（2026-10-13）**：本目录的工作是基于上游 1.1.0 快照做的，而上游 `main` 在本轮工作
> 期间前进到了 1.1.1。发布时是把本目录的改动**并入**上游 1.1.1，而不是覆盖——上游 1.1.1 的
> 大师预设表修复（`MasterPresetListSync` / `PresetSelection`）、Pro video ISZ 与
> `build.ps1`/`verify_aura.py` 的版本号单一来源全部保留。唯一按机型分流的是 Pro video ISZ：
> 它逐字节匹配 PMA110 的 `CameraHWConfiguration.config`，且从未在 X8U 上验证，故只在 PMA110
> 档案上生效。

---

## 1. 落盘内容一览

| 本目录路径 | 已合并到 | 说明 |
|---|---|---|
| `patches/gr_basictone.cpp` | `app/src/main/cpp/gr_basictone.cpp` | `getSettingInt` 改双机型指纹表 + fail-safe |
| `patches/GrFocalLength.java` | `app/src/main/java/local/jiege/hook/gr/GrFocalLength.java` | 新增 `Zoom.labelBitmaps` 多命中回退 |
| `patches/symbols.json` | `app/src/main/assets/symbols.json` | 190 → 191 条，严格超集 |
| `patches/post-mount.sh` | `module/post-mount.sh` | 改为按机型判定是否 bind 后端库 |
| `patches/post-fs-data.sh` | `module/post-fs-data.sh` | 改为按机型选 payload 与 ISP/gamma |
| `payload/camera_unit_config` | `module/payload_pkj110/camera_unit_config` | ✅ **注入已启用**：按 X8U 原厂数据重新生成，字符串池下标按字符串身份重映射，见 §4.2.1 / §4.4 |
| `payload/camera_unit_feature_config.protobuf` | `module/payload_pkj110/camera_unit_feature_config.protobuf` | ✅ **注入已启用**：同上（曾因池下标越界导致启动即崩，已修） |
| `x8u-repair.sh` | `module/x8u-repair.sh`（刷入后落在模块目录里） | **免重启修复脚本**：取证 + 拆 `/odm/lib64` overlay，见 §3.7 |
| `x8u-config-revert.sh` | `module/x8u-config-revert.sh` | **撤掉配置注入 + 解除「不稳定应用」启动限制**，见 §4.4 |
| `backup-pma110/` | —— | 覆盖前 5 个原文件的完整备份（无 git，用于回退） |

`native_entry.cpp`、`native_hooks.h`、`pop_path_remap.cpp` **不需要改**：
`symbols.json` 210 个指纹里 **209 个在 7.006.94 上原样命中**，第 210 个（`Zoom.labelBitmap`）
已有等价条目（第 5 节）。

> ⚠️ `module/service.sh` **本轮未动**，它**没有机型门控**，会无条件往相机
> `override_config_data.xml` 写 PMA110 的值，其中
> `com.oplus.camera.mode.data.db.version 102` 与
> `com.oplus.camera.capture/preview.hdr.cap.mode.value`（含 `xpan`/`underWater`/`telephoto`/`gr`）
> 是**逐设备**的。
>
> 2026-10-13 更新：相机打不开的真因已查明是**配置基线过旧导致的启动崩溃**（§4.4），
> 不是 `service.sh`。而且相机现在连进程都起不来，`service.sh` 那几行**还没机会被执行到**。
> 因此它的优先级已下调：等相机能正常启动之后再单独处理。见 §7。

## 2. 为什么 `gr_basictone.cpp` 只改一个值

`gr_basictone.cpp` 里只有 **1 个** 跨版本会漂移的裸偏移：未导出的内部函数
`android::getSettingInt`。其余全部按 **符号名** `dlsym` 解析，与地址无关。

其余 5 处结构体硬编码偏移已逐字节核对，两版**完全一致**（详见第 3 步报告）：

| 偏移 | 含义 | 出现位置 | 结论 |
|---|---|---|---|
| `frame + 0x8110` | `AlgoProcessData::settings` 指针 | `getLmtParamsV1` | 两版 `mov w23,#0x8110` + `ldr x0,[x20+x23]` 完全相同 |
| `frame + 0x18` | capture mode | `doFilterProcessV2` | 两版都是 `ldr w8,[x19+0x18]; cmp w8,#0x22; cmp w8,#0x1d` |
| `parameters + 0x1a` | vignette 使能位 | `getVigParamsV1` | 两版 `strb/ldrb w8,[x19+0x1a]` 位置与数量一致 |
| `object + 0x008` | 256 字节 LMT 路径缓冲 | `BasicTone_OGL::loadLut` | 同类结构后续偏移（0xea8/0xeb0/0xee0/0xee4/0xf18）全部不变 |
| `object + 0xeb0` | LUT 缓存键 | `BasicTone_OGL::loadLut` | 两版 `ldr/str w?,[x20+0xeb0]` 三条指令位置一致 |

`getSettingInt` 改成**指纹表**，加载时用函数序言前 16 字节在两张表里选：

```
PMA110 / 7.006.77  @ 0x150cdd0
    paciasp; sub sp,sp,#0x90; stp x29,x28,[sp,#0x60]; stp x21,x20,[sp,#0x50]
PKJ110 / 7.006.94  @ 0x13f46d4
    paciasp; sub sp,sp,#0x90; stp x29,x28,[sp,#0x60]; stp x23,x22,[sp,#0x70]
```

两条序言只差第 4 条 `stp`，前 16 字节足以区分。匹配不到就**不装 hook**（相机保持原样，不会崩），
比猜偏移安全。`onLibraryLoadedForGr` 也补上了 `libBasicTonePhotoX10.so` 分支——X8U 上
`libBasicTonePhoto.so` 本身就是真后端，不会再有路由桩转发。

## 3. 后端库：为什么必须搬出 `system/`（`post-mount.sh` + `payload/lib64/`）

X8U 的 `/odm/lib64` 与 X9U 结构不同：

| 文件 | PMA110（原目标） | PKJ110（X8U 原厂） |
|---|---|---|
| `libBasicTonePhoto.so` | 8,960 B **路由桩**（只导出 `getAlgoAPI`） | 538,400 B **真实后端**（744 个导出，含 `BasicTone_OGL::*`） |
| `libBasicTonePhotoX9.so` | 538,232 B 后端 | **不存在** |
| `libBasicTonePhotoX10.so` | 591,552 B 后端 | **不存在** |
| `libAlgoInterface.so` | 79,298,968 B | 60,867,488 B |
| `libmsnativefilter.so` | 10,957,976 B | 7,992,584 B |

### 3.1 前两版都错了：KernelSU 的 overlay 绕过脚本

第一版补丁把 `post-mount.sh` 里 4 条 `bind_library` 注释掉，第二版把它改成按机型分流——**两次都没用**。
实机（PKJ110 / ColorOS 17 / 相机 7.006.123）复现的故障是：打开相机停在人像界面、预览全黑、界面无响应。
抓现场时 `/proc/mounts` 给出了答案：

```
KSU /odm/lib64 overlay ro,lowerdir=/mnt/vendor/mountify/odm/lib64:/odm/lib64,redirect_dir=on
```

`metamount.sh` 会把模块 `system/` 下的树**无条件**叠加到系统分区上。而 KernelSU 的阶段顺序是：

```
post-fs-data 阶段：通用 .d 脚本 → restorecon/sepolicy → 普通模块 post-fs-data.sh
                  → 加载 system.prop → metamount.sh（挂载所有模块 system/）→ post-mount.sh
```

也就是说：

- **`post-fs-data.sh` 在 overlay 之前跑**（所以它看到的 `/odm` 是原厂视图，这正是 ISP 文件名判定有效的原因）；
- **`post-mount.sh` 在 overlay 之后跑**；
- **`system/` 里的东西两台机器都会被叠加，没有任何脚本能拦。**

只把 `post-mount.sh` 的 bind 关掉，等于关掉了一个本来就没在起作用的东西——**真正下手的是 `system/odm/lib64/`**。

实机 `ls -la /odm/lib64` 的实测结果（都把 mtime 标成开机时刻）：

| 文件 | 设备上实际 | X8U 原厂应为 |
|---|---|---|
| `libAlgoInterface.so` | 79,298,968 | 60,867,488 |
| `libBasicTonePhoto.so` | **8,960（路由桩）** | 538,400 |
| `libmsnativefilter.so` | 10,957,976 | 7,992,584 |
| `libBasicTonePhotoX9/X10.so` | **凭空出现** | 不存在 |

### 3.2 黑屏的完整因果链

```
X8U 的 libAlgoInterface.so
   └─ 按名字加载 libBasicTonePhoto.so
        └─ 拿到的是 8 KB 路由桩（X9U 的，被 overlay 顶掉了真后端）
             └─ 桩内 dlopen("/odm/lib64/libBasicTonePhotoX10.so")
                  └─ 命中了同一份 overlay 刚创建的 X9U X10 后端
                       └─ X9U 引擎按 X9U 传感器跑在 X8U 上 → 预览管线停在那里
                            └─ 相机停在人像界面、预览黑、UI 无响应（主线程被阻塞，无 Java 异常）
```

关键核实：X8U 原厂 `libBasicTonePhoto.so`（538,400 B）对 `libBasicTonePhotoX9/X10` 字符串引用为 **0**；
X8U 原厂 `libAlgoInterface.so` 对 `libBasicTonePhoto.so` 引用 **1 次**。前者证明 X9/X10 两个文件在 X8U 上
是**惰性文件**（没有任何东西会去加载它们），后者证明这条加载链真实存在。

### 3.3 现在的布局

`system/` 只留**两台机器上都安全**的东西，凡是设备相关的都搬到 payload、由 `post-mount.sh` 按机型绑：

| 位置 | 内容 | 行为 |
|---|---|---|
| `system/odm/lib64/` | 仅 `libBasicTonePhotoX9.so`、`X10.so` | overlay 创建这两个路径（X9U 原厂没有它们，而 `mount --bind` 要求目标已存在，只有 overlay 能「凭空创建」）。在 X8U 上是惰性文件 |
| `payload/lib64/` | `libBasicTonePhoto.so`、`libAlgoInterface.so`、`libmsnativefilter.so` | 只有 PMA110 在 `post-mount.sh` 里 bind（目标在 X9U 上是原厂文件，一定存在） |

```
profile=PMA110  → bind X9 / X10（overlay 刚创建）+ BasicTonePhoto / AlgoInterface / msnativefilter
                  （从 payload/lib64/）—— 效果与改动前一致
profile=PKJ110  → 一个库都不 bind，也不会有任何库被 overlay 覆盖
```

`tools/verify_aura.py` 里加了硬断言：那三个 X9U 库**不允许**出现在 `system/` 下，出现就构建失败——
防止以后有人顺手把它们放回去。

**机型判定**（两个脚本里同一段 `detect_profile()`，**必须保持完全一致**）：
按 **model 系属性优先**依次读，命中 `*PKJ110*` 或 `*PMA110*` 即返回：

```sh
ro.product.vendor.model ro.product.vendor.name
ro.product.model        ro.product.name
ro.product.odm.model    ro.product.odm.name
ro.product.bootimage.model
ro.build.product
ro.build.display.id
ro.oplus.version.my_manifest
ro.product.vendor.device ro.product.device ro.product.odm.device
ro.product.odm.vendor.device ro.vendor.oplus.device      # 兜底，实测在 PKJ110 上为空
```

都不命中时：

1. 看 ISP 调谐文件名：有 `com.qti.tuned.lighthousemain.bin` → PMA110，有
   `com.qti.tuned.zfmain.bin` → PKJ110；
2. 仍不满足 → 保守取 PKJ110（不换库只会少功能，挂错库会让整个相机挂掉）。

> **坑（已修）**：**机型名不在 `ro.product.device`**。实测 PKJ110 上 `ro.product.device=OP5DD3L1`，
> `ro.product.odm.vendor.device` 与 `ro.vendor.oplus.device` **均为空**，`ro.product.model=PKJ110`、
> `ro.build.display.id=PKJ110_15.0.1.502(CN01)`。第一版按 device 优先探测 → 全部落空 → 侥幸靠
> ISP 文件名兜底判对。改 model 优先后，`test_profile.sh` 14/14 通过，日志显示
> `detect: ro.product.vendor.model=PKJ110` **命中**（而非兜底）。
>
> 第二个坑是**可观测性**：`detect_profile` 跑在 `$( )` 子 shell 里，`log()` 若写成 shell 变量，
> 变量传不回父 shell，"命中"和"兜底"输出**长得一模一样**，7/7 全绿却没有信息量。必须写成
> `log(){ echo "$1" >> "$LOGFILE"; }` 才拿得到真信号。

> **坑（已规避）**：不能用 `[ -f /odm/lib64/libBasicTonePhotoX9.so ]` 判机型。这个文件**本身就在模块里**，
> overlay 在本阶段之前已经把它挂进 `/odm`，所以在 X8U 上它也「存在」。ISP 调谐文件是设备独有、
> 模块不带，才是可靠依据。

原 `post-mount.sh` 用 `set -eu` + 无条件 `test -f`，遇到 X8U 没有的 `libBasicTonePhotoX9.so` 会
**整脚本中止**；新版改成「缺就跳过并记日志」。

### 3.4 顺带修掉的第二处越界

`post-fs-data.sh` 里原来把 `payload/x10_basictone/`（X10 调色盘的 `lmt/`、`palette/`、4 个
`SimTool_Palette_*.ini`）**无条件** merge 进 `/odm/etc/camera/basictone`。这些是**逐设备调谐表**，
由 BasicTone/AlgoInterface 直接读取，性质与后端库同类，现在按机型门控。GR 自己的
`setting_Retro/` 仍然照常 merge。

1.3.0 起 X8U 侧改为**只** merge `palette/{HDR,SDR}/SC16Lut0..8` 与 4 个
`SimTool_Palette_*.ini`（调色盘色卡本身），`lmt/{LMTPhotoLut*,SCLut*}` 仍然不 merge —— 详见
§4.10。

### 3.5 刷入后怎么确认真的生效

```bash
adb shell ls -la /odm/lib64/libBasicTonePhoto.so /odm/lib64/libAlgoInterface.so /odm/lib64/libmsnativefilter.so
```

必须是 X8U 自己的尺寸：

| 文件 | 生效后应为 | 故障时应为 |
|---|---|---|
| `libBasicTonePhoto.so` | **538400** | 8960 |
| `libAlgoInterface.so` | **60867488** | 79298968 |
| `libmsnativefilter.so` | **7992584** | 10957976 |

只要 `libBasicTonePhoto.so` 不是 538400，就说明 overlay 还在覆盖（包没换成新版、或没重启）。

**为什么换包之后必须重启**：KernelSU 刷入模块会**热更新** `/data/adb/modules/<id>/` 下的文件内容
（所以设备上 `camera_unit_config` 的 mtime 会变成刷入那一刻），但**已建立的挂载不会随文件内容变化
而更新**：`mount --bind` 与 overlayfs 都按 inode 引用（判据是 `mountinfo` 里源路径的 `//deleted`
后缀）。**本机只能软重启，而软重启会重跑模块脚本、却不会重建 `metamount` 的 overlay**——
详见 §3.6，那里还有让修复"免硬重启"生效的做法。两个不需要 root 的判据：

```bash
adb shell cat /proc/uptime        # 第一个数 = 已开机秒数；小于刷包时间 = 刷完没重启
adb shell ls -la /odm/lib64/      # 被 overlay 的文件 mtime = 刷入时刻；原厂文件 = 固件构建时间
```

实测 X8U：`libAlgoProcess.so` 等未被覆盖的原厂库是 `2026-10-07 23:15`，
而 `libBasicTonePhoto.so` / `libBasicTonePhotoX9.so` / `X10.so` / `libAlgoInterface.so` 都是
刷入那一刻的时间戳——**时间戳分成两拨，本身就证明 overlay 只覆盖了它该覆盖的那几个**。

**bind / overlay 会"冻结"在建立时刻的 inode 上，这是比 overlay 更强的证据。** 配置文件不是
overlay 而是 `mount --bind` 进去的，`/proc/self/mountinfo` 直接暴露源路径。实测（刷了 3 次新包、
始终没重启）：

```
/adb/modules/omnicam_aura/payload_pkj110/camera_unit_config//deleted
/adb/modules/omnicam_aura/payload_pkj110/camera_unit_feature_config.protobuf//deleted
/adb/modules/omnicam_aura/merged/meishe_lut//deleted
/adb/modules/omnicam_aura/merged/basictone//deleted
```

`//deleted` = bind 源已被 KSU 的原地更新删掉，mount 仍握着旧 inode。于是：

```bash
adb pull /odm/etc/camera/config/camera_unit_config /tmp/x   # → 32,453 B（旧丢键版），不是 29,803 B
adb pull /odm/etc/camera/config/camera_unit_feature_config.protobuf /tmp/y
md5sum /tmp/y                                               # → 2d89d2a7…（旧的末尾追加版），不是 02fe7031…
```

也就是说，**"刷入后尺寸/内容没变"跟修复有没有生效无关**——修复的产物确实进了
`/data/adb/modules/omnicam_aura/`（KSU 热更新的 mtime 可见），只是 mount 不会重新读取它。

判据汇总（都不需要 root）：

```bash
adb shell cat /proc/uptime                       # 首数 < 刷包时刻的秒数 → 刷完没重启
adb shell cat /proc/self/mountinfo | grep odm    # 出现 //deleted → bind 源已被更新删掉
adb pull /odm/etc/camera/config/camera_unit_config /tmp/x && ls -l /tmp/x   # 32,453 = 旧版
```

模块日志（需 root）：

```bash
adb shell su -c 'cat /data/adb/modules/omnicam_aura/mount.log'   # 应含 begin profile=PKJ110
adb shell su -c 'cat /data/adb/modules/omnicam_aura/bind.log'    # 应含 config=.../payload_pkj110
```

**代价（必须知道）**：依赖 X9/X10 **后端库**的功能在 X8U 上拿不到 —— X8U 跑的是自己的
`libBasicTonePhoto.so` / `libAlgoInterface.so` / `libmsnativefilter.so`，模块不替换它们。
GR 模式、复古 POP、移轴、水印、高像素、调色盘这些不依赖后端库替换的功能不受影响（调色盘见 §4.10）。

### 3.6 本机 root 是临时的：只能软重启，overlay 不会重建（关键约束）

**实测设备状态**（`/data/local/tmp` 取证）：

| 物证 | 含义 |
|---|---|
| `com.pkj110.rootapp` v0.15.0 | 临时 root 工具，CVE-64560 hack，`uid 0 + SELinux permissive` |
| `kernelsu.ko` + `ksud` | **KernelSU 是 LKM 模式**，由工具 insmod 加载，不是内核内置 |
| `pkj110su --daemon`（`rootinit.sh`） | 驻留的 root daemon，日志原话「已就绪的临时 root daemon 不受影响」 |

所以：**硬重启 = 掉 root**（内核重启，LKM 和 daemon 都没了）；**软重启 = 用户态重启，root 与内核挂载全部保留**。
用户的每一次"重启"都是软重启。

这带来一个**必须写进代码的后果**——软重启会重跑模块脚本，但**不会重建挂载**：

| 机制 | 由谁建立 | 软重启后 |
|---|---|---|
| 模块脚本 `post-fs-data.sh` / `post-mount.sh` | KernelSU 调 `ksud` 执行 | **会重跑** ✅ |
| `/odm/lib64` 的 overlay（X9U 后端库） | `metamount.sh`（KernelSU 内核侧的阶段） | **不重建，冻结在首次内容** ❌ |
| `/odm/etc/camera/*` 的 `mount --bind` | 模块脚本自己 | 脚本重跑就可能重建 ✅（但旧 bind 要显式 umount，见下） |

实测证据（两次抓 `mountinfo` 对比挂载 ID）：

| 挂载点 | 14:48 | 14:59 | 结论 |
|---|---|---|---|
| `meishe_lut` | id=269，源 `…/modules/omnicam_aura/merged/…//deleted` | id=269，源 **`/data/adb/omnicam_aura/merged/…`** | 脚本重跑并重建 ✅ |
| `basictone` | 空目录 | `lmt odt setting setting_Retro vig` | 重建 ✅ |
| `config/camera_unit_config` | id=**266**，源 `…//deleted` | id=**266**，源 `…//deleted` | **没重建** ❌ |
| `/odm/lib64` | 2 个堆叠 overlay | 2 个堆叠 overlay，`lowerdir=/mnt/vendor/mountify/odm/lib64:/odm/lib64` | **没重建** ❌ |

`/mnt/vendor/mountify/odm/lib64` 是 metamount 的工作区，里面**仍然是为 X9U 准备的三个后端库**。

**因此 `post-mount.sh` 在 PKJ110 分支上做了两件事**（这是让修复真正生效的关键，见脚本内注释）：

1. `umount -l /odm/lib64` 循环，直到没有 overlay —— `/odm/lib64` 立刻回落到真实的 erofs，
   也就是 X8U 原厂后端；
2. `rm -f /mnt/vendor/mountify/odm/lib64/{libBasicTonePhoto,libAlgoInterface,libmsnativefilter}.so`
   —— 这样即使下一次 metamount 重建 overlay，工作区里也没有 X9U 库可盖。

`bind_file` 同时补了 **umount-first**：KernelSU 重装模块会删除 `/data/adb/modules/<id>` 下的
inode，旧的 `mount --bind` 会继续服务那个死 inode（`mountinfo` 显示 `//deleted`），相机就会一直读旧
文件。先 `umount` 再 `mount --bind`，才能保证读到当次刷入的版本。

### 3.7 免重启修复脚本 `x8u-repair.sh`

如果不想刷机、只想立刻把设备拉到正确状态，用 root shell 跑：

```bash
adb push port-x8u/x8u-repair.sh /data/local/tmp/
# 然后在临时 root 的 shell 里：
sh /data/local/tmp/x8u-repair.sh
```

它会：**A** 打印取证（`bind.log` / `mount.log` / 模块 `payload_pkj110` 列表 / 挂载现状）→
**B** 拆掉 `/odm/lib64` 的 overlay（期望尺寸回到 538400 / 60867488 / 7992584）→
**C** 备份并清理工作区里的 X9U 库 → **D** 撤掉两个配置文件的旧 bind 并**导出原厂配置**
（**不再重绑**，理由见 §4.4）→ **E** `am force-stop com.oplus.camera`。

> 2026-10-13 起，配置相关的操作请改用 `x8u-config-revert.sh`（§4.4）——它除了撤 bind，
> 还会尝试解除 ColorOS 的「不稳定应用」启动拦截。`x8u-repair.sh` 只负责 `/odm/lib64` 那部分。

脚本幂等，重复跑无副作用。刷入新版模块后它也会出现在
`/data/adb/modules/omnicam_aura/x8u-repair.sh`。

**只读模式**：`sh /data/local/tmp/x8u-repair.sh check` 只跑 A 段取证并退出，
一个字节都不写。不放心时先跑它。

**必须以 root 身份执行**（脚本开头有 uid 自检）：非 root 跑 `repair` 会打印警告并
**直接中止**（`exit 1`）。原因：`/data/adb` 对 shell 用户是 `Permission denied`，
`mount`/`umount` 也需要 root —— 以普通身份跑会得到满屏 `Permission denied`，等于白跑。
（`check` 模式在非 root 下允许继续，但 `/data/adb` 那几段会是空的。）

**安全边界（本机是临时 root，用户明确要求"不能动分区"）**：本脚本只做两类操作 ——
`mount`/`umount`/`mount --bind`（内核挂载表，重启即消失）与 `/data` 上的普通文件
`mkdir`/`cp`/`rm`。**没有任何一条语句以写方式打开 `/dev/block/*`，没有 `dd`/`flash`/`mkfs`，
从不 `remount rw`，不碰 `boot`/`super`/`vbmeta`/分区表，不 `setenforce`、不 `insmod`。**
因此与「变砖」之间没有通路，最坏情况只是相机进程崩一次。

额外的**硬保险**：每次 `umount` 之前先读 `/proc/self/mountinfo` 确认该挂载点的**顶层**
确实是我们注入的（`/odm/lib64` 必须 `fstype=overlay`；配置文件的 `root` 必须指向
`omnicam_aura`/`payload_pkj110`）。对不上就跳过、绝不 `umount` —— 连"误拆原厂分区挂载"
的可能性也排除了。

### 3.8 ColorOS 的「不稳定应用」启动拦截（**症状**：进程被拒于创建之前）

> 因果提示：这条拦截是**果**，崩是因。相机先崩（§4.4 的池下标越界），才被 ColorOS 记进名单。
> 所以解除拦截只是让相机"能重新启动"，**不解决崩溃**；两者都要处理。

**症状**：刷模块后相机从"能进但黑屏"变成"完全打不开"——点图标无任何反应，进程不创建。

**真因**：国行 ColorOS 17 有一条国际版没有的启动拦截路径
`OplusStartupStrategy.isAppClassifyRestricted()`（国际版因 `isExpVersionExt()` 直接返回 false）。
相机被记进「不稳定应用」名单后，AMS 在**创建进程之前**就拒绝启动：

```
W/OplusAppStartupManager: prevent start by classifyResrict com.oplus.camera,
    cmp com.oplus.camera.Camera by activity  callingUid 2000
D/OplusAppStartupMonitor: notifyUnstableAppInfo: Bundle[{unstable_record_type=prevent,
    unstableTime=..., reason=Activity, packageName=com.oplus.camera,
    unstable_restrict_switch=true}]
W/Battery: StartupManager: onUpdateUnstablePreventRecord:  prevent record :com.oplus.camera
```

**实测证据（2026-10-13）**：

| 实验 | 结果 |
|---|---|
| 显式 `-n com.oplus.camera/.Camera` | 拦 |
| **LAUNCHER 意图**（= 用户点图标的同一个 intent） | **拦** |
| `STILL_IMAGE_CAMERA` / `IMAGE_CAPTURE` | 拦 |
| `com.android.settings` / `com.coloros.alarmclock` / `com.coloros.gallery3d` | **不拦（0 次）** |
| 全设备被拦包名去重 | **只有 `com.oplus.camera`** |
| `pm enable`（清 stopped=true） | 无效 |
| `settings delete secure blocked_remind_pkg_name_record` | 无效 |

**记录存放位置**：`com.oplus.battery`（进程 `com.oplus.athena`）里的
`com.oplus.startupapp.data.StartupProvider`（authority **`com.oplus.startup.provider`**）。
从 `Battery.apk`（`/system_ext/app/Battery`，v1.3.7）dex 中提取到的表名与键名：

```
表：UnstableRecord / Record / AppToShow / MaliciousRecord / MaliciousDetailRecord
键：frequent_unstable_count、last_unstable_time、unstable_restrict_switch、
    startup_prevent_record、sys_app_prevent_start_record、startup_strategy_restrict
解除接口：derestrictUnstableApp
UI：com.oplus.startupapp.view.PreventRecordActivity / StartupAppListActivity
    （需要 oplus.permission.OPLUS_COMPONENT_SAFE，adb shell 调不起来）
```

**ROM 静态限制表**（只读分区，随固件发，本身没变过）：
`/my_region/etc/startup/sys_startup_v3_config_list.xml`（168 KB，525 条）、
`sys_startupmanager_config_list.xml`、`/my_stock/etc/startup/sys_startup_policy_config.xml`。
相机在其中是 `type="1" mask="748"` / `type="2" mask="732"`（比常见值 `716` 还**多**许可位），
说明它并非被 ROM 表单独拉黑——**被拦的是运行时记录**。

**两条解除路径**：
1. **UI（零风险，优先）**：设置 → 电池 → 应用启动管理 → 找到「相机」→ 允许；
   或该页的「启动记录 / 被阻止的应用」里点「解除」。
2. **root 脚本** `x8u-camera-unblock.sh`：默认只读探查并导出 `com.oplus.battery` 的数据库
   （设备无 `sqlite3`/`busybox`，改库走"拉出→本地改→推回"）；`fix` 模式先整目录备份再
   `pm clear com.oplus.battery` 并复测。

**注意**：这四类拦截与本模块的挂载行为无关——模块只做 `/odm` 的 overlay 拆除与配置 bind，
不写任何 ColorOS 启动管理数据。相机此前反复崩溃，是它被系统判为"不稳定"的诱因（§4.4）。

## 4. 配置文件的适配（`post-fs-data.sh` + `payload_pkj110/`）

OmniCam 原 payload 的 `camera_unit_config` 其实就是「**X9U 原厂配置 + GR 条目**」。直接搬到 X8U
会把 X9U 的传感器能力表塞进去（例如 `com.oplus.sat.wide.support.imagesizes` 是 `4080x3064...`
而 X8U 是 `4096x3072...`、X9U 独有的 `telephoto_*` / `sat_*` / 3DOL 用例）。

所以 X8U 的两个配置文件是**用 X8U 原厂文件重做的**，放在独立的 `module/payload_pkj110/`：

1. `camera_unit_config`：以 X8U 原厂 JSON 为底，只移植这些条目

   | 位置 | 追加内容 |
   |---|---|
   | `mode_type_list[rear_wide / rear_main / rear_tele / rear_ultra_tele / rear_sat]` | `gr_mode`, `retro_camera_mode`, `tilt_shift_mode` |
   | `mode_type_list[front_main]` | `retro_camera_mode` |
   | `mode_operation_mode` | `gr_mode="8009"`（插在 `professional_mode` 后）, `retro_camera_mode="8001"`（末尾） |
   | `usecase_info` | `gr_mode_case`, `gr_mode_hq_raw_case`, `retro_camera_case` |
   | `capture_stream_number` | `gr_mode`, `retro_camera_mode`, `high_pixel_mode`, `tilt_shift_mode` |

   X8U 原厂**已经**有 `tilt_shift_mode`（`mode_operation_mode`），无需新增。

2. `camera_unit_feature_config.protobuf`：X8U 原厂一个 GR/retro 条目都没有（字符串搜索
   `gr_mode`/`retro`/`tilt_shift` 命中 0）。产物 = X8U 原厂字节流，在**顶层 field2 内部末尾**
   插入从 X9U payload 原样搬来的 3 个 `map<string,Msg>` 条目（`gr_mode`、`retro_camera_mode`、
   `tilt_shift_mode`）——**不是**追加到文件末尾，原因见 §4.2。

**按机型选目录**：`post-fs-data.sh` 里

```sh
PROFILE=$(detect_profile)
if [ "$PROFILE" = PMA110 ]; then CONFIG_SRC=$MODDIR/payload
else                            CONFIG_SRC=$MODDIR/payload_pkj110; fi
...
# 两个机型走同一条路径：逐文件 bind 这两份配置，配置目录不做任何合并
for NAME in camera_unit_config camera_unit_feature_config.protobuf; do
    bind_file "$CONFIG_SRC/$NAME" "/odm/etc/camera/config/$NAME"
done
```

两个机型都**逐文件** `bind_file` 上表那两项，`/odm/etc/camera/config` **不做整目录合并**。
1.3.0 曾一度把 PKJ110 的该目录整目录合并，为的是携带一份合成出来的 OCS「config parse version 2」
容器（`oplus_camera_feature_config`）—— 那是**错的方向**：实测证明这份容器会让相机在**进程内第一次
取特性值时死锁**（ANR，主线程卡在 `CameraConfigure.getProjectSupportFeatureValue` 的
`ConditionVariable.block()`，见 §4.10.5）。1.3.2 保持删除它、设备侧的 bind、以及生成它的
`tools/build_feature_config_v2.py`；调色盘开关**不再从任何地方补**——1.3.1 试过在 Java 侧回答
`CameraConfig.d`，开关确实生效但相机随即原生崩溃，已撤回，见 §4.10.11/§4.10.12。
两条路径都**不覆盖** `payload/` 里 PMA110 需要的那一套，也不动 PMA110 的原厂字节。

**X8U 上不绑定**的项及原因：

| 项 | 原因 |
|---|---|
| `oplus_camera_config` / `algo_switch_config` / `aps_config` / `*_decision*.json` | X8U 盘上是**加密二进制**（魔数 `010159fc` / `0101b4cf`），本工程无解密能力，生成不出编辑版 |
| `com.qti.tuned.lighthouse*.bin`（ISP） | X8U 是 `com.qti.tuned.zf{main,ultrawide,tele,ultratele,front}.bin`，跨传感器调谐会毁图 |
| `gamma_*_conf.json` | 三个**文件名相同但内容不同**（X9U 94,132 / 130,358 / 130,358 B vs X8U 72,699 / 120,463 / 131,135 B），是逐传感器 tone 曲线，采样值都不一样 |

**两个机型都下发**的硬件无关资源：`payload/gr_adjustment_lmt/`（CWCM/SC 3D LUT）、
`payload/meishe_lut/`、水印资源。它们通过 `bind_merged_dir` 叠加在设备的 live 目录之上，
且带「live 目录不存在就跳过」的保护。

> `payload/x10_basictone/` **不是**这类资源：它是 X10 调色盘的逐设备调谐表（`lmt/{LMTPhotoLut*,SCLut*}`、
> `palette/{HDR,SDR}`、4 个 `SimTool_Palette_*.ini`），会被 BasicTone/AlgoInterface 直接读取。
> 因此它按文件类型拆开：`lmt/` **只在 PMA110 merge**（见 §3.4），`palette/` 与 4 个
> `SimTool_Palette_*.ini` 自 1.3.0 起两个机型都 merge（X8U 原厂目录里这些名字**完全不存在**，
> 属于纯追加，撤销只需去掉 bind —— 见 §4.10）。
> GR 自己的 `payload/setting_Retro/` 两个机型都下发。

### 4.1 `camera_unit_config` 必须做「文本级插入」，不能重新序列化

原厂 `camera_unit_config` 在 `mode_type_feature_support` 对象里把
`"mode_type_feature_support_preview_size"` 写了**两次**：一份 `video_mode`、一份 `master_video_mode`。

第一版移植包用 `json.load(..., object_pairs_hook=OrderedDict)` 读进来、`json.dump(indent=2)` 写出去。
**OrderedDict 会把重复键折叠**，于是 `video_mode` 那一整块 preview size 表被永久删除，
产物只剩 `master_video_mode` 一份（32,453 B）。这类"少了一张表"的配置缺陷正是预览管线出问题的候选原因，
而且它**在每台 X8U 上都会发生**，与是否重启无关。

此外原厂空白本来就不规则（同一份文件里存在 `}}` 连写、成员间空行无统一规律），
没有任何单一格式化规则能逐字节复刻，所以"解析 + 重排"这条路本身就是错的。

现在 `build_x8u_port.py` 走 `jsonpatch.py`：在**原始文本**上按路径定向 splice，
未触及的字节保持原样。产物 **29,803 B，12 处 splice**。

**验证（三样缺一不可）**：

| 检查 | 第一版（坏） | 现在 |
|---|---|---|
| `mode_type_feature_support_preview_size` 出现次数 | 1 | **2** ✅（已写成自检护栏） |
| 与原厂递归 diff | —— | 恰好 31 项（6 个数组变长 + 25 个新增），**零删除 / 零改值 / 零改类型** |
| 长度守恒 | —— | `len(new) == len(old) + Σsplice` |

### 4.2 `camera_unit_feature_config.protobuf`：三个 mode 住在 field2 内部，不是顶层末尾

第一版把三个条目**追加到文件末尾**，位置错了。用 `pb_path.py` 递归定位后真相是：

```
X9U： [顶层] field2 (i319) ── 内部 ──> field1 (i20)  ← gr_mode 就住在这里
```

`camera_unit_feature_config.protobuf` 的真实结构是：

| 顶层 | 内容 |
|---|---|
| `field1` × N | 扁平 name/value 列表（裸 ASCII，如 `"common"` / `"on"` / `"[on,off]"`） |
| `field2` × 1 | **mode 定义表** `map<string,Msg>`，条目形如 `0A <len> 0A <klen> <name> 12 <vlen> <value>` |
| `field3` × 1 | 另一组 mode 定义 |

`gr_mode` / `retro_camera_mode` / `tilt_shift_mode` 住在 **field2 内部**。追加到文件末尾会让它们
变成**顶层 `field1` 的元素**，相机根本不会把它们当 mode 定义读：

| | 顶层 field1 条数 | field2 |
|---|---|---|
| 末尾追加（错） | **299**（296 原厂 + 3 误入） | 30,600 B（没动） |
| 插入 field2 内部（对） | **296**（原厂原样） | **47,692 B**（30,600 + 17,092） |

而且 `field2` 是 length-delimited，插完**必须回填它自己的 varint 长度前缀**——旧注释里
"顶层无长度前缀、不需要回填"的说法是错的（要回填的是 field2，不是顶层）。

X8U field2 恰好到 `under_water_mode` 结束（20 条），X9U 的这三个条目紧随其后（21/22/23 条），
所以插在末尾即对齐同一相对位置。

**但只把条目插对位置还不够——这才是"能装进去、一启动就崩"的第二层。**

### 4.2.1 顶层 `field1` 是**字符串池**，`field2` 用**下标**引用它

上面表格里把 `field1` 描述成"扁平 name/value 列表"，这只说对了一半：它是
`repeated string`，**它的下标就是全局字符串表**。`field2` / `field3` 内部**不写字符串字面量**，
写的是 `field1` 池的**下标**（varint）。一条 feature 记录用池解码后长这样：

| 字段 | 解码出的字符串 | 语义 |
|---|---|---|
| `f1` | `com.oplus.preview.flash.mode` | feature key |
| `f2` | `pref_camera_flashmode_key` | pref key |
| `f3` | `[on,off,torch,auto]` | 可选值表 |
| `f4` | `off` | 默认值 |
| `f5` | `main_menu,other_app,…` | 出现场景 |
| `f6` | `string` | 类型 |

（`f7` 恒为 1，是标志位，**不是**下标。）

X8U 池 **296** 条、X9U 池 **319** 条，两者**从下标 50 起就分叉**。所以原样搬运 X9U 条目是错的：

* 下标 ≥ 296 **直接越界** → `LazyStringArrayList.get(314)` 崩溃（X9U 的 314 正是
  `com.oplus.camera.feature.gr_photo`）；
* 下标 < 296 虽不越界，却指向 X8U 池里**另一个字符串** → 语义全错，比崩溃更隐蔽。

正确做法是**按字符串身份重映射**：把 X9U 下标 `v` 换成 `P9[v]` 在 X8U 池里的下标；若 X8U
池没有该字符串，就**追加到池尾**并用新下标。X8U 原有引用全 `< 296`，追加只延长池、不改既有
下标，因此原厂数据一个字节都不受影响。varint 变宽会改变所有**祖先**消息的长度前缀，所以重映射
必须沿"已知引用路径"递归下降重建、**逐层回填长度**；不能盲目把字符串当消息解析（payload 里大量
字符串恰好能被解析成合法消息）。

产物：`camera_unit_config` 20,261 → **29,803 B**；
`camera_unit_feature_config.protobuf` 44,912 → **62,330 B**（池 296 → **307** 条，
field2 payload 30,596 → 47,732 B，条目 20 → 23）。

三个移植条目各自的重映射量：

| 条目 | value | 重映射 | 其中越界下标 |
|---|---|---|---|
| `gr_mode` | 1,652 B | 574 处 | 2 个（296, 314） |
| `retro_camera_mode` | 7,744 B | 2,587 处 | 1 个（315） |
| `tilt_shift_mode` | 7,633 B | 2,540 处 | 3 个（316, 317, 318） |

池新增 11 条：`com.oplus.camera.feature.gr_photo`、`[0~6]`、`pref_camera_flash_changed_by_filter`、
`[7,8]`、`[9~100]`、`com.oplus.camera.feature.retro_camera`、`com.oplus.camera.feature.video_live_photo`、
`[1~6]`、`com.oplus.camera.feature.tilt.shift`、`com.oplus.camera.feature.ai_composition`、
`key_ai_composition`。

**自检**（`selftest_protobuf`，逐条断言）：

1. 原厂 296 条池前缀逐字节不变，追加后池内容 == 预期
2. 顶层 `field1` 既有条目 `(fno, off, end)` **三元组完全不变**——抓住"误入 field1"的那一枪
3. 顶层 `field3` 内容逐字节不变
4. 原厂 field2 payload 是产物 payload 的**前缀**（逐字节），条目数恰为 `20 + 3`
5. **池下标全部在界内**（`_shape_refs`，16380 处引用，最大下标 306 < 池长 307）
6. 三个条目的 **token 多重集与 X9U 逐一对齐**（574 / 2587 / 2540）

> **踩过的坑**：`_shape_refs` 与 `_rebuild` 用的 shape 是**两套命名空间**——前者从顶层
> `field2` 起算、后者从 entry 的 **value 字段**起算，差一层 `/f2/f1/`。早先的自检用了错的那个，
> 于是绝大多数引用路径匹配不上，`引用总数` 只报 **96**、**"越界 0" 是假象**（只检到了浅层一条）。
> 改成以 value 字段为根后报 16380，才是真的全量检查。`verify_port_payload.py` 是独立重写的
> 只读复核器，不共用构建逻辑，用来交叉验证。

### 4.3 `merged/` 工作目录必须放在 `$MODDIR` 之外

`bind_merged_dir` 的工作方式是"**拷贝 live 目录 + 叠加 extra → bind 回原处**"，所以它需要一个
可写的源目录。第一版把它放在 `$MODDIR/merged/`，这是个真实的脆弱点：

KernelSU 在**原地安装/更新模块时会把整个 `/data/adb/modules/<id>/` 删掉重新解包**。如果
`merged/basictone` 正被 bind 挂在 `/odm/etc/camera/basictone` 上，它的 inode 就被删了——
mount 仍然持有这个死 inode，于是目标目录**读出来是空的**。`/proc/self/mountinfo` 会把这种源
渲染成 `.../merged/basictone//deleted`，这正是判定依据。

对 `/odm/etc/camera/basictone` 来说，"空目录"= BasicTone 的音调表整块消失 → 预览管线 stall，
和库覆盖是同一类黑屏。实测刷过几次模块后，设备上这两个 bind 的源**全部**是 `//deleted`。

现在改成 `MERGED_ROOT=/data/adb/omnicam_aura/merged`（模块目录之外，KSU 更新不会碰），
并在重建前先 `umount "$TARGET"`；若 unmount 没成功则退化为每次运行用独立目录
（`$NAME.$$`），**绝不 `rm -rf` 一个正在被 bind 的源目录**。

> 注意这仍是"刷模块后不重启就看不到新内容"的同一个道理：bind/overlay 在开机时建立，
> 之后换文件内容不影响已建立的挂载。见 §3.5 的实测证据。

### 4.4 相机启动即崩的真因：字符串池下标未重映射（**不是**"基线 odm 比设备旧"）

> 本节推翻过一版结论。2026-10-13 曾把注入**停用**，理由是"移植基线 odm 比设备旧"。该理由
> **已被证伪**——见 4.4.1。真因是 §4.2.1 的**池下标**问题，现已修复并恢复注入。

崩溃堆栈（`dumpsys dropbox --print system_app_crash`，设备侧可复现，共 **109** 次）：

```
java.lang.IndexOutOfBoundsException: Index 314 out of bounds for length 296
	at com.google.oplus.protobuf.LazyStringArrayList.get(LazyStringArrayList.java:97)
	at com.oplus.ocs.camera.configure.CameraConfigure.initFeatureInfo(CameraConfigure.java:556)
	at com.oplus.ocs.camera.configure.CameraConfigure.initCameraTypeFeatureTable(CameraConfigure.java:443)
	at com.oplus.ocs.camera.configure.CameraConfigure.initCameraFeatureTable(CameraConfigure.java:386)
	at com.oplus.ocs.camera.configure.CameraConfigure.initFeatureTable(CameraConfigure.java:357)
	at com.oplus.ocs.camera.configure.CameraConfigure.parseProtobufFeature(CameraConfigure.java:296)
	at com.oplus.ocs.camera.configure.CameraConfigure.decodeConfigFileVersion1(CameraConfigure.java:189)
	at com.oplus.ocs.camera.configure.CameraConfigure.parseFromProtobuf(CameraConfigure.java:119)
```

`LazyStringArrayList` 是 protobuf 的 **repeated string**，也就是顶层 `field1` 字符串池。
"长度 296" 是**池的长度**，不是"我们少放了 18 条条目"——相机取的是**池下标 314**，
而 X8U 池只有 296 条 ⇒ 越界。这个 314 来自我们**原样搬过来的 X9U 条目**（X9U 池有 319 条，
它的 314 是 `com.oplus.camera.feature.gr_photo`）。

**后果链（因果不可颠倒）**：崩溃 → ColorOS 把 `com.oplus.camera` 记为不稳定应用 →
`prevent start by classifyResrict` 拦截启动 → 彻底打不开。**崩是因，拦是果**；见 §3.8。

#### 4.4.1 "基线 odm 比设备旧"是错误结论

当时对比的尺寸表（池都是 296，误以为"少 18 条"）：

| 文件 | 顶层 field1 条目数 | 尺寸 |
|---|---|---|
| 本工程用的基线（`x8u_extract/*/`） | 296 | 44,912 B |
| 当时的移植产物 | 296 | 62,004 B |
| X9U（`module/payload/…`） | 319 | 65,773 B |

真正的判定方法是**把设备自带的原厂配置拉回来逐字节比对**，而不是猜版本：

```sh
sh /data/local/tmp/x8u-config-revert.sh   # umount 我们自己的 bind，露出 erofs 原厂文件
                                          # 导出到 /sdcard/x8u-pristine-config/
```

结果（设备拉回 vs 构建基线）：

| 文件 | md5（两侧相同） |
|---|---|
| `camera_unit_config` | `6ff8bb9c9fed6e3054ae0bad64a05ac8` |
| `camera_unit_feature_config.protobuf` | `d28f6cec50cbdb8d33d220904e99d7e6` |

**逐字节相同，且池都是 296 条。** 所以基线**就是**设备自己的配置，不存在"旧 odm"。
崩溃与"条目少 18 条"无关，只与**下标引用**有关。

#### 4.4.2 处置

`post-fs-data.sh` 恢复对两个文件的注入（其余四个 `oplus_*` 加密配置 PKJ110 仍保持原厂）：

```sh
for NAME in oplus_camera_config … ; do
    [ "$PROFILE" = PMA110 ] || continue
    bind_file "$CONFIG_SRC/$NAME" "/odm/etc/camera/config/$NAME"
done
for NAME in camera_unit_config camera_unit_feature_config.protobuf; do
    bind_file "$CONFIG_SRC/$NAME" "/odm/etc/camera/config/$NAME"
done
```

`x8u-repair.sh` 的 D 段同步恢复重绑。

#### 4.4.3 第二类崩溃：POP 预览 GL 线程 NPE（疑似同根，待实机确认）

在配置**撤掉之后**（15:56–15:58）又出现 **7** 次另一种崩溃：

```
java.lang.NullPointerException: Attempt to invoke virtual method
    'void com.oplus.camera.common.gl.y0.s()' on a null object reference
	at com.oplus.camera.common.gl.s.p0(GLES20Canvas.java:120)
	at eq.d1.Ug(CameraPreviewUI.java:638)
	at eq.d1.f1(CameraPreviewUI.java:192)
	at nn.l.F0(PreviewRenderer.java:328)
	at nn.l.onDrawFrame(PreviewRenderer.java:34)
	at mn.t.r(PreviewEffectProcessor.java:16)
	at com.oplus.camera.common.gl.i0.d(GLThread.java:736)
	at com.oplus.camera.common.gl.i0.run(GLThread.java:22)
```

两类崩溃的时间窗口**互不相交**：

| 窗口 | 签名 | 次数 | 当时配置 |
|---|---|---|---|
| 15:10:58 – 15:39:40 | `IndexOutOfBoundsException: Index 314 …` | 109 | 移植配置生效 |
| 15:56:06 – 15:58:51 | `NullPointerException @ CameraPreviewUI:638` | 7 | 已撤、原厂配置 |

用户观察与之吻合：**"原相机自带的界面都没问题，一切换到模块添加的界面就卡死"**（长按切人像正常）。
这条 NPE 与配置**无关**——它在配置撤掉、设备跑**原厂配置**时同样发生，所以它是**独立于 §4.4 的
第二个根因**（不是"配置缺了 feature 记录"）。2026-10-13 已把它查到字节码级，见 **§4.5**。


### 4.5 POP 预览崩在 GL 线程：`draw_inverse_mask` 程序从未创建（**第二个独立根因**）

**症状**（2026-10-13，全部原厂配置也可能是移植配置下都会复现）：

> 理光（GR）界面正常，原相机自带界面都正常，**只有 POP 界面一进去就崩、相机被关掉**。

崩溃签名（`dumpsys dropbox --print`，15:56:06–16:56:11 共 **10** 次，`FATAL EXCEPTION: PreviewGLThread`）：

```
java.lang.NullPointerException: Attempt to invoke virtual method
    'void com.oplus.camera.common.gl.y0.s()' on a null object reference
  at com.oplus.camera.common.gl.s.p0(GLES20Canvas.java:120)
  at eq.d1.Ug(CameraPreviewUI.java:638)
  at eq.d1.f1(CameraPreviewUI.java:192)
  at nn.l.F0(PreviewRenderer.java:328)
```

要读它得先拿到字节码（本机没有可用的 baksmali，用 SDK 自带的 `dexdump`）：

```bash
python - <<'PY'
import zipfile; z=zipfile.ZipFile(r"OplusCamera-7.006.123.apk")
for n in [x for x in z.namelist() if x.endswith(".dex")]:
    d=z.read(n)
    if b"CameraPreviewUI.java" in d and b"PreviewEffectProcessor.java" in d: print(n)  # -> classes23.dex
PY
dexdump -d classes23.dex > d23.txt     # 崩溃栈全部落在 classes23.dex
```

`eq.d1` 就是 `CameraPreviewUI`；**行 638 落在 code unit `0x027d`**：

```
027d: invoke-virtual {v6, v2, v1, v4, v5},
        Lcom/oplus/camera/common/gl/s;.p0:(Landroid/graphics/RectF;F[FF)V
```

该方法的日志文案是 `"drawInverseMaskOverlay, greenFlashStress, width: …"`，所以 `Ug` = `drawGLInverseMask`。
`GLES20Canvas.p0(RectF,F,[F,F)` 内部（`classes.dex`）第 120 行只有一句：

```
0075: iget-object v1, v0, Lcom/oplus/camera/common/gl/s;.j0:Lcom/oplus/camera/common/gl/y0;
0077: invoke-virtual {v1}, Lcom/oplus/camera/common/gl/y0;.s:()V
```

`j0` 是 `GLES20Canvas` 的 **draw-inverse-mask shader program**。全 dex 里 `j0` 只有两处写入，**都在
同一个构造器里**：

```
0090: iput-object v6(0), s.j0          // 先置 null
...
025a: invoke-static {}, Lia/n0;.i0:()Z // 开关
025e: if-eqz v0, 026b                 // i0()==false -> 跳过
0260: new-instance y0 (R$raw.texture_vertex_shader_inverse_mask,
                          R$raw.draw_inverse_mask_shader)
0269: iput-object v0, s.j0            // 只有 i0()==true 才创建
```

**`i0()` 的实现**（`Lia/n0;`）：

```java
static Boolean p;                       // 进程内静态缓存
static boolean i0() {
    if (p == null)
        p = (Integer.parseInt(CameraConfig.v("com.oplus.feature.colorful.screen.torch.config", "0"), 2) & 4) == 4;
    return p;
}
```

**是否绘制由另一个开关决定**（`Ug` 内 `0x0122`，字符串 `shouldKeepGLInverseMaskForRetroUI, …`）：

```java
static boolean C() {                    // Lia/n0;.C
    return "retroCamera".equals(DataManager.getInstance().b(n9.e.k, ""));
}
```

**两个开关相互独立，于是就有了洞**：

| 开关 | 条件 | X8U |
|---|---|---|
| `C()` 决定"要不要画 inverse mask" | 当前模式 == `retroCamera`（POP） | **true**（用户切到 POP） |
| `i0()` 决定"程序有没有被创建" | vendor tag `…colorful.screen.torch.config` 的 **bit 2** | **false**（该 tag 不存在） |

⇒ 走到 `p0` 时 `j0 == null` ⇒ `GLES20Canvas.java:120` NPE ⇒ 相机被关。

**为什么 X8U 没有这个 tag**（对照两份 `oplus_camera_config`）：

| 文件 | 大小 | 格式 | colorful.screen.torch |
|---|---|---|---|
| `x8u_extract/…/oplus_camera_config`（设备原厂） | 70,696 B | **加密二进制**（`\x01\x01Y\xfc…`） | 无 |
| `module/payload/oplus_camera_config`（X9U） | 97,527 B | **明文 JSON** | 3 条，`…torch.config = "111"` |

X9U 的值 `"111"` 是**二进制位串**（`parseInt("111", 2)` = 7 = `0b111`，bit 2 置位）⇒ X9U 上 `i0()==true`，`j0` 正常创建，POP 不崩。
X8U 没有这条 tag，`CameraConfig.v` 回落到默认 `"0"` ⇒ `i0()==false`。

**为什么原相机自带界面和 GR 都不崩**：它们的模式名不是 `retroCamera`，`C()` 为 false，
`drawGLInverseMask` 根本不会执行。**这不是"配置缺记录"，也与是否注入移植配置无关。**

**X8U 其实什么都有，只差这个开关**（`aapt2 dump resources`）：

```
raw/draw_inverse_mask_shader                    (0x7f100047)
raw/texture_vertex_shader_inverse_mask          (0x7f10012c)
dimen/inverse_mask_radius / _radius_16_9 / _radius_full_screen / _offset_split_screen …
array/colorful_torch_mode_icons / _names / _values
color/colorful_torch_overlay_start / _end / _outline
drawable/colorful_torch_simple_dot
id/torch_mask_view / torch_full_screen_view / torch_color_selector …
```

即 **代码 + shader + UI 资源全在**，只是 feature 位关掉了（X8U 官方没有 retro 模式，用不到屏幕补光）。

**修复**（`app/src/main/java/local/jiege/hook/pop/PopScreenTorch.java`，由 `PopPort.installCamera` 装上）：

hook 未混淆的 `com.oplus.camera.configure.CameraConfig.v(String,String)`，只对这一个 key 把 bit 2 补上：

```java
if (KEY.equals(param.args[0])) {
    int value = 0;
    try { value = Integer.parseInt((String) param.getResult(), 2); } catch (Throwable ignored) { }
    if ((value & 4) != 0) return;
    param.setResult(Integer.toBinaryString(value | 4));  // 写回二进制串，只补 bit 2
}
```

> ⚠️ **第一次实现踩的坑（务必记住）**：这个 tag 是**二进制位串**，消费方一律用
> `Integer.parseInt(value, 2)` 读（`ia.n0.P` / `CommonUtil.14`，而且 `CameraSettingsConfig
> .initialize()` 在**启动路径**就会读到它）。若按十进制回写 `"4"`，会在 `parseInt("4", 2)` 抛
> `NumberFormatException: For input string: "4" under radix 2`，**相机启动即崩、所有模式全挂**
> （2026-10-13 17:14 实测）。必须用 `Integer.toBinaryString(...)` 写回。

选这个 hook 点是因为 **`CameraConfig` 与 `v` 都没有被混淆**（dexdump 证实），跨 build 稳定；
`ia.n0` / `gl.s` / `gl.y0` 都是混淆名，不可靠。hook 必须在 `i0()` 首次调用（`GLES20Canvas`
构造）之前装上——`handleLoadPackage` 阶段即可，此时 `p` 还是 null。

**验证方法**：模块日志会打出 `PopPort: screen torch config 0 -> 100 (draw_inverse_mask program
becomes available)`（`100` 为二进制，即十进制 4）；随后 POP 界面应正常渲染（不再有
`CameraPreviewUI.java:638` 崩溃）。

**回退**：删掉这次 hook 即可，无需动任何分区或配置。


### 4.6 POP 仍卡：进入 POP 时 **HAL 拒绝重建的流组合**（第三个根因，2026-10-13 17:35 起）

> ⚠️ **本节结论已被 §4.7 推翻。** 这里看到的 `configure_streams() → ILLEGAL_ARGUMENT` 是
> **下游症状**；真因是 `camera_unit_config` 的 `usecase_info` 形状错误导致会话根本建不起来。
> 本节保留为取证过程记录（会话探针 §4.6.4 仍然有效，正是它证明了"pop 会话请求数 = 0"）。

§4.5 的 `draw_inverse_mask` program 补建**已被实机确认成功**（LSPosed 日志：
`PopPort: draw_inverse_mask program built for the retro UI`，`unavailable` 一条都没有），
**POP 不再崩（不再有 `CameraPreviewUI.java:638` NPE）**。但症状变成**"有画面之后整个相机卡死"**。

#### 4.6.1 §4.4.3/第十轮的错误归因（**已证伪**）

第十轮曾把"卡死"归因于：第十轮 hook `com.oplus.feature.colorful.screen.torch.config` 污染了
flash/补光子系统 ⇒ 配流失败。**本轮的 `PopInverseMaskProgram` 完全不碰该配置**
（LSPosed 日志 17:21 之后再无 `screen torch config`），**同样的失败照旧发生** ⇒ 该归因**不成立**。
（注意：那次改值确实会造成问题——它把十进制 `"4"` 写进二进制位串，17:14–17:17 实测让**启动路径**
抛 `NumberFormatException`、所有模式全挂；但那是另一个 bug，不是 POP 卡死的原因。）

#### 4.6.2 真凶：应用重建会话，HAL 拒绝

```
17:36:00.607  Camera3-OutputStream: disconnectLocked: disconnecting stream 35/36/37/38 from native window
17:36:00.608  [CAM-OEMLAYER] configure_streams() HalOp: X
17:36:00.609  AidlCamera3-Device: configureStreams: Transaction error: ILLEGAL_ARGUMENT
17:36:00.609  Camera3-Device: Camera 0: configureStreamsLocked: Set of requested inputs/outputs not supported by HAL
17:36:00.609  CameraDevice-JV-0: Stream configuration failed due to: endConfigureLocked:946
17:36:00.628  IPU_FilterProcessUnitSDK: processImage begin params, [… retro_ccd_fisheye_open, false …]
17:36:00.662  CombineLut_V2.0.3.3: read3DlutSet() SimTool_Setting: /odm/etc/camera/basictone/setting_Retro/SimTool_Master.ini
```

之后 **60 秒以上零帧**（`camx … repeatingRequestEnd` 停在 `frameNumber:968`），APS 队列排空
（`APS_ALOG: flushRoutine g_pendingCount=0`），`PreviewGLThread` 停在 `GLThread.java:868`
（`i0.C` 上的 `Object.wait()`，等一个永不到来的 notify），相机设备**软关掉后不再重开**。

硬件上限（`dumpsys media.camera`，camera 0）：

```
android.request.maxNumOutputStreams (c0006): int32[3]   [1 3 2]
android.request.maxNumInputStreams  (c0008): int32[1]   [1]
```

⇒ 返回的是 `Set of requested inputs/outputs not supported`，即 **POP 要的流组合超出 X8U 的 HAL 能力**
（或含 X8U 不支持的尺寸/格式/输入流）。

**为什么"一切换到别的模式也卡"**：相机设备是共享单例。配流失败后会话死掉，任何模式都拿不到帧。

#### 4.6.3 取证时必须避开的两个误判（本轮踩过）

1. `dumpsys media.camera` 里 17:36:09 那条 `DISCONNECT device 0` **不是故障**——当时
   `mWakefulness=Dozing`、前台是桌面，**相机 Activity 已 paused**，属正常释放。
   判据：`dumpsys window | grep mCurrentFocus` + `dumpsys activity activities | grep ResumedActivity`。
2. 858 次 `System: A resource failed to call HardwareBuffer.close` / `ConsumerBase: abandonLocked`
   是配流失败后的缓冲区泄漏，是**果**。

#### 4.6.4 会话探针（本轮交付的诊断件）

`app/src/main/java/local/jiege/hook/common/CameraSessionProbe.java`，由 `ModuleEntry` 的
`com.oplus.camera` 分支 `Log.guard` 装上。hook 未混淆的
`android.hardware.camera2.impl.CameraDeviceImpl` 的 `createCaptureSession*`
（`hookAllMethods` 覆盖全部重载），打印：

- 每一路 output 的 `Surface.toString()` —— 原生名自带尺寸/格式/张数（如 `ImageReader-1920x1080f22m35-…`）；
- `getUsageFlags()` / `getDynamicRangeProfile()` / `getInputConfiguration()` / `getSessionType()`；
- **5 帧调用栈**，用来定位是哪个模式代码路径发起的这次会话。

全反射、全 `try/catch`，**只写日志、不改行为**。日志形如
`PopPort: session request: outputs[… | …] from …`。
预期用法：切一次"照片"（对照基线）→ 切 POP（拿到被拒的那一组）→ 比对多出来/变掉的那一路流。

#### 4.6.5 顺带拆掉的雷：设备处于"好配置只在已删除 inode 里"的自相矛盾态

`/proc/mounts` 只有 4 条 `/odm` 挂载（`camera_unit_config`、`..._feature_config.protobuf`、
`meishe_lut`、`basictone`），**`/odm/lib64` 无任何挂载 ⇒ 库层干净**。但：

| 项 | 设备实况 | 应为 |
|---|---|---|
| `post-fs-data.sh` | **旧版**（PKJ110 分支 `injection DISABLED`） | 新版（§4.4.2，无条件注入两文件） |
| `payload_pkj110/….protobuf` | **62,004 B / `02fe7031…`（池 296 = `Index 314` 崩的那份）** | **62,330 B / `b0ee50c1…`（池 307）** |
| 当前 live 挂载 | `b0ee50c1…`（62,330 B）✔ | —— |
| `camera_unit_config` | `17290f60…`（29,803 B）✔ 两侧一致 | 同 |

**好版本只活在"已删除 inode"里**（`ls -l` 的 `nlink=0` 即指纹），磁盘文件是坏的 ⇒
**只要 bind 被重建（真开机 / 刷模块后重启），就会绑上坏文件 → `Index 314` 启动即崩 → 又被
ColorOS 拉黑（§3.8 老路）**。已于 17:43 就地写修（`cat 好的 > payload 路径`，不动已建挂载）：
payload protobuf = `b0ee50c1…` / 62,330 B，`post-fs-data.sh` = 11,009 B / 0755。
备份：`/data/local/tmp/cfgbak/`、`/data/local/tmp/cfgbak_proto_old.bin`、`/sdcard/x8u-pristine-config/`。

> 迭代提示：**LSPosed 在进程启动时加载模块代码**，所以换 APK 只需
> `su -c 'am force-stop com.oplus.camera'` 再重开相机即可生效，**不需要软重启**（17:11 装包 →
> 17:14 的新进程就打印了新代码的日志）。


### 4.7 POP 卡死的**最终根因**：`usecase_info` 被写成了 pair-list，`parseUsecaseInfo()` 半途而废

> 这一节推翻了 §4.6 的结论。**§4.6 观察到的 "HAL 拒绝流组合" 是下游症状**，不是病因。
> 真正的病因是 `payload_pkj110/camera_unit_config` 里三个新增用例的 **JSON 形状错误**。

#### 4.7.1 症状

X8U 上进入 POP：预览**全黑**、界面活着但**永不建会话**；因为相机设备是共享单例，
之后切到任何模式也都是黑的（与用户观察"切到其它界面也卡住"完全一致）。

#### 4.7.2 实机异常栈（决定性证据）

logcat，tag `CameraUnit, Camera2StateMachineImpl`：

```
java.lang.RuntimeException: getUseCaseValues, retro_camera_case's usecase_info are not configured!
    at com.oplus.ocs.camera.producer.mode.BaseMode.getUseCaseValues(BaseMode.java:2958)
    at com.oplus.ocs.camera.producer.mode.BaseMode.buildStreamSurface(BaseMode.java:3227)
    at com.oplus.ocs.camera.producer.device.CameraSessionEntity.getAllSurfaceWrapper(CameraSessionEntity.java:112)
    at com.oplus.ocs.camera.producer.device.Camera2Impl.createNewSession(Camera2Impl.java:3138)
    at com.oplus.ocs.camera.producer.device.Camera2StateMachineImpl$StateMachineHandler.handleMessage(Camera2StateMachineImpl.java:423)
```

异常在 `createNewSession()` 里就抛了 ⇒ **应用根本没有调用 `createCaptureSession`**
（会话探针 §4.6.4 在同一个进程里记录了 **0 条** session request，正好印证）。

应用随后把它翻译成既有错误码：

```
Camera2StateMachineImpl$2.onConfigureFailed  →  ErrorResult.STREAM_SURFACE_ERROR (10002)
ProducerImpl: onSessionConfigureFail, error result: [error code: 10002, error info: stream surface error]
OCAM_OneCameraImpl: onCameraPreviewError, error: PREVIEW_CONFIG_FAILED
```

**注意顺序**：`STREAM_SURFACE_ERROR` 只在 `Camera2StateMachineImpl$2.onConfigureFailed()`
里被 `sget-object` 取出（dexdump 全库只此两处），它是**被动回调**产物，不是病因。
§4.6 把 `stream surface error` 读成"HAL 拒绝了流列表"，方向反了。

#### 4.7.3 代码住在一个**系统 jar** 里 —— 这是此前一直找错地方的原因

`com.oplus.ocs.camera.producer.*` / `...producer.info.CameraConfigHelper` /
`...configure.JsonParser` **都不在相机 APK 的 33 个 dex 里**，而在：

```
/product/framework/com.oplus.camera.unit.sdk.jar     5,319,483 B  （内含单个 classes.dex）
```

（`logcat` 里的 `nativeloader: ... caller=/product/framework/com.oplus.camera.unit.sdk.jar` 就是线索。）

取证命令：

```bash
adb pull /product/framework/com.oplus.camera.unit.sdk.jar        # 0644，普通 shell 即可读
python -c "import zipfile;open('unitsdk_classes.dex','wb').write(zipfile.ZipFile('unit.sdk.jar').read('classes.dex'))"
$ANDROID_HOME/build-tools/37.0.0/dexdump -d unitsdk_classes.dex > unitsdk.txt
```

#### 4.7.4 解析器要求什么形状

`JsonParser.parseUsecaseInfo()`（dexdump 原文）：

```java
JSONObject usecase = mConfigJson.getJSONObject("usecase_info");
for (String key : usecase.keys()) {
    JSONArray arr = usecase.getJSONArray(key);
    LinkedList<Pair<String,String>> list = new LinkedList<>();
    for (int i = 0; i < arr.length(); i++) {
        JSONObject obj = arr.getJSONObject(i);   // ← 每个元素必须是「对象」
        String k = obj.keys().next();
        list.add(new Pair<>(k, obj.getString(k)));
    }
    map.put(key, list);
}
// 整个方法只有一个 try/catch：抛异常 → handleException() → return 已装进去的那半张表
```

所以：

| | `usecase_info["retro_camera_case"]` |
|---|---|
| X9U（PMA110）原版 | `[ {"capture_meta":"rear_sat"}, {"preview":"rear_sat"}, … ]` ✅ |
| 移植包 PKJ110 版（坏） | `[ [["capture_meta","rear_sat"]], [["preview","rear_sat"]], … ]` ❌ |

第二行是 Python `list(dict.items())` 的形状 —— **`build_x8u_port.py` 添加新条目时没有套用
§4.1 的"文本级 splice"约定，把新条目序列化成了 pair-list**。

#### 4.7.5 为什么是"原生模式全好、只有 GR/POP 坏"

Android 的 `JSONObject` 内部是 `LinkedHashMap`，所以遍历顺序 = 文件顺序。坏掉的三个键
恰好排在 `usecase_info` 的**最后三位**（索引 47/48/49）：

```
  0 … 46   47 个原生用例（sat_photo_case … ai_high_pixel_case）   → 全部解析成功 ✅
 47        gr_mode_case          ← BROKEN  解析到这里抛异常，整个方法返回
 48        gr_mode_hq_raw_case   ← BROKEN  ← 永远没被读到
 49        retro_camera_case     ← BROKEN  ← 永远没被读到
```

**一个形状错误，代价是它后面所有条目全部丢失。** 这也解释了为什么
"照片/人像/视频/专业都正常，唯独模块加进来的模式进不去"。

#### 4.7.6 修复（纯数据，不用重刷、不用重启手机）

逆变换 `[[k, v], …] → {k: v}`（递归），共还原 **72 个节点**：

| | 修复前 | 修复后 |
|---|---|---|
| `camera_unit_config` 大小 | 29,803 B | **32,454 B** |
| md5 | `17290f6029609c7379ee50e5b7c7c02b` | **`6b6c77aa8694bd55d2eaaacaec5a659a`** |
| pair-list 节点 | **50** | **0** |
| `usecase_info` 非对象条目 | 3 | 0 |
| `capture_stream_number` 变形模式 | 4（`tilt_shift_mode` / `gr_mode` / `high_pixel_mode` / `retro_camera_mode`） | 0 |

同时校验：`usecase_info/retro_camera_case`、`gr_mode_case`、
`capture_stream_number/retro_camera_mode`、`capture_stream_number/gr_mode`
与 X9U 原版**逐项相等**（Python `==`）。

**实机应用步骤**（配置在进程启动时读一次，所以只需重启相机 App）：

> **优先用现成脚本**（幂等、带前置校验与复核，见 §4.8.4）：
>
> ```bash
> ./port-x8u/apply-pop-fix.sh check    # 只读：看两边 md5 / 挂载 / bind 源
> ./port-x8u/apply-pop-fix.sh apply    # 推包 → bind → 写回 payload → 复核 → 重启相机
> ```
>
> 下面是它内部做的等价手动流程，留档备查：

```bash
adb push module/payload_pkj110/camera_unit_config /data/local/tmp/unit_fixed.json
# ① 写穿挂载点 → 立刻生效（挂载源的 inode 已 nlink=0，不能靠改 payload 文件生效）
adb shell su -c 'cat /data/local/tmp/unit_fixed.json > /odm/etc/camera/config/camera_unit_config'
# ② 同时覆盖 payload 文件 → 保证下次开机脚本绑到的是修好的那份
adb shell su -c 'cat /data/local/tmp/unit_fixed.json > /data/adb/modules/omnicam_aura/payload_pkj110/camera_unit_config'
adb shell su -c 'toybox md5sum /odm/etc/camera/config/camera_unit_config \
    /data/adb/modules/omnicam_aura/payload_pkj110/camera_unit_config'   # 两份都应为 6b6c77aa…
adb shell su -c 'am force-stop com.oplus.camera'                        # 重开相机即可
```

回退：`port-x8u/broken/camera_unit_config.broken.bak`（原始坏版本，仅供留档；已刻意放在
`module/` 之外，避免任何一次重新打包把它带进模块）。

#### 4.7.7 新增护栏：`tools/verify_payload_json.py`

§4.1 的"递归 diff 31 项、零改类型"自检**查不出这个 bug**——因为三个新条目是"新增"，
不是"改值/改类型"，而自检只比对新旧两份文件、没有拿 X9U 模板校验新增项的**形状**。

所以新增一个独立校验器：递归扫描 payload 里所有 JSON，凡出现
`[[k, v], …]`（每个元素都是两元数组、首元素是字符串）即判定为变形并 exit 1。

```bash
python tools/verify_payload_json.py                 # 扫描 module/payload* + port-x8u/payload 全部 JSON
python tools/verify_payload_json.py path/to/file    # 单文件
```

判定依据：设备原厂配置里**这种形状出现 0 次**（X9U 原版 0 个节点），所以任何一处都是 bug。

---

### 4.8 离线复核与今晚的上机流程（2026-10-13 18:00，设备不在手）

手机被撤下，本轮只能离线作业。以下都是**没有设备也能定论**的东西，逐条留档。

#### 4.8.1 修复版 = 设备当时跑过的正版（决定性交叉验证）

`port-x8u/_devpull/` 里有 14:30 / 14:49 / 14:59 三次从设备上拉回的 `camera_unit_config`
（`246ee6b0…`，32,453 B）。把它与修复版逐字节比对：

```
FIX  len 32454   DEV len 32453
40060 字节前缀完全相同；FIX 只多一个结尾 \n
⇒ FIX == DEV（语义完全相等，差异仅为 echo/cat 追加的换行）
```

这条比"自检通过"强得多：它说明 §4.7.6 的逆变换**没有引入任何新内容**，只是把
`build_x8u_port.py` 后来写坏的那份**还原成了原本就在设备上跑通过的那一份**。

时间线也对得上，两个 POP 阻塞点是**先后**出现、不是同时：

| 时刻 | `camera_unit_config` | 已修的 POP 阻塞点 | 现象 |
|---|---|---|---|
| 14:30–14:59 | `246ee6b0`（= 修复版） | 只修了 §4.4（启用配置） | POP 崩在 `draw_inverse_mask` NPE（§4.5 未修） |
| 15:10–15:39 | 同上 | 仍只修了 §4.4 | 启动即崩 `Index 314`（protobuf 池，§4.4） |
| 15:18 起 | `17290f60`（pair-list 坏版） | —— | 配置层坏了 |
| 17:21 起 | 同上 | §4.5 已修（program 补建成功） | POP 有画面但永不建会话（§4.7 的形状问题） |

也就是说 `246ee6b0` 那一版**从未与 §4.5 的修复同时存在过**，所以"配置对 + program 有"
这个组合直到今晚才会第一次实测。这解释了为什么此前每一轮都"修好一个又冒出下一个"。

#### 4.8.2 `camera_unit_config` 的离线全量核对

对修复版（32,454 B / `6b6c77aa`）做三向核对（X8U 原厂 20,261 B / `6ff8bb9c`、
X9U 模板 40,869 B / `6b2fa717`），全部通过：

| 检查 | 结果 |
|---|---|
| 递归扫描 pair-list 节点 | **0**（坏版 72 个，首个在 `usecase_info/gr_mode_case[0]`） |
| X8U 原厂节点被删除 | **0** |
| X8U 原厂标量/类型被改动 | **0** |
| 数组增长 | 只有 `mode_type_list/{rear_wide,rear_main,rear_tele,rear_ultra_tele,front_main,rear_sat}` 6 处，且**纯追加**、追加项均来自 X9U |
| 新增条目 | **恰好 9 个**，全部与 X9U **逐字节相等** |
| `usecase_info` 三个新键的形状 | 都是 `[ {k:v}, … ]`（对象列表） |
| `retro_camera_case` 在 `usecase_info` 中的位置 | **最后一位** |

最后一条不是巧合、必须保住：`JsonParser.parseUsecaseInfo()` 是单 try/catch，**它前面任何
一条再坏一次，`retro_camera_case` 就又被吞掉**。所以三个新键整体挪到末尾是正确的摆放，
以后也不要往它们后面插东西。

九个新增条目（就是这九条把 POP/GR/移轴接进来的）：

```
mode_operation_mode      gr_mode, retro_camera_mode
usecase_info             gr_mode_case, gr_mode_hq_raw_case, retro_camera_case
capture_stream_number    tilt_shift_mode, gr_mode, high_pixel_mode, retro_camera_mode
```

#### 4.8.3 `gr_basictone.cpp` 的"缺 7.006.123 指纹"是**误判，已撤销**

§7 曾记一条待办："只差 `gr_basictone.cpp` 的 `getSettingInt` 序言指纹，在 7.006.123 上
fail-safe 落空 ⇒ GR 的原生 hook 不会装上"。**这条前提是错的**：

`resolveSettingInt()` 是按**序言字节**匹配的，`build` 字段只用于打日志：

```cpp
for (const auto &layout : kSettingIntLayouts) {
    auto *candidate = static_cast<char *>(base) + layout.offset;
    if (std::memcmp(candidate, layout.prologue, sizeof(layout.prologue)) == 0) …
}
```

而 `libAlgoInterface.so` 是 **ODM 固件库，不属于相机 APK**——相机 App 从 7.006.94 升到
7.006.123 不会动它。直接本地验：

```bash
python - <<'PY'
d = open(r"G:/ColorOS17/x8u_extract/odm_lib64/libAlgoInterface.so","rb").read()
print(d[0x13f46d4:0x13f46d4+16].hex(' '))
PY
# 3f 23 03 d5 ff 43 02 d1 fd 7b 06 a9 f6 57 07 a9   ← 与表里 PKJ110 条目逐字节相同
```

**命中。** 所以 PKJ110 上的 GR 原生 hook 本来就是装得上的，"7.006.94 那条 entries 在
7.006.123 上失效"这个说法不成立（表里那个版本号只是**标注该序言取自哪次构建**，不参与匹配）。

顺带确认：`/odm/lib64` 上**没有任何 overlay**（§4.6.5），设备读的就是固件原件。
上机时用一条命令闭环（预期 `28715a07bac87e3c2d286c9db2ca8f7c`）：

```bash
adb shell su -c 'md5sum /odm/lib64/libAlgoInterface.so'
```

对不上才需要重新取指纹并补表；对得上就不用管。

#### 4.8.4 今晚的上机流程（一条命令，幂等，可回退）

新增三个脚本，`apply-pop-fix.sh` 是主机侧唯一入口（设备侧两个放在 `module/` 里随包下发，
没 PC 时也能在手机上直接跑，见 §8.1）：

```bash
./port-x8u/apply-pop-fix.sh check     # 只读取证：源 / live / payload 三方 md5 + bind 源
./port-x8u/apply-pop-fix.sh apply     # 推包 → bind → 写回 payload → 复核 → 重启相机
./port-x8u/apply-pop-fix.sh revert    # 撤回原厂配置（不动 payload）
./port-x8u/apply-pop-fix.sh diag      # 自动重启相机 + 采样日志/线程转储，pull 回本地
```

设备侧 `x8u-fix-pop.sh` 比 §4.7.6 的手动命令多做了三件必要的事：

1. **先 `umount` 再 bind**，并把 bind 源是不是 `//deleted`（§4.6.5 那个自相矛盾态）
   当**失败**报出来——只比 md5 看不出这个，因为 live 的 md5 可能是对的、而下次开机就回退；
2. **同一份字节写回 `payload_pkj110/`**，让"现在就生效"和"下次开机仍生效"用同一次动作完成；
3. **写前校验源 md5**，源不对就中止，不会把半成品绑上去。

判据（`apply` 成功后）：

```
[pop] ===== 复核 =====
[pop]   live    camera_unit_config  ok
[pop]   payload camera_unit_config  ok
[pop]   bind    camera_unit_config  源=/data/local/tmp/x8u-fixcfg/camera_unit_config
… 两份配置 × (live/payload/bind) 六项全 ok，末行 "[pop] 全部通过"
```

若 POP 仍不正常，**别改配置**，直接跑 `apply-pop-fix.sh diag`：它会重启相机、等你 20 秒切到
POP、然后一次性采集 12 组证据（挂载表 / 配置形状 / 模块 bind.log / LSPosed 日志 /
dropbox 异常 / 建会话与配流 / `session request` 探针输出 / `dumpsys media.camera` 流能力 /
线程转储 / 原生库指纹）。`session request` 那组是分水岭：

- **有** output 列表 ⇒ 会话已经发出去了，问题在 HAL/流组合，去看 §4.6 的取证；
- **0 行** ⇒ 应用在 `createNewSession()` 就抛了，仍在配置层，回到 §4.7.2 的异常栈。

#### 4.8.5 顺手拆掉的一颗地雷：`port-x8u/payload/` 是手工维护的副本

`port-x8u/payload/`（交付用的那份）在 18:01 修好 `module/payload_pkj110/` 时**漏掉了**，
仍留着坏版 `17290f60` / 29,803 B。它不参与模块，但任何"从这里再打包一次"的动作都会把
§4.7 的 bug 原样带回设备。已同步，并且：

- `tools/verify_payload_json.py` 的默认扫描根加入 `port-x8u/payload/`；
- 新增 `tools/verify_port_artifacts.py`，把"两份 payload 必须互为镜像"写成硬断言
  （外加 md5 定值、X9U payload 不许被动、语义 diff、原生指纹、zip 内容六组检查）。

#### 4.8.6 新增发布闸门 `tools/verify_port_artifacts.py`

一条命令覆盖上面 4.8.2–4.8.5 与打包一致性，全离线：

```bash
python tools/verify_port_artifacts.py              # 六组检查，要求全绿
python tools/verify_port_artifacts.py --skip-zip   # 重打包之前跑
```

```
[1/6] pinned payload bytes        PKJ110 两份 + PMA110 两份不变
[2/6] mirror                      module/payload_pkj110 == port-x8u/payload
[3/6] pair-list deformation       verify_payload_json.py 退 0，且能识别留档的坏版
[4/6] semantic diff               见 4.8.2 那张表
[5/6] native fingerprint          libAlgoInterface.so 序言仍与 gr_basictone.cpp 匹配
[6/6] KernelSU archive            zip 内 payload/脚本与磁盘逐字节一致，且不含 *.bak
```

`camera_unit_config.broken.bak` 已从 `module/payload_pkj110/` 移到 `port-x8u/broken/`：
它留在模块源码树里就迟早会被某次朴素打包带进模块。第 6 组检查会对任何 `.bak` 直接报错。

当前状态（1.3.2）：**PASS: all checks green**（`OmniCam-Aura-1.3.2-KSU.zip`；
包内含 `module/x8u-fix-pop.sh`、`module/x8u-fix-vibrant.sh` 等五个脚本，可脱离 PC 在设备上执行）。

### 4.9 POP 的「浓郁」滤镜没反应 —— X8U 的 ODM 里**整个 `positive_*` 色卡家族都不存在**

（2026-10-13 19:40，实机反馈：*"这次可以了，但 POP 里面的浓郁滤镜用不了，其它的正常"*）

#### 4.9.1 症状

POP（`retroCamera`）可以进了，10 个滤镜里 9 个正常，只有「浓郁」选了画面不变。

#### 4.9.2 决定性证据：meicam 报"文件打不开"，而不是"参数不对"

用户在 POP 里扫滤镜时，`port-x8u/_diag/pop_test.log` 对**其他 7 个滤镜**都能正常取到色卡，
唯独「浓郁」这一档反复出现（每次预览帧都重试一次）：

```
D IPU_BaseProcessUnitSDK: setProcessParameter, key:mKeyName: filter_type,
    mType: class java.lang.String, value: positive_hdr_normal_a_1.bin,positive_hdr_normal_d_1.bin
E meicam  : Failed to open lut file /odm/etc/camera/meishe_lut/positive_hdr_normal_a_1.bin
E meicam  : Unkown lut file       /odm/etc/camera/meishe_lut/positive_hdr_normal_a_1.bin
E meicam  : Failed to open lut file /odm/etc/camera/meishe_lut/positive_hdr_normal_d_1.bin
E meicam  : Unkown lut file       /odm/etc/camera/meishe_lut/positive_hdr_normal_d_1.bin
```

`filter_type` 的值**就是色卡文件名**；meicam 去 `/odm/etc/camera/meishe_lut/<name>` 打开，
打不开就整档不生效（不报错到 UI，只是"没反应"）。所以这不是参数问题，是**缺文件**。

#### 4.9.3 定位：POP 的「浓郁」= `camera_filter_retro_vibrant_dsc` = "Positive"

`aapt2 dump resources` 对 X8U 相机 APK（7.006.123）逐条核对：

| 资源名 | 中文/英文取值 |
| --- | --- |
| `camera_mode_retro_camera` | **POP** |
| `headline_mode_retro_camera` | 复古相机 |
| `camera_filter_retro_vibrant_dsc` | **Positive** |
| `camera_filter_oplus_nong_yu` | **浓郁 / Vibrant** |
| `camera_filter_oplus_nong_yu_film_hint` | **正片 胶片 / Positive Film** |

所以用户说的「浓郁」就是 POP 的 Vibrant = **Positive（正片）**。它走
`FilterConstant.STYLE_FILTER_TYPE_POSITIVE_HDR_PATH = "positive_hdr_normal_a_1.bin,positive_hdr_normal_d_1.bin"`
（`com/oplus/camera/filter/FilterConstant.java:154`）。同类常量共 5 个，展开成 **8 个文件名**：

```
positive_normal.bin                        STYLE_FILTER_TYPE_POSITIVE
positive_master.bin                        PRO_FILTER_TYPE_POSITIVE
positive_sdr_gen_a_1.bin / _d_1.bin        FILTER_TYPE_POSITIVE_SDR_PATH
positive_hdr_normal_a_1.bin / _d_1.bin     STYLE_FILTER_TYPE_POSITIVE_HDR_PATH   ← 日志里被打的那个
positive_hdr_master_a_1.bin / _d_1.bin     PRO_FILTER_TYPE_POSITIVE_HDR_PATH
```

#### 4.9.4 为什么"真"色卡拿不到：X8U 固件里根本没有它

这不是本移植引入的文件，是**这台机器的固件从来没给过**：

- 实机 `su -c 'find / -name "positive*"'` → **0 命中**（整机范围，含 `/odm` 与 OPPO 自家
  `OPCameraPro` 的 `custom_lut/system/`，那里另有 500+ 条色卡，同样一条 `positive_*` 都没有）；
- 出厂 F.21 `odm.img`（EROFS，3.36 GB）用 `_meta/erofs_dir.py` 读出
  `/etc/camera/meishe_lut` 共 **111 个条目**，逐条核对 **无一条 `positive_*`**；
- 同一个目录里 `portra400_*` 与 `blackandwhite_HC_*` 的"更新版"家族是**齐的**
  （`portra400_hdr_normal_a_1.bin` 等 8 个全在），说明"滤镜更新包"只给了 portra/黑白，
  **没给 positive**；
- 原始 X9U 工程包 `OmniCam-Aura-main.zip` 里也只有那 16 个 meishe_lut 文件，
  同样没有 `positive_*` —— 即原版在 X9U 上是**靠 ODM 自带**，所以作者没打包，
  移植到 X8U 后就露出来了。

结论：`positive_*` 是 X9/X10 世代的 ODM 资源；X8U 的相机 APK 与 X9 共用代码，
代码里写着这些常量，但这台机器的滤镜清单从未启用第 8 档 —— 移植把它露出来之后，
"看得到、点得动、不出画"。

#### 4.9.5 修复：把本机自带的「正片」色卡按 8 个名字补齐

X8U 的 ODM 里有一个语义等价、**确实存在**的正片色卡：

```
/odm/etc/camera/meishe_lut/gr.posi.rgba.bin   24,128 B   md5=ec7eee635f70dbf299d6c49e750739b7
```

（`gr.*` 家族 = 正片/负片/黑白：`gr.posi` / `gr.nega` / `gr.bw` / `gr.hi.bw`；
它被 `FilterHelper` 当 `filter_type` 用，和缺的那个走**同一条 meicam 取色卡路径**。）

它的容器格式与目录里其它色卡完全一致，都是 `.MS-LUT `：

```
偏移  0  ".MS-LUT "        magic（0x20 41 55 4C 2D 53 4D 2E… 的 ASCII）
偏移  8  int32            type（1/2/3/4，不同家族取值不同）
偏移 12  int32            dim（本目录里有 17 / 20 / 32 / 33）
偏移 16  int32            0
偏移 20  int32            1
偏移 24  int32            家族参数（0/1/24/536/1176…）
文件大小 = 48 + 4*dim + 3*dim³      → dim17: 14855  dim20: 24128  dim32: 98480  dim33: 107991
```

所以只要**字节照抄**，不需要猜头结构、也不需要改任何代码/重编译 APK：
把这 24,128 B 复制成 8 个文件名丢进 meishe_lut 即可。落点就是
`module/payload/meishe_lut/`（post-fs-data.sh 的 `bind_merged_dir` 会把它并进
`/odm/etc/camera/meishe_lut`，与其它 15 份 payload 色卡同一条路）。

> 为什么用 `gr.posi` 而不是随便挑一个：POP 这一档的官方说明文字就是
> "正片 胶片 / Positive Film"，而这是整机**唯一**带 positive 语义的色卡。
> 它是 X8U 世代的正片调子，与 X9 世代 `positive_*` 的原始曲线不会逐位相同，
> 但同属"正片"家族 —— 这是在没有原始文件的前提下能做到的最接近的替代。

#### 4.9.6 一条命令（幂等，可回退）

```bash
./port-x8u/apply-vibrant-lut.sh check     # 只读：源 / payload / live(omd) 三处 md5 + 合并树 bind 源
./port-x8u/apply-vibrant-lut.sh apply     # 推 8 份 → 写模块 payload → 写合并树 → 复核 → 重启相机
./port-x8u/apply-vibrant-lut.sh revert    # 撤掉这 8 份
```

设备侧脚本 `module/x8u-fix-vibrant.sh` 随包发货，没有 PC 时可在手机上直接
`su -c 'sh /data/adb/modules/omnicam_aura/x8u-fix-vibrant.sh apply'`（会用自己的 payload 当源）。

两个关键细节（都在脚本里，写下来免得以后踩）：

1. **落点是"合并树的源目录"，不是 `/odm` 本身。** `/odm/etc/camera/meishe_lut` 是
   `mount --bind /data/adb/omnicam_aura/merged/meishe_lut`；往 mountinfo 第 4 字段
   （`/adb/omnicam_aura/merged/meishe_lut`）前面补 `/data` 就是可写源目录。
   写进去立刻对相机可见，**不用重启手机**。
2. **必须 `chcon u:object_r:vendor_configs_file:s0`。** 目录内其它色卡都是这个标签
   （`label_tree` 打的）；新 `cp` 进去的文件若标签不对，相机（untrusted_app）读不到 ——
   症状会和"文件不存在"一模一样，白折腾。

#### 4.9.7 实机结果与"换一个替代色卡"的办法

应用后 `/odm/etc/camera/meishe_lut` 条目数 **134 = 111 出厂 + 15 旧 payload + 8 新增**，
8 份全部 `24128 B / ec7eee63…`，owner/mode/SELinux 标签与目录内其它色卡逐字段相同。

若用户觉得这个"正片"调子还不够"浓郁"，换替代色卡只需换一份源文件、重跑 apply：
先 `./port-x8u/apply-vibrant-lut.sh revert`，把 `module/payload/meishe_lut/positive_*.bin`
换成新源（例如 `vivid-lut.cube.rgb.bin` 24,128→98,480 B 的"浓郁"味、或
`bright_coloured.bin` 的"鲜艳"味），**同时把
`tools/verify_port_artifacts.py` 里的 `POSITIVE_LUT_SIZE/MD5` 与
`module/x8u-fix-vibrant.sh` 里的 `SZ_LUT/MD_LUT` 一起改掉**，再
`python tools/zip_module.py module OmniCam-Aura-1.3.1-KSU.zip`。

护栏：`tools/verify_port_artifacts.py` 第 1 组新增 8 份色的
`POSITIVE_LUT_SIZE/POSITIVE_LUT_MD5` 定值与归档内校验，第 6 组加了
`x8u-fix-vibrant.sh` 的 size/md5 定值；`tools/verify_aura.py` 也要求这 8 个名字在包里。

### 4.10 X8U 的 X10 调色盘（Palette）：开关不在盘上，在进程里 —— 而进程里也开不了

（2026-10-13 晚，实机反馈：*"调色盘没有出现"*。本节取代 1.3.0 的结论 —— 那份结论**方向是错的**，
并且会把相机锁死。）

**最终结论（先看这个）**：三层错，逐层剥开，最后卡在 X8U 自己的原生件上。

| # | 想法 | 结果 |
|---|---|---|
| 1 | 从盘上补一个 OCS「config parse version 2」容器把开关喂进去 | **错**：容器让相机在进程内第一次取特性值时死锁（ANR）——§4.10.4/§4.10.5 |
| 2 | 在 Java 侧回答 `FeatureApi.getProjectSupportFeatureValue` 这一个 key | **死代码**：`oa.k.b == false`，该方法在 X8U 上从不被调用——§4.10.3/§4.10.10 |
| 3 | 改钩 `CameraUnitUtils.getVendorTagConfig`（兜底链） | **零调用**：`CameraConfig.d` 自带 `ConcurrentHashMap` 缓存，兜底链每进程只跑一次——§4.10.10 |
| 4 | 落点改到必经的 `CameraConfig.d` | **开关生效，但相机必崩**：`Lut3dDataManager.e` 走调色盘分支 → `libCombineLutJni.processV3` 读野指针，进程反复重启——§4.10.11 |

所以 **1.3.2 不装这个钩子**：开关是原厂值 false，面板不出现，相机稳定（§4.10.12）。
要真正做成，缺的是 X9U 那份能处理调色盘组合的 `/product/lib64/libCombineLut*.so`，见 §4.10.11。

#### 4.10.1 症状与第一层判据

大师 / 哈苏滤镜下本该弹出**调色盘面板**，X8U 上**不弹**（用户实机确认）。但：
模块日志里 `X10Filter: palette compatibility`、`X10Filter: palette color compatibility`、
`X10Filter: palette capture hook installed`、`HighPixelEffects: preview palette / preview palette
engine installed` 全都打了 ⇒ **LSPosed 钩子是装上的**，ipuapi 的调色盘栈也在。

决定性的一点：`HighPixelEffects.paletteConfigured()` 是通过
`XposedBridge.invokeOriginalMethod(paletteMode, null, {"common"})` **调用原方法**的，即
**主动尊重系统开关**。所以"模块在不在"不是问题，问题是**开关本身取不到值**。

#### 4.10.2 开关的解析链（反汇编实录）

```
Lut3dDataManager.paletteMode(scene)
  → CameraConfig.d("com.oplus.feature.color.palette.support", false, false)   // → boolean
      CameraConfig.d → w → b → y
        y(String,Z)String :                            // CameraConfig.java:15
            v = FeatureApi.getProjectSupportFeatureValue(key)
            if (TextUtils.isEmpty(v)) { if (def) CameraConfig.a.block(); v = CameraUnitUtils.getVendorTagConfig(key); }
            return v
  → FeatureApi.getProjectSupportFeatureValue(key)      // FeatureApi.java:1，一行转发
  → CameraConfigure.getProjectSupportFeatureValue(key) // 最终落点
```

`CameraConfig.d` 把 `"true"` 映射为 true、`"false"` 为 false，其余按字节解析。所以只要
`FeatureApi.getProjectSupportFeatureValue` 对这一个 key 返回 `"true"`，`paletteMode()` 就是 true。

#### 4.10.3 版本闸门（根因）

`com.oplus.ocs.camera.configure.CameraConfigure`（住在 `/product/framework/com.oplus.camera.unit.sdk.jar`）
的 `getProjectSupportFeatureValue` 只有 30 条指令：

```java
if (!ProtobufConfigureHelper.isFeatureConfigureFileExist()) return null;   // line 319
if (ProtobufConfigureHelper.getConfigParseVersion() != 1) {                // line 321
    PROJECT_FEATURES_READY_CONDITION.block();                             // line 323
}
return sVendorTagMap.get(key);                                             // line 325
```

真正的闸门是 **parse version**，不是文件存在性。两个辅助方法都在 `ProtobufConfigureHelper`
的 `<clinit>` 里算好并缓存（公开的那个各自只有一条 `sget`，返回缓存字段）：

```java
featureConfigureFileExist() = isFileNotEmpty("camera_unit_feature_config.protobuf")   // v1 路径
                           || isFileNotEmpty("oplus_camera_feature_config")           // v2 路径
getParseVersion()           = isFileNotEmpty("oplus_camera_feature_config") ? 2 : 1
```

⚠️ **X8U 是有那份 v1 文件的**（`camera_unit_feature_config.protobuf`，正是本模块 bind 上去的那份），
所以 `isFeatureConfigureFileExist()` 在 X8U 上返回 **true**，第 319 行**不会** `return null`。

真正让取值失效的是：`sVendorTagMap` 只由 `CameraConfigure.generateVendorTagMap(byte[])` 填充，
而**全工程唯一调用它的是 `decodeConfigFileVersion2()`** —— v1 的 `decodeConfigFileVersion1()`
从不调它。v1 下这张表始终是空的 ⇒ `sVendorTagMap.get(key)` 对**所有** `com.oplus.feature.*` 都是
null ⇒ `paletteMode()` 恒 false ⇒ `CombineLutHelper.init(..., "/odm/etc/camera/basictone/palette")`
永远不执行。**效应与"缺容器"一致，但归因必须是 parse version**（用文件存在性判断会在 X8U 上判反，
见 §4.10.9）。

#### 4.10.4 第一版方案（已废弃）：合成 V2 容器

`CameraConfigure.decodeConfigFileVersion2` 的格式（整数**全部大端 int32**）：

```
int32 odmFileVersion
int32 entryCount
repeat entryCount times: { int32 tagId; int32 length; byte[length] payload }
```

- `tagId == 1` → UTF-8 文本 `key:value|key:value`，`generateVendorTagMap` **先按 `|` 再按 `:`**
  分割（写成 `=` 会被当成 value 的一部分）。
- `tagId == 2` → 裸 `ProtobufFeatureConfig.FeatureTable` 字节。

于是把设备**自己的** `camera_unit_feature_config.protobuf`（62,330 B）原样搬为 tag 2，只追加
`com.oplus.feature.color.palette.support=true` 作为 tag 1（产物 62,398 B，`odmFileVersion=2,
entryCount=2`）。**格式本身是对的**：实机确实看到 `config parse ver2: true`。

#### 4.10.5 为什么必须撤回：它会死锁相机（决定性证据）

放上容器后，相机进程**主线程永久阻塞**，界面上根本画不出窗口：

```
ANR: Input dispatching timed out (Application does not have a focused window)

"main" prio=5 tid=1 Waiting
  at java.lang.Object.wait(Native method)
  at android.os.ConditionVariable.block(ConditionVariable.java:98)
  at com.oplus.ocs.camera.configure.CameraConfigure.getProjectSupportFeatureValue(CameraConfigure.java:323)
  at com.oplus.ocs.camera.config.FeatureApi.getProjectSupportFeatureValue(FeatureApi.java:1)
  at com.oplus.camera.configure.CameraConfig.y(CameraConfig.java:15)
  at com.oplus.camera.configure.CameraConfig.B(CameraConfig.java:112)
  at com.oplus.camera.preset.MasterPresetDeferredImportProvider.onCreate(MasterPresetDeferredImportProvider.java:37)
  at android.content.ContentProvider.attachInfo → ActivityThread.installContentProviders
  at android.app.ActivityThread.handleBindApplication
```

逐条对上：

- `PROJECT_FEATURES_READY_CONDITION` 是 `new ConditionVariable()`，Android 里**默认是关闭态**，
  `block()` 会一直等 `open()`。
- 唯一会 `open()` 它的是 `CameraConfigure.initialize()` → `parseFromProtobuf()` →
  `decodeConfigFileVersion2()`（v1 分支则在 `parseFromProtobuf` 里**先** open 再 decode）。
- 而 **`FeatureApi.initialize()` 本身是"晚"调用的**：它的调用点在一段延迟初始化里
  （`androidx/activity/b.run()` 内，旁边是 `runOnUiThread` 与异常上报）。开机日志里**始终没有**
  `CameraConfigure: initialize, configParseVersion:` 这一行 —— 说明 `initialize()` 在主线程卡住时
  从未被执行。
- `MasterPresetDeferredImportProvider.onCreate` 属于 `installContentProviders` 阶段，**早于**
  `Application.onCreate()`。它是本进程第一次问特性值的地方。

⇒ **有人等、没人唤醒。** 原厂 X8U 撞不到这一步，正因为第 319 行就 `return null` 了。

⚠️ 因此**不要再尝试往该目录放 `oplus_camera_feature_config`**：它不是"多一个开关"，而是把整个
`com.oplus.feature.*` 取值路径从"同步返回 null"切换成"等待一个尚未打开的条件变量"。类初始化
（`<clinit>`，line 50-61）只 `new ConditionVariable()`，不 open。

#### 4.10.6 最终方案：在 Java 侧回答这一个开关

> **本节结论已被 §4.10.11 推翻。** 这里写的"在 Java 侧回答开关"确实把 `paletteMode()` 变成了
> true，但实机证明它会**让相机在 basic tone 初始化时原生崩溃**，所以这段钩子**最终没有下发**。
> 保留本节是为了留下"为什么当时这么想"和"错在哪"；当前的落盘状态见 §4.10.12。
>
> 另外本节代码里的钩子目标是 `FeatureApi.getProjectSupportFeatureValue`，那**在 X8U 上也是死代码**
> （`oa.k.b == false`，见 §4.10.10），真正的落点是 `CameraConfig.d`。两处错在 §4.10.10/§4.10.11。

新增 `local.jiege.hook.x10.PaletteFeatureSwitch`（由 `ModuleEntry` 装上）：

```java
XposedHelpers.findAndHookMethod(
        "com.oplus.ocs.camera.config.FeatureApi", loader,
        "getProjectSupportFeatureValue", String.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam x) {
                if ("com.oplus.feature.color.palette.support".equals(x.args[0])) x.setResult("true");
            }
        });
```

- 钩子**在 `CameraConfigure` 之前**拦截，那个条件变量**根本不会被碰到**。
- 只回答调色盘这一个 key；其它 key 落到原路径，行为与原厂 v1 **逐字节相同**（一律 null）。
- 装之前先探一次 `ProtobufConfigureHelper.getConfigParseVersion()`：**只在该值 ≠ 1 时让路**
  （那说明盘上确实有 v2 容器，X10 那类机器能自己回答，且它们的 `initialize()` 时序本来就是对的）。
  ⚠️ **不要改用 `isFeatureConfigureFileExist()` 做这个判断** —— 它在任何带 v1 配置文件的机器上
  都返回 true（含 X8U），会把钩子在最需要它的地方跳过。这是 1.3.1 首版的实机 bug，见 §4.10.9。
- 探不到（类缺失或调用抛异常）就按 v1 处理并照常装挂 —— 装挂只影响调色盘这一个 key，而漏装会让
  面板一直不出现。
- 色卡仍由模块 payload 提供（`palette/{HDR,SDR}/SC16Lut0..8` + 4 个 `SimTool_Palette_*.ini`），
  见 4.10.8。

**副作用为零的判据**：`parseVersion` 保持 1（容器不在盘上），配置文件目录回到**逐文件 `bind_file`**
（不再整目录合并），`/odm` 全程只读。

#### 4.10.7 色卡与 `post-fs-data.sh`

`Lut3dDataManager.e`：`paletteMode == true` 时调
`CombineLutHelper.init(..., "/odm/etc/camera/basictone/palette")`（另有
`com.oplus.natural.tone.support` 分支）。X8U 原厂 basictone 里**没有** `palette/`，而 X10 的
`palette/{HDR,SDR}/SC16Lut0..8`（18 张，各 **49,152 B**）与 X8U 原厂 `lmt/` **零同名** ⇒
可**纯追加、可回退**。

1.3.0 起 PKJ110 的 basictone **只 merge `palette/` 与 4 个 `SimTool_Palette_*.ini`**，
`lmt/`（逐机型 tone 表）保持 X8U 原厂不下发。注意 `bind_merged_dir` 的合并源必须**同时**带上模块
运行时创建的 `setting_Retro/`（`/odm` 只读、无法在挂载视图里重新 mkdir），否则会把它遮住。

#### 4.10.8 回退

1.3.1 下发的 APK 里**没有**这段钩子（见 §4.10.11），所以 X8U 的调色盘开关就是原厂值 `false`，
`paletteMode()` 恒为 false，面板不出现；配置文件目录从未被改动，容器从未落盘，`/odm` 全程只读。
唯一留在设备上的是色卡（`palette/{HDR,SDR}/SC16Lut0..8` + 4 个 `SimTool_Palette_*.ini`），
它们只是多出来的文件，没有开关指向它们，纯追加、可删。

#### 4.10.9 实机踩坑：守卫一度用错方法，钩子在 X8U 上把自己跳过了

（2026-10-14 07:1x，1.3.1 首版上机。日志只有一句：

```
X10Filter: palette switch: platform has its own config container, left alone
```

而设备上 `/odm/etc/camera/config/oplus_camera_feature_config` **并不存在**。原因是 1.3.1 首版拿
`ProtobufConfigureHelper.isFeatureConfigureFileExist()` 当守卫，而这个方法：

```java
public static boolean isFeatureConfigureFileExist() {   // 只读缓存字段，不查盘
    return sFeatureConfigureFileExist;                  // = featureConfigureFileExist()
}
private static boolean featureConfigureFileExist() {    // 真正判定
    return isFileNotEmpty(V1_PATH) || isFileNotEmpty(V2_PATH);   // X8U 有 V1 ⇒ true
}
```

X8U 有 v1 文件 `camera_unit_feature_config.protobuf`（本模块自己 bind 的那份）⇒ 守卫为 true ⇒
连 `FeatureApi` 钩子都没装。**改用 `getConfigParseVersion()`（X8U 上是 1）** 后正常，见 §4.10.6。

教训：判断"这台机器是不是 v2"要问 **parse version**，不能问"配置文件在不在" —— 后者对 v1 也为真。

#### 4.10.10 第二层错：连"取值链"都不是活的 —— `CameraConfig.d` 自带缓存

（2026-10-14 07:1x，把守卫改对之后。钩子装上了，但模式依旧报 false：

```
X10Filter: palette switch installed: com.oplus.feature.color.palette.support=true
HighPixelEffects: mode answers key_support_palette = false
```

为了定位，当时在**两个**候选落点各挂了一个带计数的探针（`CameraUnitUtils.getVendorTagConfig`
与 `FeatureApi.getProjectSupportFeatureValue`），重启相机后：

```
X10Filter: palette switch: vendor tag source com.oplus.ocs.camera.appinterface.adapter.CameraUnitUtils
             loader=dalvik.system.PathClassLoader[DexPathList[[zip file ".../OplusCamera.apk"],...]]
--- vendorTag(...) ---   （零输出）
--- featureApi(...) ---  （零输出）
```

类能加载、钩子在场，但两个方法**一次都没被调用**。原因是 `CameraConfig.d` 的字节码第一段：

```
sget-object v0, CameraConfig.d:Ljava/util/concurrent/ConcurrentHashMap;
invoke-virtual {v0, v2}, ConcurrentHashMap;.get(Object)Object
check-cast v1, Boolean
if-eqz v1, 000f          // 命中直接 return
...  string "true"/"false" / Byte.parseByte(v) > 0  ...
invoke-virtual {v0, v2, v4}, ConcurrentHashMap;.put(Object,Object)Object   // 算完写回
```

也就是说 **`w`→`b`→`y` 整条兜底链每个键、每个进程只跑一次**，之后 `d` 永远从自己的 `Boolean`
缓存返回；`w` 还有第二层 `ConcurrentHashMap f`（String 缓存，`containsKey` 判定）。
`Lut3dDataManager.f` 在进程早期就问过这个键（把 false 缓存了），所以后面再怎么查都不再走兜底。

⇒ **落点只能是 `CameraConfig.d` 本身**——它是所有读者（含 `Lut3dDataManager.f`）必经的那一层，
在它上面前置 `setResult(true)` 与缓存无关。已给 `assets/symbols.json` 加指纹：

```json
"CameraConfig.flag": { "kind": "method", "in": "com.oplus.camera.configure.CameraConfig",
  "params": ["java.lang.String", "boolean", "boolean"], "ret": "boolean", "static": true }
```

实机命中唯一一项，日志自报落点：

```
X10Filter: palette switch installed: com.oplus.feature.color.palette.support = true at com.oplus.camera.configure.CameraConfig.d
X10Filter: flag probe: com.oplus.camera.super.text.mode.support      ← 探针证明确实是这一层
X10Filter: palette switch answered com.oplus.feature.color.palette.support = true
```

教训：**先确认目标方法真的被调用，再讨论它返回什么。** 反编译出"正确的值来源"不等于那是活路径；
带计数的探针比再看三遍反汇编便宜。另外：`invokeOriginalMethod` 只跳过**该方法自己**的钩子，
方法体里对别的方法的调用照走钩子 —— 所以 `HighPixelEffects.paletteConfigured()`
（`invokeOriginalMethod(f, "common")`）能反映 `CameraConfig.d` 上的钩子。

#### 4.10.11 开关生效了，然后相机必崩 —— 结论：X8U 上不能在 Java 层开

（2026-10-14 07:3x。钩子上挂后 40 秒内相机必崩，之后进入崩溃循环。）

```
10-14 07:36:43 I/Zygote: Process 23662 exited due to signal 7 (Bus error)
10-14 07:38:49 I/Zygote: Process 25650 exited due to signal 11 (Segmentation fault)
10-14 07:39:10 I/Zygote: Process 26153 exited due to signal 11 (Segmentation fault)
10-14 07:39:18 I/Zygote: Process 26754 exited due to signal 7 (Bus error)
```

tombstone（`/data/system/dropbox/system_app_native_crash@*.txt`，用
`su -c cp` 到 `/data/local/tmp` 再 `adb pull`，**别 `adb shell cat`**）：

```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000020 (read)
    #00 __memmove_aarch64_nt+592
    #01 libCombineLutJni.so  Java_com_oplus_camera_combinelut_CombineLutJNI_processV3+380
    #04 OplusCamera.apk (offset 0x2608000)  com.oplus.ocs.camera.CombineLutHelper.process+38
    #06 OplusCamera.apk (offset 0x5e000)    com.oplus.camera.common.utils.Lut3dDataManager.e+696
```

**决定性的一步是把 `+696` 换算成 dex pc**：

- `Lut3dDataManager.e(IILjava/lang/String;)Z` 的 `insns size: 545` 个 16-bit code unit
  ⇒ 若 `+696` 是 code unit 偏移，则 696 > 545，落在方法外，不可能；
  ⇒ 它只能是**字节**偏移，`696 / 2 = 348 = 0x15C`。
- 而 `e` 在 `0x15C` 的指令恰好是（见 `e` 的汇编码）：

```
22a5f8: if-eqz v13, 0163          |0126:   ← v13 就是调色盘开关
22a60a: aput-object v2, v1, v3    |012f:   m[5] = "1"
22a61c: aput-object v2, v1, v3    |0138:   m[6] = "1"
22a664: invoke-virtual/range {v13..v22},
        CombineLutHelper.process:(IIIIFFF[Ljava/lang/String;[I)[F   |015c:   ← 崩溃点
```

  且 `v13` 在 `0x0064` 由 `invoke-static Lut3dDataManager.f:(Ljava/lang/String;)Z` 得来，
  即**调色盘模式**。对照 `0x0163` 起的那条分支（调色盘关闭时走）：它只会调
  `process(IIII[I)`（`0x0195`），**根本不会到 `0x15C`**。

⇒ **能走到崩溃点，前提就是调色盘为开；而它变开只可能来自这个钩子**
（原厂 X8U 的 `CameraConfig.d` 对该键返回 false，1.3.1 首版实机日志已证
`mode answers key_support_palette = false`）。

崩溃前的最后两行日志把现场钉死（同一次调用，间隔 0.11 秒）：

```
07:39:10.324 I/OCAM_Lut3dDataManager(26153): initializeBasicToneEnv, combineType: 1, capMode: 2 capModeName common
07:39:10.330 E/OCAM_Lut3dDataManager(26153): initializeBasicToneEnv, init result 0, mPaletteFlag: false, mIsHDR: false
07:39:10.442  SIGSEGV
```

`capModeName common` ⇒ 这次调用正是 `f("common") == true` 的那一支
（`f` 的后半段就是 `"common".equals(scene) || "portrait" || "night"`）。
`init result 0` 说明 `CombineLutHelper.init()` 是成功的，**崩在它后面的 `process`**。

**所以：`CameraConfig.d` 钩子撤掉，1.3.1/1.3.2 都不下发它。**
需要强调的是崩的不是 Java 层——`processV3` 是 X8U 自己的 `/product/lib64/libCombineLutJni.so`，
与相机 APK 同分区；它处理不了调色盘组合。**要真正做成，缺的是 X9U 那份能处理调色盘的原生件
（`libCombineLut.so` / `libCombineLutJni.so`），而它不在这份移植素材里**——和 §4.9 的
`positive_*` 色卡缺口属于同一类问题：开关背后得有设备侧既有实现。

**顺带记一笔"为什么不做半成品"**：可以让 `HighPixelMode.getSupportFunction("key_support_palette")`
单独返回 true，面板就会出现（不动 `CameraConfig.d`），但 `Lut3dDataManager.f` 仍是 false
⇒ 调色盘 LUT 不会被合成，选色**没有任何效果**。面板"显示但无效"比不显示更糟，故不做。

#### 4.10.12 落盘状态（1.3.2）

| | 状态 |
|---|---|
| `CameraConfig.d` 钩子 / `PaletteFeatureSwitch.java` | **已删除**（连 `ModuleEntry` 的装挂、`symbols.json` 的 `CameraConfig.flag` 指纹一起） |
| 色卡 `palette/{HDR,SDR}/SC16Lut0..8` + 4 个 `SimTool_Palette_*.ini` | **仍随包下发**（纯追加、无开关指向、可删） |
| `payload_pkj110/oplus_camera_feature_config` | 不存在（V2 容器，历史上会死锁） |
| 配置文件目录合并 | 不做，两机型都逐文件 `bind_file` |
| `/odm` | 全程只读 |
| 撤回后的实机验证 | 重装 APK + 重启相机，稳定观察期内 **0 次原生崩溃**，模块各钩子正常上挂 |

要重开这个话题，起点是：`symbols.json` 加回上面那个 `CameraConfig.flag` 指纹 → 钩 `CameraConfig.d`
（只对 `com.oplus.feature.color.palette.support` 返回 `Boolean.TRUE`）→ **先准备好 X9U 的
`libCombineLut*.so`**，否则只会重现这次的崩溃。

---

### 4.11 POP 点快门不出片（**第四个独立根因**，2026-10-14 定案）：APS 的预览配置里没有 `retroCamera`

§4.9 修好的是「浓郁」色卡；§4.5–4.7 修好的是 POP 能进、能预览。仍然存在的症状是
**点快门毫无反应**（屏幕快门与硬件相机键都一样，预览正常、滤镜正常、`is_export,true` 从不出现）。

#### 4.11.1 判定方法：`BaseMode.m8()` 上挂探针（`XposedBridge.log`，不走 logcat）

`BaseMode.m8(bb.g, boolean, boolean)` 是拍照前的最后一道总闸，5 个出口里 3 个走 `ab.a.j`(Log.e)
可见、2 个走 `ab.a.t`/`g`（**`ab.a` 的静态位 `b` 恒 false**，见 §4.11.5）静默。
所以只能上探针：`app/src/main/java/local/jiege/hook/pop/PopShutterProbe.java`（**临时文件**，
`PopPort.installCamera` 末尾一行 `Log.guard(TAG,"POP shutter probe",…)` 拉起；收尾时一并删）。

实测（POP vs 照片，同一台机、同一支镜头）：

| 观测项 | POP（`fm.y1`） | 照片（`fm.v1`） |
|---|---|---|
| `m8` 返回值 | **false** | **true** |
| `L4()`（= ModeUi.filterType） | `"polaroid_sdr.mslut"` | `"default"` |
| `bb.g.d`（APS 给的滤镜名） | **null** | `"default"` |
| `bb.g.b`（`PREVIEW_RESULT_CAMERA_ID`） | **-1** | 0 |
| `bb.g.q`（`PREVIEW_RESULT_FEATURE_TYPE`） | **0** | 61 |
| `bb.g.T0`（`PREVIEW_RESULT_LIVEPHOTO_ENABLE`） | **-1** | 0 |
| `logcat: getPreviewConfig, preview config is null` | **33 次** | 0 次 |
| 实际出片 | ❌ | ✅ |

#### 4.11.2 因果链

```
PKJ110 的 /odm/etc/camera/config/oplus_camera_algo_switch_config = X8U 原厂加密容器
        （132984 B，`01 01` 头 + 高熵块；X9U payload 那份是明文 JSON 109976 B）
   ↓ post-fs-data.sh 对 PKJ110 显式 `continue`，这四个 oplus_* 一律不 bind
AlgoSwitchConfig.initialize(ctx)
   → UpdateHelper.getValidConfigData(ctx, "oplus_camera_algo_switch_config")
   → getConfigString()：OplusStringJNI.needGetString() 为假时**按 UTF-8 直接当 JSON 解析**
   → parseConfigData() 填 sPreviewConfigMap（键 = "<mode>@<cameraId>"）
   ↓ 实测映射表 60 个键：4kVideo/aiEnhancement/aiHighPixel/common/commonDummy/commonSatHal/
     commonVideo/…/master/microscope/movie/night/panorama/portrait/sticker/xpan … 
     **没有 retroCamera（也没有 grmode）**
相机请求 `retroCamera@0` ⇒ AlgoSwitchConfig.getPreviewConfig(...) = null
   ↓
APS 不为 POP 产出任何预览结果 ⇒ bb.g 全部停在构造默认值
   ↓
BaseMode.m8 依次撞两处（两处都实测到）：
  ① `this instanceof fm.y1` 的滤镜一致性闸门
     strL4 = L4() = "polaroid_sdr.mslut"；str2 = isEmpty(d) ? "default" : d = "default"
     ⇒ 两个 TextUtils.equals 全不等 ⇒ return false
  ② 闸门放行后（把 d 对齐成 L4() 再跑）走到尾句
     `if (Pc(gVar)) return gVar.b == getCameraId();`
     b = -1、getCameraId() = 0 ⇒ return false
```

`bb.g` 的每个字段都是 OCS 预览结果键（`bb.g.v6(CameraPreviewResult,boolean)` 里现场 `new`
`PreviewResultKey` 逐个取）：`b`←`PREVIEW_RESULT_CAMERA_ID`、`d`←**`"com.oplus.preview.filter_type"`**、
`e`←`PREVIEW_RESULT_ERROR_CODE`、`q`←`PREVIEW_RESULT_FEATURE_TYPE`、
`T0`←`PREVIEW_RESULT_LIVEPHOTO_ENABLE`、`Z0`←`KEY_PREVIEW_RESULT_CAMERA_MODE`。
**POP 下全是默认值 ⇒ 一句话：APS 对 POP 一个预览结果都没给。**
`Pc(bb.g)`（`BaseMode.java:3168`）只在 `q ∈ {73,92,97} && T0 != -1` 时才为 false，POP 的
`q=0 T0=-1` ⇒ `Pc=true` ⇒ 必然走 `b == getCameraId()`。

#### 4.11.3 为什么 GR 没事、POP 有事（这条解释了"POP 是拍照模式的子类"）

`fm/y1`(POP) 与 `fm/v1`(照片) 都直接继承 `dm/w2`(=`CommonCapMode`)，是**兄弟**，不是父子；
POP 多出来的只有 `m8` 里那段 `this instanceof fm.y1`（以及 `Jg` 里同内容、但由 `s3` 门控、
只在换滤镜时跑、不拦拍照的那段）。真正让 GR 能用的是**另一件事**：

- 映射表缺的两个 mode 恰好就是**被移植的两个**：`retroCamera` 与 `grmode`。
- `GrModeHooks` 让 GR **冒充 professional**（`CameraEntry.enter` 把 `"gr"` 换成 `"professional"`，
  再继承 `ProMode` 的方法与 `getSupportFunction`）⇒ GR 走的是 `master@0` 那套现成 APS 配置，
  根本不需要 `grmode`。
- POP 的移植只做了 UI/色卡/mask（`PopResources` / `PopInverseMaskProgram` / `PopHighPixel` …），
  **没做"让 POP 落到一个 APS 认识的 mode 上"这一步**。

#### 4.11.4 两条修法（**路线 B 已实施，见 §4.12**）

> **2026-10-14 更新**：下面两条里，**路线 B 已落地**（用设备原厂明文重建 + 纯追加 `retroCamera`，
> 实机验证 POP 出片）。完整做法、生成器与闸门见 **§4.12**。
> 路线 A（Java 侧兜底）**未采用**，保留在此作为备选。

- **A：Java 侧兜底（GR 同款思路，不碰盘）** —— 钩
  `com.oplus.ocs.camera.consumer.apsAdapter.config.AlgoSwitchConfig.getPreviewConfig`，
  当 `mode == "retroCamera"` 时回退到某个存在的 `PreviewConfig`（如 `master@<id>`/`common@<id>`）；
  再处理 ①（`d==null` 时对齐 `L4()`，或改成"APS 未表态即放行"）。
  可逆、作用域仅限该 mode。**待验证**：`b`/`q`/`T0` 是否因此变正常。
- **B：配置侧** —— 给 PKJ110 造一份含 `retroCamera` 的 `oplus_camera_algo_switch_config`。
  ⚠️ §7 里"需要先解决解密"这句**已被本轮修正**：SDK 的 `getConfigString()` 在
  `needGetString()` 为假时**按明文 UTF-8 读**，所以**明文 JSON 是被接受的**，理论上直接 bind
  X9U 那份即可。但它同时是 `aps_capture_configs` 的全模式表（23 个 mode），
  **会改变所有模式的 APS 行为**，且 X9U 的 `file_version=16.001 / aps_version=6 / aps_mode=1`
  与 X8U 加密版是否同代未知。要动就得整机全模式回归，并按纪律同步
  `tools/verify_port_artifacts.py` 定值与本文档新增一节。
  ⚠️ §4.10 的教训（往盘上补容器把相机锁死）在此同样适用。

#### 4.11.5 两条排障纪律（本轮踩过，写下来免得再踩）

1. **LSPosed 模块代码来自"已安装的 App"**，不是 KSU 模块目录里的 APK：
   `/proc/<pid>/maps` 指向 `/data/app/~~*/local.omnicam.aura-*/base.apk`。
   改完钩子必须 `adb install -r module/omnicam-aura-<版本>.apk`（同 debug 证书，直接过），
   只换 `/data/adb/modules/omnicam_aura/*.apk` **不生效** —— 上一轮"探针没有日志"就是这个，
   不是日志读不到。
2. `ab/a` 的门控（`classes*.dex` `<clinit>`）：`b=true、c..m=false`，
   而 `c` 是全工程唯一写入点且恒 false ⇒ `initLog` 里 `b = c` ⇒ **`b` 恒 false** ⇒
   `ab.a.t`(Log.i)、`ab.a.g`(Log.d)、`ab.a.E`(Log.v) 在这台机器上**永远静默**；
   只有 `ab.a.j`(Log.e)、`ab.a.L`(Log.w) 可见。**"日志里没有"不等于"没发生"** —— 必须加探针。
   设备侧读 LSPosed 日志：`su -c 'cp /data/adb/lspd/log/modules_*.log /sdcard/Download/x.txt'`
   再 `adb pull`（直读 `/data/adb/...` 会被 mode `-rwxrwx---` 挡）。

---

### 4.12 §4.11 的修法落地：PKJ110 的 `oplus_camera_algo_switch_config` 从**设备原厂明文**重建（1.3.3）

§4.11.4 的**路线 B** 已实施。缺口很明确：PKJ110 出厂那份
`oplus_camera_algo_switch_config` 里**没有 `retroCamera`**，所以 POP 拿不到 APS 预览配置。
补上它、且**只补它**，POP 的快门就能正常触发。

#### 4.12.1 缺口定位：原厂表里没有 `retroCamera`

- PKJ110 出厂那份配置（`/odm/etc/camera/config/oplus_camera_algo_switch_config`）的
  `aps_capture_configs` / `aps_preview_configs` 里**不含 `retroCamera`**（也不含 `grmode`）。
- GR 之所以能用，是因为 `GrModeHooks` 让 GR **冒充 `professional`**（见 §4.11.3），走的是
  `master@0` 那套现成配置，根本不需要 `grmode`。POP 没有这层伪装。
- **A/B 实证（决定性）**：把一份**只比原厂多 `retroCamera`** 的同名配置 bind 到
  `/odm/etc/camera/config/oplus_camera_algo_switch_config`，`am force-stop` 后重开相机 ⇒
  POP 出片（`IMG20261014100815.jpg`），`ApsAdapterLog.e "getPreviewConfig, preview config is null"`
  **33 → 0**；照片、大师同时正常 ⇒ **差异确实只有这一个 mode 条目**，与模式类钩子、符号表、
  运行时库都无关。

#### 4.12.2 为什么是“纯追加”，而不是搬 X9U 那份

**不是**把 X9U（PMA110）那份配置文件直接拿来用，原因有二：

1. X9U 那份是 `aps_capture_configs` 的**全模式表**（23 个 mode），整份搬过来会**改变本机所有
   模式的 APS 行为**，风险面远超 POP 本身，要动就得整机全模式回归。
2. X9U 的 `file_version` / `aps_version` / `aps_mode` 与本机那份是否同代未知。

为了让产物**可复现、可审计、且只含我们真的想加的东西**，改为在**设备自己的原厂明文**上做追加：

1. 用临时探针 `ConfigDumpProbe`（hook `UpdateHelper.getValidConfigData(Context, String)` 的
   `afterHookedMethod`，把返回值写到 `/data/user/0/com.oplus.camera/files/config_dump/<name>.json`）
   dump 出**设备自己那份配置解密后的明文**；
2. 在这份**明文文本**上**纯追加** `retroCamera` 块，并把追加块按 X8U 自己的词汇表裁剪
   （见 §4.12.3）。

dump 出来的原厂明文：**132,973 B，是 JSONC**（带两处 `//` 注释，例如
`"aps_version" : 6,  // 2: aps2.0 platfrom; ...`），模式集合 21/36。磁盘上那份 132,984 B 的同名文件
是加密容器（`01 01` 头），**逐字节不同**。⇒ 又一次印证 §4.1 的纪律：**只做文本级 splice，
绝不重新序列化**。

#### 4.12.3 生成器 `tools/build_x8u_algo_switch_config.py`

```
基底   port-x8u/_pristine/x8u-pristine-config/oplus_camera_algo_switch_config   （唯一接受的基底）
模板   module/payload/oplus_camera_algo_switch_config                            （X9U 的 retroCamera 块）
产物   module/payload_pkj110/oplus_camera_algo_switch_config  +  port-x8u/payload/ 镜像
```

- `strip_jsonc()` 剥掉 `//`、`/* */` 与尾逗号，再用 `json.loads(..., object_pairs_hook=OrderedDict)`
  保序解析（原厂文件本身不是合法 JSON）。
- **词汇表裁剪**：`vocabulary(section)` 收集该 section 里所有 dict key 名与 `algo_list` 里的算法名；
  `prune()` 丢掉 X8U 不认识的 key、过滤 `algo_list` 里 X8U 不认识的算法名。实测裁掉
  `aps_algo_colorsensor` / `aps_algo_dcg_fusion` / `aps_algo_rsp`（capture 两个 entity 各若干），
  **保留** `aps_algo_color_palette`（X8U 原厂有这个词）。这样 `retroCamera` 只说 X8U 听得懂的话。
- **排版还原**（否则 diff 会炸）：`aps_capture_configs` 用 ` : `、`aps_preview_configs` 用 `: `；
  标量数组写 `[ a, b ]`；缩进 4 空格；追加块整体再加 8 列；插入点取
  `'\n    "aps_preview_configs"'` 之前（即 capture 数组收尾处）。
- `build_text()` 返回 `(payload, problems, notes)` 供闸门复用；`verify()` 断言
  "模式列表 = 原 + `['retroCamera']`、原有条目逐项相等、顶层键不变"。
- 用法：`python tools/build_x8u_algo_switch_config.py [--check]`。

产物：**141,474 B / md5 `593dbc7913682da175663d708b005120`**。

#### 4.12.4 绑定与发布闸门

- `module/post-fs-data.sh`：把 `oplus_camera_algo_switch_config` 从 PMA110 专属的循环里**摘出来**，
  改成对 PKJ110 **无条件** bind（`bind_file` 自带 umount-first）。PMA110 仍只 bind 另外三个
  `oplus_*`。PKJ110 payload 目录因此从 2 个文件变成 3 个（`camera_unit_config`、
  `camera_unit_feature_config.protobuf`、`oplus_camera_algo_switch_config`）。
- `module/x8u-repair.sh`（设备侧"不重启也能修"的那个脚本）**同步**：D 段的重绑列表从
  2 个文件加到 3 个，A 段的"期望值"也从过期的 29803/62004 B 改成闸门里那三个真值
  （32454 / 62330 / 141474 B）。否则"用修复脚本代替重启"这条路会把 POP 修好的一半漏掉。
  新大小/md5 同步进 `verify_port_artifacts.py` 的 `MODULE_SCRIPTS`。
- `tools/verify_port_artifacts.py`：`PKJ110_PAYLOAD` 加第三项定值；`post-fs-data.sh` 大小/md5 定值
  更新；`check_semantics()` 末尾新增 **`check_algo_config()`**（第 4b 组）：
  动态 `importlib` 载入生成器 → 重建结果无 `problems` → `module/payload_pkj110/` 与
  `port-x8u/payload/` **等值** → 产物与**原厂文本前缀逐字节一致**（到第一个追加点为止）→
  尾部一致（`\n    ]\n}\n`）→ `"mode"` 出现次数**恰好多 2**。

#### 4.12.5 实机验证（自有版本）

- **手工 bind（首验）**：把自有 141,474 B 配置 bind 上去 → 重启相机 → `preview config is null`
  **0 次** → POP 出片 `IMG20261014101708.jpg`（11,332,980 B）→ 照片 `IMG20261014101908.jpg` →
  大师 `IMG20261014101930.jpg`（回归通过）。
- **模块自身 bind（1.3.3 装机终验）**：装机后 `post-fs-data.sh` 在开机时自动完成三个 bind
  （`/proc/self/mountinfo` 的源路径无 `//deleted`，三个 md5 与 payload 逐一相等）→
  `am force-stop com.oplus.camera` 后重开 → 三模式出片：POP `IMG20261014110041.jpg`（13.1 MB）、
  照片 `IMG20261014110153.jpg`、大师 `IMG20261014110222.jpg`，`preview config is null` **0 次**。
  ⇒ **不依赖手工 mount，装包开机即生效。**

⚠️ **本轮事故**：为验证 `post-fs-data.sh` 会自动 bind，执行了 `adb reboot`。**本机是临时 root**
（`com.pkj110.rootapp` 用 CVE-64560 提权 + `insmod kernelsu.ko`，LKM 模式），**硬重启会掉 root**。
详见 §4.12.6。

#### 4.12.6 纪律：绝不能硬重启（2026-10-14 事故）

> 用户原话：**「以后记住不能硬重启，硬重启又掉root了」**。

- 硬重启（`adb reboot` / 断电重启）⇒ `/proc/uptime` 归零 ⇒ LKM 里的 KernelSU 没了 ⇒
  `su` 立刻变成 `/system/bin/sh: su: inaccessible or not found`，`/data/adb` 不可读，**所有模块失效**。
- **只能软重启**（重跑模块脚本而不重置内核），且**必须由用户本人操作**。
- 需要"重启才生效"的步骤（典型：`post-fs-data.sh` 的 bind），优先改用**手工 `mount --bind`**，
  或请用户软重启 —— **不要自己 reboot**。
- 恢复临时 root 只能由用户重跑 `/data/local/tmp` 下的 root 工具（`com.pkj110.rootapp` /
  `pkj110su --daemon` / `rootinit.sh`）。恢复后模块脚本**不会自动重跑**：软重启，或手工补 mount
  （模块内那份 `x8u-repair.sh` 已同步为 3 个配置，可直接当手工修复用）。

---

## 5. 符号层（Java / LSPosed）：209/210 原样可用（**前提是被测 APK 为 7.006.94**）

> **版本前提（务必先看）**：本节数字来自 `G:/ColorOS17/x8u_extract/OplusCamera.apk`，
> 它的 `versionName` 是 **7.006.94**（176,141,656 B）。
> 而实机 `/product/app/OplusCamera/OplusCamera.apk` 是 **7.006.123**（176,189,839 B，
> `lastUpdateTime=2026-10-07 16:41`）——**两者不是同一个 APK**，
> 所以下面的命中率**只对 7.006.94 成立**，在 7.006.123 上必须重跑。
> 重跑用的 dex 已从实机 APK 解出（`G:/ColorOS17/_meta/dex_cam_7123/`，33 个 dex），
> 结论见 §5.1。

用离线回放器把 `symbols.json` 全部 210 条指纹在 X8U 的 dex 上重跑（复现命令见
`G:/ColorOS17/符号比对报告_X8U相机与相册.md`）：

| 段 | X8U 命中 | 说明 |
|---|---|---|
| 相机 | **189 / 190** | 唯一失效：`Zoom.labelBitmap` |
| 相册 | **20 / 20** | 完全一致 |
| 套用本包 `patches/symbols.json` 后 | **190 / 191** | 新增条目命中，旧条目保留给 X9U |

**唯一真实差异 `Zoom.labelBitmap`**：该符号把字段钉死在混淆名 `I6` 上，而 7.006.94 里
`ZoomSeekBar.I6` 已变成 `android.graphics.Path`；两个 Bitmap 缓存改名成 **`H6` / `J6`**。

它的调用点只有一处（`GrFocalLength.dropLabelCache`：切进 GR 前把标签位图缓存置空以强制重绘）。
注意 `Symbols.set()` **只接受单命中字段**，所以不能把原条目改成 `multi`；正确做法是新增一条
多命中符号，Java 侧用 `Symbols.all()` 全部置空：

```json
"Zoom.labelBitmaps": {
 "kind": "field",
 "in": "com.oplus.camera.feature.zoom.view.ZoomSeekBar",
 "type": "android.graphics.Bitmap",
 "multi": true
}
```

`patches/symbols.json` 相对原文件**只多这 6 行**（严格超集，X9U 不受影响——已实测：
camera 190→191、gallery 完全一致、原有条目零改动）；`patches/GrFocalLength.java` 优先用新符号、
缺失时回退旧的 `iconBitmap` + `labelBitmap` 两条 set。

其余 3 个曾被认为失效的 Zoom 符号（`Zoom.unitText`、`Zoom.unit`、`ZoomBase.labelVisible`）
经复核是**离线回放器自身的两个 bug** 造成的假阴性，不是 X8U 差异，已修正（勘误见报告 §7）：

| 符号 | X8U 命中 | 命中依据 |
|---|---|---|
| `Zoom.unitText` | `ZoomSeekBar.nc(String,boolean)String` | 方法内含 `["×","mm"]` 与 `indexOf`/`substring` |
| `Zoom.unit` | `ZoomSeekBar.B5:String` | `writtenBy`=`Eg`(relabel)，`readBy` 含 `getZoomUnitSuffix` |
| `ZoomBase.labelVisible` | `ta.q.C1:boolean` | 被 `ra`(drawText)/`t7`(drawChip) 读，且**未被** `onSingleTapUp` 写 |

## 6. 回退与构建

**回退**（本仓库不是 git 仓库，所以备份是唯一退路）：

```powershell
Copy-Item port-x8u\backup-pma110\cpp\gr_basictone.cpp            app\src\main\cpp\gr_basictone.cpp -Force
Copy-Item port-x8u\backup-pma110\assets\symbols.json             app\src\main\assets\symbols.json -Force
Copy-Item port-x8u\backup-pma110\java\GrFocalLength.java         app\src\main\java\local\jiege\hook\gr\GrFocalLength.java -Force
Copy-Item port-x8u\backup-pma110\module\post-mount.sh            module\post-mount.sh -Force
Copy-Item port-x8u\backup-pma110\module\post-fs-data.sh          module\post-fs-data.sh -Force
Remove-Item module\payload_pkj110 -Recurse -Force
```

**构建**（本机已验证具备的组件）：

| 组件 | 本机状态 |
|---|---|
| JDK | `C:\Program Files\Java\jdk-25.0.3` |
| Android SDK | `C:\Users\Mr-DCP\AppData\Local\Android\Sdk`（由 `local.properties` 指定） |
| platform | `android-37.0` ✅ |
| build-tools | `37.0.0` ✅ |
| NDK | `29.0.14206865` ✅ |
| CMake | `3.22.1` ✅（本轮新装） |
| Gradle | wrapper 指定 `9.7.1`，联网下载 |

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.3'
.\build.ps1
```

产出 `OmniCam-Aura-1.2.0-KSU.zip`（内含 APK、模块脚本、两套 payload）。

**实机验收顺序（每步都可回退）**

0. 刷入后**先看日志确认机型判定**：`/data/adb/modules/omnicam_aura/mount.log` 首行应为
   `begin profile=PKJ110`，`bind.log` 首行应为 `begin profile=PKJ110 config=.../payload_pkj110`。
   若显示 `profile=PMA110`，说明判定错了，**立刻停手**再查。
1. 先只装 APK（模块只装 `service.sh` 部分）→ 相机能否正常启动、GR 模式是否出现在模式列表。
2. 看 LSPosed 日志有没有 `RicohGrPort: getSettingInt layout=PKJ110/7.006.94 offset=0x13f46d4`。
   出现这一行说明指纹表命中、native hook 已装上。
3. 进 GR 拍一张，日志应出现 `RicohGrPort: BasicTone profile=GR path=/data/user/0/com.oplus.camera/files/ricoh_gr/lmt`。
4. 变焦条切到 GR：标签应变成 35mm 等效焦距且单位独立绘制。
5. 若 3 没出现：在 `isGrFrame` 里加日志打印 `frame`、`*(void**)(frame+0x8110)`、
   `getSettingInt(settings, &kGrSizeKey, -1)` 的返回值，确认 `gr_effect_size` 是否真为 63。
6. 若崩溃：优先怀疑 `frame+0x18` 与 `object+0x008`——静态核对一致，但属于**写**操作；
   先注释掉 `withGrCaptureMode` 与 `loadLut` 的路径改写，保留 `getLmtParams`/`getVigParams` 观察。

## 7. 仍未解决 / 需要人工决定的事项

- [ ] ISP GR 调谐（`com.qti.tuned.zf*.bin`）与 gamma 表的 X8U 版本 —— 需要厂商调优工具，本工程无法生成。
- [ ] `oplus_camera_config` / `aps_config` / 两个 `*_decision*.json` 的解密与 GR 化
      （X8U 盘上为加密二进制）。`oplus_camera_algo_switch_config` **已解决**（§4.12）。
      > **2026-10-14 更正**：**解密不是必须的**。SDK 的 `UpdateHelper.getConfigString()`
      > 在 `OplusStringJNI.needGetString()` 返回假时**直接把字节按 UTF-8 当 JSON 解析**，
      > 所以**明文 JSON 会被接受**（X9U payload 里那几份就是明文）。
      > 真正的取舍变成"要不要用 X9U 的全模式表覆盖 X8U 的行为"，见 §4.11.4 路线 B ——
      > 最终**没有**用 X9U 的表，而是从设备原厂明文重建，见 §4.12。
- [x] **POP 点快门不出片**（§4.11）—— **已修复（1.3.3，§4.12）**。根因：PKJ110 的
      `oplus_camera_algo_switch_config` 里没有 `retroCamera`，
      `AlgoSwitchConfig.getPreviewConfig("retroCamera",0,…)` 返回 null，APS 不给预览结果，
      `bb.g.b=-1` ⇒ `BaseMode.m8` 尾句 `return gVar.b == getCameraId()` 必为 false。
      修法采用**路线 B**：从设备原厂明文重建该配置并**纯追加** `retroCamera` 块
      （`tools/build_x8u_algo_switch_config.py`，产物 141,474 B /
      `593dbc7913682da175663d708b005120`），`post-fs-data.sh` 对 PKJ110 无条件 bind。
      实机 POP / 照片 / 大师三模式回归通过，`preview config is null` 33 → 0。
- [ ] **X10 调色盘：Java 层开不了，卡在 X8U 自己的原生 LUT 合成器**（§4.10）。
      1.3.0 的容器方案会把相机锁死（主线程 `ConditionVariable.block()`，§4.10.5）；
      1.3.1 改用 Java 侧回答 `com.oplus.feature.color.palette.support`（落点是 `CameraConfig.d`，
      不是 `FeatureApi`，见 §4.10.10），**开关确实生效**（`paletteMode()` 变 true），
      但相机随即在 `Lut3dDataManager.e` 走调色盘分支时**原生崩溃**：
      `processV3` 读野指针（`libCombineLutJni.so`），反复重启（§4.10.11）。
      1.3.2 已撤回该钩子；X10 的 `palette/{HDR,SDR}` 色卡 + 4 个 `SimTool_Palette_*.ini` 继续 merge。
      要真正做成，**前置条件是拿到 X9U（PMA110）那份能处理调色盘组合的
      `/product/lib64/libCombineLut.so` + `libCombineLutJni.so`**，本工程素材里没有。
      把面板单独显示出来（不动 `CameraConfig.d`）是可行的，但选色不会有任何效果，故不做。
- [ ] `libmsnativefilter.so` 是否要替换（模块 10.96 MB vs 设备 7.99 MB）——默认不换。
- [ ] 实机验证 `frame+0x18` / `object+0x008` 两处写操作（静态一致，但仍属高风险写）。
- [ ] `Zoom.labelBitmaps` 用的是类型级多命中，若后续机型把非缓存 Bitmap 也放进 `ZoomSeekBar`，
      需要收紧（可加 `readBy` 限定到 `@Zoom.drawText`/`@Zoom.drawIcon`）。
- [ ] PKJ110 的 `payload_pkj110/` 是否要补齐 `oplus_*`：~~需要先解决解密~~
      → **2026-10-14 更正：解密不是前置条件**（SDK 接受明文 JSON，见 §4.11.4）。
- [x] ~~设备实际相机版本是 7.006.123，不是移植物依据的 7.006.94~~ —— **已完成**。
      设备 APK 已拉到 `x8u_extract/OplusCamera-7.006.123.apk`（176,189,839 B），解出 33 个 dex 到
      `_meta/dex_cam_7123/`，`replay.py camera dex_cam_7123 replay_camera_7123.json` 结果
      **190/191 命中（99.5%）**；唯一 MISS 仍是 `Zoom.labelBitmap`（`0 matches`），已有
      `Zoom.labelBitmaps` 多命中替代且命中 → **`symbols.json` 在 7.006.123 上不需要改**。
- [x] ~~**只差 `gr_basictone.cpp` 的 `getSettingInt` 序言指纹**（7.006.123 上 fail-safe 落空）~~
      —— **误判，已撤销**：`resolveSettingInt()` 按**序言字节**匹配，`build` 字段只打日志；而
      `libAlgoInterface.so` 属 ODM 固件、不随相机 APK 版本走。本地实读偏移 `0x13f46d4` 的 16
      字节与表里 `PKJ110` 条目**逐字节相同**（§4.8.3）⇒ PKJ110 上 GR 原生 hook 本来就能装上。
      上机只需一条 `md5sum /odm/lib64/libAlgoInterface.so`（预期 `28715a07bac87e3c2d286c9db2ca8f7c`）
      闭环。裸偏移确实不用重取，但原因是"库没变"，不是"只缺指纹"。
- [ ] `module/service.sh` **无机型门控**。它会无条件写 PMA110 的
      `com.oplus.camera.mode.data.db.version 102`、`com.oplus.camera.capture.hdr.cap.mode.value`、
      `com.oplus.camera.preview.hdr.cap.mode.value`（含 `xpan`/`underWater`/`telephoto`/`gr`）。
      这些是逐设备键，且是**硬替换整个值**而非追加。
      **本轮仍不动它**：这一轮只改配置一处变量，保持"单变量可二分"；若补齐配置后 POP 仍在
      `createNewSession()` 之后出问题，它就是下一个嫌疑（那时再按"只在键缺失时才写、或按
      `detect_profile` 分流"收敛）。
- [x] ~~**待实机验证 `camera_unit_config` 32,454 B / `6b6c77aa…`**~~
      —— **已完成（2026-10-13 晚，用户实机确认"全部正常"）**。离线三向核对全绿（§4.8.2），
      随后上机验收通过：打开相机正常，切 POP **画面实时动**、不再定格也不闪退，切回照片/人像
      亦不连带卡死。POP 全部滤镜（含此前失效的「浓郁」，§4.9）均生效。
- [x] ~~**未解之谜：`bind_file` 为什么在 14:59 那次没有重建配置 bind。**~~
      —— **已闭环**。`bind_merged_dir` 在同一次执行里成功重建（挂载 ID 变化 + 源路径变成新
      `MERGED_ROOT`），但两个配置的挂载 ID 仍是 266 / 279、源仍是 `//deleted`。真因是**悬空 bind
      仍持有 `nlink=0` 的旧 inode，`mount --bind` 覆盖不上去**（`mountinfo` 的 `//deleted` 后缀）。
      已在 `bind_file` 里补 umount-first 循环。上机复核：`/odm/etc/camera/config/` 下两个配置的
      bind 源**不再带 `//deleted`**，live 与 payload 的 md5 一致。
- [x] ~~实机确认四条候选是否修好~~ —— **四条已在设备上逐项验证通过**（2026-10-13 15:18，
      无需 root，走 `/odm` 直读 + `adb pull`）：
      | 检查项 | 设备实测 | 期望 |
      |---|---|---|
      | `libBasicTonePhoto.so` | **538400**，mtime `2026-10-07 23:15` | 538400 |
      | `libAlgoInterface.so` | **60867488** | 60867488 |
      | `libmsnativefilter.so` | **7992584** | 7992584 |
      | `/odm/lib64` overlay | **已无挂载**（`mountinfo` 里整条消失） | 无 |
      | `camera_unit_config` | **29803 B** / `17290f60…` | 同 |
      | `..._feature_config.protobuf` | **62004 B** / `02fe7031…` | 同 |
      | `basictone` / `meishe_lut` | `lmt odt setting setting_Retro vig` / 整排 `.bin` | 非空 |
      四个 bind 的源路径也都不再带 `//deleted`。**模块侧状态已 100% 正确。**

      > **⚠️ 本轮（17:43）更正**：上面这份状态**当时只对了一半**，且后来被 KSU 原地重装打乱：
      > * 两条配置 bind 的源**又变回 `//deleted`**（`nlink=0`，见 §4.6.5），
      > * 设备 `post-fs-data.sh` 用的是**旧版（`injection DISABLED`）**、`payload_pkj110/….protobuf`
      >   被换回**旧的 62,004 B / `02fe7031…`（池 296）**。
      > 也就是说"注入生效"只是因为 **live 挂载恰好还握着那份已删除的好 inode**（62,330 B / `b0ee50c1…`），
      > **磁盘上是坏的**。已于 17:43 就地修好，详见 **§4.6.5**。判断注入状态请始终用
      > `md5sum 挂载视图` vs `md5sum 磁盘文件` 两两对照，**不要只看文件大小/mtime**。
- [x] ~~唯一剩下的未知量是相机 UI 本身~~ —— **根因已定位并修复（见 §4.4）**：不是"黑屏"，是启动即崩
      → 被系统拉黑。崩溃 `IndexOutOfBoundsException: Index 314 out of bounds for length 296`
      的真因是 **X9U 条目的字符串池下标没按字符串身份重映射到 X8U 的池**（不是"基线 odm 旧"）。
      - 崩溃证据（`adb shell "dumpsys dropbox --print"`，本地 `_meta/crash_timeline.py` 分类）：
        **109** 次 `IndexOutOfBoundsException`（15:10:58–15:39:40，移植配置生效期）
        + **7** 次 `NullPointerException @ CameraPreviewUI.java:638`（15:56:06–15:58:51，配置已撤期）。
        两类窗口互不相交 → 是两个签名，不是同一个。
      - 拦截证据：`logcat` 每次 `W/OplusAppStartupManager: prevent start by classifyResrict com.oplus.camera`。
      - 限制记录位置（`com.oplus.battery` 的 `record.db`，表 `unstable_record`）：
        ```
        _id=3  com.oplus.camera|0  exception_class='java.lang.IndexOutOfBoundsException'
                                   exception_msg='Index 314 out of bounds for length 296'
                                   unstable_reason='crash'  restrict_begin_time=1791877068795
        ```
        **只有 `crash` 那一行的 `restrict_begin_time` 非零 → 它才是"当前被限制"的开关。**
      - 解除路径（逆 `Battery.apk`）：`derestrictUnstableApp`，文案 `"the app has been updated"`；
        UI 入口 `PreventRecordActivity`（需 `oplus.permission.OPLUS_COMPONENT_SAFE`，必须手点）。
- [x] ~~重建配置移植的前提：先拿到设备自带的原厂配置~~ —— **已完成**。设备拉回的原厂配置
      与构建基线**逐字节相同**（`camera_unit_config` `6ff8bb9c…`、protobuf `d28f6cec…`，池皆 296），
      "基线比设备旧"被证伪 → 直接用原基线重建即可，无需换基线。
- [ ] **待实机验证新产物**：`camera_unit_config` 29,803 B / `17290f60…`、
      `camera_unit_feature_config.protobuf` **62,330 B** / **`b0ee50c1…`**（池 296→307，条目 20→23）。
      离线复核：16380 处池引用、最大下标 306 < 307、越界 0、三条目 token 与 X9U 全等
      （`_meta/verify_port_payload.py` + `build_x8u_port.py` 自检）。推包后需确认：
      1. 相机能启动（不再 `Index 314`）；
      2. **切到 POP / GR / 移轴界面不再崩**（验证 §4.4.3 的 NPE 是否同根）；
      3. `/odm/etc/camera/config/*` 两条 bind 的源不带 `//deleted`。
- [ ] `service.sh` 的 PMA110 硬替换键（`mode.data.db.version 102` 等）仍未处理，但**已降级**：
      相机现在连进程都起不来，`service.sh` 那几行还没机会被执行到。等相机能起来之后再单独看。

## 8. 复现本移植包的工具

### 8.1 仓库内（离线，随包交付）

```
tools/verify_aura.py             模块布局硬断言 + 打包一致性（原有）；含"未重落 parse-version-2 容器 + 调色盘开关不得装挂"反断言
tools/verify_port_artifacts.py   发布闸门：md5 定值 / payload 镜像 / JSON 形状 / 语义 diff / 原生指纹 / zip 内容
tools/verify_payload_json.py     pair-list 变形检测（扫描 module/payload* + port-x8u/payload）
tools/zip_module.py              打包 module/ → OmniCam-Aura-<ver>-KSU.zip

module/x8u-fix-pop.sh            【设备侧】umount-first bind + 写回 payload + 六项复核（随模块下发，可无 PC 直接跑）
module/x8u-pop-diag.sh           【设备侧】12 组诊断取证，一键 pull
module/x8u-repair.sh             【设备侧】免重启修复（库层 overlay 残留，§3.7）
module/x8u-config-revert.sh      【设备侧】配置级回退到原厂

port-x8u/apply-pop-fix.sh        【主机侧】唯一入口：check / apply / revert / diag
port-x8u/x8u-apply-config.sh     早期版本：把 /data/local/tmp/x8u-cfg 下的配置 bind 上去
port-x8u/broken/                 留档的坏版（pair-list），刻意放在 module/ 之外
```

设备侧脚本放 `module/` 是为了**随包一起装进 `/data/adb/modules/omnicam_aura/`**——
`x8u-fix-pop.sh` 在找不到 host 推入的暂存目录时会**退化为直接用模块自带的
`payload_pkj110/` 当源**，所以没 PC 也能修：

```bash
su -c 'sh /data/adb/modules/omnicam_aura/x8u-fix-pop.sh check'
su -c 'sh /data/adb/modules/omnicam_aura/x8u-fix-pop.sh apply'
```

### 8.2 设备取证与逆向（`G:\ColorOS17\_meta`）

```
G:\ColorOS17\_meta\build_x8u_port.py     # 生成 payload/ 下两个配置文件（camera_unit_config / feature protobuf）
G:\ColorOS17\_meta\build_x8u_symbols.py  # 生成 patches/symbols.json（原表 + Zoom.labelBitmaps）
G:\ColorOS17\_meta\replay.py             # symbols.json 离线回放（REPLAY_SYMBOLS 可指定符号表）
G:\ColorOS17\_meta\dexlib.py             # 零依赖 DEX 读取器（已修 MUTF-8 长度与 iput 族）
G:\ColorOS17\_meta\insp_zoom.py          # 类/方法/字段转储 + 单方法代码关系
G:\ColorOS17\_meta\jsonpatch.py          # 字节级 JSON 手术（按路径定向 splice，不重序列化）
G:\ColorOS17\_meta\verify_proto.py       # 无 schema 严格 protobuf 校验（顶层必须恰好消费完）
G:\ColorOS17\_meta\pb_path.py            # 递归定位 map entry 的嵌套路径 + 同级兄弟（查出 §4.2 的错位）
G:\ColorOS17\_meta\pb_keys.py            # 列出顶层 field1 的 map key
G:\ColorOS17\_meta\pb_elems.py           # 元素级 protobuf 转储
G:\ColorOS17\_meta\diff_json.py          # 递归 JSON diff + 重复键检测
G:\ColorOS17\_meta\test_profile.sh       # detect_profile 分支测试（真实属性集桩，log 落盘）
G:\ColorOS17\_meta\field_xref.py         # 全局字段读写者交叉引用（writtenBy/readBy 判定）
G:\ColorOS17\_meta\dis_verify.py         # ELF 符号/反汇编/地址反查
G:\ColorOS17\_meta\field_scan.py         # 函数内字段偏移与调用目标抽取
G:\ColorOS17\_meta\cmp_func.py           # 同源函数逐指令比对（difflib 对齐）
G:\ColorOS17\_meta\extract_nid.py        # 按 nid 从 EROFS 提取文件
G:\ColorOS17\_meta\erofs_dir.py          # 多块目录正确遍历
```

隔离 Python：`C:\Users\Mr-DCP\.workbuddy\binaries\python\envs\default`（已装 lz4、pyelftools、capstone）。
