# 增加Box64 0.3.8候选

## 原因
0.3.8的Native和PRoot资产已发布，但APK目录和运行链路尚不识别；仅增加目录条目无法保证可安装和实际运行。

## 变更范围
在游戏编辑页对应后端的Box64候选中增加glibc_box64_0.3.8和rootfs_box64_0.3.8，接入已有安装、卸载、重装操作与按游戏保存选择。补齐Native探测、PRoot绑定及脚本版本识别；运行时使用当前选中版本的程序和配套库。保留现有默认值和旧版候选，禁止自动升级和失败后换版。

编译正常arm64 debug APK，名称保持termux-app_apt-android-7-debug_arm64-v8a.apk，testOnly=false，供用户手动安装。设备测试由用户完成，不远程安装或启动游戏，不提交推送代码。

api_docs=N/A：不改变HTTP契约，仅使用现有资产下载地址。product_docs=Required：增加用户可选版本，最终验收后更新docs/product/runtime-components.md；此前仅登记规划。
