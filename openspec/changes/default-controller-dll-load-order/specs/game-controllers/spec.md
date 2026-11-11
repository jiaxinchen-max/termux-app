# 游戏控制器

## MODIFIED Requirements

### Requirement: 控制器库缺省加载顺序统一

Function Library 的 XInput 与 DirectInput 在未显式配置时必须显示并采用 native,builtin。新配置必须包含两项默认值。普通 Wine／Proton 必须保留用户显式的受支持加载顺序；其他函数库、手柄映射和接口启用开关不受影响。

#### Scenario: 新建游戏并启动
- 当新建游戏且不修改控制器库设置
- 则菜单两项均显示 Native, built-in，保存及启动两组均使用 native,builtin。

#### Scenario: 缺省与显式选择并存
- 当 DirectInput 显式选择 builtin，而 XInput 未设置
- 则菜单与启动分别使用 builtin 和 native,builtin，不相互覆盖。

### Requirement: Hangover 接受正确的混合加载顺序

Hangover 必须默认 native,builtin，并允许 builtin；保持匹配架构与 builtin 标记的 DLL，不强制把已选 native,builtin 改成 builtin。纯 native 等不受支持值必须明确拒绝并终止启动，不静默改写。配套载荷缺失或部署失败仍必须终止，不能使用未应用控制器协议的旧载荷。

#### Scenario: Hangover 使用默认策略
- 当使用 Hangover 且没有手动修改控制器库
- 则菜单和启动均为 native,builtin，使用对应 Hangover 配套 DLL，prefix 无需重建。

#### Scenario: Hangover 收到不受支持策略
- 当 Hangover 被显式配置为纯 native
- 则启动明确失败，不把值改为 builtin 后继续。
