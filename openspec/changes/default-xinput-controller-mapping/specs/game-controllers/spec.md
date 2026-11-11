# 程序控制器

## MODIFIED Requirements

### Requirement: 默认使用 XInput 映射并同时启用两个接口

推荐、稳定、兼容预设及新导入的 Native／PRoot 程序必须默认使用 XInput 映射，同时启用 XInput 和 DirectInput 接口；两组控制器 DLL 默认加载顺序必须为 native,builtin。

#### Scenario: 新建程序或选择预设
- 当用户导入 Native／PRoot 程序，或显式选择任一非自定义预设
- 则所得配置使用 XInput 映射且两个接口均开启。

### Requirement: 保留显式映射选择

更新默认值不得修改已有程序的配置。用户仍可手动选择 DirectInput 布局；映射修改保持下次启动生效，不改变接口开关或重新生成 prefix。

#### Scenario: 已有程序使用 DirectInput
- 当已有程序保存了 DirectInput 映射并启动，或用户使用自定义配置
- 则保留该映射及触控布局，不因新默认值而改写。
