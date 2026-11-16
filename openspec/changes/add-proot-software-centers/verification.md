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
此前模拟器独立目标 software-center-check-20261116 底包安装成功，但 ADB run-as 的网络上下文拒绝 DNS 套接字，依赖安装未完成；App 服务代执行尝试未确认成功，不能据此判断 App 网络。已确认测试前 com.termux 主进程不存在。新 APK 不通过 ADB 自动安装，供用户手动下载。实际三个桌面的应用中心界面、正确/错误/取消密码和真实 DEB 安装/卸载仍待设备验证。

更新 APK 后，新建或重新安装目标 PRoot 桌面生效；不在已有容器的普通启动过程中补装依赖。

## 授权和产品资料
用户“批准执行”确认本方案，沿用任务结束推送进度授权。api_docs=N/A（没有 HTTP 合约变化），product_docs=Required，目标 docs/product/desktop-software-center.md，consistency=Pending。尚未最终验收，不固化产品文档、不归档、不发布外部组件。

## 2026-11-16 XFCE 安装权限回归
- 原始安装日志显示 XFCE 和新增应用中心包均配置成功，失败在最终 desktop_initialize → XFCE prepare：尝试在 rootfs 内的宿主 PREFIX 占位目录下 mkdir。该目录权限为 000，由 proot-distro 用于隔离，不应开放。
- 修正配置和 IPC 的 guest 挂载点为 /home/termux/.local/share/termux-xfce-<id> 与 /tmp/tusb-<hash>；host/guest 总线地址各自使用对应路径。Native 路径不变，未增加启动修复。
- 新回归用例在提交 7745fa5f 的实现上可重现 PermissionError，修正后通过。当前轮 18 项服务/启动测试、3 项安装路径测试、6 项壁纸测试通过；其中 USB read-only 测试验证宿主控制路径与 guest GIO 总线地址分离。运行资产内存语法编译、diff 检查、APK 构建通过。
- 真机链路：先 force-stop com.termux 并确认进程不存在；使用现有 ubuntu-xfce4-1，完成修正后的准备，普通 termux 用户读取持久配置，启动私有会话 D-Bus 并成功 ListNames，IPC 写入能从宿主读取；PREFIX 占位目录仍为 000。随后真实 guest root 的 D-Bus、PolicyKit、PackageKit 和密码代理达到 ready，并通过本次拥有的进程组正常清理，ready 标记移除。未伪造 PAM/授权结果，未执行软件交易。
- 尚未通过 App 完整启动桌面或验证应用中心 GUI 的真实密码交易，不声称端到端验收通过。安装完成状态仍需用户覆盖新 APK 后重试 XFCE 安装；不用删除容器或清空数据。
- 本轮 APK 签名 v1/v2 验证通过，arm64-v8a、非 testOnly，74 个运行源资产字节匹配，无 pyc；44,533,485 字节（42.47 MiB）。SHA256：0d906fe63e40500c294cf720f607083a0229a67355a555c53c30c9f6b135e55b。HTTP 完整下载哈希一致。
- 设备及构建日志保存在 /home/user/workspace/artifacts/xfce-permission-fix-20261116/；严格 Harness 门禁仍因脚本缺失不可执行，产品验收 Pending。

## 其他桌面同类问题检查
以 87357f8a 检查其余三个桌面的保存/安装/恢复路径，未发现当前支持组合会触发 XFCE 的 000 PREFIX 写入错误。4 项隔离路径审计、10 项 Plasma 启动监督和 5 项普通用户会话测试通过。Ubuntu GNOME 的 USB read-only 旧分支包含类似写法，但当前 UI 和启动参数契约禁止该组合，未更改业务逻辑；未来开放前必须修正。模拟器目前没有 GNOME/Hyprland PRoot 桌面，本次不声称其完整安装或 GUI 验收通过。详见 docs/desktop-testing/desktop-path-permission-audit-20261116.md。
