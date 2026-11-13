# 设计

共享启动器 desktop_gnome_launch.py 有 Shell Ping、扩展信息、桌面服务三组就绪循环。为单次 subprocess 超时提供可重试结果，保留总期限、进程存活和明确错误检查；每次查询受剩余期限约束，避免无界重试。输出可能为 bytes，日志处理须统一为文本。

Activity、Service、存储和输入协议不变；shell 就绪检查才是修改边界。X11 和 Android 后端均保持现有选择。组件下载沿用已发布 Mutter 48.1-25 的锁定，新 APK 必须包含指针组件版本更新与本次脚本修复。

尚无仓库 protected policy 文件，policy id 不可确定；按启动高风险代码记录影响、回滚和授权。通过单次超时后成功、总超时、进程退出、明确扩展错误的针对性测试，再用模拟器原生冷启动、原生/PRoot 往返、鼠标移动点击验证。mock 结果仅作单测，不能替代设备回归。缺失 Harness strict 脚本标记 Unavailable，不冒充通过。人工确认前不改业务代码。
