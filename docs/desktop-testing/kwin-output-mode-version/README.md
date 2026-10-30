# output-mode 协议版本真实发送层回归

`check.cpp` 链接真实 KWin 库，创建独立 Display、虚拟输出、OutputDeviceV2Interface，通过 socketpair 发送实际 wl_registry.bind 请求，协议 logger 观察实际生成函数发出的 mode 事件。没有复制被修复的判断逻辑来代替生产代码。

固定预期：v18 不收到 flags（opcode 4）；v19/v20 各收到一次 flags，Custom=1；每个版本都必须收到一次 mode.size，证明对象绑定及发送链路真实执行。原库在 v18 失败作为负向对照，修复库三个版本都通过。

在指定编译机复用配置好的工具链构建，不修改 KWin CMake 配置：

```sh
python3 build.py /path/to/ninja /home/builder/.termux-build/kwin/build /absolute/path/check.cpp /absolute/path/check-output-mode-version
```

Android 诊断目录布局：

```text
check-output-mode-version
old/libkwin.so.6
fixed/libkwin.so.6
```

在 Termux 环境运行 `bash run.sh /absolute/path/test-directory /data/data/com.termux/files/usr`。旧库预期退出 1，修复库预期退出 0。其余运行依赖取同设备的已安装 Termux 库。此测试不启动桌面；容器 Plasma 仅支持 forky，trixie Plasma 不列为支持或验收目标。
