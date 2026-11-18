## ADDED Requirements

### Requirement: 配套包格式独立于宿主默认值

系统 SHALL 在相同固定源码及 NDK 下明确使用已发布组件对应的 xz 等级 6 格式构建 termux-api.deb，不能依赖宿主发行版的默认压缩选择。

#### Scenario: Ubuntu 构建原有可信组件

- **WHEN** Linux 构建机的 dpkg-deb 默认压缩格式为 zstd
- **THEN** 生成的四 ABI 配套 ZIP、内部文件及目录仍与当前可信目录的大小和 SHA 完全一致，generateApiCli 成功

### Requirement: 保留可信目录校验及交付身份

系统 SHALL 保留完整目录比对，不能为通过构建而重新登记未经发布的字节或跳过校验。

#### Scenario: 可信目录被改变

- **WHEN** 固定目录与实际构建结果不一致
- **THEN** 构建失败且不会自动覆盖目录或更新已发布组件

#### Scenario: 应用交付边界

- **WHEN** 应用本次构建修复
- **THEN** APK 名称、类型及签名规则不变，API 组件版本、URL、名称、大小及摘要不变
