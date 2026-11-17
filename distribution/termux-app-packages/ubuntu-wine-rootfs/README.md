# Ubuntu 26.04 Wine 镜像构建

这些构建工具只准备候选制品，不修改已安装的共享 Wine 根，不发布 Release。

当前 v13 镜像和三类前缀描述已绑定真实制品，`../prebuilt-rootfs-index-entry.json`
对应已发布镜像。单独发布步骤在用户明确批准后创建四个新 prerelease，匿名完整下载校验
大小和 SHA256 后才启用两份索引；状态见 `publication-status.json`。当前候选来自锁定 OCI/APT 的分阶段构建，
下述单一 builder CLI 尚未从零完整跑过；补充媒体归档已在主机与设备字节一致重现。

## 输入与来源

- `ubuntu-oci-lock.json` 固定官方 Ubuntu 26.04 / Resolute ARM64 OCI manifest，两个标签的 index digest 已核对一致。
- `ubuntu-packages.tsv` 固定基础镜像和 APT 安装后的完整包版本。构建按此安装，并比较实际安装结果；软件源缺少锁定版本时明确失败，不自动升级。
- `ubuntu-deb-sha256.txt` 固定本次 APT 下载的包摘要。构建在裁剪前核对缓存，并将包保留在工作目录；连同 OCI 输入保留，便于重建。
- `ubuntu-media-lock.json` 固定 Ubuntu 的 GStreamer GL、游戏媒体插件及其依赖原包和逐文件摘要。用 `package-ubuntu-media.py --deb-directory /path/to/debs --output /path/to/ubuntu-media-ubuntu2604-arm64-v4.tar.xz` 重现补充包。选取播放、格式识别、音视频转换、WAV/AVI 解析和 FFmpeg 解码插件；Wine 视频后处理所需 deinterlace/videoflip、Proton 所需 capssetter 及 GL 转换插件也包含在内。插件原样放入 GStreamer 默认扫描目录 `/usr/lib/aarch64-linux-gnu/gstreamer-1.0`，其余补充库及版权文件放到 `/usr/local`。只补充不存在或内容完全一致的文件，不覆盖冲突、不伪造 dpkg 安装记录；完整 apt 包锁与这些补充文件分别追踪，不将选取插件宣称为完整原包安装。
- Hangover 输入由 `hangover-ubuntu-lock.json` 固定，只适配同一 Wine 源码提交的 `winedmo.so`，其余应用 payload 保持。系统基础库来自 Ubuntu。
- FFmpeg 4 兼容库由 `ffmpeg4-source-lock.json`、`build-ffmpeg4.sh` 和 `package-ffmpeg4.py` 生成，最终归档由 `ffmpeg4-compatibility-lock.json` 固定。只安装版本化库到 `/usr/local/lib`，保留 Ubuntu FFmpeg 8；完整预检拒绝未知成员、符号链接目录、摘要错误和覆盖冲突。

运行验收证据与来源锁分开记录。`BuiltNotRuntimeQualified` 仅表示构建输入已固定，不能据此声称 App、媒体播放或 GPU 验收通过。

## 在 ARM64 Termux 构建

需要完整源码检出、Python、PRoot-distro，以及三个已经生成并核对摘要的输入归档：

```sh
python distribution/termux-app-packages/build-ubuntu-wine-rootfs.py \
  --work "$HOME/ubuntu-wine-image-build" \
  --hangover-archive /path/to/rootfs_hangover_11.16.tar \
  --ffmpeg4-archive /path/to/ffmpeg4-ubuntu2604-arm64-v1.tar.xz \
  --ubuntu-media-archive /path/to/ubuntu-media-ubuntu2604-arm64-v4.tar.xz
```

脚本只使用 `wine-ubuntu2604-build` 私有构建容器。复用已有构建容器需要显式传入 `--reuse-build-container`，不得指向正式共享根。裁剪中断的根不能直接继续打包；保留日志后重新准备构建根。

构建会逐文件重新生成 Hangover 前缀清单，保存 `build-inputs.json`、包缓存、裁剪日志、ELF 审计及真实 `image.json`，再生成 `rootfs-ubuntu2604-hangover-pdb2-minimal-v1.tar.zst`。Wine 10 和 Proton 前缀分别用 `requalify-prefix.py` 配合各自锁定的组件归档生成，不能混用 DLL、Unix 模块或前缀。

完整模块扫描保留可选外设缺库的原始结果，不以补装外设包或删除模块制造通过。候选根裁剪后仍需完成三类 Wine 的真实设备运行、虚拟桌面、媒体、退出及残留进程检查；发布、启用下载索引和 APK 安装分别受授权约束。

压缩使用 Zstandard `-6 --long=25`（32 MiB 窗口），兼容现有解压器。Ubuntu Rust coreutils 的多份可执行文件原样保留：其安全校验拒绝用符号链接共享同一入口，不能用通用多调用程序假设替换。压缩包大小与根目录展开大小分别记录，不把裁剪回收字节当作剩余体积。

媒体验收分层保留证据：`media-smoke.c` 验证 WMV2 原生及 Box64 wrapper 编解码；`make-wmv2-avi.py --packet wmv2-frame.bin --output fixture.avi` 生成真实单帧压缩视频容器；`wine-dshow-smoke.c` 通过公开 DirectShow 接口播放 PCM 或指定视频，并核对完成事件和视频尺寸。`wine-media-smoke.c` 是直接 winedmo 私有接口诊断，其 ABI/回调失败不能单独推定公开播放行为。任何层失败都保留原始结果，不将有限格式探针解释为全部游戏视频兼容。


公开播放与释放诊断分别记录：`wine-dshow-smoke.c` 在调用 Stop/COM Release 前写下 RenderFile、Run、完成事件和视频尺寸的实际返回值，随后把释放状态写到同名 `.cleanup.json`。只有冒烟进程包含 10 秒释放 watchdog；触发时记录 `TimedOut` 并以原始 124 终止该探针，批处理另存每项实际退出码。播放失败仍失败，释放超时仍保留，不能将“播放通过”写成“自然退出及完整游戏兼容通过”。此代码不进入 App 的退出路径，也不改变生产 Wine、JIT 或会话停止策略。
