# 简化核心组件入口

用户已确认移除独立组件入口，只保持核心组件 Build from network 和现有手动安装入口。游戏编辑页继续选择 Wine、Box64 和图形组件；自动下载、缓存与准备机制保留。

移除设置中 Native/PRoot 的组件菜单、独立组件目录页和导航状态。启动失败的组件修复入口改为对应后端的核心组件页。环境状态、网络构建、现有备份与从文件恢复不变。不增加兼容、回退、重建 prefix 或资产校验。

无 HTTP 契约修改，api_docs 为 N/A；影响用户流程，product_docs 为 Required，拟在用户验收后维护 docs/product/runtime-components.md，目前 Pending。
