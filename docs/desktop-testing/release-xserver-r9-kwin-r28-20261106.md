# Xserver r9 / KWin r28 发布记录（2026-11-06）

两项已公开发布并设为 latest，旧 release 保留。

| 组件 | 发布 | 源码提交 | deb SHA256 |
|---|---|---|---|
| Xserver 21.1.16-9 | https://github.com/jiaxinchen-max/xserver/releases/tag/termux-xlorie-21.1.16-r9 | ebf4a76b8e0f24c69a820c1043dc11f9cb92c39b | fa06b6bcc1dcc19b4b78d7497fc33acce43cc72320c62b7a9679a9113aeceaac |
| KWin 6.6.0-28 | https://github.com/jiaxinchen-max/kwin/releases/tag/termux-hwaccel-6.6-r28 | fe13811e72f9b62e5439a4fd607b228b10961fe8 | d6b97467522ce0736d0f142baddd333072da76208c1bca985685b4a2b2d82cdf |

Xserver 删除 minimal GLX provider 虚报的 GLX_INTEL_swap_event；KWin 在 Android 后端正常 teardown 阶段释放全局 EGL share context，避免 Qt TLS 销毁后析构崩溃。运行模式和 renderer 仍必须显式指定，没有失败回退。

两个 deb 在指定的 Termux 编译机重打包，两次独立打包均逐字节一致。正式二进制与先前正式 App UI 验证的候选完全相同。Xserver 基于完整 r8 底包，216 项 payload 仅 Xlorie 改变；KWin 基于完整 r27，856 项仅 libkwin 改变，ABI、依赖及其余 payload 保持原样。版本、必要的 size/md5 元数据随包更新。

Xserver release 共 14 项资产；KWin 共 25 项，包含 9 个 Native 与 4 个 forky Discover deb。KWin 附带的 libandroid-stub 改为已发布的 30-3，与 App 清单一致；Discover 仅适用于 glibc forky 容器，不得安装到 Bionic Termux。其余依赖资产保持原字节。全部 39 项名称、大小、GitHub SHA256 已核对；App 两个安装清单的全部 14 个公开下载 URL 已实际下载并通过 SHA256 校验。

源码分支为 codex/gnome-glx-swap-event 和 codex/kwin-r28-egl-teardown，正式 tag 均对应上述提交。资料包含源码、真实编译源、配方/配置、底包、已验证二进制、重打包输入和校验清单。Xserver 编译缓存与 Git 源差异是 autotools 生成文件及历史 VERIFICATION.md；KWin 的四个共同文件差异都位于独立 Qt AHB 插件，生产插件保持原字节。具体范围见每个 release 的 BUILD-INPUTS 和 source comparison/relationship 文件。输入归档可重现固定二进制的 deb 打包，不包含完整离线 NDK/sysroot，不宣称 native 编译字节可重现。未收录设备日志、截图、数据库、归档或凭据。

此前 UI 验证边界：Fold 的 GNOME GL3/LLVMpipe 概览、应用网格、Files、Direct touch、音频及小米的 GNOME GL3/Zink/Turnip 概览、Files、时钟刷新通过；Fold Native KWin System EGL 启动和 Stop 通过。Fold 验证临时禁用了 phantom process 限制，未重新验证 Proot Plasma LLVMpipe Stop。本次版本号重打包没有重复整个 UI 矩阵，不能扩大这些结论。

App 本地下载清单、XServerTasks、DesktopLaunchSpec 和相关测试 fixtures 已同步版本与 SHA。本地 assembleDebug、desktop scripts contract、39 项 restore prerequisite fixtures、38 项 GNOME/USB install tests 通过。ARM64 APK SHA256 为 fa6640ac767c6a716e55adb95b7c7cd91243df3ec193f0b8139fb59999b2579a。本轮没有推送 App 工作区，也没有创建 App release；上述含新 pin 的 APK 尚未部署设备。

发布证据位于 /tmp/fold-ui-clean-20261104-142515：xserver-public-release-verification.json、kwin-public-release-verification.json、published-download-verification/RESULT.json。GitHub REST 发布阶段曾返回 500/502/空响应，最终使用最小 HTTP/1.1 PATCH 成功公开，再读取公开 API 和 tag 完成核对；没有创建重复 release。
