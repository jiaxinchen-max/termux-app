# 组件版本共用页面验证（2026-11-02）

## 实现

- 组件管理与游戏配置均打开 `RuntimeComponentVersionsActivity`，共用已安装版本、在线安装、本地归档导入及任务控制台。
- 管理模式展示安装状态；选择模式返回明确的组件 ID、类型及 Native/PRoot 环境，仅修改当前游戏配置。
- 支持 Wine、Box64、DXVK，以及 Native 的 VKD3D、Turnip。PRoot 不展示 Native 自定义组件。
- 已安装列表检查实际文件和安装凭据；缺失版本保留错误状态，不替换版本或运行模式。
- 本地归档导入、prefix 初始化和启动按组件 ID 与环境解析路径；Native 内置多版本使用具体版本 ID。

## 构建与自动验证

- 本地 JDK 17，离线构建 arm64-v8a 调试 APK 成功，已安装到 Pixel_7_API_35（`emulator-5554`）。
- `termux-ui` 577、`app` 29、`termux-x11` 108 项测试通过，失败、错误、跳过均为 0。
- `git diff --check` 通过。

## App 界面验证

1. 通过正式导入入口创建独立 notepad 验证配置。
2. 游戏配置 → Common → DirectX translation → DXVK，展示实际已安装的七个 Native 归档版本。
3. 从系统文件选择器导入 DXVK tar.gz，安装任务成功；列表出现 `validation-dxvk`，选择并保存后配置记录对应 custom ID。
4. 从组件管理 → Direct3D → DXVK 打开相同 Activity，展示同一新版本，管理模式不修改游戏配置。
5. 从组件管理 → Box64 → Install from file 导入 Box64 tar.gz，安装任务成功，完成指定 GLIBC loader 下的 ELF 版本探测。
6. 游戏配置 → Advanced → Box64 build 打开相同页面，选择 `validation-box64` 并保存。
7. 安装最终 APK 后，经终端 Preferences → UI Mode 重新进入，DXVK 名称显示正确；Box64 页面仍标记该版本为当前游戏所选。
8. 经游戏菜单移除自建 notepad 记录，清理本轮自建组件与共享目录样本；没有清理既有 Wine prefix、运行环境或用户游戏文件。

本轮界面验证覆盖导入、管理、选择与持久化；未将其作为 DXVK/Turnip 硬件渲染验证。

构建日志、界面截图、导入凭据副本：`/tmp/component-versions-20261102/`。
