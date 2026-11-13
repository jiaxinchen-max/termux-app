# Hyprland 独立主题包

本目录保存主题源资源，App 管理的 Python 逻辑不放入主题归档。当前兼容关系：

| 合成器 | 主题 ID | 交付修订 | 主要配套 |
| --- | --- | --- | --- |
| `0.57.0-r7` | `classic-r7` | r7 | XFCE 面板、genmon、swaybg、通知服务 |
| `0.57.0-r7` | `m3ww-v1` | r8 | EWW、Foot、Fish、Starship、字体、swaybg、通知服务 |

r8 的公开发布说明及 source-lock 复用 r7 原生合成器，因此没有另造 `0.57.0-r8` 下载项。参考[发布材料](https://github.com/jiaxinchen-max/termux-app-packages/releases/tag/hyprland-android-0.57.0-70ec30e7-r8)。主题包由本仓库生成并在组件资产库分发；对应公网组件资料见[独立组件发布](https://github.com/jiaxinchen-max/termux-app-packages/releases/tag/hyprland-components-20261113-r1)，目录保留相同文件的下载地址、大小及 SHA-256。APK 只携带可信目录和安装逻辑，安装/修复时从固定 URL 按需获取所选主题；校验后的缓存和已有安装可离线复用。

在仓库根目录执行：

```sh
python3 tools/build-hyprland-themes.py
python3 tools/build-hyprland-themes.py --check
```

工具使用排序后的文件、固定权限及零时间戳生成 USTAR/gzip，主题归档输出位于 `distribution/hyprland-themes/build/`，不作为 APK assets 输入；`hyprland-components.json` 与 shell 目录仍生成在 `termux-ui/src/main/assets/local-games/`，同时生成 Java 组件定义。`--check` 重新生成全部内容并逐字节比较，不写文件。资源许可随各自主题打包，字体附有单独许可。

目录分别固定主题归档 SHA-256、manifest SHA-256、兼容 runtime、资源清单与依赖。安装器拒绝未知组合、未列出的文件、重复成员、路径越界、链接和超限归档；通过临时目录、锁及备份恢复避免发布半成品。主题装入 `$PREFIX/share/termux-hyprland/themes/<id>`，可编辑配置保存在各桌面的私有目录。

App 的创建、设置和桌面卡片分别显示合成器及主题。切换要求桌面停止；候选安装任务成功且安装状态有效后才保存新选择。主题切换保留容器和安装凭据，不重装已校验的合成器。首次升级旧 Hyprland schema3 时，保留原始配置记录的 `.schema3-backup`，再按旧主题标记或默认配置指纹导入 schema4；其他桌面仍用 schema3。用户修改过的配置采用保留策略，切换不会强制重置这些文件。

Termux 原生 deb 编译使用指定远程编译机及 `/workspace/termux-packages/build-package.sh`。静态主题归档不需要 deb 编译。Linux 环境下，`termux-api.deb` 随 App 一起编译并独立发布，不打入 APK；Hyprland、KWin 等 termux-packages 生态包使用远程编译机。本轮不重编未变化的 Hyprland 合成器。

自动化覆盖资产、迁移、参数、失败回滚及会话清理；真实 Android 图形、输入、原生/PRoot 场景仍需设备验证。构建或单元测试通过不能替代实机验收。
