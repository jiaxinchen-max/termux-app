# 游戏控制器

## MODIFIED Requirements

### Requirement: 输入库加载设置独立生效

系统必须分别应用用户对 DirectInput 与 XInput 的有效 DLL 加载策略，不得仅因为映射模式不同而忽略另一组设置。不同架构的运行时必须使用与其加载方式匹配的 DLL，不得混用。

#### Scenario: 同时设置两组库
- 当用户将两组库分别设置为 native,builtin 和 builtin
- 则普通 Wine/Proton 启动覆盖中同时包含两组对应值，与选择的映射模式无关。

#### Scenario: 只设置一组库
- 当只有一组库显式配置
- 则应用该组配置，不为未配置的另一组增加强制加载策略。

### Requirement: 用户可以独立选择手柄映射

编辑页 Advanced 必须显示当前手柄映射并允许选择 XInput 或 DirectInput。选择必须保存在现有输入配置中，保留触控布局编号，并在保存后的启动中传递至 Android 桥接及对应启动脚本。

#### Scenario: 保留触控布局
- 当用户将 xinput:3 改为 DirectInput 并保存
- 则配置为 dinput:3，下次启动使用模式 0 和触控布局 3，无须重建 prefix。

#### Scenario: 不改变 DLL 策略
- 当用户只修改手柄映射
- 则 DirectInput/XInput 的 DLL 策略保持用户原选择，不能随映射互相覆盖。
