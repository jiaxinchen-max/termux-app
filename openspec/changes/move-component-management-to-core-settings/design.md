# 设计

## 现状
GameRuntimeOptionsView.renderRoot同时展示Components管理入口与Wine版本选择。LocalGamesActivity的设置已存在核心组件下Native/Proot页，但组件页被showPage限制为必须携带游戏ID。openComponentVersions混合游戏选择与全局管理模式。GameLaunchActivity缺失组件提示也引用旧游戏管理入口。

## 导航与状态
在设置Native和Proot各自的环境页增加组件管理入口，复用现有分类页与RuntimeComponentVersionsActivity管理模式。组件管理接收显式后端，独立于游戏及编辑草稿。进入版本管理时使用createManageIntent；全局安装行为不修改任何游戏选择。
从分类页返回对应Native/Proot设置页，从版本页返回分类页；重建页面保留后端和分类。切换后端时重建对应目录，避免旧缓存显示另一个后端内容。继续复用现有状态刷新与安装控制台。
删除编辑页Components行，保留直接Wine选择、当前版本显示、保存和放弃语义，以及每游戏的图形/转换层选择。移除无调用方的游戏管理选择路由；启动缺失组件入口改为对应后端的设置管理。

## 影响与回滚
仅涉及界面导航、资源文案与相关测试，无需改动运行时脚本、资产、持久化格式或进程控制。回滚仅撤销本变更的导航差异，保留此前未提交修改。若实施中发现必须更改高风险运行时路径，先更新影响说明并申请对应批准。

## 验证
覆盖Native/Proot从设置进入管理、无游戏时管理、后端隔离、返回路径、页面重建、编辑页无Components行、Wine选择保存/放弃不变、设置管理不写游戏档案。复测预装Hangover展示。构建arm64普通debug APK，名称固定termux-app_apt-android-7-debug_arm64-v8a.apk且testOnly=false，供用户手动安装测试。
当前OpenSpec CLI、Harness严格校验脚本及仓库引导文件缺失，记录不可用并执行可用本地检查，不能声称严格门禁通过。
