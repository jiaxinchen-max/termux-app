# 独立 GLES 诊断探针

仅在指定远端编译机编译。两个程序必须显式传 `--mode system`，绝对加载 `/system/lib64/libEGL.so` 和 `/system/lib64/libGLESv2.so`，使用 GLES3、1x1 pbuffer 和自身离屏 FBO。它们不连接 DISPLAY、不创建 App 窗口、不修改正在运行的桌面，也不支持失败换模式。

```sh
/home/builder/desktop-fixes-20261030/android-sdk/ndk/25.1.8937393/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang \
  -std=c11 -O2 -Wall -Wextra gles-transfer-probe.c -ldl -o gles-transfer-probe
# 渐变程序同样编译，将上述两个文件名改为 gles-gradient-probe。
```

传输程序包含 Xserver v6 实际 `glamor_upload_pixels`、`glamor_read_pixels` 函数。调用：

```sh
gles-transfer-probe --mode system
```

覆盖 depth8/24/32 的 native 与 RGBA 转换读取、12 种宽度、4 种高度，共 288 组，核对每个像素及行 padding、前后保护字节。它不验证完整 XRender 合成路径。

渐变程序包含 r6 实际 linear/radial 顶点和片段 shader、共用 get_color 源码。精度由参数明确指定，算法不变：

```sh
gles-gradient-probe --mode system --precision mediump /absolute/writable/directory
gles-gradient-probe --mode system --precision highp /absolute/writable/directory
```

每次生成四个 1280x64 RGBA 原始像素文件：linear/radial × 两种平移量。报告重复绘制稳定性、每行颜色范围和不透明输入的 alpha 偏差。radial 本身允许横向颜色变化，不能将其 row spread 当作错误；本例的异常指标是本应全不透明却出现 alpha=0。需要同时查看生成像素和日志，不能把程序退出码 0 当作图像验收通过。

小米 Adreno660 的实测见 `../../xserver-v7-gradient-20261030.md`。离屏诊断不替代正式 App UI 验证。
