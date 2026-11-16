# 窗口显示队列重入回归

`test-window-queue.py --source <Mutter源码目录>` 提取并编译该目录中真实的窗口队列函数、可见性更新及同步刷新函数；显示更新为显式测试替身，模拟 `update_window_visibilities` 在清除已消费标记后发出 `WINDOW_VISIBILITY_UPDATED`，回调立即重新入队；同时模拟 shown 回调内嵌套同步刷新并重新入队。随后按窗口销毁路径取消任务，确认没有待处理窗口残留。

原版 Mutter 50.1 与只移除 caller 重复清理的初版均在销毁后仍有 pending 任务的断言处失败；应用本配方 `0002-preserve-reentrant-visibility-queue.patch` 后通过。这验证同步刷新不能擦掉回调的新任务标记，不属于 GPU 或完整 compositor 集成测试。需要宿主 C11 编译器与 GLib 开发头文件，断言必须开启。

实际模拟器诊断日志另见 OpenSpec `evidence/mutter-visibility-crash.txt`：已 unmanage 且 surface 已释放的同一窗口继续参与下一帧可见性更新，最终读取 role 时段错误。完整设备压力验证结果记在 `verification.md`；诊断探针和诊断日志补丁不属于交付配方。

## 异步键盘映射

`python3 test-keymap-async.py` 提取当前 Android 后端中的实际 keymap 异步函数，用 GLib 主循环验证调用返回后才发出信号、描述对象引用持有、取消及错误传播。同步旧实现会在输入源选择重入断言处失败。需要 C11 编译器及 GIO 开发头文件；这是执行顺序与所有权回归，不代替 Android 实际键盘输入测试。
