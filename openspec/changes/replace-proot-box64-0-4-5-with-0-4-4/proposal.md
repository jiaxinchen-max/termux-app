# 替换PRoot默认Box64为0.4.4

用户明确要求编译PRoot0.4.4并替换当前0.4.5。固定上游v0.4.4源码，在原编译机生成PRoot专用二进制，携带已有PRoot x86_64支持库，发布原资产仓库并交付APK。保留PRoot0.2.8/0.3.8和所有Native版本，不做迁移、自动升级、静默回退或重建prefix。

API文档：N/A，无HTTP契约变化。产品文档：Required，docs/product/runtime-components.md；最终用户验收前一致性为Pending。用户手动安装和测试，保持原APK命名，正常debug而非testOnly。
