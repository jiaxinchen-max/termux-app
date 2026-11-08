# Proposal

## Why

当前精简环境已通过真实App服务发布及两份前缀运行验证，但新安装仍先下载Debian、运行apt安装Hangover再裁剪，设备完整重建实测约32分钟。用户要求直接接入预装精简RootFS，避免在设备重复构建及持有未裁剪中间树。

## What Changes

- 新安装和显式重建默认下载已预装Hangover pdb2、locale和必要驱动的精简镜像，在私有暂存目录解压、校验后发布为唯一共享RootFS。
- 安装路径不再下载Debian基础镜像或Hangover deb源包，不执行客体apt、locale-gen或裁剪，不静默回退旧构建流程。Termux宿主的PRoot/PulseAudio等必要依赖仍需准备。
- 镜像独立不可变身份，包含版本、来源、裁剪策略、前缀运行来源和校验清单；保留现有Hangover运行选择值，区分镜像载荷身份和Wine源组件身份。
- 保留APK小前缀模板和每游戏独立可写前缀，无完整wineboot初始化；可选Wine/Box64/DXVK仍独立管理，Native和桌面不受影响。
- 生产包重新从已验证的发布根导出并审核，不直接将缺少生产凭据的体积测量候选当正式包。约308MiB仅作现有压缩参考，最终按正式制品实测。
- 交付原名普通安装APK；用户手动安装。Git、Release发布和设备部署分别按明确授权操作。

## Capabilities

### New Capabilities

- `prebuilt-proot-rootfs-delivery`：精简游戏基础镜像的版本绑定、下载、单次安全解压、校验、发布与失败恢复。正式baseline目录为空，声明本次行为并记录缺失，不声称已完成baseline同步。

### Modified Capabilities

无可读取的正式baseline；依赖minimize-installed-proot-rootfs已验收实现及其证据，不归档或改写该变更。

## Impact

涉及组件目录和下载准备、RootfsSetupRecipe/AssetInstaller/ForegroundService/spec codec、RootFS setup/启动/前缀消费者、镜像打包校验工具和测试。新配方需显式重建，保留旧任务读取边界，不实施旧用户数据迁移；失败保持已发布根和游戏C盘。

无HTTP契约变更，api_docs=N/A。用户可见安装/修复及兼容边界改变，product_docs=Required，目标docs/product/runtime-components.md和wine-library.md，最终用户接受后完成一致性审查。权威Harness/产品baseline缺失，strict-run仍Unavailable、产品一致性Pending。

风险为压缩包不安全成员、路径迁移、身份错绑、磁盘峰值及取消中断；通过私有暂存、全链校验、故障测试和真实App两份前缀回归控制。回滚为保留旧根至新根通过发布后校验，失败还原；外部版本不可覆盖。
