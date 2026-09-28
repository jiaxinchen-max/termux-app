# Games runtime 组件激活恢复提案

## 背景

组件目录 change 的有效证据生成后，后续实现又增加了 runtime 激活链，但没有归属新的 Harness change。该实现把 Games 私有安装回执继续当成启动可用状态、重复解包已校验归档并删除调用方文件，旧安装也没有修复入口。结果是组件页显示“已安装”，真实启动仍可能报组件缺失。

## 目标

- GLIBC preflight 只认可 launcher 实际使用的 `$PREFIX/glibc` 能力。
- RootFS 继续读取 Games 私有 verified component，不错误依赖 GLIBC runtime。
- runtime 激活复用 Games 已安全解压的 immutable version directory，不重复解包、不删除调用方资源。
- 对升级前已有的本地组件提供显式“激活”修复入口。
- 远程组件索引继续兼容 schema v1/v2。
- 干净 Termux bootstrap 上完成真实 GLIBC 前缀创建，不依赖未声明的 `patchelf`、p7zip。
- Games 启动前由 Host 幂等安装 APK 内置 Termux:X11 bridge，不依赖终端模式设置页。

## 非目标

- 不重做组件网络下载、断点续传和 Games 私有 active/previous 事务。
- 不改 TermuxService、X11 控件/native 和用户游戏目录。
- 不在本 change 完成共享 GLIBC 文件树的崩溃恢复事务；失败时仍由 Host 返回激活失败，Games active pointer 不前移。

## 用户可见行为

- 仅下载到 Games 私有目录、但尚未进入 launcher runtime 的 GLIBC 组件显示“需要激活”。
- 用户可直接激活已有 verified component，不重新下载。
- 激活完成后组件页显示已安装，preflight 与组件页结论一致。

## 影响范围

`:games` 组件任务、组件页、preflight 与 runtime backend capability；主 app 的 LocalGames Host adapter、TermuxBox 本地安装入口、GLIBC bootstrap 脚本和 APK 内置 X11 bridge 安装器。

## 风险与回滚

保留 Games immutable version directory 和原有任务回执。若 Host 激活失败，runtime probe 不通过，UI 仍保留“需要激活”，不会伪报成功。回滚本 change 不删除用户组件或 runtime 文件。
