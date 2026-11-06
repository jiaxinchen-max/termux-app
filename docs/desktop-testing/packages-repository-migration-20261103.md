# 组件仓库迁移（2026-11-03）

`jiaxinchen-max/termux-box-packages` 已重命名为 `jiaxinchen-max/termux-app-packages`；原有 18 个 release 的资产名称、大小和 SHA-256 保持一致。App 内组件索引目录同步改名为 `termux-app-packages`，运行时加载、版本选择器、测试和分发工具使用新路径，全部自有组件下载地址显式指向新仓库。

GNOME/USB 旧地址配置的 `gnome-usb-20261030-r1` 实际未发布，现已将 16 个固定构建产物发布到新仓库；所有公开地址已实际下载校验，与安装脚本中的大小和 SHA-256 一致。

termux-app 旧 release 的 12 个运行组件/配置资产已按原名迁至新仓库的三个 `legacy-termux-app-*` release，每项大小和 SHA-256 校验通过后，经用户明确授权删除源副本；旧 release 说明已增加迁移链接。两份未被下载流程引用的历史环境归档 `termux-backup.tar.1.gz`（1,125,899,282 字节）及 `termux-backup.tar.gz`（386,172,595 字节）按用户要求直接删除，未迁移。代码同名路径仅用于用户主动生成的本地备份，不依赖已删除的 GitHub 资产。

审计确认 termux-app release 已无 deb、组件 ZIP 或运行环境归档；74 个 APK 的名称、大小和 SHA-256 与迁移前完全一致。当前组件源索引及发布索引同步至 21 项，历史组件另存迁移清单；KWin、Xserver 及第三方上游地址不变。包仓库发布工作流核验显式 URL、大小与 SHA-256 后原样发布索引，不再按组件 ID 合成 URL。

验证：本地 APK 构建成功，26 项组件索引/版本测试和 36 项 GNOME/USB 安装合同测试通过。新版安装到 emulator-5554，通过正式 App 的 UI Mode 查看核心组件与运行组件选择页面，确认重命名后的 asset 索引正常加载。此次未重装已有运行环境。

详细日志、迁移前后 release 清单、逐项 SHA-256 记录保存在 `/tmp/terminal-area-20261103/` 的 `packages-*`、`app-package-release-migration*`、`gnome-usb-public-download-check/` 和 `source-component-deletion-approved.json`；新仓库 `repository/` 保留公开组件与清理审计清单。
