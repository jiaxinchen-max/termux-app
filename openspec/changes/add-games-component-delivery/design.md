# Games 组件交付设计

## 总体方案

```text
asset index -> ComponentIndexParser -> ComponentDescriptor
ComponentTaskForegroundService -> ComponentDeliveryCoordinator
  -> ComponentDownloader -> verified .archive
  -> ComponentInstaller -> staging -> immutable version -> active.properties
```

## Activity 与导航

本分片只提供公开 Intent API；组件页面后续调用，不修改当前 Activity。

## Service 与任务状态

- 模块 manifest 声明 `exported=false` 前台 Service。
- 单线程 executor 保证同一进程内安装串行。
- `START_STICKY`；创建时扫描持久 Repository 并恢复活动状态。
- PAUSED/CANCELLED/INSTALLED 不自动恢复；FAILED 仅显式 retry。
- 状态先持久化后更新通知。

## 数据、存储与迁移

```text
files/games/components/
  tasks/*.properties
  downloads/*.part|*.archive
  install/.staging/<taskId>/
  install/<package>/versions/v<version>-<sha-prefix>/
  install/<package>/active.properties
```

active pointer 写入临时文件并通过 backup/rename 替换，记录 active 与 previous。

## 组件下载与安装

- 索引要求 HTTPS、正数版本/大小、64 位 SHA-256、唯一合法 ID。
- archive 二次校验后解压；限制条目数和展开字节数。
- staging 写 receipt 后 rename 为不可变版本目录。
- pointer 切换失败不删除旧 active；rollback 交换 active/previous。

## X11、输入与运行会话

N/A。

## 受保护路径

- `games/build.gradle`：加入 app 已使用的 commons-compress/xz 直接依赖。
- `games/src/main/AndroidManifest.xml`：声明模块私有 Service。
- `app/src/main/assets/termux-box-packages/index-v1.json`：迁入 games，路径兼容。
- 批准依据：2026-08-27 用户明确要求继续该分片。

## 测试策略

- 使用真实现有 index fixture 验证 16 个条目。
- 索引重复 ID、非 HTTPS、错误摘要拒绝。
- tar.xz 正常安装、path traversal 拒绝、失败保留 active、版本切换和 rollback。
- Coordinator 活动任务恢复、pause/cancel/retry 状态。
- Service AndroidTest 编译、manifest merge、lint、assemble、app compile。

## 风险与回滚

按 proposal 执行。
