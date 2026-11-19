# Native/PRoot Box64 的 GStreamer 依赖回移

将上游 [PR #4528](https://github.com/ptitSeb/box64/pull/4528) 的一行依赖修复回移到默认 Box64 0.4.4，不升级主版本。`wrappedgstreamer.c` 使用 GLib 的对象系统，依赖应为 GLib/GObject/GModule。源码文件 `gtkclass.c` 的名字并不代表需要安装 GTK。

用户随后明确授权扩展到当前六项候选并原地发布：Native/PRoot 各自的 0.2.8、0.3.8、0.4.4。六项状态和原/新归档摘要以 `release-manifest.json` 为准；以下初次 0.4.4 对照结果保留为历史验证证据。

## 六候选构建与打包

三个版本固定提交及独立补丁见 `source-locks.json` 和 `patches/`；每版源归档逐文件对照，仅 `wrappedgstreamer.c` 一行不同。全部在原编译机的 SDK 内，以 `build-matrix.sh SOURCE NEW_BUILD NEW_OUTPUT native|proot` 构建；PRoot0.4.4 复用下述已验证候选。Native 解释器/RUNPATH 指向 Termux GLIBC，PRoot 解释器为 `/lib/ld-linux-aarch64.so.1` 且无 Native RUNPATH，LOAD 对齐至少64KiB。

`replace-package-binary.py` 从六份当前公开原包分别生成同名新归档，拒绝覆盖原包，只替换指定的 AArch64 Box64 ELF。逐成员比对其余内容和权限、路径、时间、属主及链接不变；原包和索引留存回滚。组件版本、ID、标签、文件名、URL和默认选择保持，索引修订仍为1，不触发已装组件自动升级。

编译机的六目标共42项验证通过：每项分别检查版本、静态/动态 x86_64 程序及 GStreamer 核心、图案帧、自定义元素回调、WMV2 实际解码；六项目标均无多余 GTK 依赖错误。模拟器 Native 媒体检查使用独立 SDK 测试库和设备 GLIBC，PRoot 使用现有 Ubuntu 媒体库。设备实际结果和公开下载核验在最终发布清单中单独记录，不把 SDK 检查等同于完整 App/游戏验收。

本轮完整构建、原包、发布包及证据目录为同一 SDK 的 `/work/idap-box64-gstreamer-release-20261119/`。已安装用户使用更新后的 App，手动重新安装对应 Box64 组件取得新归档，无需重建 Wine 前缀。生产资产的可回滚输入在该目录 `baselines/`。

## 构建来源

组件与探针均在 `builder@120.24.191.156:6022` 编译，沿用已有 SDK：

```text
/home/builder/proton-sud-dc26-20261108/debian13-buildroot
```

候选、源码、夹具及原始日志保存在该 SDK 的 `/work/idap-box64-gstreamer-20261119/`。编译前读取 `box64-0.4.4-proot-results.tar.xz` 的 CMakeCache、历史 Wine/Proton 构建及补丁；对照源码归档检查 2587 个文件，只有 `src/wrapped/wrappedgstreamer.c` 不同。

`source-lock.json` 固定源码、上游修复、补丁、构建脚本及候选二进制摘要。在编译机的 SDK 内，对新解压的锁定源码应用补丁后执行：

```sh
patch -d /work/box64-source -p1 < box64-0.4.4-glib-dependencies.patch
sh build-proot.sh /work/box64-source /work/new-box64-build /work/new-box64-output
```

脚本拒绝复用已存在的构建及输出目录。编译参数沿用 ARM64 dynarec、Release、BAD_SIGNAL、NOLOADADDR、64 KiB LOAD 对齐和 `/lib/ld-linux-aarch64.so.1`，不覆盖历史构建或正式组件。

## 已验证结果与边界

编译机 Debian 13 SDK / QEMU aarch64 / GStreamer 1.26.2：

| 检查 | ARM64 原生 | 原版 Box64 | 回移候选 |
| --- | --- | --- | --- |
| 核心流水线、EOS 与释放 | 通过 | 通过，报 GTK 缺失 | 通过，无 GTK 报错 |
| 64×64 图案帧与校验和 | 通过 | 通过，报 GTK 缺失 | 通过，无 GTK 报错 |
| 自定义元素状态回调 | 2 次，通过 | 2 次，通过 | 2 次，通过 |
| AVI/WMV2 实际解码 | 通过 | 通过 | 通过 |
| 历史静态/动态 ELF 冒烟 | 不适用 | 通过 | 通过 |

共 16 项通过。WMV2 解码输出 64×64 I420、6144 字节，FNV-1a 校验和 `4180745012`，与已有 FFmpeg 4 编解码探针的参考输出一致。测试依赖只解压到独立目录，没有安装到 SDK 根目录或生产镜像。

Android 模拟器的 Ubuntu 26.04.1 / GStreamer 1.28.2，在 App 的 `untrusted_app_27` 域中复测：核心流水线、自定义元素与 WMV2 解码共 9 项通过。镜像裁剪后没有 `videotestsrc`，图案生成测试在 ARM64 原生、原版和候选中均以原始退出码 1 失败；保留该限制，不为测试图案向生产镜像补包。

低层探针对比使用独立注册表，并设置 `GST_REGISTRY_FORK=no` 方便诊断。它不构成修改生产扫描策略的建议；公开 Wine 媒体测试保持默认扫描策略。Wine 探针通过 App 的 TermuxService 执行，使用隔离的前缀副本、现有 Xlorie 和 PulseAudio，播放结果、COM 释放与原始进程退出码分别记录。

公开 DirectShow 探针采用 App 后端的 `wine explorer /desktop=idap-gst,1280x720` 命令，播放同一个 AVI/WMV2 夹具：

| Proton 11 测试 | 播放及 COM 释放 | 原始会话退出码 |
| --- | --- | --- |
| 原版 / PE64 | RenderFile 成功、完成事件、64×64、failures=0；释放 Completed | 0 |
| 候选 / PE64 | 同上 | 0 |
| 原版 / PE32 | 180 秒启动超时，无播放或释放结果；日志未进入 GStreamer | -9，超时清理；测试状态 124 |
| 候选 / PE32 | RenderFile 成功、完成事件、64×64、failures=0；释放 Completed | 1，探针结束后关闭私有 wineserver |

候选 PE32 的播放、释放通过与会话退出码 1 分开保留，不能记为自然退出 0。原版 PE32 的单次启动超时不构成补丁修复 32 位启动的因果证明。此次覆盖真实 App 域及虚拟桌面后端命令，未覆盖 Java 控制器、冻结 LaunchSpec 或真实游戏的完整用户流程，也不等于全部编码格式或 Media Foundation 验收。

早期直接调用探针、没有虚拟桌面的两组 PE64 测试均在 Video Renderer/Explorer 建窗阶段失败；补齐 App 虚拟桌面命令后原版与候选均通过。因此早期窗口失败不能当作 GTK 或媒体缺库证据。另有一组超时清理不完整的早期结果已标记为受干扰，不用于验收。

**原版也能完成实际解码和 Proton PE64 视频播放，因此 GTK 依赖错误尚未被证明是此前 Wine 播放失败的根因。** 历史 Proton 的 `winegstreamer.so` 引用的 254 个 GStreamer/GObject 符号均可在 Box64 wrapper 声明中找到；这只是静态覆盖核对，不能证明回调和真实游戏兼容。

汇总见 `verification-summary.json`。完整编译及 Android 证据包保留在编译机候选目录，分别为 `verification-evidence.tar.xz` 和 `device-evidence.tar.gz`。Android 测试结束后已验证无测试进程及前缀副本，原前缀注册表哈希检查通过；证据回传核验后清理模拟器专用测试目录。

## 交付状态

初次 0.4.4 对照阶段只保留独立候选。本轮六候选的发布授权及状态见 `release-manifest.json`，组件和 App 索引同步更新；不修改镜像、APK、共享根目录或用户 Wine 前缀。发布回滚使用已备份的原归档和索引。

仓库缺少 `.harness/scripts/verify.sh`；严格门禁实际尝试返回 127，不能记为通过。产品文档和归档等待最终验收。
