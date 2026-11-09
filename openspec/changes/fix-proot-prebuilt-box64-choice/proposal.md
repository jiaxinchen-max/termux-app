# 修复 PRoot 预编译 Box64 候选

## 目标
用户已批准恢复预编译 PRoot Box64 候选。PRoot 普通 Wine/Proton 的 Box64 版本列表显示我们编译的 rootfs_box64_0.4.5：未安装时可下载，安装后可选、可保存；默认选择对应相同版本并正确标记当前项。

## 范围
仅修复版本过滤、默认展示及安装状态来源。保留该资产在通用目录中的 hidden 属性，专用 Box64 选择器例外显示；移除对旧 RootFS 内复制路径的安装判断，采用现有独立组件安装记录。Native 0.2.7/0.4.4 与 PRoot 隔离。Hangover 内置 WowBox64/FEX 不使用此候选。

## 文档、风险和回滚
无 HTTP 契约变更，api_docs=N/A。product_docs=Required，验收后维护 docs/product/game-runtime-components.md，一致性 Pending。无旧数据迁移、prefix 重建或失败回退。修改前快照用于局部回滚。重点验证新安装、安装后选中、保存及现有依赖/绑定链路，不改变资产和运行时脚本。

## 授权
用户已明确批准执行恢复候选列表的修复。
