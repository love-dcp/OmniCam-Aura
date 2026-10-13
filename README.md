# OmniCam Aura

![OmniCam Aura 图标](artwork/OmniCam-Aura.png)

OPPO Find X9 Ultra（PMA110，相机 7.006.77）与 OPPO Find X8 Ultra（PKJ110，相机 7.006.94／实机
7.006.123）的无界面 KernelSU 模块与 LSPosed 插件。同一个包在两台设备上通用，机型在开机时自动判定。

## 功能

- 理光 GR 模式、影调、焦距条、快照对焦及相机／相册水印。
- POP 模式、复古资源与高像素支持；滑杆进成片，成片走 Ultra HDR。
- 移轴拍照模式。
- 专业视频（含 O-Log2）强制启用 ISZ：主摄 2x、长焦 6x 去掉 ISO 门限（模块目录放
  `disable_pro_video_isz` 可关闭）。**仅 PMA110 生效**，原因见下表。
- Find X10 的清透／琥珀滤镜、调色盘预览与成片、闪光灯亮度、AI 构图配置及哈苏相关滤镜、
  调色盘和柔光资源。
- 大师模式：相机异常重启后预设列表补全，滤镜不再成片显示为“默认”；当前预设按预设记录
  而非列表位置保持。

LSPosed 包名为 `local.omnicam.aura`，作用域为 `com.oplus.camera` 和 `com.coloros.gallery3d`。
APK 没有 Activity、启动入口或设置界面，功能固定启用。

## 机型档案

模块启动时判定机型（判定结果写在模块目录的 `mount.log` 与 `bind.log`），两条路径的差别：

| | PMA110（Find X9 Ultra） | PKJ110（Find X8 Ultra） |
|---|---|---|
| 配置来源 | `payload/`，完整配置集 | `payload_pkj110/`，两份按 X8U 原厂数据重新生成的配置；调色盘开关由 APK 在 Java 层补（见下） |
| 后端库 | 用模块自带的 X9U 后端覆盖 `/odm/lib64` | **完全不改**，X8U 自己的库即真实后端 |
| ISP 调谐 | 挂载 `com.qti.tuned.lighthouse*.bin` | 不下发（X8U 用逐传感器的 `com.qti.tuned.zf*.bin`） |
| gamma 表 | 挂载 | 不下发（逐传感器曲线不同） |
| X10 调色盘调谐 | merge | 只 merge `palette/{HDR,SDR}` 色卡与 4 个 `SimTool_Palette_*.ini`；`lmt/` 保持原厂。开关由 `PaletteFeatureSwitch` 在 Java 层回答 |
| Pro video ISZ | 启用 | 跳过（未在 X8U 上验证过） |

**一条硬规则：凡是设备相关的后端库都不能放进模块的 `system/` 目录。** KernelSU 的
`metamount.sh` 会把 `system/` 下的树无条件叠加到系统分区，脚本没有机会按机型拦截。把 X9U 的
`libAlgoInterface.so` / `libBasicTonePhoto.so` / `libmsnativefilter.so` 放在那里，X8U 上会被
叠成「X9U 的 8 KB 路由桩去 dlopen X10 后端」，预览停住、取景黑屏。这三个库因此放在
`payload/lib64/`，由 `post-mount.sh` 只在 PMA110 上 bind；`system/` 只保留
`libBasicTonePhotoX9/X10.so` 两个惰性文件（overlay 是唯一能「凭空创建」路径的机制，X9U 需要它们
做 bind 目标，而 X8U 的库不引用这两个名字）。

PKJ110 的完整移植过程、两条 POP 故障的根因分析（配置层 `usecase_info` 畸形、native 层
`dlopen(NULL)` 空指针）与 POP「浓郁」滤镜缺失色卡的处置，见
[`port-x8u/README.md`](port-x8u/README.md)。

## 安装

1. 在 KernelSU 中安装发行页的模块 ZIP。
2. 安装同一发行版的 APK，并在 LSPosed 中启用 Aura，勾选相机和相册作用域。
3. 重启设备使模块挂载和 Hook 生效。

Aura 与 OmniCam Muse 的相机 Hook 和挂载资源重叠，只启用其中一套。

## 构建

在 Windows 上安装 JDK 17、Android SDK（含 API 37、Build Tools、NDK 29.0.14206865 和
CMake 3.22.1）、Python 3 与 Git，然后运行 `./build.ps1`。脚本从 `module/module.prop` 读取版本号，
生成配套 APK 和同名的 KernelSU ZIP，并进行结构与签名检查。设置 `ANDROID_HOME`／
`ANDROID_SDK_ROOT` 或通过 `local.properties` 指向 SDK。

两道路闸可以单独运行：

```bash
python tools/verify_aura.py             # 包完整性、LSPosed/native 入口、布局不变式、签名
python tools/verify_port_artifacts.py   # PKJ110 载荷定值、语义 diff、原生指纹、归档一致性
```

构建默认使用 Android 调试签名。若要使用自己的发布证书，设置 `OMNICAM_KEYSTORE`、
`OMNICAM_STORE_PASSWORD`、`OMNICAM_KEY_ALIAS` 和 `OMNICAM_KEY_PASSWORD`；证书与密码均不应提交
到仓库。不同证书签发的 APK 不能直接覆盖安装。

## 来源与许可

本仓库的原创源码和脚本按 [MIT 许可证](LICENSE) 开放。相机厂商的库、固件配置及媒体资源保留原
权利人的权利；MIT 许可证不授予这些文件的再许可。项目与相机厂商无隶属关系。
