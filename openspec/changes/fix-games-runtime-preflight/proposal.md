# Proposal: 修正 Games 启动预检与 TermuxBox 运行时状态不一致

## Why

Games 启动预检只读取模块私有组件回执，而实际启动脚本使用 `$PREFIX/glibc` 下的 TermuxBox 运行时。已有 TermuxBox 组件因此被误判为缺失，启动任务以 `preflight_component_missing` 失败。

## What changes

- 由主应用 Host adapter 暴露只读的真实运行组件能力探测。
- 启动页与运行配置页统一通过 `AndroidLaunchPreflight` 校验。
- preflight 失败持久化具体 subject，启动页显示可读原因并支持重试。
- 保留 SAF、存储、索引和显式版本 pin 校验。

## Non-goals

- 不清理、迁移或覆盖用户已有 TermuxBox 和游戏数据。
- 不在本变更中重做组件下载与激活架构。
- 不降低显式指定但索引中不存在的组件版本约束。
