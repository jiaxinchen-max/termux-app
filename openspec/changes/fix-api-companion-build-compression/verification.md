# 验证记录（2026-11-18）

## 根因与实现

原脚本在官方 Ubuntu 26.04 dpkg-deb 1.23.7ubuntu1 上复现目录不一致；固定 `-Zxz -z6` 后恢复已发布的 xz 格式和等级。正式实现与已验证候选一致，仅修改一处打包命令和说明注释，保留完整目录校验。

## 已执行验证

- 正式仓库执行 `./gradlew :termux-api:generateApiCli --rerun-tasks --console=plain`，使用官方 Ubuntu 26.04 dpkg-deb：退出码 0，BUILD SUCCESSFUL。
- 固定 NDK 25.1.8937393，候选在 Debian 1.22.22、Ubuntu 24.04 dpkg-deb 1.22.6ubuntu6.6、Ubuntu 26.04 dpkg-deb 1.23.7ubuntu1 上分别构建四 ABI，完整目录字节均与可信目录一致。
- 正式四 ABI ZIP：arm64-v8a 162197 字节、armeabi-v7a 79894 字节、x86 80343 字节、x86_64 80897 字节；大小和 SHA-256 全部与可信目录一致。
- 在隔离副本故意篡改可信目录，构建退出码 1，未自动改写目录。
- 正式可信目录及 Workflow 的 SHA-256 与修复前一致；APK 命名、类型、签名、组件 URL 和已发布资产保持原样。

本地完整日志、官方打包器下载校验及四 ABI 摘要记录位于 `/home/user/workspace/artifacts/fix-actions-api-catalog-20261118/`，正式结果为 `formal-gradle-verification.json` 和 `formal-gradle-generate-api-cli.log`。

## 审查和限制

直接源码自审未发现需修复项。Harness 引导文件、OpenSpec CLI 及严格验证脚本缺失，严格门禁调用实测退出码 127，不能宣称通过。以上证据是本地真实构建验证，完整 GitHub Actions 由用户重跑。

失败执行中 apt-android-5 另遇 libxtrans 上游 HTTP 500，属于独立检出故障；本补丁不修改子模块地址或提交。现有 x86_64 架构警告不会使本次 dpkg 打包失败。
