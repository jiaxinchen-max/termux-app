# 设计

build_termux_package 保持当前桥接包名称、版本和依赖，bin 继续 0755，两个 loader.apk 的包内权限恢复为原有 0755，APT/Pacman 安装完成钩子设为 0444，生成的 APK 内嵌 deb 同步更新。shell-loader 内容来自当前源码构建，不通过临时解包手改产物。

当前 Gradle 实际将 loader APK 输出到 intermediates/apk/debug；生成器兼容旧 outputs 路径及当前路径，并在删除旧生成目录前检查输入存在，避免产物缺失时清理后才失败。

TermuxGamesX11BridgeInstaller.ensureInstalled 在已安装快速返回前和安装成功后，移除 termux-x11 与可选 termux-display 加载器的所有写权限；失败抛出 IOException，经既有准备错误路径报告，不能继续宣称桥接可用。缺失 termux-x11 仍走原安装流程，缺失可选 display 加载器不触发网络重装。

只触及桥接包生成器、内嵌 deb、Games 桥接准备及相关回归。无登记保护路径；影响是应用私有加载器文件权限，由应用所有者可恢复，包管理器可替换安装。回滚恢复源文件及旧产物；设备权限回滚 chmod 0755。验证旧权限回归、包内权限及 postinst 执行后的最终权限、现有启动脚本、桥接安装回归、arm64 APK。设备先记录原模式和哈希，修为只读后重复 termux-x11 帮助与应用真实启动，记录 X11 socket、Wine 阶段及屏幕。

Harness 严格门禁脚本缺失，真实执行后按 Unavailable 记录。产品一致性等待验收，不归档、提交、推送或安装新 APK。

## 解包回归修正

上一轮把包内权限设为 0444 的方案已被用户拒绝并由真实 dpkg 证伪：安全同步会重新以 O_WRONLY 打开 .dpkg-new；非 root 环境此时因只读而失败。不得使用 unsafe-io 绕过同步。包数据保留原 0755，只在 postinst/post_install 完成后只读，Games 加载前迁移保持不变。包名、版本、原名 APK 和 Render bridge 入口沿用既有用户要求。

增加当前普通 UID 的真实 dpkg 隔离根目录解包测试，显式 --no-force-unsafe-io，覆盖首次解包和 loader 已只读后的再次解包；随后运行真实 postinst 路径替换版本确认最终权限。所有主机 dpkg 操作仅写测试根目录，设备同类验证使用应用私有临时目录及独立数据库，不安装到现有 prefix，不执行新 APK 部署。
