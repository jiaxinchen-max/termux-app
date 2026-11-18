# 发布元数据设计

仅修改 .github/workflows/attach_debug_apks_to_release.yml 中 workflow_dispatch.inputs 的 release_tag、release_name 和 release_notes 的默认值。两个版本值保持相同的 v1.0.8.1；说明使用 YAML literal block，保留真实换行，分别以中文和英文按大颗粒度概括整个新增 termux-ui 模块。

说明覆盖已实现的 Wine 程序与游戏管理、Native/PRoot Linux 桌面管理、运行组件管理、文件与批量操作、输入与显示设置、备份恢复。直接核对 Activity、Service 和对应源码后写入，不宣传未交付功能。Android Activity/Service、任务状态、shell、X11、输入实现、存储和组件下载代码均不改动。

仓库未提供 protected paths 注册表或 policy id；本次仅改已获用户明确指示的发布默认字段。恢复旧默认值即可回滚；不影响已发布制品。通过 actionlint、YAML 解析和结构比较验证只有三个默认值发生变化；无需重编译APK或执行发布。

双语说明按“中文”“English”分节，每种语言均包含相同的六类能力；沿用同一release_notes输入，发布时同时显示两段。
