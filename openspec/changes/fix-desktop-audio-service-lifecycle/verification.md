# 验证记录

## 已完成

- 根因追溯：52ccd543 将原音频复用流程从日常 start 入口移到 prepare，只剩 --start。新卡片尺寸提交没有改启动，测试残留共享服务触发已存在的 PID 识别缺陷。
- Python 音频生命周期 18 项通过，覆盖 cold start、端点与 Android sink、健康共享/包替换复用、停旧后启动、PID 身份变化、信号失败、Android hidepid 与文件锁串行化。
- termux-ui 的 DesktopRestoreInitializationTest 5 项、DesktopArchiveLaunchSpecTest 4 项通过；APK assembleDebug 成功。
- 模拟器真实服务替换：两个 run-as 测试残留音频进程 18545/19733 变为单个 22346，端点与 Android sink 可用，第二次调用保持同 PID。
- Native：测试前 force-stop，经实际管理页 Start/二次确认打开，音频直接复用，gnome-ready 已发布，取得真实 GNOME 桌面截图 native-running.png。
- PRoot：干净 App 进程经实际管理页确认启动，日志已通过音频复用并完成 Android AHB 渲染桥握手；本次 GNOME 自身就绪超时退出 exit:1。未把音频修复等同于 PRoot 桌面完整验证通过，正在复查。
- APK 固定文件名 termux-app_apt-android-7-debug_arm64-v8a.apk，44,525,099 字节（42.46 MiB），原 debug key 签名 v1/v2 通过、无 testOnly、仅 arm64，72 个运行资产与源码逐字节一致。SHA256 913a4c7cf2d58a3491ea642c3121db37c5c4c936aae01294ae36e416ead9dadb。

## 验证边界

设备测试按用户偏好没有通过 ADB 上传安装完整 APK，直接更新同字节 desktop_common.sh 和 desktop_audio_service.py；保存了修改前运行脚本。正常 APK 部署走原保存/安装/恢复流程，其他设备覆盖 APK 后需在桌面设置保存一次更新已部署脚本；当前模拟器已应用。

初始化大套件 24 项中 8 failures/4 errors，以及安装音频夹具的渲染组合失败，均在未修改 HEAD 的隔离基线复现，失败用例集合相同；没有宣称全套绿灯。仓库缺少 Harness verify.sh、四份流程指令及 OpenSpec CLI，严格门禁未运行。

产品资料一致性 Pending，等待用户验收，不归档。七个既有脏子模块未修改。详细日志、基线对照、真实音频生命周期、截图和 APK 元数据保存在 artifacts/desktop-start-regression-20261116 与 artifacts/desktop-audio-lifecycle-apk-20261116。
