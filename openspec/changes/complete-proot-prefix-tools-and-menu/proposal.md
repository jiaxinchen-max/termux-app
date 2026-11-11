# 补齐 PRoot 的 Wine 菜单和配套工具

## 问题

用户反馈 PRoot 的 Wine 开始菜单只有原始内容，而 Native 有额外菜单和 C 盘资源。对照八份预构建 manifest 后确认：五份 Native 每份有 21 个额外快捷方式，三份 PRoot 均为 0。Native 打包器还合入 `opt/prefix-expanded/drive_c` 等内容，PRoot 打包与恢复链路没有对应的工具和菜单资源。

控制器 DLL 与 `wine-bridge` 已有两后端各自的释放逻辑，本问题不等于 PRoot 完全没有释放运行资源。原菜单多个条目指向 `Z:\usr\glibc\opt`，不能原样复制给 PRoot；Native 的 shell32 等系统 DLL 也不能直接覆盖 Hangover 的不同架构运行时。

## 目标

- 为 Hangover、PRoot Wine 10 和 PRoot Proton 的预构建 prefix 补齐可用的公共工具菜单及必需资源。
- 保留入口的实际目标必须在 prefix 或所选 Wine 运行时内存在，移植工具采用稳定的 C 盘路径，避免 Native 专用主机路径和无效入口。
- 不以完整 Native prefix、其系统 DLL 或整套应用安装包替换 PRoot。只纳入菜单实际需要的可用工具；Native 专用安装命令不机械移植。
- 资源仍由现有资产仓库包和缓存恢复，不把大资源塞进 APK，不重新执行 wineboot，不增加自动升级和历史数据迁移。

## 文档和交付

HTTP 契约不变，`api_docs` 为 N/A。用户可见的默认工具和菜单发生变化，`product_docs` 为 Required，目标 `docs/product/wine-prefix.md`，一致性 Pending，最终用户验收后再形成规范。

本地交付包括三套可恢复的 prefix 资产、必要的构建接入和原名称 APK；仓库发布、Git 操作与设备操作需按现有授权范围处理，不在规划阶段执行。
