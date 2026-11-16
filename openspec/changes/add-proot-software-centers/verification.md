# 验证与交付记录

## 已完成
- 三个 PRoot 安装路径显式增加 GNOME Software 和 DEB 插件；安装阶段刷新应用目录。Plasma 保留 Discover，Native 不新增应用中心。
- 安装/保存/恢复生成容器服务配置并验证依赖；普通启动只创建 IPC 和启动运行服务，没有新增 APT 更新、安装、版本/硬件探测或修复。
- 容器 root 运行私有 system bus、PolicyKit、PackageKit 和进程注册代理；桌面、应用中心和 GTK 密码窗口以 termux 运行。沿用真实 PAM/cookie，不增加放行规则或假登录会话。
- XFCE 所有 PRoot 会话统一监督，包括 USB off；静态配置保存到持久目录。Hyprland 应用菜单增加应用中心，guest 客户端使用 guest system bus。
- 77 项相关 Python 测试、9 项 Java 测试通过。`test-software-centers.py` 覆盖持久化、缺插件保存失败、GNOME root 服务命令、XFCE USB off 的真实子进程清理、服务部分启动失败、服务死亡、认证代理异常和无自动激活；安装测试执行真实 shell 入口，包/下载命令使用隔离替身。
- `:app:assembleDebug`、相关 Java 测试、Python 资产内存语法编译、三个 shell 语法与 `git diff --check` 通过。
- APK 签名 v1/v2 验证通过、arm64-v8a、非 testOnly、74 个源运行资产逐字节匹配，没有新增桌面大资源或 pyc。HTTP 完整下载与 SHA256 一致。

## 现有失败和限制
`test-desktop-initialization.py` 共 24 项，8 个失败、4 个错误。在基线 f39f5f8a 独立导出源码上复跑，失败项及结果完全一致：旧 Debian、GNOME/X11、账户和准备契约假设，与本次变更无新增差异。详细日志保存在任务 artifacts。其他上述相关回归均通过。

仓库缺少 Harness bootstrap、verify.sh 与 OpenSpec CLI，因此没有声明严格门禁通过。源码审查见 review-findings.yaml；原有七个 dirty C/C++ 子模块未修改、未暂存。

## 设备与生效方式
模拟器独立目标 software-center-check-20261116 正在准备，未更改原有桌面/备份；已确认测试前 com.termux 主进程不存在。新 APK 不通过 ADB 自动安装，供用户手动下载。实际三个桌面的应用中心界面、正确/错误/取消密码和真实 DEB 安装/卸载仍待设备验证。

更新 APK 后，新建或重新安装目标 PRoot 桌面生效；不在已有容器的普通启动过程中补装依赖。

## 授权和产品资料
用户“批准执行”确认本方案，沿用任务结束推送进度授权。api_docs=N/A（没有 HTTP 合约变化），product_docs=Required，目标 docs/product/desktop-software-center.md，consistency=Pending。尚未最终验收，不固化产品文档、不归档、不发布外部组件。
