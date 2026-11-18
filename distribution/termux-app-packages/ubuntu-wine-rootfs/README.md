# Ubuntu 26.04 Wine 镜像构建

这些构建工具只准备候选制品，不修改已安装的共享 Wine 根，不发布 Release。

当前 v13 镜像和三类前缀描述已绑定真实制品，`../prebuilt-rootfs-index-entry.json`
对应已发布镜像。本轮已沿用现有文件名、Release 和 URL 原地更新；四份资产均已匿名完整下载核对
大小和 SHA256，下载索引已同步，状态见 `publication-status.json`。当前镜像从锁定 OCI/APT 在新私有根完整构建，
裁剪名称守卫修复后从裁剪阶段续跑，并非单次 builder CLI 从零一次通过；补充媒体归档已在主机与设备字节一致重现。
镜像压缩包为 344435653 字节（328.48 MiB），比原发布快照减少 18.27%。按用户明确指示，GStreamer 媒体检查本轮排除，
原始失败和超时记录保留；其余图形及实际虚拟桌面共 9 项能力检查通过，来自 8 次不同执行，Proton64 图形与桌面共用同次成功报告。
App 界面和硬件 GPU 验收仍待完成，不能将本轮范围内的结果解释为全部媒体或游戏兼容性通过。

## 输入与来源

- `ubuntu-oci-lock.json` 固定官方 Ubuntu 26.04 / Resolute ARM64 OCI manifest，两个标签的 index digest 已核对一致。
- `ubuntu-packages.tsv` 固定当前镜像的完整构建包版本（已切换 GNU coreutils provider；不是原发布快照的包表）。构建按此安装，并比较实际安装结果；软件源缺少锁定版本时明确失败，不自动升级。
- `ubuntu-deb-sha256.txt` 固定本次 APT 下载的包摘要。构建在裁剪前核对缓存，并将包保留在工作目录；连同 OCI 输入保留，便于重建。
- `ubuntu-media-lock.json` 固定 Ubuntu 的 GStreamer GL、游戏媒体插件及其依赖原包和逐文件摘要。用 `package-ubuntu-media.py --deb-directory /path/to/debs --output /path/to/ubuntu-media-ubuntu2604-arm64-v4.tar.xz` 重现补充包。选取播放、格式识别、音视频转换、WAV/AVI 解析和 FFmpeg 解码插件；Wine 视频后处理所需 deinterlace/videoflip、Proton 所需 capssetter 及 GL 转换插件也包含在内。插件原样放入 GStreamer 默认扫描目录 `/usr/lib/aarch64-linux-gnu/gstreamer-1.0`，其余补充库及版权文件放到 `/usr/local`。只补充不存在或内容完全一致的文件，不覆盖冲突、不伪造 dpkg 安装记录；完整 apt 包锁与这些补充文件分别追踪，不将选取插件宣称为完整原包安装。
- Hangover 输入由 `hangover-ubuntu-lock.json` 固定，只适配同一 Wine 源码提交的 `winedmo.so`，其余应用 payload 保持。系统基础库来自 Ubuntu。
- FFmpeg 4 兼容库由 `ffmpeg4-source-lock.json`、`build-ffmpeg4.sh` 和 `package-ffmpeg4.py` 生成，最终归档由 `ffmpeg4-compatibility-lock.json` 固定。只安装版本化库到 `/usr/local/lib`，保留 Ubuntu FFmpeg 8；完整预检拒绝未知成员、符号链接目录、摘要错误和覆盖冲突。

运行验收证据与来源锁分开记录。`BuiltNotRuntimeQualified` 仅表示构建输入已固定，不能据此声称 App、媒体播放或 GPU 验收通过。

原发布快照的包锁和脚本保留在提交 `21e2b4aa`。当前 GNU provider 包锁用于已原地发布的瘦身镜像，不能据此解释或覆盖旧制品的来源记录。

## 在 ARM64 Termux 构建

需要完整源码检出、Python、PRoot-distro，以及三个已经生成并核对摘要的输入归档：

```sh
python distribution/termux-app-packages/build-ubuntu-wine-rootfs.py \
  --work "$HOME/ubuntu-wine-image-build" \
  --hangover-archive /path/to/rootfs_hangover_11.16.tar \
  --ffmpeg4-archive /path/to/ffmpeg4-ubuntu2604-arm64-v1.tar.xz \
  --ubuntu-media-archive /path/to/ubuntu-media-ubuntu2604-arm64-v4.tar.xz
```

脚本默认使用 `wine-ubuntu2604-build` 私有构建容器；旧根已经裁剪时，使用 `--build-container-name wine-ubuntu2604-build-slim-20261118` 从锁定 OCI 在新私有根重建。名称参数仅接受受限后缀，不能指向共享根或任意路径，不改变镜像文件名/发布地址。复用已有构建容器需要显式传入 `--reuse-build-container`，不得指向正式共享根。裁剪中断的根不能直接继续打包；保留日志后重新准备构建根。

构建会逐文件重新生成 Hangover 前缀清单，保存 `build-inputs.json`、包缓存、裁剪日志、ELF 审计及真实 `image.json`，再生成 `rootfs-ubuntu2604-hangover-pdb2-minimal-v1.tar.zst`（未发布候选）。Wine 10 和 Proton 前缀分别用 `requalify-prefix.py` 配合各自锁定的组件归档生成，不能混用 DLL、Unix 模块或前缀。

完整模块扫描保留可选外设缺库的原始结果，不以补装外设包或删除模块制造通过。候选根裁剪后仍需完成三类 Wine 的真实设备运行、虚拟桌面、媒体、退出及残留进程检查；发布、启用下载索引和 APK 安装分别受授权约束。

设备运行验收须使用 App 自身启动链路及其 SELinux 安全域，并核对 `/proc/self/attr/current`。本轮发现 ADB `run-as` 的 `runas_app` 域会拒绝文件私有映射写入后恢复执行权限，而同设备 App 的 `untrusted_app_27` 域允许该操作；调试域的映射失败不能直接作为产品启动回归结论。验收启动参数也应与 `start_rootfs_game.sh` 一致，不额外强制设置 App 未设置的 `PROOT_NO_SECCOMP=1`；功能、超时、媒体释放和残留进程分别保留原始结果。

压缩使用 Zstandard `-6 --long=25`（32 MiB 窗口），兼容现有解压器。候选使用 Ubuntu 官方 `coreutils-from-gnu`，来源由 `coreutils-provider-lock.json` 固定，标准命令链接至已锁定的 `gnu-coreutils`。官方 OCI 中的 Rust provider 为 Protected；构建在私有根内先模拟安装，`check-coreutils-plan.sh` 仅允许移除 `coreutils-from-uutils` 和 `rust-coreutils`，再允许实际替换。打包前校验 GNU provider、关键命令及 Rust 载荷无残留。现有镜像协议继续拒绝硬链接，不用符号链接共享 Rust 程序。非目标GPU裁剪移除 asahi/intel/powervr_mesa Vulkan 库与同名 ICD JSON，保留 freedreno/lvp/gfxstream/virtio、媒体、字体和 LLVM/Gallium。新增 `runtime-extras` 批次精确移除 Perl 开发头文件/独立 POD、`usr/bin/pebble`、ARM64 Highway contrib/test 版本库与链接，保留 Perl 运行模块及 JPEG XL 使用的 Highway 核心。删除 Highway 辅助库时，旧主/辅助加载器缓存先进入隔离区，目标架构 `ldconfig -X -i` 在私有阶段生成新主缓存及新辅助缓存（若生成），分别记录后安装，失败按日志恢复原库和缓存；不删除 systemd/udev 或 Flite。候选通过完整验证后按本轮授权原地更新发布资产及下载索引；相同文件名不代表相同内容，必须同步真实大小与SHA，保留旧来源锁和备份供回滚。压缩包大小与根目录展开大小分别记录，不把裁剪回收字节当作剩余体积。

媒体验收分层保留证据：`media-smoke.c` 验证 WMV2 原生及 Box64 wrapper 编解码；`make-wmv2-avi.py --packet wmv2-frame.bin --output fixture.avi` 生成真实单帧压缩视频容器；`wine-dshow-smoke.c` 通过公开 DirectShow 接口播放 PCM 或指定视频，并核对完成事件和视频尺寸。`wine-media-smoke.c` 是直接 winedmo 私有接口诊断，其 ABI/回调失败不能单独推定公开播放行为。任何层失败都保留原始结果，不将有限格式探针解释为全部游戏视频兼容。


公开播放与释放诊断分别记录：`wine-dshow-smoke.c` 在调用 Stop/COM Release 前写下 RenderFile、Run、完成事件和视频尺寸的实际返回值，随后把释放状态写到同名 `.cleanup.json`。只有冒烟进程包含 10 秒释放 watchdog；触发时记录 `TimedOut` 并以原始 124 终止该探针，批处理另存每项实际退出码。播放失败仍失败，释放超时仍保留，不能将“播放通过”写成“自然退出及完整游戏兼容通过”。此代码不进入 App 的退出路径，也不改变生产 Wine、JIT 或会话停止策略。
