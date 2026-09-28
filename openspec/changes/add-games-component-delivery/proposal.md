# Games 组件交付提案

## 背景

Games 已有持久下载、断点续传和 SHA-256 校验，但仍缺组件索引 Owner、校验后安装、版本切换和 Android 长任务 Owner。

## 目标

- 将现有 `index-v1.json` 资产迁入 `:games`，保持原 asset path 兼容 TermuxBox。
- 实现严格 schema v1 索引解析、重复 ID 和字段校验。
- 实现 tar.xz 安全解压、staging、receipt、不可变版本目录和 active pointer 原子切换。
- 安装失败保持旧 active 版本；支持 previous pointer 回滚。
- 新增模块私有前台 Service，持久恢复 QUEUED/DOWNLOADING/VERIFYING/VERIFIED/INSTALLING 任务。
- 提供 enqueue/pause/resume/retry/cancel Intent API 和通知进度。

## 非目标

- 不改现有 TermuxBoxRepository 安装路径。
- 不接入组件 UI 页面，不下载游戏本体。
- 不实现 Wine/X11 启动。

## 用户可见行为

组件任务可在后台前台服务中持续执行；进程重建后恢复活动任务。通知显示当前组件和阶段。

## 影响范围

- `games/build.gradle`
- `games/src/main/AndroidManifest.xml`
- `games/src/main/assets/termux-box-packages/index-v1.json`
- `games/src/main/java/com/termux/localgames/components/**`
- `games/src/main/java/com/termux/localgames/service/**`
- 原 `app/src/main/assets/termux-box-packages/index-v1.json` 移交模块所有。

## 风险与回滚

- 索引迁移保持 asset path 不变；回滚时移回 app。
- 解压拒绝 traversal、绝对路径、越界链接、特殊设备和资源上限超出。
- active pointer 最后切换；失败时旧版本目录和 pointer 不变。
- Service `exported=false`；移除 manifest 声明即可关闭。
