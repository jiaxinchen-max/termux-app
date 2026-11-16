# 实施与验证记录

用户已明确回复“批准”确认规划。本轮按原有持续授权提交、推送进度；不发布组件 Release，不改七个既有脏子模块，不改日常启动或音频服务链路。

## 已实施

- Hyprland PRoot 加入当前 Ubuntu/resolute 归档范围。专用 v3 元数据记录 schema 4 和明确合成器/主题，严格拒绝未知、缺失、重复及额外字段；其他桌面保留原 v2 格式。实际 tar 导入生成新 ID、alias 时保留组件与用户内容；原名称及重名后缀继续复用原事务规则。
- 恢复前复用原固定组件准备流程，已有宿主组件无需网络；缺失组件从原资产库校验获取。宿主准备分支不创建容器、不运行 guest apt。恢复 wrapper 使用新身份和 `.hyprland-ready`、显式尺寸与组件参数；失败不发布成功结果，新建宿主 workspace 纳入 inode 所有权清理。
- XFCE 在安装、保存、恢复阶段补齐发行版编译使用但缺失的 `/usr/share/xfce4/backdrops/xubuntu-wallpaper.png`，引用容器内已有 greybird.svg。有效文件、自选壁纸和 XFConf 保留；拒绝父路径或图像链接越界，不新增启动修补和图像资产。
- USB 管理弹窗使用局部实色日夜主题和 Material 控件；明确区分运行中禁用与环境不支持。Hyprland、当前 Ubuntu GNOME、Plasma 仍不支持本 USB 后端，分别解释原因并隐藏无效安装引导。依赖/会话读取异常会恢复刷新入口，关闭后取消刷新和界面更新。

## 已通过的证据

- `:termux-ui:testDebugUnitTest --tests 'com.termux.ui.desktop.archive.*' --tests 'com.termux.ui.desktop.api.DesktopRestoreInitializationTest'`：56 项通过，包括两种 Hyprland 主题 × default/zink 客户端的真实文件系统 tar 往返、元数据拒绝、新身份契约、恢复初始化与音频失败不发布成功；既有归档安全/失败用例继续通过。
- `PYTHONDONTWRITEBYTECODE=1 python3 docs/desktop-testing/test-hyprland-restore-host.py`：4 项通过（包含主题失败/缺失 shell 子场景），执行生产安装脚本，覆盖已有组件离线复用、依赖安装失败、固定运行库下载失败、主题/必要文件失败；所有场景验证原容器内容不变、不创建 guest、不调用 guest 安装或本地初始化。
- `PYTHONDONTWRITEBYTECODE=1 python3 docs/desktop-testing/test-xfce-wallpaper.py`：6 项通过，覆盖缺失默认、重复保存、已有文件、容器绝对链接、父路径/源图越界、资源缺失。
- 模拟器 `120.24.191.156:8057`：先 force-stop App，再备份已部署 initializer，部署小脚本并执行保存阶段的同一壁纸准备函数。容器内实际 GdkPixbuf 成功解码新默认路径（缩略图 115×72，退出 0），未修改用户壁纸 XML。备份位于 App home 的 `.termux/three-issues-20261116/desktop_initialize.py.before`。未安装 APK，未修改默认入口。
- `:app:assembleDebug`、shell 语法检查、Python 用例加载与 `git diff --check` 通过。直接源码自审覆盖元数据/解包信任边界、宿主准备隔离、workspace 所有权清理、UI 异常出口和运行时边界；结果见 review-findings.yaml。
- APK 使用原 debug key，v1/v2 验签通过，非 testOnly，仅 arm64-v8a；72 个运行资产逐字节对应源码，无 pyc 或新增大资产。沿用既有去旧签名、重压缩、zipalign 后重签流程，最终 44,533,291 字节（42.47 MiB），较上一交付增加 8,192 字节。
- 最终 SHA256：`2e87d74e53b82ba066dfe18c0048efa82151e4a4c83388a2b28ee5de357796c4`。本地 HTTP HEAD 200，完整 HTTP 下载内容 SHA256 相同；外部代理需要用户已有登录，未宣称匿名公开可用。

## 尚未通过/待验收

- 新 APK 的 USB 弹窗日夜截图、真实点击、刷新异常和关闭生命周期交互尚待安装后验证。现有安装包不包含新的 Java UI，因此不把旧界面检查计作新包通过。
- Hyprland 已有主机的实际 Android UI 导出和独立恢复至可启动桌面的端到端验证未完成；本轮完成的是生产引擎在真实临时文件系统中的 tar 往返、宿主安装脚本隔离测试及 wrapper 执行测试。没有把这些说成模拟器整容器恢复已通过。
- XFCE 已证明默认路径与解码正确，实际桌面显示截图仍待 UI 启动验证。
- 指定严格门禁命令实际执行退出 127：仓库没有 `.harness/scripts/verify.sh`；指定 Harness 源文件、OpenSpec CLI 和基线资料也缺失。替代检查不等于严格门禁通过。
- 产品资料一致性维持 Pending；本轮没有用户最终验收，不更新 canonical 产品资料或归档变更。

## 使用与回退

覆盖安装固定名称 APK。已有 XFCE 桌面打开设置保存一次，再启动即可应用新脚本和默认壁纸；无须重装容器。模拟器本轮已直接执行壁纸准备。Hyprland 在新 APK 的容器菜单中提供导出，保留原 USB 关闭/停止桌面/互斥条件。

回退源码不会删除容器或备份；旧 APK 不支持新的 Hyprland v3 归档，需保留新 APK 用于恢复。设备小脚本回退可使用上述备份；有效新增默认链接无须清理，保留用户自选配置。
