# 修复 Native GNOME 的 SpiderMonkey ICU ABI 不匹配

设备 Native GNOME 不能进入桌面。现场 `session.log` 明确显示 `gnome-shell` 的依赖 `libmozjs-128.so` 需要 `libicui18n.so.78`，设备仅安装 ICU79.1。GJS1.82.3 链接 `libmozjs-128.so`，当前软件源则提供 SpiderMonkey140，不能直接升级替换其接口。

拟保留 SpiderMonkey128.10.0、现有定制补丁、包版本及文件名，在用户指定 Termux 编译机通过原 `build-package.sh` 自动处理依赖并针对 ICU79 重编。验证后更新原组件仓库 GNOME 发布位置与 APK 下载清单，再编译原文件名的非 testOnly APK，用户手动安装验收。

仅修复经证实的二进制 ABI 不匹配；保留显式 Native Android/System EGL 模式，不修改等待时间或做失败回退，不降级全局 ICU 或伪造跨 ABI 链接。

api_docs 为 N/A：无 HTTP 契约变更。product_docs 为 Required：涉及 Native GNOME 与系统依赖兼容性，目标为 `docs/product/desktops.md`（当前仓库缺失，验收后再补齐）；一致性保持 Pending。

2026-11-11用户已批准本规划的编译、同名资产发布与App接入，并要求先推送当前代码；已完成首个提交9ceaf759并核对远端。后续实际桌面测试仍由用户通过App完成。
