# 当前验证记录（未完成全量验收）

普通 arm64 APK：`termux-app_apt-android-7-debug_arm64-v8a.apk`，47,052,095字节，无testOnly标记，已核对签名并由用户手动安装。SHA256：`6a98594bf70edda06476bbff8215ad68efaaf1ca2db94b4c490831441b07895c`。

测试设备120.24.191.156:8057，显式初始化APK配套CLI与UPower组件后，BatteryStatus连续返回真实电量，耗时23–246毫秒；独立D-Bus上UPower注册3.456秒，查询0.251秒。Plasma会话内UPower正常在线。Dolphin实际窗口正常显示，首次首帧20.15秒，移除系统调用跟踪后的复测4.065秒；冷启动波动和Wayland首帧等待未宣称全部解决。

配套C传输/异步电池查询及静态清单6项测试通过。termux-ui已有771项JUnit结果无失败，本次Gradle为UP-TO-DATE。app全量177项中8项失败、1项跳过，并发生OOM；定向复测4项中仍有2项失败：DesktopSessionControlsTest退出重试最终完成等待超时，以及GameControllerMappingEditorTest运行选项视图为空。未证明属于旧问题。

已实测音频信息、只读音量、摄像头信息、传感器枚举及单次采样/清理、媒体info、TTS引擎枚举、keystore列表、USB空列表。无红外硬件、缺少通知访问权限与未知方法明确返回错误。没有执行实际短信、电话或通知回复。USB授权/FD与其他硬件、交互权限及长会话仍待完整验证。

设备没有已安装的proot容器，Debian/Ubuntu guest回归待补。不同UID设备探针被Android终止，不能计为隔离实测通过。逐项状态见coverage-matrix.json。

Harness引导文件及strict gate脚本缺失，严格门禁为Unavailable。产品文档一致性Pending；本次推送不代表最终验收或归档。用户在知悉上述限制后明确要求先推送代码。
