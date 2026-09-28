# Games runtime 组件激活恢复设计

## 总体方案

组件状态拆成两层：Games 私有 verified install 表示下载资产可复用；Host runtime probe 表示 GLIBC 启动链实际可消费。GLIBC preflight 只使用后者，RootFS preflight 使用前者。

## Activity 与导航

组件页保留原分组。GLIBC 条目在私有 active 存在但 Host probe 不可用时，状态显示“需要激活”，主操作改为“激活”。

## Service 与任务状态

`ComponentTaskForegroundService` 增加 ACTIVATE 命令。命令读取 immutable active receipt，校验 version/SHA 与当前目录一致，再调用 Host。新下载的安装任务在 Games version directory 发布后、active pointer 前移前调用同一 Host 激活路径。

## 数据、存储与迁移

不迁移既有 task/receipt。升级前组件通过显式激活动作补齐 runtime。Host 不取得 Games version directory 所有权。

## 组件下载与安装

`RuntimeComponentActivation` 传递 safely extracted prepared directory，不传 archive。TermuxBoxRepository 从该目录的 `glibc/` 发布到 `$PREFIX/glibc`，只为实际发布文件生成 metadata。bundled/remote index 同时接受 schema v1/v2。

## 干净前缀资源

`scripts-v29` 与 `prefix-apps-v3` 将 `drive_c.7z`、`directx.7z` 预展开为普通目录，避免设备侧隐式依赖 p7zip 及 commons-compress 不支持的 BCJ2。bootstrap 优先复制预展开目录，保留旧包回退并给出明确错误。Box64 interpreter 已正确时不强制要求 `patchelf`。

## X11、输入与运行会话

`X11SessionView` 仍是 Games 独立控件，不复用终端集成 UI。两个 runtime backend 都声明 `termux-x11` Host capability；Orchestrator 在 preflight 前、runtime provisioning Host 在提交 bootstrap 前调用同一幂等准备入口，由 app 安装内置 `xkeyboard-config` 与 Termux:X11 DEB。失败返回稳定 `x11_bridge_install_failed`，不得进入启动或前缀脚本。

## 受保护路径

涉及 app 侧 `TermuxBoxRepository`、LocalGames Host adapter、GLIBC bootstrap asset 和 app instrumentation；不修改 TermuxService、TermuxActivity、X11 控件/native 或 Manifest。

## 测试策略

- JVM：prepared directory 交接；GLIBC 不接受 private-only install；RootFS 接受 private verified install。
- app 强制编译与 Games 全量单测。
- ARM64 AVD：卸载 app 后从空数据安装，下载并激活 9 个真实组件，创建 Wine 前缀，自动安装 X11 bridge；另跑 Games 设备测试全集。

## 风险与回滚

共享 GLIBC 树目前不是目录级原子切换，进程崩溃级恢复另行处理；本 change 先消除重复解包、资源所有权和状态误判。激活异常不会改变 Games active pointer。
