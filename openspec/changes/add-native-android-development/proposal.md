# Proposal

## Why

用户已确认将 AndroidIDE 的手机端 Android 项目开发能力接入 termux-app，执行环境固定使用 Termux native。复用现有终端和包管理，提供从创建项目、编辑到生成 APK 的完整路径。

## What Changes

- 在 Settings 新增 Advanced 子菜单，提供默认关闭且持久化的 IDE 开关；开启时横竖屏标签栏新增 IDE，关闭时隐藏入口并保留数据。
- 新增开发入口、项目列表、创建/打开项目、文件树、代码编辑与保存。
- 新增工具链检测及按需安装：复用已安装 JDK、Android 构建工具、CMake、Ninja、Clang 与 NDK 相关包，缺失项明确展示。
- 新增基于项目 Gradle Wrapper 的构建、日志、取消和 APK 导出；APK 安装交由用户手动完成。
- 第一阶段完成 Java/XML 编辑、基础高亮、Java Android 模板与构建闭环。完整语义补全、工程索引、布局设计器列入后续阶段，不将其宣称为首期交付。
- 同期验证 native C/C++ 编译及 Gradle externalNativeBuild 适配条件；Termux 编译器与 sysroot 不直接视为完整标准 NDK。
- 工具链按需安装，不塞进 APK；保持原始 APK 命名和构建类型。

## Capabilities

### New Capabilities

- `android-development`: 手机端项目管理、编辑、native 工具链准备、构建与产物导出。

### Modified Capabilities

无。当前仓库未提供已有基线能力规范。

## Impact

计划涉及 termux-ui 新增开发页面、项目数据和构建任务，以及 app 中的导航和清单声明；复用现有终端及进程能力，不合入第二套 Termux。无新增或变更 HTTP 合约，api_docs 为 N/A。产品文档规划路径为 docs/product/android-development.md，最终用户验收前不作为已实现的权威说明。

兼容性：保留现有最低 Android 版本，能力不满足时单独限制开发功能。风险包括 AndroidIDE 耦合、JDK/AGP 版本组合、NDK 目录与目标 ABI 兼容、磁盘占用及工程脚本执行。回滚为移除开发入口及新增模块，不删除用户项目、工具链、容器或游戏。源码复用保留 GPL 及第三方许可。

## 已批准的编辑工作区调整

以原版 AndroidIDE 的开发界面为布局参考：紧凑顶部工具栏、可展开侧边文件树、多文件标签编辑区、可收起的底部构建日志。保持现有 Termux native 工具链、构建监督、手动安装与原始 APK 命名。

## 已批准的欢迎页调整

将IDE工程列表首页替换为AndroidIDE风格欢迎页：品牌与开始提示、最近项目及创建/打开/导入项目、工具、终端和日志入口。宽屏双栏、窄屏上下布局，复用现有功能，不新增Git克隆或自动安装。
