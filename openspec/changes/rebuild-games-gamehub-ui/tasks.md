# Games 盖世式本地游戏体验重构任务

- [x] 基于完整录屏补齐竞品证据、proposal、design、specs 和 tasks。
- [x] 记录用户以“照这个重新实现”确认录屏分析与规划范围。
- [x] 重构 LocalGamesActivity 为横竖屏自适应 Shell，组件和设置退出一级导航。
- [x] 重做横屏大封面游戏库、竖屏紧凑网格、导入快捷入口和手柄焦点。
- [x] 实现共享游戏详情/更多 Action Model，以及横屏 Dialog/竖屏 Bottom Sheet。
- [x] 将 PC 引擎设置改为横屏左分类右内容并接入现有 RuntimeProfile。
- [x] 将组件维护降级为工具入口，保留任务、下载、续传、校验和安装行为。
- [x] 将 LaunchTask 映射为三阶段启动 UI 和用户可恢复错误动作。
- [x] 修正返回键/返回手势为控制中心开关，退出仅允许显式触发。
- [x] 补齐操作、性能、设置、键盘分类及真实能力接线。
- [x] 新增独立 `X11SessionView` 控件和 Games 会话适配层，移除 GameSessionActivity 的终端 Host/Controller 耦合。
- [x] 隔离 Games X11 偏好命名空间，保证终端偏好不受游戏控制中心修改影响。
- [x] 更新 GameSession AndroidTest，完成本分片代码评审和测试评审。
- [x] 重新采集本分片 Gradle、Harness strict 和模拟器自动化证据；沿用未改变 UI 的既有控制中心截图。
- [x] 移除 Games X11 底部终端 Extra Keys，并补主游戏库横竖屏手动切换按钮。
- [ ] 基于 GameHub APK/录屏补齐容器菜单配置注册表、默认值和运行时映射，不增加无效开关。
- [ ] 后续补齐 GameHub 高级本地会话能力：虚拟手柄编辑、性能/GPU/FPS 控制、内嵌进程列表、快捷键和容器维护。
- [ ] 用户接受后归档，并将旧 `redesign-games-consumer-ui` 标记为被本 change 取代。
