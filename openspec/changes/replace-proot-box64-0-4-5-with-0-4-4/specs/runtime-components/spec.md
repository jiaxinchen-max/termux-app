# 运行组件变更

## MODIFIED Requirements

### Requirement: PRoot默认使用Box64 0.4.4
系统必须提供独立PRoot0.4.4组件，使用该组件作为未显式指定Box64版本时的默认值，候选中保留0.2.8和0.3.8，移除0.4.5。Native版本不变。

#### Scenario: 默认选择和启动
- 当PRoot普通Wine游戏未显式指定Box64时
- 则依赖解析、安装检查、启动和prefix维护均使用rootfs_box64_0.4.4的程序与配套库，文件缺失立即失败。

#### Scenario: 手动选择旧候选
- 当用户显式选择PRoot0.2.8或0.3.8
- 则仍使用该独立组件的程序和库，不回退默认版本。

#### Scenario: 分发与构建
- 当打包PRoot0.4.4
- 则程序为AArch64，解释器为/lib/ld-linux-aarch64.so.1，支持库为x86_64，不引入Native路径、额外系统设施或运行时签名摘要校验。
