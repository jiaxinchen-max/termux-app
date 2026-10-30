# Discover 网络状态测试

在指定编译机的隔离 Debian forky arm64 环境执行，Qt 为 6.11.2。测试采用真实 QQmlEngine、QML Timer、Connections、QtQuick Controls Popup；NetworkFixture 仅为测试输入，不替换生产网络 backend。

```sh
python3 generate-fixture.py /path/to/patched/discover/qml/DiscoverWindow.qml fixture.qml
cmake -S . -B build -DQt6QmlWorkerScript_DIR=/usr/lib/aarch64-linux-gnu/cmake/Qt6QmlWorkerScript
cmake --build build -j2
QT_QPA_PLATFORM=offscreen QT_QUICK_BACKEND=software QT_QUICK_CONTROLS_STYLE=Basic \
  build/discover-network-test "$PWD/fixture.qml"
```

覆盖初始短暂离线、连续真实非 Online 状态不重置 1 秒期限、Online 关闭提示、关闭动画期间重新离线。最后一个用例强制确认 Popup 在 exit transition 中仍 visible，避免漏测竞争窗口。

包资源核验在编译机宿主 Python 执行，需要 libzstd：

```sh
python3 verify-embedded-qml.py /path/to/qrc_resources.cpp \
  /path/to/extracted-deb/usr/bin/plasma-discover /path/to/final/DiscoverWindow.qml
```

脚本确认最终 ELF 包含整个生成资源数组，解压 DiscoverWindow.qml 的 zstd 条目并与最终源逐字节比较。上述测试不替代设备上的正式 App UI 验证。
