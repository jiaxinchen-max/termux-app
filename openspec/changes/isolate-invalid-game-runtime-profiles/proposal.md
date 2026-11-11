# 隔离无效游戏配置

截图报错 missing_runtime_profile_field:xinputEnabled。读取任一旧配置失败会中断 LocalGamesActivity 全库加载；删除过程也依赖完整配置，造成无效游戏不能从界面移除。

用户已明确批准修复：无效配置只影响对应游戏，保留错误与删除入口，不补字段、不迁移、不回退运行。有效游戏、新导入与 Native/PRoot 集合继续工作。若绑定元数据仍可信，按其实际后端归类；绑定也损坏时在两种集合保留错误卡片，避免猜后端或隐藏记录。

删除前按可验证的绑定/任务强杀。删除无效记录和对应配置，但保留无法证明归属的运行目录及外部游戏文件；不能用默认 RuntimeProfile 猜测绑定。手柄 DLL 现状为每次启动前全量同步、协议按需启用，本轮只核对，不改部署时机或载荷。

不涉及 HTTP，api_docs 为 N/A。用户可见游戏库行为变化，product_docs 为 Required，目标 docs/product/game-library.md，验收前 Pending。不含 Git 操作、设备操作、外部发布、保护路径或配套二进制改动。
