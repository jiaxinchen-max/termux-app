# Wine 导入准备与空间统计

## 行为

- Wine 文件导入沿用首页选中的 Native / Proot，文件菜单显示确切模式，不再二次选择。
- Desktop 创建必须显式接收列表模式，缺参数直接报错；Wine Runtime 模式只读。
- 编辑页“保存并准备”和 Runtime 保存复用准备入口。配置先持久化，再准备组件、共享运行时和对应 prefix；准备失败不再吞掉或推迟到启动。
- 保存后显示准备会话，成功有提示，失败保留输出。删除配置的确认文案明确包含未共享容器数据。
- 工作目录显示真实路径；保存时检查目录存在且位于游戏根目录内，再存为相对路径。Runtime General 使用相同规则。
- 空间统计包括实际 prefix、容器数据、配置/封面、缓存、启动/准备日志、游戏目录、共享组件和构建缓存。共享占用单列；只读扫描不跟随符号链接，按设备和 inode 去重，读失败或超限标记部分统计。
- 修复模板循环覆盖 `WINE_PACKAGE`：构建完其他 Wine 模板后，恢复调用者所选 Wine，避免 Hangover 配置被最后一个 Proton 模板污染。

## 验证

设备：Pixel_7_API_35，`emulator-5554`。APK 本地构建并安装。

- 17 项单元测试通过：运行组件 shell 合约 9 项、工作目录 3 项、库存分类 1 项、导入 4 项。新增模板循环回归测试在修复前复现 `runtime_translator_package_mismatch`，修复后覆盖 Hangover / Wine / Proton 三种选择。
- 设备扫描测试 1 项通过：不跟随 `/proc` 符号链接，重复扫描同一文件不重复计数。
- 正式 UI 从 Native / Proot 列表导入同一临时 EXE，各产生独立配置，未出现模式重选。Desktop 两种创建页同样直接继承列表模式。
- Native 编辑页保存成功，未启动 EXE、未进入 Runtime。任务：`prefix-7a580f36-b48b-41cd-8b48-00bd9f063692`。
- 工作目录从真实根目录改为真实 `/subdir`，持久化值为 `subdir`。输入根目录外的 `/storage/emulated/0/Download` 时，保存被阻止并显示字段错误。
- Proot 保存任务 `setup-e4a52181-707a-421a-9fca-4792bd9a06c1` 成功：共享镜像已就绪，独立 prefix 从对应模板初始化，耗时约 24.7 秒。运行时、DLL、翻译器和 CJK 字体标记均对应 `rootfs_hangover_11.16` / `hangover`。
- 再次保存任务 `setup-bb6df455-4cdf-442f-a7f4-896e1b21071c` 成功，约 13.6 秒，未重建镜像、未克隆 prefix、未运行 wineboot。
- Proot 空间页显示独占 prefix 2.66 GB、共享占用 7.72 GB（运行组件 6.89 GB、模板缓存 828 MB）、游戏目录 259 kB，均为逻辑文件大小。
- 从游戏卡片正式启动测试 EXE，显示文件管理器窗口。任务 `launch-6ae01f28-e25d-4441-8470-f949f011b0d0`；工作目录为 `/mnt/games/game/subdir`，启动日志无 `run_prefix_wineboot_update`。通过会话抽屉正式退出，任务终态 `CANCELLED`。

## 清理

用户授权删除不影响后续流程的文件后，已清理失败的 `tmpl-build`、过期导出备份和 28 份历史诊断副本；另核对无运行使用者、无手动组件后，清理配方不匹配的旧共享 Wine 镜像并成功重建。已安装组件与桌面容器保留。两条临时配置已从 App 删除，测试 EXE 和配置残留已清理；设备任务日志保留。最终可用空间约 5.5 GB。

测试截图和日志保存在本机 `/tmp/terminal-area-20261103/`；关键截图为 `proot-save-ready-storage.png` 和 `prepared-proot-launch-visible.png`。
