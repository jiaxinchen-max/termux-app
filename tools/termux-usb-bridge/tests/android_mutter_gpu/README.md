# Android Mutter 显示/输入测试视图

只用于已具有 `com.termux.x11.X11SessionView` 的配套调试 APK。
不改 Termux APK、不注册假桌面、不伪造首帧；调用实际 LorieView producer API。

`SurfaceTest.java` 与 `AndroidManifest.xml` 使用编译机 Android SDK 构建，测试 APK
必须与目标调试 APK 签名相同。安装后：

```sh
adb -s emulator-5556 shell am instrument -w \
  org.termux.mutter.gputest/org.termux.mutter.gputest.SurfaceTest
```

测试保持 60 分钟或收到停止文件。状态为目标 App HOME 下
`tusb-gnome-fold/gpu-system/surface-status.json`，记录真实连接/呈现计数。
在同目录写 `surface-control.json` 可发送一次输入：

```json
{"action":"click","x":640,"y":400}
```

也支持 `key`（Android `code`）、`text`（`text` 字符串）、`touch`（`x/y`）。
`controls` 仅表示 producer 调用成功，须结合截图判断 UI 是否实际响应。
输入坐标使用 App 当前上报的输入空间；测试首轮协商尺寸为 1280×800。

退出时在同目录创建 `surface-stop`，结束 compositor/测试服务，卸载
`org.termux.mutter.gputest`，恢复 view preferences、termux.properties、phantom
进程配置及本轮改变的旋转配置。正式 Termux APK 和用户数据保留。
