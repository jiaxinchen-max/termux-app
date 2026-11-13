## ADDED Requirements

### Requirement: 宿主与容器渲染职责独立
系统 MUST 独立声明、持久化及展示宿主显示端/合成器与容器桌面/应用渲染。System EGL只用于宿主，容器默认不表示直接使用Android系统EGL。

#### Scenario: 创建或设置容器桌面
- **WHEN** 用户查看或编辑渲染配置
- **THEN** 显示分别有明确职责的宿主与容器配置；按真实桌面拓扑说明容器中是否运行合成器。

#### Scenario: XFCE默认容器环境
- **WHEN** 用户查看XFCE容器渲染
- **THEN** 宿主选项控制Xlorie，容器显示默认及当前未配置独立加速的限制，不把宿主Zink称为容器Zink。

### Requirement: 仅支持当前双层格式
系统 MUST 仅接受schema3配置、version2归档与version3启动规格，不迁移或兼容旧数据。复制、重命名、输出修改和恢复不得丢失当前两层信息。

#### Scenario: 读取不支持格式
- **WHEN** 输入为旧版或未知版本、或混用新旧字段
- **THEN** 明确拒绝，不映射、不重写、不生成迁移备份。

#### Scenario: 保存或恢复当前配置
- **WHEN** 用户保存当前配置或导入受支持备份
- **THEN** 两层模式完整保留，现有归档完整性与安全检查生效。

### Requirement: 按支持矩阵执行两层契约
系统 MUST 在安装及启动前核验双模式组合，宿主字段仅控制宿主，容器字段仅控制容器，不通过一个全局glMode同时控制两层。

#### Scenario: GNOME Zink配对
- **WHEN** 容器Mutter选择Zink
- **THEN** 当前白名单要求宿主Zink/dma-buf，未通过验证的System宿主组合不可冒充支持。

#### Scenario: 不兼容组合或运行失败
- **WHEN** 所选组合不支持，或驱动/缓冲/窗口启动失败
- **THEN** 给出明确错误，禁止联动改值或自动回退，不写就绪成功，按会话归属清理。

#### Scenario: Hyprland客户端加速
- **WHEN** 选择Hyprland容器Zink
- **THEN** 宿主保持System EGL，沿用真实硬件与Wayland窗口诊断；模拟器不支持只记录负向结果。

用户补充并授权：创建页仅展示受支持桌面候选卡片；当前底层发行版固定Debian，不展示发行版选择或Coming soon占位。Ubuntu GNOME的独立发行版问题另行核对，不作为已支持项目显示。

## KWin PRoot 后续修正约定

用户已确认 fix-kwin-proot-renderer-selection。本文原始 Plasma 同名配对限制由该活动变更的独立四组合、默认 System EGL/LLVMpipe 和实际后端验证方案取代；其他桌面白名单与历史验证记录保持。
