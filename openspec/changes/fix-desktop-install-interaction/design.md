# 实施设计

根因是apt/pkg的-y只确认APT问题，DEBIAN_FRONTEND只影响debconf，dpkg的conffile冲突还需显式处理。desktop_install.sh宿主流程没有设置这两层，截图中升级OpenSSL时出现修改过的配置文件，终端保持打开等待输入。

在desktop_install.sh初始化时导出DEBIAN_FRONTEND=noninteractive；在两处pkg install、两处pkg update和五处apt install中传递-o Dpkg::Options::=--force-confdef及-o Dpkg::Options::=--force-confold。前者采用dpkg默认行为，后者在没有默认时保留现有配置。项目bootstrap的pkg脚本确认up*分支会执行apt full-upgrade，故pkg update也必须覆盖。范围覆盖Native XFCE4/Plasma/GNOME、PROOT宿主安装、容器恢复宿主依赖补齐；不改包选择、版本、安装来源、容器生命周期、原有run/fail/trap状态传播。

不新增全局APT配置文件，不给终端盲目输入yes，不吞掉包管理器失败，不替换维护脚本或增加失败回退。回滚本次desktop_install.sh参数及环境变量即可，不触碰用户文件。

测试在临时目录构建带conffile的小包，通过非特权独立dpkg根目录真实产生旧/新/本地修改配置冲突，执行实际桌面安装脚本到宿主安装边界。PTY保留输入通道但不发送输入，用有界测试超时识别交互阻塞；验证配置保留、自动完成及失败码传播。既有恢复依赖及安装脚本夹具复测。用户自行安装APK和设备UI验收；仓库缺少Harness严格脚本，不宣称门禁通过。

既有恢复依赖回归存在两条摘要/完整性断言，与HEAD中已删除的运行时校验冲突；仅更新测试预期为不触发校验和重装，不恢复产品校验。测试命令桩仅在python模块探测分支加载importlib.metadata，避免每条dpkg查询重复加载无关模块。另将既有命令日志断言允许新增dpkg参数，保留错误码及依赖诊断检查。
