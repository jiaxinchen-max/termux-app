# 修复 GitHub Actions 的 API 配套包目录不一致

## 问题和目标

发布执行 `44343711805` 的 apt-android-7 构建在 generateApiCli 失败。脚本已固定 NDK、源码和 SOURCE_DATE_EPOCH，但未固定 dpkg-deb 的压缩格式。Debian 默认 xz，Ubuntu 默认 zstd，四 ABI 的 CLI 和脚本均未变化，仅嵌入的 termux-api.deb 字节变化导致 ZIP 及目录大小/SHA 不一致。x86_64 架构名警告并非本次失败原因。

本次目标为保持现有组件字节和可信目录，在所有受测 Linux 打包环境恢复可复现构建；只给 dpkg-deb 增加 `-Zxz -z6`，附解释注释。修复候选已在仓库外验证，用户随后明确要求推送供其重跑，现应用该已验证的最小补丁。

## 范围、风险和回滚

仅修改 tools/embedded-termux-api/build.py，不改 ABI、架构名、APK 名称/类型/签名、Workflow、组件 URL、目录、已发布资源或运行时代码。保留完整目录校验，不使用 --write-catalog 绕过失败。另一个 apt-android-5 作业因 libxtrans 远端 HTTP 500 在检出阶段失败，是独立外部故障，不将其描述为本修复解决。

风险是其他工具链仍可能引入字节差异，因此验证使用相同固定 NDK 分别调用真实 Debian、Ubuntu 24.04、Ubuntu 26.04 打包器，并确认四 ABI 整个目录字节完全一致。回滚只需撤销压缩参数及注释；无需替换线上资源或清理设备。

## 文档及授权边界

api_docs=N/A：不涉及 HTTP API 契约。product_docs=N/A：纯构建可复现性修复，运行时功能及下载资产字节不变。用户故障报告授权排查和准备修复，用户本轮要求推送供其重跑，确认 Stage 1/2 方案、最小补丁实施及提交推送至既有 master-x11-submodule-tmp 分支。不创建 tag，不启动含发布步骤的 Actions，也不修改已发布资产。
