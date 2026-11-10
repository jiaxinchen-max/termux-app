# Native Box64运行库

## ADDED Requirements
### Requirement: 配套库完整发布
系统 MUST 为Native Box64 0.4.4提供已有Native基础包中的x86-64配套运行库。
#### Scenario: 安装0.4.4
- **WHEN** 准备该版本组件
- **THEN** 同一组件包含原AArch64 Box64和7个正确架构的x86-64运行库，包括libgcc_s.so.1，无需另一Box64版本存在

### Requirement: 使用所选版本库路径
系统 MUST 从所选Box64版本目录搜索其配套库，保持版本隔离。
#### Scenario: 启动配置
- **WHEN** Native游戏选择内置或自定义Box64
- **THEN** 启动搜索路径包含所选组件lib/x86_64-linux-gnu，不使用另一版本库补洞，保留Wine既有路径
#### Scenario: 版本缺失
- **WHEN** 所选Box64二进制缺失
- **THEN** 保持原有立即失败行为，不自动回退版本或重建prefix
