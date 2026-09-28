# Games 组件下载设计

## 总体方案

```text
ComponentDownloader (blocking worker primitive)
  -> HttpConnectionFactory
  -> FileComponentTaskRepository
  -> <package>-<version>.part
  -> size + SHA-256 -> <package>-<version>.archive
```

Downloader 本身不创建线程；Service/Orchestrator 必须在 worker 线程调用。网络连接通过工厂注入，JVM 测试使用 fake HttpURLConnection。

## Activity 与导航

N/A；UI 接入后续实现。

## Service 与任务状态

- 调用开始先持久化 DOWNLOADING。
- 每个写入批次更新 downloadedBytes，并回调进度。
- 暂停进入 PAUSED；取消进入 CANCELLED；I/O/协议/校验错误进入 FAILED。
- VERIFYING 后只有大小与 SHA-256 通过才进入 VERIFIED。

## 数据、存储与迁移

每个任务一个 `.properties` 文件，包含 `schemaVersion=1` 和完整 ComponentTask 字段。写入临时文件、flush/fsync 后 rename，防止半写。未知 schema 拒绝加载。

## 组件下载与安装

- partial > 0 时发送 `Range: bytes=<offset>-`。
- 已记录 ETag 时优先发送 `If-Range: <etag>`，否则使用 Last-Modified。
- 206 必须解析 `Content-Range: bytes <offset>-<end>/<total>`，offset 与 total 必须匹配。
- 200 表示忽略 Range 或对象已变化，覆盖 partial 从零开始。
- 远端 ETag/Last-Modified 与记录值冲突时重新建立无 Range 请求。
- 416 且 partial 大小等于声明大小时直接进入本地校验。
- 校验成功后 rename 为 `.archive`；本分片不解压安装。

## X11、输入与运行会话

N/A。

## 受保护路径

不触碰 protected path；不新增 Gradle 依赖或 Manifest 组件。

## 测试策略

- Repository round-trip、schema 拒绝与原子覆盖。
- fresh 200 下载。
- 206 正确 offset 续传。
- 200 忽略 Range 安全覆盖。
- ETag 变化回退完整下载。
- Content-Range 错误拒绝。
- SHA-256 错误不得生成 verified archive。
- 暂停保留 partial 和持久进度。

## 风险与回滚

按 proposal 执行。
