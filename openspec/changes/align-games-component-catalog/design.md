# Games 组件目录与 GameHub 对齐设计

## 总体方案

保持“一个可校验归档 = 一个交付 package”的现有边界，在 `ComponentDescriptor` 上增加产品投影：

```text
ComponentDescriptor
├── delivery: id, versionCode, url, size, sha256
├── identity: displayName, versionName, type, framework
├── presentation: summary, base, recommended, selectable
└── compatibility: runtimeBackends

ComponentType
├── IMAGE_FS
├── CONTAINER
├── GPU_DRIVER
├── DX_WRAPPER
├── TRANSLATOR
├── GENERAL_COMPONENT
└── RUNTIME_SUPPORT
```

GameHub 的 `IMAGE_FS/CONTAINER/COMPONENT` 是上层分类；Games 使用更细的稳定 `ComponentType` 支撑设置槽位和维护页分组。现有下载、任务、安装和 preflight 仍按 descriptor 的交付 ID 工作。

当前真实资源映射：

| 资源 | 类型 |
| --- | --- |
| Wine 8.18/9.x | CONTAINER |
| hangover Debian source | IMAGE_FS |
| Turnip、VirGL Mesa | GPU_DRIVER |
| DXVK、WineD3D | DX_WRAPPER |
| Box64 binaries | TRANSLATOR |
| prefix-apps、libudev、locale | GENERAL_COMPONENT |
| glibc-prefix、scripts | RUNTIME_SUPPORT |

## Activity 与导航

- 组件页按固定产品顺序创建 section，不再用 `category == wine` 二分。
- 行卡片主标题为 `displayName`，副标题包含 `versionName`、framework 和技术 ID；简介独立显示。
- 基础组件、推荐组件使用文本标签；内部运行支持放在末组，避免和可选版本混排。
- 没有真实 descriptor 的 GameHub 槽位以页面级“当前未提供”能力说明展示，不进入下载任务和安装状态机。
- RuntimeProfile 的 Wine/GPU/DX 字段改为不可自由输入的 exposed dropdown，adapter 来自 bundled index，并按 backend 过滤；现有持久值不在目录时仍可显示但保存前给出明确错误，避免静默改写旧配置。

## Service 与任务状态

不修改 Service 状态机。`ComponentDescriptor.createTask()` 继续只使用交付字段；展示元数据不进入 `ComponentTask`。缺失资源说明不是 descriptor，因此不能 enqueue。

## 数据、存储与迁移

- index schema 从 v1 升到 v2；parser 同时接受 v1/v2。
- v1 通过稳定 ID 兼容映射生成类型和展示元数据，确保旧 fixture、远程缓存或降级包仍可展示。
- v2 严格校验新增字段和枚举；运行后端只接受 `glibc_termux_box`、`rootfs_proot`。
- package ID/version/url/size/sha256 不变，既有 task、download、active/previous 和 receipt 无需迁移。

## 组件下载与安装

- 不拆分已有归档的物理交付；`prefix-apps` 在归档未提供成员 manifest 前只作为“Windows 通用组件包”展示。
- 新增独立资源必须具有 HTTPS URL、精确 size 和 SHA-256，不能只因 GameHub 有同名槽位就写入 index。
- 下载、断点续传、摘要校验、staging 和回滚逻辑不改。

## X11、输入与运行会话

N/A。

## 受保护路径

- 修改 `games/src/main/assets/termux-box-packages/index-v1.json`。该路径不在当前 protected policy 的 `app/src/main/assets/termux-box-packages/**` 范围内，但仍按组件索引高风险变更处理：保留 v1 解析、交付字段不变、运行完整索引测试。
- 不修改 app、Manifest、TermuxService、终端、X11、native 和构建文件。

## 测试策略

- Parser：v1 兼容映射、v2 全字段、非法 type/backend/未知字段、重复 ID。
- Catalog：GameHub 类型分组顺序、展示元数据、安装与任务状态不受新增字段影响。
- RuntimeProfile UI：支持项下拉来源、backend 过滤、旧值保留和非法值阻断。
- Activity：组件 section、主标题不再显示裸 ID、recreate 后任务状态保持。
- 回归：`:games:testDebugUnitTest`、`:games:connectedDebugAndroidTest`、`:games:lintDebug`、`:games:assembleDebug`、`:app:compileDebugJavaWithJavac`、module boundary、Harness strict。

## 风险与回滚

按 proposal 执行。最大风险是把展示类型误用于运行依赖解析；设计上 `ComponentType` 只负责目录投影和选择器过滤，实际依赖仍由 backend 的 package ID 集合决定。
