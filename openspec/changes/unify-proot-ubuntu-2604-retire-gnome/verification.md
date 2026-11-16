# 验证记录

## 已完成部分
用户“实施”作为本方案实施确认。独立 GNOME PRoot 从创建目录和渲染支持矩阵移除，安装、启动及直接 Python 调用拒绝，Native GNOME 保留。删除使用独立配置，不调用启动编码器。没有更改已有容器数据、Native 包或凭据。

- javac 编译领域模型及目录/渲染/启动配置测试，JUnit 4.13.2：20 项通过。
- python3 docs/desktop-testing/test-retired-proot-gnome.py：3 项通过，覆盖实际 shell 安装/启动拒绝、渲染矩阵拒绝、模拟 proot-distro 的真实删除脚本调用。
- 修改的三个 shell 文件 sh -n 通过，Python launcher AST 解析通过；git diff --check 通过。
- 删除入口人工源代码审查：deleteAndRemoveContainer -> forceStop -> removeContainer -> encodeRemoval -> desktop_remove.sh；不经过 requireSupported，未删除设备数据。

## 软件源与兼容证据
检查 https://ports.ubuntu.com/ubuntu-ports/dists/resolute/main/binary-arm64/Packages.xz 与 universe 对应索引：GNOME 50.1、GLib 2.88、XFCE 4.20；提取 Ubuntu session/gnome-shell/mutter/Yaru 实际 DEB，无 X11 session。
核对 https://github.com/GNOME/mutter/blob/50.1/src/core/meta-context-main.c：不支持 --x11，仅 native/headless backend。原设计保持 X11 的假设不成立，需用户决定 Ubuntu GNOME 的后续方向。
上游 Mesa Resolute 资产确实存在，12674045 字节，SHA256 51aa11a8e7bc8c98bc9e852b260ee2dac99392b4ee8658182e4837c309818faf；已下载核对哈希及归档结构/AArch64 ELF，libgallium 依赖 libLLVM.so.21.1。未实测设备加载，未安装或发布该资产。
编译机密码登录曾成功并确认原 /workspace/termux-packages 在场；后一次只读查询遇到 SSH banner EOF，未执行编译或部署。

## 未完成与限制
四类底包统一、Ubuntu GNOME 50 显示链适配、Resolute GIO 构建发布、设备全新创建与运行验证尚未完成。未构建 APK，未声称桌面兼容。仓库缺少 Harness strict gate/OpenSpec CLI，不能执行完整严格门禁。产品文档一致性保持 Pending，未归档。
