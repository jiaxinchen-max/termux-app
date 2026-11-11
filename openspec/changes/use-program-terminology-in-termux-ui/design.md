# 设计

## 文案清单

解析 termux-ui/src/main/res 的 string／plurals／string-array 实际文本，避免把 local_game 等资源键误当显示文案。默认语言和简体中文共159项含 game／游戏，分布于两种语言的 strings.xml 与 component_versions.xml。重点包括详情标题与启动按钮、导入／编辑、程序目录、运行环境安装、通知、会话退出和音量、说明及组件版本。

采用按大小写对应的 program／programs／Program／Programs／PROGRAM 替换；“游戏”改“程序”。Play your PC games 改 Run your PC programs，Never played／Last played 改 Never run／Last run；中文游玩改运行，保存进度和存档描述改为未保存更改／数据，避免残留娱乐语境。保留 Wine、Box64、DirectInput、DirectPlay 等准确技术名称。

## 非资源文案

核对 Java 字符串并仅调整 X11SessionOverrides 的自然语言异常说明；Properties文件头、空值参数名、Bundle键、机器错误码、持久化字段和URI不是界面文案，不改。核对内嵌组件索引的 summary，将 Proton 的 Windows games 描述改为 Windows programs，保持URL、ID、版本、摘要与依赖数据完整。仅调整三个启动脚本 echo／printf 中的人类可读说明，不修改变量、参数、事件名称或执行条件。

## 边界及回滚

Activity、Service、任务状态、shell流程、X11、输入、存储布局、prefix和组件下载安装规则保持原实现。仅资源、说明和日志变化，不新增用户步骤、权限、兼容迁移或回退。脚本文件虽然有文字增量，启动控制分支和准备流程不变。无需修改Manifest或受保护构建配置。回滚本次独立增量，不回滚既有已授权修改。

## 验证与限制

纯文案变更不新增复述资源的单元测试。保留实施前快照，审查资源键／属性和占位符／转义完全一致，实际显示值中无 game（忽略大小写）或游戏／游玩。扫描 Java 自然语言、XML直接文字与组件显示字段，记录保留内部标识的理由；不要求对程序自定义名称或系统真实路径改写。

执行现有启动脚本回归，核对仅说明输出变化；运行现有 termux-ui／app 测试并编译正常arm64 APK，核对签名、testOnly、版本、打包文案和资产变更清单。截图中的两处必须在打包默认资源中变为 About this program 和 Launch program，中文同步为程序介绍和启动程序。严格Harness工具与产品基线缺失，不将本地检查称为完整门禁；用户手动安装验收。
