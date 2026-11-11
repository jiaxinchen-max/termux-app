# Native GNOME ICU79 修复记录（2026-11-11）

正式App的GNOME启动日志显示，GJS依赖的libmozjs-128.so仍链接ICU78，设备已是ICU79.1。用户批准同版本重编、原资产替换与APK接入，并要求先推送当前代码；已先完成9ceaf759并确认远端一致。

## 构建及范围

在指定7022编译机执行原`/workspace/termux-packages/build-package.sh`，目标为Android Bionic aarch64，前缀为`/data/data/com.termux/files/usr`。使用`-I -w -f -j8`，由标准脚本处理依赖，不使用跳过依赖或继续构建选项。当前目标ICU头文件及SONAME均为79。

独立工作目录保留旧源码、源码缓存、原包和26份补丁。Mozilla128构建脚本不兼容编译机Python3.14，改用独立的宿主Python3.11；该工具不进入Android包。标准打包首次遗漏前缀中未更新的头文件，已保留原头文件目录后经原构建入口重新安装打包。最终原文件清单完全一致，只有`bin/js128`和`lib/libmozjs-128.so`变化；头文件、配置及补丁均保留。编译机原配方已恢复，候选配方与构建日志单独保留。

## 发布和核验

原发布位置：<https://github.com/jiaxinchen-max/termux-app-packages/releases/tag/gnome-usb-20261030-r1>。

- 同名包：`spidermonkey_128.10.0-3_aarch64.deb`，11,385,652字节。
- SHA256：`d5a3437b0d0543e58cb01e07cad277334ee849b663c06aef71f5d4c4e8973308`。
- 版本、文件名、SONAME及导出符号集合不变；依赖声明约束为`libicu (>= 79), libicu (<< 80)`。
- 两个ELF均为Bionic aarch64，动态依赖仅由ICU78改为ICU79；GJS1.82.3实际导入的275个符号全部齐全。
- 现场103个启动依赖ELF闭包在替换候选mozjs后无ICU78残留。此为静态检查，动态加载模块与正式桌面尚待用户验收。
- 暂存上传并验证服务器摘要后交换名称，经原公开URL重新下载核对后删除旧资产；其他发布包完全保留。同步发布元数据与`GNOME-ICU79-VERIFICATION.json`。
- App仅同步包及js128的清单摘要元数据；未恢复运行时摘要校验、增加自动版本升级、缓存迁移或失败回退。

GNOME安装测试38项与Shell语法检查全部通过。APK assembleDebug成功，45,227,266字节；原签名、arm64-v8a、非testOnly，文件名保持`termux-app_apt-android-7-debug_arm64-v8a.apk`。实际APK内安装清单与源文件逐字节一致，包含已发布的新包元数据。

APK SHA256：`96e23bacf2a516abdbbaed2efbeeb6bbc9d3f9ee7ac7b973fc8ff22d13ac0311`。

## 验收状态

已仅清除测试设备`120.24.191.156:8057`中的旧同名SpiderMonkey下载缓存，供显式重新安装GNOME使用；未通过后台启动桌面代替App验证，也未通过ADB安装APK。用户手动安装APK后需在App中重新执行GNOME桌面安装，再验证进入桌面。

正式App UI验收、产品文档一致性保持Pending，未归档。仓库严格Harness脚本缺失，未声明该门禁通过。完整本地证据位于`/home/user/workspace/artifacts/rebuild-native-gnome-icu79/`，包括原包、候选包、构建输入归档、公开下载副本、检查日志和APK核验结果。
