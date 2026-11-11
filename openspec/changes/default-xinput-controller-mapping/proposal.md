# 统一默认控制器映射

## 原因

用户确认以 XInput 布局作为默认映射，旧程序需要传统布局时再手动选择 DirectInput。推荐、稳定及 PRoot 导入已使用 XInput，兼容预设仍使用 DirectInput，默认行为存在例外。

## 变更

所有非自定义运行预设统一使用 XInput 映射；默认同时启用 XInput 和 DirectInput 接口，两组 DLL 默认加载顺序继续为 native,builtin。保留用户对已有程序保存的映射及接口选择，保留运行时手动切换入口和下次启动生效规则。

## 范围

仅修改兼容预设的默认映射，并补全现有默认配置测试断言；不改 DLL、协议、运行时或 prefix。无 HTTP 契约变更，api_docs 为 N/A。产品文档为 Required，验收后更新能力文档 docs/product/game-controllers.md；当前仓库尚无该产品基线，先保持一致性审查 Pending。
