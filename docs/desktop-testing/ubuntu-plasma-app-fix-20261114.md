# App Ubuntu Plasma 底包修复

用户明确“修复”后，新建PRoot Plasma现请求ubuntu:resolute（Ubuntu 26.04），实例为ubuntu-kwin-plasma-N，安装Kubuntu桌面设置、Plasma、Dolphin、Konsole及Python。宿主保留KWin 6.7.5-4/r4；Native和其他桌面配置保持。安装器、Python初始化与归档校验统一真实发行版识别，拒绝Debian和错误Ubuntu版本；不迁移旧数据。Ubuntu Turnip resolute资产已通过公开release清单核对，未将Debian二进制用于Ubuntu。

目标测试95项通过：Java创建/启动/归档51、初始化24、真实安装入口隔离命令4、非交互安装10、Hyprland6。包管理/网络由隔离夹具替代，不代表设备全新安装验收。直接源码审阅无未解决发现。OpenSpec 1.4.1严格校验通过；Harness strict-run缺失脚本，Unavailable。产品文档仍Required/Pending，未归档；用户随后明确授权提交并推送App桌面修复。

APK为45358582字节（43.26MiB），SHA256 a00b5e08a726a6ab10564a182eed47386058a1e963b90a1e0aa956cd4e76b616。原签名、testOnly=false、arm64-v8a、16KiB zipalign、七个实际脚本一致；实际DEX的DesktopCreationCatalog包含KWIN_PLASMA/ubuntu/resolute且无forky；完整HTTP GET字节数/SHA相符。设备没有卸载、重装或新容器安装；完整Ubuntu/Kubuntu运行、硬件和性能验收待用户测试。

前轮Native r4实际特效/黑屏验证见artifacts/native-plasma-black-screen；本轮不将其扩大为Ubuntu容器功能通过。旧APK和受影响文件备份保留。
