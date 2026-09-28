## ADDED Requirements

### Requirement: 组件下载支持安全断点续传

系统 MUST 持久化 partial offset 和远端对象标识，并仅在 HTTP Range 响应与本地状态一致时追加数据。

#### Scenario: 服务端支持续传

- **WHEN** 本地存在有效 partial 且服务端返回匹配的 206 Content-Range
- **THEN** 从本地 offset 追加下载
- **AND** 持久化新的 downloadedBytes、ETag 和 Last-Modified

#### Scenario: 服务端忽略 Range 或对象变化

- **WHEN** 服务端返回 200，或 ETag/Last-Modified 与持久状态冲突
- **THEN** 系统不得把完整响应追加到 partial
- **AND** 截断旧 partial 并从零安全重下

### Requirement: 未校验组件不得进入可用状态

系统 MUST 同时校验声明大小和 SHA-256，且只有校验成功后才生成 verified archive。

#### Scenario: SHA-256 不匹配

- **WHEN** 下载字节摘要与组件描述不一致
- **THEN** 任务进入 FAILED
- **AND** 不生成或覆盖 verified archive
