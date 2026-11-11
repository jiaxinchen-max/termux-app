# 设计

## 证据

- `package-native-wine-prefix.py` 将脚本包的 `opt/prefix/start/` 映射至 `drive_c/ProgramData/Microsoft/Windows/Start Menu/`，并合入 C 盘资源。
- `prefix-hangover-pdb2`、`prefix-wine10-wow64`、`prefix-proton11-wow64` 的 manifest 均没有额外开始菜单文件。
- `rootfs_prefix_warmup.sh` 对受支持运行时通过 `restore_wine_prefix.py` 恢复预构建清单，不自动生成这些额外入口。
- 从公开资产仓库读取现有脚本包并检查 21 份快捷方式，确认多个工具和安装入口使用 `Z:\usr\glibc\opt`；不能仅复制快捷方式。
- 证据存于 `/home/user/workspace/artifacts/proot-prefix-resources-audit/audit.json` 和 `shortcut-targets.json`。尚未对设备作检查或修改。

## 实施方案

1. 从现有已发布资源中逐项列出可用于 PRoot 的工具、快捷方式及直接依赖。将工具存至统一 C 盘工具目录，重建指向本地 C 盘路径的快捷方式；Wine 自带工具使用其既有 C 盘路径。
2. 对每个保留的入口检查目标存在、PE 架构和配套资源。涉及 Native 外部脚本、安装路径或缺失目标的入口，必须完成 PRoot 适配才能纳入；不得只增加点不开的菜单。
3. 通过可复现的打包流程向三份 PRoot 预构建 prefix 的 delta 和 manifest 加入菜单及必要工具。不覆盖所选 Wine/Hangover 的架构相关系统 DLL，不整包移植 Native 的 C 盘 overlay。
4. 保持三套 prefix 独立及现有网络/缓存恢复流程。更新相应资产描述和需要的 APK 接入，资源不内嵌 APK。版本保持现有语义，不新增根据版本号自动升级、运行时修复或回退。已有 prefix 的验收使用显式移除或重新准备，避免迁移逻辑。
5. 先完成本地包、静态审计和恢复测试，远端资产发布前展示具体产物和影响，不提前发布。

## 验证

- 对新包检查所有快捷方式的目标、参数、工作目录及配套文件，禁止残留 `Z:\usr\glibc` 主机路径。
- 分别恢复 Hangover、Wine 10、Proton 的 prefix，核对菜单和工具文件存在，Wine 架构相关 DLL 保持原有来源。
- 回归预构建恢复，不调用 wineboot、不引入网络校验或内容签名校验、不自动覆盖已有 prefix。
- 统计新增压缩和展开体积，确认只收录实际引用资源；APK 按原文件名、原签名、非 testOnly 方式构建。
- 用户通过 App 界面验收菜单显示与工具启动；不以后台运行替代最终界面验证。

## 风险与回滚

主要风险为快捷方式仍引用 Native 路径、工具的 PE 架构或直接依赖不受所选运行时支持，以及误覆盖系统 DLL。通过逐条入口审计、三运行时独立恢复、原运行时文件对照降低风险。

回滚只撤销本次打包与接入的文件，保留工作区此前 PRoot 裁剪、X11 清理和工具栏返回修改。资产发布前保存旧包、旧描述和索引快照；发布后的回滚需有对应发布授权。未发现可读取的仓库保护路径规则，不推断存在额外保护清单。

严格 Harness 脚本、OpenSpec CLI 和产品文档基线在当前仓库缺失；如继续缺失，记录本地证据，不宣称通过不存在的门禁。
