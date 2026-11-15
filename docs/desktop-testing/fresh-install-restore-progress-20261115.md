# 全新安装导入历史备份排查进度

## 现场状态
用户反馈全新安装 APK 后导入历史备份失败。2026-11-15 连接 120.24.191.156:8057 返回 failed to authenticate，adb devices 显示 unauthorized。已请求在模拟器允许当前电脑调试或提供失败错误码；本轮尚未读取现场归档任务及安装日志，实际失败原因未确认。未停止、修改或删除真实设备进程、容器及备份。

## 已确认的独立缺陷
host-prerequisites 只核对固定组件、PulseAudio、proot 和 proot-distro，未检查 eglinfo。完整安装 initialize_desktop 会安装 python/mesa-demos，但恢复刻意跳过完整安装，随后 desktop_initialize.py 的 plasma_rendering（宿主 Zink/llvmpipe）或非 Plasma Mesa 宿主探测直接运行 $PREFIX/bin/eglinfo，因此新宿主可以前置检查成功、稍后因缺工具失败。

使用既有宿主依赖夹具复现：host=llvmpipe，eglinfo 不存在，旧检查成功且无 apt 安装。新增缺工具测试在修复前失败。历史备份的实际宿主模式尚不明确，不能将此独立缺陷冒充现场根因，尤其 System EGL 不经此工具。

## 修复范围
沿用已批准的恢复前补齐必要宿主依赖范围，仅对显式宿主 Zink/llvmpipe 检查可执行 eglinfo，缺少时安装 mesa-demos 并复查。System EGL 保持无此依赖；工具已存在时不因本补丁更新索引或装包。不改变备份格式、容器已装包、DNS 或回滚规则。

## 验证
- 9 项本地恢复初始化 Python 回归通过。
- arm64 APK clean 构建通过，43.28 MiB；签名有效、testOnly=false、APK 内安装脚本与源码一致。
- 新增宿主夹具覆盖两种桌面与两种 Mesa 宿主渲染、System EGL 无需工具、不可执行文件与安装未修复工具；全部 47 个宿主依赖夹具通过（原 39 个加 8 个新增），新增测试在补丁前失败、补丁后通过。
- 指定 Harness 规则及 strict verify 脚本仍缺失，未宣称严格门禁通过。

## 待完成
读取 archive-task.properties 和 restore-prerequisites 下本次任务 install.log/result.properties，确认失败位于元数据检查、宿主装包、解包或离线初始化。ADB 恢复授权后，功能验证前强制停止 App，从 UiLauncher 干净启动；任何恢复重试须使用新身份，不覆盖原备份/容器。此项未验收、未归档。
