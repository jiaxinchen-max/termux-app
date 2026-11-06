# Android Surface 重建后的 GNOME 重绘

Mutter 48.1-23 的 `EVENT_SCREEN_SIZE` 只更新输入 viewport。App 旋转或重建
Surface 时，即使收到了该事件，静止的 GNOME scene 仍没有 damage，直到下一次
输入使 Clutter 产生一帧才恢复画面。

48.1-24 在每次有效 viewport 事件上对当前 backend 的 stage 调用
`clutter_actor_queue_redraw()`。同尺寸 Surface 重建也触发；零尺寸事件不触发。
这一处理与 Mutter 上游 backend resume 使用的 stage redraw API 一致。
不会重连显示服务、重启 Shell，或修改已协商的 AHB、输出尺寸、60 Hz、system renderer。

## 独立回归

仅在编译机执行，使用生产源码中实际的 viewport 分支，替换 Clutter 边界为计数 stub：

```sh
python3 test_android_viewport_redraw.py \
  --source meta-backend-android.c --cc /usr/bin/gcc
```

覆盖旋转、同尺寸 Surface 重建、无效 viewport、输入映射和附加 payload 长度，
并断言输出模式不变。23 源码应在“有效 viewport 没有请求帧”断言失败，24 应通过。
该回归不替代正式 App 界面验收。

## App 验收

Native GNOME 显式使用 android/system/60 Hz。静止桌面和已打开的 Files 窗口中，
通过 App/系统旋转改变方向，旋转后不再触摸桌面；检查画面能自行恢复且输入坐标正确。
构建和设备验收证据由本轮主任务记录。

## 编译机验证结果

2026-10-30，在 `120.24.191.156:7022` 的 `/workspace/termux-packages`：

- 修改前配方与缓存源码都与仓库的 23 修订逐字节一致，并已保留备份。
- 23 负对照在“有效 viewport 没有请求帧”断言失败；24 独立回归通过。
- 官方命令 `./build-package.sh -a aarch64 -f -s -j8 mutter` 成功，12 个 ELF 符号检查通过。
- 产物 `mutter_48.1-24_aarch64.deb` SHA256：
  `c222405b73404c57799e53f32344b9db28adc8de2ba9ef98704d81950ab27349`。
- 核心 `lib/libmutter-16.so.0.0.0` SHA256：
  `dd001f7aeb271f7b915fadf80dd5cf74c3966322bfa89e3061a3cba128562094`。

编译日志、正负回归日志与 23 原文件位于编译机
`/home/builder/desktop-fixes-20261030/gnome-usb-release/` 下的 `mutter24-*` 项。
本子任务未操作设备，也未发布产物。
