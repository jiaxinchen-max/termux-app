# 容器预装Hangover与退出耗时修复

## 问题与目标
用户反馈容器导入游戏的Hangover不应手动安装，退出游戏长时间转圈。当前精简RootFS已内置Hangover，版本页却以usr/bin/wine是否存在决定能否列入已安装；基础镜像未就绪时将默认选择显示为未安装，混淆镜像提供的运行时与独立组件。设备当前wine为普通可执行文件、wineboot为指向wine的相对链接，未发现绝对链接问题。最新启动日志明确退出时session_wait从0循环到25。

## 规划行为
Hangover作为容器镜像预装运行时展示和选择；不提供Hangover单独下载、手动安装或卸载操作。基础镜像尚未准备时显示“随容器预装，保存时准备容器”，不伪称环境已就绪。手动安装普通Wine/Proton的现有能力保持。
用户确认退出时快速终止本次游戏会话，不走25秒自然退出等待或额外15秒wineserver等待。按本次任务标记限定进程，终止完成才返回列表；失败明确呈现。游戏自行正常结束时保留注册表正常落盘。

## 范围与非目标
覆盖版本列表、容器准备的Hangover分类、游戏退出命令与PRoot/Native清理边界及测试。不增加内容校验、wineboot、重建、回退或旧数据兼容。不修改镜像资产，无HTTP契约变化。设备仅串行只读取证，用户手动安装验收。

## 文档与授权
api_docs=N/A：无HTTP契约变化。product_docs=Required，目标docs/product/wine-library.md与docs/product/runtime-components.md；当前基线缺失，一致性Pending。用户已通过“批准执行”确认方案，已按此方案实施；不含提交、推送、安装部署或归档授权。
