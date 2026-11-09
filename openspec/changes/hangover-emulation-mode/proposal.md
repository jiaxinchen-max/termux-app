# Hangover 内置翻译模式选择

用户已要求增加 Hangover 内置的 FEX / WowBox64 模式选择。当前编辑器将 Hangover 当作整体运行类型，没有内部模式入口。

在 PRoot Hangover 游戏编辑首页、Wine 选择下方增加“Hangover 翻译模式（32 位）”，默认 WowBox64，可选 FEX，显示当前值。明确 64 位程序保持 ARM64EC FEX。每游戏独立保存，启动显式选择 DLL；缺少所选 DLL 立即失败，不回退。保持既有预构建 prefix 和资产，不增加下载或初始化路径。

无 HTTP 契约变更，api_docs 为 N/A。用户流程改变，product_docs 为 Required；拟在验收后维护 docs/product/runtime-components.md，目前 Pending。
