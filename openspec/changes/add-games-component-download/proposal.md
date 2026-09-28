# Games 组件下载提案

## 背景

领域基础已提供持久 ComponentTask 模型和 Repository 契约。现有 TermuxBox 下载使用整文件覆盖，网络中断后无法续传，也未校验 Range 与远端对象版本。

## 目标

- 实现文件型 ComponentTaskRepository，进程重启后可恢复任务元数据。
- 下载写入 `.part`，使用 Range 和 If-Range 从已确认 offset 续传。
- 保存并校验 ETag/Last-Modified；对象变化时安全回退为完整下载。
- 严格校验 206 Content-Range、本地大小、声明大小和 SHA-256。
- 服务端忽略 Range 返回 200 时截断旧 partial 并安全重下。
- 支持协作式暂停/取消，保留可恢复 partial。

## 非目标

- 不实现组件索引 UI、前台 Service、通知、解压安装或版本原子切换。
- 不修改现有 TermuxBoxRepository；新实现先在 Games 模块独立验证。
- 不发起真实外网下载测试。

## 用户可见行为

该分片提供后端能力；后续组件页面可展示持久进度并执行暂停、继续、取消和重试。

## 影响范围

- `games/src/main/java/com/termux/localgames/components/**`
- `games/src/main/java/com/termux/localgames/data/FileComponentTaskRepository.java`
- `games/src/test/**`

## 风险与回滚

- 风险：错误 append 导致损坏；仅在 206 且 Content-Range 起点匹配时追加。
- 风险：远端对象变化；比较 ETag/Last-Modified 并回退完整下载。
- 风险：校验失败误标可用；只有大小和 SHA-256 同时通过才进入 VERIFIED。
- 回滚：删除新增实现，不影响现有 TermuxBox 组件管理。
