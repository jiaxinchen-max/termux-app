# 将运行组件管理迁移到核心组件设置

## 问题与目标
游戏编辑页的“Components / 组件”入口管理全局共享运行组件，位置容易被理解为游戏私有配置。将其迁移至“设置 → 核心组件 → Native / Proot → 组件”，编辑页保留当前游戏的Wine版本选择及图形等配置。

## 范围
复用现有组件分类、版本管理、安装与状态刷新；管理入口显式指定Native或Proot，不依赖游戏ID，不为任何游戏写入Wine选择。维护返回路径和页面重建后的后端分组。保留核心组件设置现有环境安装、备份、恢复操作。
清理仅服务旧游戏编辑入口的路由；启动缺失组件提示若引用旧入口，改为进入对应后端的设置管理页。

## 非目标
不修改运行时、镜像或prefix，不引入旧数据兼容、校验或回退，不自动安装APK，不提交或推送。

## 文档分类
api_docs为N/A：只调整本地Android界面导航，无HTTP契约变化。product_docs为Required，目标为docs/product/runtime-components.md与docs/product/wine-library.md，最终验收后更新；当前产品基线缺失，一致性Pending。
