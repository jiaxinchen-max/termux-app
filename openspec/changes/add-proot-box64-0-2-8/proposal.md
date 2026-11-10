# 增加PRoot Box64 0.2.8

用户要求为PRoot也编译Box64 0.2.8。沿用已固定上游源码和现有编译机环境，构建PRoot专用程序、打包已有PRoot x86_64支持库并发布至原组件仓库，加入APK可选组件，供用户按游戏选择。

保持PRoot默认0.4.5、已有0.3.8和Native0.2.8/0.3.8/0.4.4，不做自动升级、兼容迁移或失败回退。正常arm64 debug APK保持原文件名，用户自行安装测试；不操作模拟器、不提交推送代码。

api_docs=N/A：沿用下载协议，无HTTP契约变化。product_docs=Required：增加用户候选，目标docs/product/runtime-components.md；用户验收前一致性保持Pending。
