# 游戏控制器

## ADDED Requirements

### Requirement: 按游戏独立启用手柄接口

系统必须在 Advanced 提供独立的启用 XInput 与启用 DirectInput 开关，默认均开启。保存、重新编辑及启动必须保持两个开关的独立值，并用于 Native、PRoot、Hangover 的桥接手柄。Function Library 仅配置库加载顺序。

#### Scenario: 仅启用 XInput
- 当用户保存启用 XInput、关闭 DirectInput 并启动
- 则 XInput 可发现桥接手柄并读取操作，DirectInput 不得枚举或创建该手柄，也不得收到其状态推送。

#### Scenario: 仅启用 DirectInput
- 当用户保存关闭 XInput、启用 DirectInput 并启动
- 则 DirectInput 可发现桥接手柄并读取操作，XInput 必须返回设备未连接且不得收到其状态推送。

#### Scenario: 同时开启或关闭
- 当用户同时开启两项
- 则两个接口均可使用桥接手柄；当同时关闭两项时，两个接口均不提供桥接手柄。

### Requirement: 接口启用与映射布局独立

改变接口开关不得改写 DirectInput 映射、触控布局或 DLL 加载顺序；修改映射或布局亦不得改写接口开关。关闭 DirectInput 手柄不得破坏其键盘鼠标设备处理。

#### Scenario: 修改映射并重新进入游戏
- 当已选择仅 DirectInput 和触控布局 3，用户更改轴键映射并保存启动
- 则保持仅 DirectInput 与布局 3，并按新映射处理操作。

#### Scenario: 切换游戏或重建桥接
- 当前一游戏只开启 XInput、后一游戏只开启 DirectInput
- 则后一游戏只应用自身快照，不得复用旧订阅或旧开关；桥接重启后结论相同。

### Requirement: 配套载荷与协议一致

应用与全部配套 DLL 必须对接口和映射标志采用相同含义，按运行时架构部署，保持 64 字节单手柄协议。不得为缺失载荷使用旧协议或不带开关的隐式回退，不得因此重建 prefix。

#### Scenario: 架构正确部署
- 当 Native/PRoot 普通 Wine 或 ARM64 Hangover 启动
- 则分别使用适配各自源版本和架构的 DLL，保留其所需 builtin 标记及加载方式，不混用 DLL。

#### Scenario: 载荷部署失败
- 当配套 DLL 缺失或复制失败
- 则明确报告失败并终止该次控制器启动，不继续运行未应用用户开关的旧逻辑。
