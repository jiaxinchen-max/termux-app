# 修复游戏编辑 Wine 选择与画面位置

## 问题与目标
用户报告编辑游戏时选择 Wine 点击没反应，并在截图明确指出版本列表不能辨认最终选中的 Wine。截图为 Native 的 Wine versions 页面，Installed 下 wine_9.3-vanilla 和 proton_11.0 均仅显示 Installed，无选中标记。另有游戏画面只在左上角的问题，后续截图已确认包含任务栏的整个 Wine 桌面缩在左上角，用户确认触发为 Wine 9.3 切换到 Proton 11。

## 已确认根因
GameRuntimeOptionsView 的组件入口通过 LocalGames.createComponentsIntent 携带 gameId 进入 LocalGamesActivity；LocalGamesActivity 仅用它读取 backend/container，却在 openComponentVersions 无条件 createManageIntent。RuntimeComponentVersionsActivity 在 MANAGE 模式将已安装行点击监听设为 null，仅显示 Installed，不携带或显示当前游戏选择。用户所走路径因此能安装却不能选择，也无法辨认当前 Wine；独立的 common/Wine select 路径测试通过不能覆盖此路径。

## 修复目标与范围
从游戏编辑进入的组件页保留游戏选择上下文及未保存的当前 Wine ID；Wine 版本页允许选择并明确显示当前选择，选中项有勾选和强调，返回编辑页显示确切版本。保存才持久化，取消不改变，失败明确报错；已安装不等于已选中。全局组件管理不绑定任何游戏，不伪造全局当前 Wine。Native/PRoot 为运行环境归属，固定展示，不允许在该页切换。

画面问题在同一变更内独立验证。用户已批准共用 X11 Java 尺寸同步补充方案，已实施并完成本地测试；未修改 C/C++ renderer 或嵌套子模块。截图故障仍待用户界面验收。

## 约束与文档
不引入向前兼容、版本回退、wineboot 或 prefix 重建；保留编辑删除强杀和仅 Launch 就绪门禁。不连接设备，用户手动验证。已有安装完成后版本选择规划仍独立，非本次必要范围不混入。

api_docs=N/A：无 HTTP 契约变化。product_docs=Required：docs/product/wine-library.md、docs/product/runtime-components.md；一致性 Pending，用户接受后再更新。Wine 方案已由用户回复“修复”确认，并已完成本地实现及测试，等待手动验收；画面问题继续独立排查；不执行 Git 操作。

## 显示修复补充范围（用户已批准并实施）

用户确认触发为 Wine 9.3 切换到 Proton 11。保留两者独立 prefix 及模板复用规则，本次不修改资产布局。分阶段修复尺寸传播：先在 Native 启动 X 服务之前归一化所选分辨率；在当前会话连接和重连时重新应用其视口并发送逻辑尺寸，避免只依赖连接前 Surface 回调。为启动日志补充所选 Wine、目标分辨率及实际 X 根窗口尺寸，为应用日志补充视口和连接时尺寸，核对 Wine 虚拟桌面、X 画布和渲染尺寸。若这些修改不足以解释截图，不将其标记为已修复，保留人工验收待办并以尺寸证据确定下一处修复。
