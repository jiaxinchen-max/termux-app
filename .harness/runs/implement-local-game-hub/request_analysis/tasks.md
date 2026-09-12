# Task Coverage Review

## 覆盖关系

- 独立 module、公开 API、主应用桥接和 Activity：tasks 1。
- 网络组件下载与断点续传：tasks 2。
- 本地导入和 EXE 识别：tasks 3。
- 游戏级配置：tasks 4。
- 隐藏终端和持久启动任务：tasks 5。
- X11、手柄、触控和 INGAME：tasks 6。
- 快照、安全卸载和恢复：tasks 7。
- 自动化、真机与回归门禁：tasks 8。

## 执行建议

总 change 保持为产品级设计基线。实现阶段按“骨架、组件、导入、启动、会话、资产安全”创建子 change，每个子 change 独立评审和验证。

首个子 change 应只创建 `:local-games` 模块骨架、公开 API、Host fake、Activity 空壳和模块边界测试，不接入真实 Wine/X11 启动。
