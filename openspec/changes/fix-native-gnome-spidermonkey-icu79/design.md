# 设计

## 真实证据

- 设备：120.24.191.156:8057，运行模式 native，GNOME Android 后端，System EGL，1280×720/60Hz。
- 调用链：gnome-shell → libgjs.so.0 → libmozjs-128.so → libicui18n.so.78 / libicuuc.so.78。
- 安装状态：GNOME Shell48.7-1、Mutter48.1-24、GJS1.82.3、SpiderMonkey128.10.0-3、ICU79.1。
- 日志加载器错误发生在创建 Wayland socket 之前；“Wayland 未就绪”只是监督程序报告的后果。
- 软件源当前提供 SpiderMonkey140.17.0-1，不满足 GJS 所需128的文件名与接口。不能用包名相同推断可替换。

103个启动依赖ELF闭包中，只有 `libmozjs-128.so` 直接依赖ICU78。指定7022编译机已只读核对：128.10.0/revision3配方、源码、原补丁和源码归档缓存均在；编译前缀ICU头文件主版本为79。尚未触发新构建或改变设备安装状态。动态加载的后续模块仍需在候选验证与正式UI验收时核对，不能将静态闭包结果当成全部运行功能已通过。

## 最小修复

在指定编译机检查现有128源码、配方及补丁后，通过原 `/workspace/termux-packages/build-package.sh` 自动处理依赖，使用用户指定网络代理。目标为 Android Bionic aarch64，前缀 `/data/data/com.termux/files/usr`，不混用 glibc 或 PRoot 产物。

优先保留既有128源码及配置，只针对真实 ICU79 重编。若128与ICU79存在源码兼容问题，先记录具体编译证据和必要补丁；不得未经审查升级 JavaScript 引擎主版本、GJS或整个GNOME栈。包版本及文件名维持 `spidermonkey_128.10.0-3_aarch64.deb`，拟明确 `libicu (>= 79), libicu (<< 80)` 的依赖边界。

扫描GNOME ELF依赖闭包，检查新包的Bionic加载器、NEEDED、SONAME及GJS实际使用的导入符号，确认无ICU78残留和新缺失依赖。保留现场日志，不通过后台启动桌面代替App验收。

## 发布、缓存与回滚

经规划与发布范围确认后，替换 `jiaxinchen-max/termux-app-packages` 的 `gnome-usb-20261030-r1` 中同名SpiderMonkey资产，同步当前索引、元数据及App清单。先暂存上传核对摘要，再交换原名称；公开下载复核成功前保留原资产，失败时恢复原名称。

当前安装脚本按同名文件复用缓存，且用户已拒绝自动版本升级和兼容迁移。本次不增加迁移；已有安装需明确清除对应旧包缓存并重装新版包。禁止降级系统全局ICU或对78创建指向79的假链接。若新构建需要额外组件重编，先回到范围确认。

## 验证与限制

编译与包检查完成后，运行最小相关脚本检查并构建原签名、非testOnly的 `termux-app_apt-android-7-debug_arm64-v8a.apk`，核对实际APK内清单并提供手动下载。设备通信串行，不通过ADB传输大型APK。正式UI验收由用户进行。

当前仓库缺失Harness工作流、严格验证脚本、OpenSpec CLI及产品基线；保留人工规划、证据和待验收状态，不能宣称严格门禁已通过。需修改的安装清单属于高影响安装路径，影响、回滚与验证如上，实施前需按工作区AGENTS要求确认。
