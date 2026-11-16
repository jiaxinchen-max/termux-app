# 任务

- [x] 检查当前镜像、安装、GNOME 版本和组件 ABI 耦合。
- [x] 编写中文提案、设计、规范、任务和文档分类。
- [x] 用户确认四类 PRoot 统一 Resolute、仅移除 PRoot 独立 GNOME、不迁移旧数据的方案。
- [ ] 核实 Resolute 的实际软件包、GNOME session/扩展/主题布局及所需驱动和 USB 桥兼容性。
- [ ] 统一创建目录、镜像/codename 元数据、安装和启动验证；清除 Debian/Noble 隐式回退。
- [x] 移除 PROOT+GNOME 创建/执行支持并明确处理已废弃记录/备份，保留 Native GNOME 与删除入口。
- [ ] 适配 Ubuntu GNOME 版本、扩展和补丁校验，保持完整性验证。
- [ ] 适配 Resolute Mesa/Zink/Hyprland 客户端与 USB GIO，所需生态包仅在原编译机原链编译，组件独立发布。
- [ ] 增加支持矩阵、底包、已废弃类型、组件与 Native 回归；审查权限、失败隔离和归档行为。
- [ ] force-stop 后实机全新安装四类桌面并验证渲染/终端/应用/支持的 USB 和授权流程。
- [ ] 运行可用检查、构建轻量 arm64 APK，明确 strict gate 缺失及任何实测限制。
- [ ] 按用户既有授权推送代码保存进度，提供 APK。
- [ ] 用户最终验收后更新产品知识并复核一致性；归档需另行授权。
