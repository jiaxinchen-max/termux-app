# 将Native Box64 0.2.7替换为0.2.8

用户明确要求编译0.2.8替换0.2.7。本次在已有编译机用上游v0.2.8源码构建Native glibc程序，带齐现有目标的x86_64支持库，发布到原组件仓库，再替换App目录和默认运行路径。保留Native 0.3.8/0.4.4及PRoot候选，不扩展新的PRoot版本。

使用新组件ID glibc_box64_0.2.8和真实版本0.2.8，移除当前运行链路的0.2.7，不进行旧数据兼容、自动升级或失败回退；不删除历史远端Release。用户手动安装原文件名的正常arm64 debug APK并测试，不操作模拟器，不提交推送代码。

api_docs=N/A：无HTTP契约变化。product_docs=Required：默认翻译器版本改变，目标docs/product/runtime-components.md，用户验收前consistency为Pending。
