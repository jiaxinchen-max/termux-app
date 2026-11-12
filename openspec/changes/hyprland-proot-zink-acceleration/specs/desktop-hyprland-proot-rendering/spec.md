## ADDED Requirements

### Requirement: 容器加速与宿主合成器分别声明
系统 MUST 为PRoot Hyprland提供显式实验Zink客户端模式，并明确宿主仍使用Android/System EGL。旧System模式保持兼容，Native不得选择PRoot专用模式。

#### Scenario: 用户显式选择实验加速
- **WHEN** 用户为PRoot Hyprland选择Zink并重新安装所需组件后启动
- **THEN** 宿主按system启动，容器应用使用受控Zink/Turnip策略，界面说明真机兼容性与实验状态。

#### Scenario: 旧桌面继续使用原模式
- **WHEN** 旧SYSTEM桌面启动或用户从Zink切回SYSTEM
- **THEN** 使用既有默认客户端行为，不自动安装/选择GPU覆盖，不改写用户桌面配置。

### Requirement: 复用完整可核验的容器Mesa组件
系统 MUST 复用固定身份的完整ARM64 glibc Mesa/Turnip组件及严格安装器，核验匹配依赖并保护其不被无意覆盖。

#### Scenario: 组件身份或依赖失败
- **WHEN** 下载内容、SHA256、归档路径或动态依赖核验失败
- **THEN** 安装报告可定位错误，不写入成功标记，允许重试且保留用户数据。

### Requirement: 实际GPU渲染与显示链路核验
系统 MUST 将驱动可用、真实硬件renderer和窗口实际显示分别核验。软件Vulkan设备和仅安装ICD不得视为硬件加速成功。

#### Scenario: 兼容真机成功
- **WHEN** 容器可访问受支持Adreno/KGSL、驱动正常且合成器支持所需缓冲导入
- **THEN** 真实Wayland图形测试窗口可见，renderer明确为硬件，并保存设备与显示链路证据。

#### Scenario: 设备或缓冲导入能力不支持
- **WHEN** 驱动加载、硬件访问或图形缓冲显示不满足要求
- **THEN** 明确说明失败环节，不静默改成llvmpipe/lavapipe，不显示硬件加速成功，并提供切回原模式说明。

### Requirement: 会话与其他桌面兼容
系统 MUST 隔离容器和宿主渲染变量，所有新增进程归属当前Hyprland会话，保持KWin/GNOME已有安装及渲染策略。

#### Scenario: 结束实验会话
- **WHEN** 用户停止桌面或会话失败
- **THEN** 清除当前会话新增进程及临时资源，保留用户配置、容器应用数据和其他桌面。
