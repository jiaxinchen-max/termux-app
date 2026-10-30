# XSERVER v8 临时 A8 picture 修复

## 真实请求与根因

小米 v7 的正式 GTK UI 花白仍存在。只读 XRecord 捕获 Ctrl+N 新建 Thunar 窗口、地址栏输入和重绘，共 12456 个绘图请求；窗口树确认 Thunar 为 client `0xe00000`，xfce-panel 为 `0xc00000`。

Thunar 未创建 linear/radial gradient。其地址栏实际使用三组 Trapezoids（17、40、54 个梯形）；4 次 linear gradient 创建均来自 xfce-panel。聚焦地址栏后的蓝白噪点也不能仅归为渐变精度。

`glamor_trapezoids()` 使用 pixman 生成临时 A8 mask，再经 `glamor_upload_picture_to_texture()` 上传并由 GPU 合成。该上传函数的 GLES 路径把 internalformat 设为外部 format，即 A8 的 unsized `GL_RED`。同一 Adreno660 驱动的独立 EGL 调用实测返回 `GL_INVALID_OPERATION (0x502)`。原函数只判断 OOM，其他错误被消耗后仍返回成功，导致采样未正确上传的 mask。

此前 288 组测试使用 `GL_R8` 分配普通 pixmap 及 TexSubImage 上传，没有覆盖这一临时 picture 的 TexImage 路径，不能据其通过排除本问题。

## 修复

追加第八补丁 `patches/xserver-r6-memory-a8-picture.patch`，仅修改 `glamor_picture.c`：

- GLES 的 GL_RED 临时 picture 固定使用 sized `GL_R8`，不尝试失败后的替代格式。
- 上传前已有 GL 错误立即记录实际 code 并终止 Xlorie，不吞掉后继续。
- 必需临时 GPU 资源失败立即终止；上传后的任意 GL 错误记录实际 code、depth、internalformat、format/type、尺寸并终止，不返回 CPU 绘制路径。

保持明确选择的 System/Zink/LLVMpipe 模式和 v7 其他逻辑。pixman 生成梯形覆盖 mask 是已有算法步骤，不是失败后更改渲染器。

## 修复前证据与独立对照

同真实 GTK 请求几何在独立离屏 X pixmap 重放，每组重复 8 次，并逐像素比较 pixman 参考：

| trace request index | 梯形数 | alpha 偏差超过 1 的累计像素 | 其中应全不透明的像素 | 最大 alpha 偏差 |
| --- | --- | --- | --- | --- |
| 6792 | 17 | 299304 | 299028 | 255 |
| 6795 | 40 | 61472 | 17454 | 255 |
| 6798 | 54 | 67424 | 17422 | 255 |

独立 GLES 内存 picture 正向对照改用 `GL_R8`，执行实际 TexImage 直接上传，288 组 depth8/24/32、native/RGBA 读回、宽高、stride/padding/guards 全部通过。原 unsized GL_RED 对照明确报 `0x502`。

编译机对实际生产上传函数的 ASan/UBSan 测试通过：R8 映射；预存 GL 错误 0x500、上传错误 0x502/0x505 保留 code 并终止；必需临时 GPU 分配失败终止。

原始 trace、摘要及各探针日志位于 `/tmp/termux-desktop-xiaomi-20261030/`。修复前 trap 的控制台统计已保存，但原始像素在会话清理后丢失。此前只检查 adb exec-out 退出码，未检查文件长度，误把 cat 缺失文件错误文本当作原始像素收入候选包；最终包已移除这六个无效文件并保留此说明。对应源码在 `native-tests/` 和 `native-tests/gles-probes/`。

## 修复后真实梯形对照

主线程按先模拟器、后小米顺序部署同 SHA 的 v8。小米重新使用相同探针、同三组请求几何、同 pixman 参考执行离屏重放：

| trace request index | 梯形数 | 重复次数 | 偏差像素 | 应全不透明像素偏差 | 最大 alpha 偏差 |
| --- | --- | --- | --- | --- | --- |
| 6792 | 17 | 8 | 0 | 0 | 0 |
| 6795 | 40 | 8 | 0 | 0 | 0 |
| 6798 | 54 | 8 | 0 | 0 | 0 |

另对三组保存的修复后 BGRA 原始图进行独立比较，每组全部 122880 个像素的 B/G/R/A 四通道均与该次参考预乘白色 mask 完全相同。修复前后使用同一探针二进制和固定请求几何，但因修复前原始像素未保留，不声称完成了前后 raw 数据逐字节比较。记录 `xrender-trap-probe-v8-after.log`、`xrender-trap-v8-all-channels.log`，修复后像素在 `v8-trap-after/`。

## 构建与正式 UI 范围

指定编译机隔离构建成功，日志确认 `glamor_picture.lo` 重编并重新链接 libglamor/Xlorie。干净 r6 基线重放八个补丁，与全部 11 个受影响文件逐字节一致。

v8 二进制 SHA256：`741e9b87cbf01ea48e2db89648bd2fd1441b6df2ddadf70ebdf05793f4544ef6`。

二进制单独保留 `artifacts/Xlorie-v8`，不覆盖 v6/v7。最终证据包附原始错误对照、三组真实 trap 修复前后结果。

主线程已通过模拟器正式 Thunar `/usr/bin` 和右键验证；随后确认小米同 SHA 的正式 UI 花白修复、右键、双指滚动和 Directtouch 单指滚动均通过，详细步骤与截图由主验证报告记录。单指滚动使用真正溢出的 `/usr/bin`，未将一屏即可显示全部项目的 root 目录作为滚动失败依据。XI2 只读核对见 `xserver-xi2-readonly-20261030.md`，未修改触控生产代码。离屏重放与正式 App UI 验收分别保留证据。
