# 小米 XI2 Directtouch 只读核对

v8 小米 container XFCE System 会话，仅查询设备和筛选 Thunar GTK 相关环境；未选择触摸事件、未抓取设备、未合成输入、未改变 UI 或生产代码。

实际查询结果：

| 项目 | 结果 |
| --- | --- |
| XInput 版本 | XI 2.4 |
| Lorie touch | device 7、enabled=1、slave pointer、attachment=2 |
| TouchClass | sourceid=7、mode=1（XIDirectTouch）、20 触点 |
| X/Y 轴 | Abs MT Position X/Y、Absolute、0..65535、resolution=10000 |
| Lorie mouse | 独立 device 6、相对 X/Y 与滚轮轴 |
| Virtual core pointer | device 2、继承 sourceid=7 的 DirectTouch class |

Thunar 环境含 DISPLAY=:0，未发现 GDK_CORE_DEVICE_EVENTS 等禁用 XI2 的环境变量。查询日志：`/tmp/termux-desktop-xiaomi-20261030/xi2-devices-readonly.log`、`gtk-environment-readonly.log`；只读工具源码：`native-tests/query-xi2-devices.c`。

源码复核：`hw/lorie/InitInput.c` 将 Lorie touch 声明为 XIDirectTouch，并通过 QueueTouchEvents 提交 Begin/Update/End。flags=0 不表示禁用 touch：Xorg `dix/getevents.c` 对首触点自行设置 TOUCH_POINTER_EMULATED，`dix/eventconvert.c` 映射为 XITouchEmulatingPointer；`dix/touch.c` 保留标准 touch listener、ownership 和兼容鼠标事件处理。兼容鼠标事件带 XIPointerEmulated，看到拖选不能单独认定 App 改成了鼠标模式。

主线程后续在真正可滚动的 Thunar `/usr/bin` 完成正式 Directtouch 单指滚动验证：顶部由 X11/[ 变为 bdftruncate/bitmap/bmtoa，截图位于 `/tmp/termux-desktop-xiaomi-20261030/container-xfce-v8-direct-bin-scroll/`。此前 root 目录一屏显示全部项目，没有可滚动溢出，不能据此判断触控失败。调查结束，无触控生产代码变更。
