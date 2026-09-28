# Games runtime 组件激活恢复任务

- [x] 审计无归属的后续改动与旧 Harness 证据时间线
- [x] 补齐并确认 proposal、design、specs 和 tasks
- [x] 拆分 GLIBC Host runtime 与 RootFS private install 的 preflight 口径
- [x] 将激活输入从 archive 改为 prepared directory
- [x] 增加旧组件显式激活入口与准确状态文案
- [x] 恢复 app 索引 schema v1/v2 兼容
- [x] 增加回归单测并完成 ARM64 AVD 实际激活验证
- [x] 消除干净 bootstrap 的隐式 patchelf 与嵌套 7z 依赖
- [x] 为 GLIBC/RootFS 增加独立 X11 bridge Host capability 与自动安装
- [x] 完成空数据 9 组件、前缀、X11 bridge 真实 AVD E2E
- [x] 完成代码评审、测试评审和最终 Harness 门禁
- [ ] 用户接受后归档
