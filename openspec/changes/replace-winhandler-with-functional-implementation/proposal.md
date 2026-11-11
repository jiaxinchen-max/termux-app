# 实现可维护的 winhandler 功能替代

## 原因

用户明确以实现功能为目标，不要求源码级复刻。当前项目仅保存 winhandler.exe 二进制；Android 调用方、启动脚本及可读的二进制符号已足够确认替代边界。建立我们能维护和重编的 Windows 端实现，接替当前资产。

## 规划范围

按用户要求将文件名和Windows路径改为 wine-bridge.exe，保留启动参数和 Android UDP 线格式，实现在当前应用中使用的启动包装、握手与退出、进程查询／终止、CPU 亲和性、键鼠注入、光标位置反馈和窗口置前。目标是功能可替代，不复制原代码布局或原始越界／近似 PID 追踪实现。手柄 DLL 请求仍直接到 Android，控制器开关、映射及 DLL 不在本次替换范围。

产物继续为当前架构的 x86-64 Windows PE，可供现有 Native／PRoot Wine、Proton 和 Hangover 启动链加载，不能误编为 glibc 或 Android ELF。先构建独立候选并验证，再新增 wine-bridge.exe 资产并删除旧 winhandler.exe；失败明确终止，不自动调用旧 EXE。

## 验证及文档

独立测试覆盖真实线格式和错误长度；Wine 集成验证启动、参数／工作目录、子进程生命周期、进程字段、终止、亲和性、键鼠／窗口行为，及手柄路径不受影响。通过后构建正常 arm64 APK，固定原文件名，无 testOnly。设备验证继续由用户手动执行。

api_docs 为 N/A：本地 UDP 及 Win32 行为，无 HTTP 变更。product_docs 为 Required：游戏启动、退出和运行时管理能力，验收后维护 docs/product/game-runtime.md；一致性为 Pending。调查依据在 /home/user/workspace/artifacts/winhandler-functional-replacement/investigation.md。

用户已确认替代范围并指定名称 wine-bridge，本次实施采用 wine-bridge.exe，未加入旧名称兼容入口。不含 Git 操作、远程发布、旧数据兼容或版本号升级。
