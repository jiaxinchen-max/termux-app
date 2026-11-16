# Resolute Glycin 在 Android PRoot 中的检测修正

Resolute 的 GdkPixbuf 2.44 使用 libglycin 2.1.1 解码 PNG 等图片。Glycin 已有自动回退：宿主内核禁止 bubblewrap 建立 namespace 时，在独立解码子进程中运行，不再嵌套 bubblewrap。Android 先拒绝读取 `/proc/sys/kernel/overflowuid`，上游未识别这一诊断，因而重复调用必然失败的 bubblewrap。DING 的图标缺失，GTK3 Terminal 则因连 `image-missing.png` 都无法加载而断言退出。

补丁只扩充既有的「bubblewrap 不可用」诊断清单，不伪造系统文件、Flatpak 身份或成功返回值，不添加全局禁用 sandbox 的环境变量。正常 Linux 仍选择 bubblewrap。回退会保留上游的 `Glycin running without sandbox` 警告；解码器没有 bubblewrap 的额外隔离，仍受 Android App UID 限制。PRoot 本身不构成安全隔离边界。

## 构建

只能在指定 Linux 编译机执行。此处是 Ubuntu glibc 库，使用既有 Resolute GIO 的 GNU 交叉工具链/sysroot；不是 Android/Bionic 的 `build-package.sh` 配方。原生 Mutter/Shell 仍沿用 `/workspace/termux-packages/build-package.sh`，不混用 libc。

输入为官方 `https://download.gnome.org/sources/glycin/2.1/glycin-2.1.1.tar.xz` 与 `https://static.crates.io/crates/glycin/glycin-3.1.0.crate`；SHA256 固定在 `build.py`。sysroot 需包含实际 Resolute 的 GLib/GIO、fontconfig、lcms2、seccomp 及其开发依赖；Rust 需安装 `aarch64-unknown-linux-gnu` target。

```sh
python3 build.py \
  --upstream /workspace/artifacts/resolute-glycin/upstream-direct.tar.xz \
  --crate /workspace/artifacts/resolute-glycin/crate.tar.gz \
  --work /workspace/artifacts/resolute-glycin/verified-work2 \
  --output /workspace/artifacts/resolute-glycin/verified-output \
  --cc /workspace/artifacts/resolute-gio/bin/cc \
  --pkg-config /workspace/artifacts/resolute-gio/bin/pkg-config \
  --sysroot /workspace/artifacts/resolute-gio/sysroot \
  --cargo /home/builder/.cargo/bin/cargo
```

`--work` 和 `--output` 必须是尚不存在的新目录，重复构建使用新的路径，防止向旧源码重复应用补丁。

构建检查真实交叉编译器、输入哈希及 Cargo 其他依赖未改变，输出库与来源清单。目标 SONAME 为 `libglycin-2.so.0`。发布前仍需检查 ELF 架构、GLIBC/依赖及对应容器的实际解码和应用启动。

## 安装边界与验证状态

生产安装助手 `desktop_install_glycin.py` 已接入 Ubuntu GNOME 安装分支：安装前验证归档、固定文件清单、构建来源和库哈希，将文件写入 `/usr/local/lib/termux-gnome/`，不覆盖发行版的软件包文件。保存／安装阶段验证安装收据及库字节，保存该桌面的 PRoot 绑定参数；启动不重复验证，直接绑定到 `/usr/lib/aarch64-linux-gnu/libglycin-2.so.0`。大资产由外部下载，不进入 APK。

原编译机按此配方构建成功，ELF 为 AArch64 glibc ET_DYN，SONAME `libglycin-2.so.0`，无 RPATH/RUNPATH 或 Bionic 依赖，最高必需 GLIBC 为 2.34。产物身份：

- 库 SHA256：`b2ea4a4a87d0fbc9746fe686e84d5848c682498418ded9585eb12f93f073a1be`
- 补丁 SHA256：`bf5d62a51cbe1bd5f12b8b296833eb5e4bc03bc9135c826c05304dd717a5c791`
- 归档：`termux-gnome-glycin-resolute-aarch64-2.1.1-r1.tar.gz`
- 归档 SHA256：`cb72b99666baacb5cd8b4fd62960f3a54568c49c04a05bd8b5f9280ba8493eb9`

使用 `package.py --directory <output> --output <archive>` 生成固定双文件 USTAR/gzip。完整编译命令、来源、Cargo.lock 和输出哈希另存于归档内的 `glycin-build-manifest.json`。资产已随 [gnome-50-resolute-20261116-r1](https://github.com/jiaxinchen-max/termux-app-packages/releases/tag/gnome-50-resolute-20261116-r1) 公开发布，原始下载 URL 的大小及 SHA256 回读通过；安装器已统一 Shell/Mutter 50 核心清单及新外观资源，Native 完整组合与四类安装回归仍未完成，不能发布 APK。

隔离测试容器已通过真实下载、归档校验和生产助手安装；实际会话使用该安装库。默认 AUTO 模式成功解码 Yaru 256×256 PNG 和 Terminal 的 16×16 fallback 图标；无效 PNG 按 `Invalid PNG signature` 返回解码错误；Terminal 正常打开并接受 Android 键盘输入。5 项安装助手回归覆盖完整性失败、路径/符号链接拒绝、安装后篡改及保留发行版库。DING 重启引出的 Mutter 显示队列重入崩溃已修复，50.1-4 已通过无诊断探针的 8 次设备压力回归及所属进程退出清理。图像解码通过仍不代表完整桌面验证完成。证据见对应 OpenSpec `verification.md`。
