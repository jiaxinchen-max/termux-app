# 验证记录

## 当前交付状态

用户已确认实施及原生 Android 后端升级，随后明确要求“先推送代码”。本次仅保存未完成进度，不构建或发布 APK，不更新发布资产。整体仍未通过验收。

## 已完成及验证证据

- 独立 GNOME PRoot 从创建目录和渲染矩阵移除，安装、启动及直接 Python 调用拒绝；Native GNOME 和删除入口保留。未迁移或删除用户容器。
- 四类保留 PRoot 的目录、底包及元数据改为 Ubuntu 26.04 LTS / ubuntu:resolute；全新安装回归尚未完成。
- 原编译机 /workspace/termux-packages/build-package.sh 已成功构建 Mutter 50.1、GNOME Shell 50.1、GJS 1.88.0、SpiderMonkey 140.17.0-1，并通过官方 ELF 符号检查。
- Mtk/Cogl/Clutter/Meta 18、GjsPrivate 1.0、Gvc 1.0、Shew 0、St/Shell 18 的真实 Android GType dump 均已生成，来源及哈希记录在各 gir/*.provenance.json；旧 ABI dump 仅保留于 gir-48。
- Native Shell 50 实机日志显示 System EGL/AHardwareBuffer 初始化、GPU 首帧提交及 GNOME Shell started。模拟器 GPU renderer 为 Android Emulator OpenGL ES Translator (Apple M1 Pro)。这不代表完整桌面、主题、应用和输入回归通过。
- 普通用户 termux 的 Ubuntu Resolute PRoot 视图内，Bionic Shell 50 亦完成 GPU 首帧并记录 GNOME Shell started；随后生产启动器的 org.gnome.Shell D-Bus 就绪检查失败并清理会话，尚不可作为可用桌面交付。
- 修正 PRoot 平台目录检测、HOME 下渲染 socket 绑定，并将可读取但不可遍历的 /linkerconfig/ld.config.txt 复制为私有快照后绑定。真实 PRoot EGL probe 在该配置下通过；未使用 PipeWire 显示桥或 CPU 回读。
- Resolute glibc GIO 在原编译机使用实际 Ubuntu arm64 sysroot 构建，归档 SHA256 为 a843a28f1821c1e598d8c653ea0db7e6540d188d42296cc5a305bd4a838982ea；仅在隔离测试容器安装，ldd/gio-querymodules/gio mount -l 可执行，未测试真实 USB，未发布。
- GIO 安装器现校验两份实际 payload 的 SHA256，错误哈希在写入前拒绝；新增相应回归。

## 自动检查

- 此前 JUnit 目录/渲染/启动配置测试 20 项及 DesktopEnvironment 测试 17 项通过。
- Native Ubuntu 初始化测试 8 项、废弃 PRoot GNOME 测试 3 项、GIO 安装测试 11 项、Ubuntu GNOME profile 测试 8 项通过。
- 完整 test-desktop-ubuntu-gnome.py 曾失败；其中安装器测试仍有旧 X11 和组件预期，需随未完成的安装器一起适配。不得把 profile 单独通过视作完整套件通过。
- Native Ubuntu 初始化测试仍覆盖旧 48 主题实现，不证明 50 主题兼容。
- 最终保存前的检查结果追加于下文。仓库缺少规定 Harness 指令、strict gate 脚本与 OpenSpec CLI，未运行严格门禁。

## 实测证据位置

- 设备 $HOME/.cache/gnome50-probe/native-test.log：Native GPU 首帧。
- 设备 $HOME/.cache/gnome50-probe/proot-work/session.log：PRoot GPU 首帧及 Shell started。
- 设备 $HOME/.cache/gnome50-probe/proot-launcher.log：D-Bus 就绪失败。
- 设备 $HOME/.cache/gnome50-probe/proot-egl-config.log：EGL probe 通过。
- 编译机 /workspace/artifacts/gnome50-native/：原生构建日志及产物。
- 编译机 /workspace/artifacts/resolute-gio/：GIO sysroot、软件包来源、构建清单及产物。

上述运行日志属于测试环境证据，未随本次源码提交打包；GIR provenance 已纳入源码。

## 未完成与风险

安装器仍引用 GNOME 48 清单、Xlorie 和 Native Ubuntu 48 外观资产，与新启动器尚不配套。需完成新组件发布、精确哈希和 Native Ubuntu 50 主题/扩展包后统一更新；当前组合不可发布。仍需修复 PRoot Shell D-Bus 就绪，验证 Ubuntu 扩展/DING、普通用户图形应用、输入和会话清理，完成 Mesa/Zink/Hyprland、四类桌面全新安装及 Native 回归。产品文档一致性保持 Pending，未归档。

## 影响、回退及授权

原生组件配方及 App 安装/启动链路改动会影响 GNOME ABI、容器底包与显示输出；本次是明确标注的 WIP 保存。回退可撤销本次提交恢复源码；设备测试使用独立容器和私有 runtime，不迁移用户数据。用户“升级现有组件进行支持，pipewire不用考虑”授权实施，“先推送代码”授权本次暂存、提交及推送；不含本轮新资产发布或 APK 交付。

## 本次进度保存前复核

重新运行 Native Ubuntu 初始化 8 项、废弃 PRoot GNOME 3 项、GIO 安装 11 项、UbuntuProfileTests 8 项，共 30 项通过。改动 Python AST、shell 语法、源码凭据模式检查及 git diff --check 通过。未重新执行完整 Ubuntu GNOME 安装器套件，其已知失败仍保留在 review findings；未执行缺失的 strict gate。

暂存后额外检查：新增的 unified diff 配方文件包含补丁上下文行及上游空白，默认 git diff --cached --check 会报告空白提示；保留原补丁语义，未机械裁剪。所有配方补丁通过 git apply --numstat 语法解析，排除 .patch 后的暂存差异检查通过。
