# Games 组件目录与 GameHub 对齐任务

- [x] 补齐 proposal、design、specs 和 tasks，记录动态 APK/在线目录不可恢复边界。
- [x] 增加 ComponentType 和展示/兼容元数据，支持 schema v1 兼容读取与 schema v2 严格解析。
- [x] 将 bundled index 的真实资源映射为 ImageFS、容器、GPU、DirectX、转译器、通用组件和运行支持；保持交付字段不变。
- [x] 重构组件维护页分组和条目，展示名称、版本、简介、框架、技术 ID、基础/推荐标签及缺失能力说明。
- [x] 将 RuntimeProfile 的 Wine、GPU、DirectX 字段接入同一目录的 backend-aware 单选选项，并保护旧配置值。
- [x] 补齐 parser/catalog/profile UI 单测和 Activity recreate instrumentation。（新增 ComponentSelectionTest 6 例、ComponentCatalogProjectionTest 7 例；单测 141 → 154，instrumentation 19/19）
- [x] 完成代码评审、测试评审、Gradle/module boundary/Harness strict 证据。（修复 2 个 MUST_FIX：用户错误文案泄露内部 token、边界脚本缺 ripgrep 时静默假通过）
- [ ] 用户接受后同步产品文档并归档。
