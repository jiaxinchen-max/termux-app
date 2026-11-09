# 设计

## 已核对的缺口
GameRuntimeOptionsView已写入逐游戏GAMES_CUSTOM_BOX64_ID，但Native内置目录的catalogSelectionId返回null，CustomComponentSelection不接受内置ID，后端没有添加对应依赖。TermuxBoxRepository将两个归档的glibc/bin/box64复制到同一位置，Probe也只识别共享文件。启动resolve_native_box64只支持默认共享文件与custom-box64-*。

## 版本路径与安装
增加统一的Native Box64版本策略，明确支持glibc_box64_0.2.7、glibc_box64_0.4.4，空选择代表确定的0.2.7。两者分别发布到files/usr/glibc/components/<id>/bin/box64。安装在目标版本旁暂存，文件完整后发布；只替换本版本，不读旧文件列表删除其他版本或共享bin。安装记录的路径也指向版本目录。Probe要求对应文件非空且可执行，其他版本或遗留共享文件不能满足。

## 用户选择与运行
版本选择页展示两套实际可选Box64，保存组件ID；0.2.7虽然是自动基础依赖，允许在专用版本选择页出现，普通内部依赖列表仍隐藏。未指定Native版本的界面显示明确0.2.7。移除不知具体版本的Native“当前运行时Box64”行。后端只添加所选内置版本；选择自定义时不添加内置Box64。启动通过Native glibc loader准确调用版本目录，缺失立即失败，无共享文件回退。自定义安装只要求glibc loader，不再要求共享Box64。

## 边界、风险与回滚
多版本共存不代表允许同时运行多个游戏会话；保留已有会话限制。PRoot的/opt/box64独立目录与翻译器约束保持。只处理Native内置版本，不擅自扩充不存在的组件ID。用户已授权修复涉及的安装和启动高影响路径。修改前保存本次快照；回滚仅恢复本次局部代码差异及旧APK，不引入运行时兼容或回退。

## 验证
真实安装代码在临时文件树依次安装0.2.7/0.4.4，断言内容、metadata、重装隔离及失败不破坏已有版本；Shell合约实际执行两个不同测试程序，按不同游戏的选择输出不同结果；缺失版本即使共享默认存在也必须失败。验证后端依赖、非法/跨后端ID拒绝、选择器与编辑保存。运行Native预构建prefix恢复回归，构建固定原名arm64且testOnly=false APK。设备由用户手动验收；不通过ADB安装。
