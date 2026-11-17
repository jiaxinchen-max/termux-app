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

# 2026-11-17 GNOME Software 激活修正

用户在诊断后回复“批准”，确认本轮修复方向。沿用 add-proot-software-centers，先补充中文规划及已批准任务，再修改代码。没有继承历史 Git 发布授权。

## 实现与验证
- 提取原有 core 会话准备函数，在 PRoot 增加 org.gnome.Software 必需服务，缺失时准备失败；Native 范围、系统总线和授权规则保持原有契约。
- 新增测试在原行为上三项中两项失败（缺失不报错、服务无法激活），修复后全部通过。真实 D-Bus 使用 /bin/false 作为明确应用替身，Spawn.ChildExited 证明调用到达 Exec，不代表真实 GUI 成功。
- 软件中心21项、安装路由3项、GNOME会话15项，共39项定向验证通过。
- Ubuntu GNOME完整安装套件18项报告7条失败；在未修改68858272719da9adccbb2ed1d9ff31029163da6a独立导出源码上重跑，失败项一致。旧夹具未复制现有安装器需要的desktop_software_center.py等运行脚本，不改写夹具来掩盖历史失败。
- 变更Python文件内存编译及git diff --check通过。源文件、命令日志及SHA256见activation-verification.json。

## 生效与回退
更新运行资产后，对已有Ubuntu GNOME容器重新保存桌面设置并重启，使保存期重新生成激活清单；普通启动不迁移。回退源码后重新保存即可，不删除容器数据或已安装软件包。

## 限制
仓库缺少Harness bootstrap、verify.sh和OpenSpec CLI，严格门禁未运行。ADB无设备，真实窗口、密码与DEB交易待设备验收。本轮未构建APK或执行Git发布。api_docs=N/A；product_docs=Required、consistency=Pending，未固化产品资料或归档。run目录受仓库/.harness/忽略规则影响，审查与验证摘要同时写入change目录供审阅。

## 2026-11-17 再次批准后的 APK 交付
用户再次回复“批准”后，继续本地构建与打包核验。arm64 构建成功；首次ABI定向构建的testOnly=true被交付检查拒绝，显式关闭该标志后重建通过，没有交付首次产物。最终APK签名v1/v2通过，证书与仓库配置一致，非testOnly，74个运行源资产逐字节一致（包含本轮修复），未包含pyc。

最终文件：termux-app_apt-android-7-debug_arm64-v8a.apk；45,547,355字节；SHA256：79838cb7563d584d9920522ce67c16c10bfbca40c0067eaa24098e5bcea0355e。本地HTTP完整下载200、长度与摘要一致。沙箱代理地址从无浏览器登录态请求返回401，供已登录Digital Worker的用户访问，不能声称已通过外部代理完整下载核验。下载地址和详细证据见activation-apk-verification.json。

安装后对已有Ubuntu GNOME容器重新保存桌面设置并重启；不需删除容器。未替用户安装APK，实机GUI/密码交易仍待验收，严格Harness门禁仍不可用。未暂存、提交、推送或部署。构建器按仓库既有CMake流程向七个C/C++子模块应用了现有补丁，未增加手工业务修改或改变gitlink提交；修改文件摘要已记录。产品文档一致性仍Pending，不归档。

## 2026-11-17 Hyprland m3ww 菜单修复与交付
用户明确要求修复Hyprland。按已批准范围先补充规划，再为m3ww PRoot应用列表追加Software，命令复用termux-hyprland-software；Native桌面文件发现保持不变。PRoot目前没有容器应用通用自动发现，此次不扩展应用索引。

新增回归在修改前6项中1项失败，明确复现Software缺失；修改后主题6项、Classic菜单3项、Hyprland运行/包装器13项、软件中心服务21项、安装路由3项共46项全部通过。确定性主题构建及--check通过，生成的目录/Java/shell资产无源码差异；Python语法和git diff --check通过。直接源码审查见hyprland-review.yaml，命令输出及源码摘要见hyprland-verification.json。

arm64本地APK构建13秒成功；保留原名termux-app_apt-android-7-debug_arm64-v8a.apk，45,653,521字节，SHA256为3f21a3017d6ef5d30ec2f634dc7590b77dc5cea31f3f677e9e9d68f968a28d98。签名v1/v2与仓库证书一致，非testOnly，74个打包运行文件逐字节等于源码，包含desktop_hyprland_theme.py和既有GNOME激活修复。HTTP本地完整下载200且长度/摘要一致，下载入口沿用相同原始文件名；未重新验证带浏览器登录态的外部代理下载。七个子模块既有构建补丁摘要与上一轮完全相同。详见hyprland-apk-verification.json。

用户安装更新后停止并重新启动Hyprland；如沿用旧运行资产，先重新保存桌面设置。不需要删除容器。未连接ADB设备，实际图形窗口、认证和DEB交易待实机验收。Harness严格脚本仍缺失，不宣称严格门禁通过。产品资料一致性Pending；未执行Git发布、安装、部署或归档。
