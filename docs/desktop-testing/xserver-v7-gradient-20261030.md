# XSERVER v7 渐变精度对照

## 问题与取证

小米 `XIAOMI_SERIAL`，显式 System，Adreno660 OpenGL ES 3.2 V@0530.49，container XFCE、GLX=0，Xlorie v6。正式 App 启动后 Thunar 地址栏背景及顶栏任务按钮出现花白噪点。此组合不能记为验证通过。

只读 `xwd -root` 已在 X drawable 中取得同样噪点，问题并非只发生在最终 AHB 呈现。采样未改窗口或渲染模式。

实测源证据保存在 `/tmp/termux-desktop-xiaomi-20261030/`：

- `phone-container-xfce-max-bin.png`：主线程正式 UI 截图。
- `xserver-readonly-current.xwd`、`xserver-readonly-current-xwd.png`：X 根窗口读回及解码图。
- `xserver-readonly-current-android.png`：同步 Android 截图。

独立 system EGL 探针使用实际 v6 传输函数，depth8/24/32、native/RGBA、不同宽高、stride/padding/guards 共 288 组全部通过。日志仅 depth15 使用转换，A8 直接 GL_RED 读取的宣告格式实测有效。此结果不验证完整 XRender 路径，不能单独宣称所有 A8 合成都正确。

同驱动实测 fragment mediump 精度 10bit、指数范围 15；highp 精度 23bit、指数范围 127。

提取实际 `glamor_gradient.c` shader，固定同一 1280x64 RGBA8 离屏 FBO、渐变参数和默认启用的 GL_DITHER，仅以明确参数切换编译精度：

| shader | 精度 | 平移量 | alpha=0 像素数 | 重复绘制变化字节 |
| --- | --- | --- | --- | --- |
| radial | mediump | 0 | 68 | 0 |
| radial | highp | 0 | 0 | 0 |
| radial | mediump | 16 | 40 | 0 |
| radial | highp | 16 | 0 | 0 |

上述输入应全不透明。linear 在两个精度下均无透明洞或横向噪点。原 radial 的 mediump 路径存在确定的精度缺陷；独立复现形态为中心细线，不能据此认定已解释正式 GTK 画面的全部噪点，仍需 v7 正式 UI 对照。

日志 `gles-transfer-probe.log`、`gles-gradient-probe.log`，原始 `.rgba` 及对应 PNG 均在同一取证目录。探针源码在 `native-tests/gles-probes/`。

## v7 改动和构建

在 v6 六个补丁后增加 `patches/xserver-r6-gradient-highp.patch`。只修改 `glamor/glamor_gradient.c`：顶点传值、linear/radial 片段计算、共用 get_color 函数统一使用 highp。未改变几何算法、像素传输、模式选择或失败处理；其他 Render/glyph shader 精度保持原值。Xlorie 已要求 GLES3，fragment highp 是该版本的必需能力，不引入精度失败回退。

编译仍在指定机器隔离目录 `/home/builder/desktop-fixes-20261030/xserver-{src,build}`，使用原 NDK r30 API24 配置。仅一个 C 文件改变，无结构布局修改；增量构建日志确认 `glamor_gradient.lo` 重编、`libglamor` 和 `Xlorie` 重链接，退出码 0。

干净基线 `b08f1752300f3d594694e09f5d8f980978becc4a` 重放七个补丁，与全部 10 个受影响源文件逐字节一致。未修改 dirty submodule 或原 Termux 构建缓存。

v7 Xlorie SHA256：`191c35a02bcde440733f3c4070caa076047acdb495fa8151fb5e42c1887d39e2`。

远端二进制单独保留为 `/home/builder/desktop-fixes-20261030/artifacts/Xlorie-v7`；v6 二进制不覆盖。离线包 `xserver-r6-local-fixes-arm64-v7.tar.gz` 只替换 `bin/Xlorie`，并附七个补丁、构建日志、源码重放校验和探针源码/结果。

## 验收状态

主线程已先在模拟器通过正式 App UI 验证 v7 的菜单、滚动，然后部署同 SHA 二进制到小米进行对照。

小米 v7 的 container XFCE System 仍在 Thunar 地址栏和顶栏任务按钮出现花白，截图 `/tmp/termux-desktop-xiaomi-20261030/container-xfce-v7-open/001-key-after.png`。因此 v7 修复了独立探针中的渐变精度缺陷，未消除正式 GTK 画面的全部噪点，不能结案。

正在用只读 XRecord 捕获真实 GTK 的 Render、MIT-SHM 和核心绘图请求，继续区分实际渐变、三角形遮罩、合成及像素上传路径。未停会话或改变渲染模式；离屏诊断不计作正式功能通过。
