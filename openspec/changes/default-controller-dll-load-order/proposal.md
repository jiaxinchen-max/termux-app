# 默认使用 native,builtin 加载手柄库

## 需求与原因

用户要求 Function Library 中 XInput／DirectInput 默认设为 native,builtin。目前普通 Wine 的菜单未配置时显示 Built-in，启动时不输出控制器覆盖；Hangover 的菜单固定 Built-in，脚本强制使用 builtin。默认显示与默认运行行为必须同步，不能只改文案。

## 变更范围

新建配置及未显式指定两项控制器库加载策略时，默认使用 native,builtin。菜单显示、保存、新启动快照与两个后端的脚本输出保持一致；用户明确选择的受支持策略保留。其他库默认值不变，映射及接口启用开关不受影响。

Hangover 允许 builtin 与 native,builtin，默认后者，不提供纯 native。取消其强制覆写 builtin 的逻辑；收到不受支持的显式策略时明确失败，不静默替换。保持对应架构 DLL 和 builtin 标记，所有配套 DLL 仍须成功部署，不重编 winhandler.exe 或 DLL，不重建 prefix。

本变更与“映射入口迁移到运行时控制器页”分别规划，后者仍等待确认；本次新要求不视为对后者全部生效语义的确认。

## 文档与确认

无 HTTP 契约变更，api_docs 为 N/A。product_docs 为 Required，最终验收后维护 docs/product/game-controllers.md，当前一致性状态 Pending。用户已于 2026-11-11 批准实施本方案，开始修改业务代码；不含 Git、设备操作或外部发布授权。
