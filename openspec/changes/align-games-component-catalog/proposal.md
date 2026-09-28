# Games 组件目录与 GameHub 对齐提案

## 背景

现有组件页把 TermuxBox 的 17 个归档包直接按 `Runtime/Wine` 展示，包 ID 同时承担展示名、组件类型和交付身份。GameHub 6.2.1 静态模型则区分 ImageFS、容器、GPU 驱动、DXVK/VKD3D、Box64/FEX、Steam 和通用依赖，并在游戏配置内提供可用版本选择。当前模型无法表达这些差异，也把 `scripts`、`prefix-apps` 等内部交付包误当作同级用户组件。

## 目标

- 在不伪造下载地址和版本的前提下，将现有资源映射为 GameHub 对应的组件类型与槽位。
- 将“用户可选择组件”和“内部基础/依赖包”分开呈现；交付仍以真实归档为原子，避免重复下载或虚假拆包。
- 为组件补齐展示名、版本名、简介、框架、基础组件、推荐项、可选择状态和支持的运行后端元数据。
- 组件维护页按 ImageFS、容器、GPU、DirectX、转译器、通用依赖和内部运行支持分组，显示技术 ID 但不再以 ID 作为主标题。
- 游戏配置中的 Wine、GPU、DirectX 选择器由同一组件索引提供选项；无资源或与当前后端不兼容的项不允许写入。
- 保持现有下载、断点续传、校验、staging、回滚、任务恢复和安装目录契约兼容。

## 非目标

- 不为主 APK 无法恢复的 GameHub 动态在线版本伪造 URL、摘要、兼容性或推荐数据。
- 不在本 change 引入 Steam 商店、云存档、游戏本体下载或账号能力。
- 不把 `prefix-apps` 猜测拆成 Mono、Gecko、PhysX、字体等独立可安装包；只有归档清单和独立校验信息明确后才允许拆成交付单元。
- 不在本 change 实现尚无可用资源和启动后端支持的 FEX、VKD3D、Steam Client。
- 不修改 TermuxService、TermuxActivity、X11/native 或主应用构建边界。

## 用户可见行为

- 组件页以中文组件名称、版本、用途、框架和状态展示真实可用资源，分组与 GameHub 的组件槽位一致。
- Wine 归入“容器/兼容层”，Turnip/VirGL 归入“GPU 驱动”，DXVK/WineD3D 归入“DirectX”，Box64 归入“转译器”；基础脚本和库移动到“运行支持”。
- 游戏配置中的 Wine、GPU 和 DirectX 字段变为目录驱动的单选列表；用户仍可看到原始技术 ID。
- FEX、VKD3D、Steam 等缺少可验证交付资源的槽位不会显示成可安装成功的假条目；组件页用能力说明明确当前未提供。

## 影响范围

- `:games` 组件索引 schema/parser/domain/catalog。
- `LocalGamesActivity` 组件维护 UI、组件行布局和本地化文案。
- `GameRuntimeProfileActivity` 支持项的索引驱动选择器。
- 组件索引 fixture、JVM 测试和 Activity instrumentation。
- OpenSpec/Harness 与本地游戏运行领域文档。

## 风险与回滚

- schema v2 解析器继续接受 v1；下载任务和安装 receipt 仍使用现有 package ID/version，不迁移已安装数据。
- 展示分类不改变安装目标和 preflight 的组件 ID，回滚时可恢复旧索引和旧分组逻辑。
- 选择器只列出当前后端真实支持的条目，防止 UI 可选但启动器无法消费。
