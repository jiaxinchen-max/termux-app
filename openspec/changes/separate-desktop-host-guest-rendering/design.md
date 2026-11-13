# 设计

## 已核实的启动拓扑
- XFCE：宿主Xlorie，DESKTOP_GL_MODE设置XLORIE_GL_MODE和native/dma-buf接收；容器启动只收到DISPLAY/PULSE_SERVER。PRoot路径XLORIE_GLX=0，不能将宿主Zink称为容器应用GPU加速。
- Plasma：宿主KWin，desktop_start.sh将同一DESKTOP_GL_MODE赋给KWIN_ANDROID_GL_MODE，并交给desktop_container_session.sh设置Qt Vulkan或软件模式。已读取固定r28组件内start-plasma及kwin-android-rendering-env，确认compositor-only不会强制切System。
- GNOME：宿主Xlorie，容器Mutter。llvmpipe配宿主system/native；Zink配宿主zink/dma-buf。当前启动器显式拒绝其他组合。
- Hyprland：宿主System EGL Hyprland，容器只启动应用。既有实验Zink仍必须通过KGSL、导入及真实Wayland窗口检查。

## 分层数据模型与当前格式
仅支持schema3清单，独立存储hostGlMode及guestGlMode。宿主提供System/Zink/llvmpipe；容器提供默认/Zink/llvmpipe，原生为不适用。旧schema1/2与旧glMode不读取、不迁移、不生成迁移备份。复制与设置只保留当前两层字段。
归档仅支持version2/desktopSchema3；旧版归档拒绝。保留当前归档安全检查、包摘要及不支持桌面限制。用户明确“不用考虑兼容旧数据”，已覆盖原迁移规划。

## 支持组合与界面
初始白名单仅覆盖现有启动契约：XFCE宿主System/Zink/llvmpipe配容器默认；Plasma保持Zink/Zink、llvmpipe/llvmpipe；GNOME保持System/llvmpipe、Zink/Zink；Hyprland保持System/默认、System/Zink。原生现有支持矩阵不变。若希望添加Plasma System宿主组合，需先获得实际导入/窗口验证证据并更新白名单，不能假定可用。
创建与设置增加独立宿主、容器行并按桌面标注职责；XFCE容器默认行只读，说明独立容器加速未配置。System EGL不出现在容器选项中。选择冲突模式时保留用户当前值、展示原因、阻止保存，不联动改另一个值。卡片同时摘要两层，原生只显示宿主。Hyprland实验与GNOME兼容提示只出现在适用的选中场景，说明不与其他桌面误关联。
新建XFCE默认宿主System/容器默认。其他新建默认保持既有受支持组合，Hyprland仍System/默认。

## 启动与安装契约
新规格分别写DESKTOP_HOST_GL_MODE与DESKTOP_GUEST_GL_MODE并升级规格版本。当前应用生成新规格；仅接受version3规格，旧版拒绝，不设适配入口。
宿主KWIN_ANDROID_GL_MODE、XLORIE_GL_MODE及接收缓冲策略只由宿主字段决定；容器Qt/Mesa/Vulkan变量与Mesa安装只由容器字段决定。组件包与启动器分发按对应职责路由，不读取旧DESKTOP_GL_MODE。GNOME显示与Shell参数直接来自两层并检查合法配对；Hyprland仍禁止宿主非System及Native实验Zink。
XFCE默认不额外装Mesa或注入Android库；不虚构容器Zink支持。启动前、安装前都校验组合，不兼容时不得停止其他桌面、写成功标记或启动部分链路。运行失败维持显式错误及清理，不回退。

## 受保护路径、验证与回滚
规则来源：/home/user/workspace/AGENTS.md的Human Checkpoints及“Never edit business code before proposal/design/specs/tasks are confirmed.”；本轮需确认后才实施。
涉及domain/DesktopDistro及渲染枚举、data/FileDesktopDistroRepository、archive/DesktopArchiveMetadata与DesktopArchiveEngine、runtime/DesktopRendererPolicy与DesktopLaunchSpec、api/DesktopTasks、创建/设置/卡片和布局/中英文资源、desktop_install.sh/desktop_start.sh/desktop_container_session.sh/desktop_gnome_launch.py及必要测试。影响为配置格式、显示和容器渲染选择；源码回滚使用本轮前快照；不提供配置或归档降级兼容。
验证包括schema3读写、当前归档往返与复制、旧/未知格式拒绝、所有支持/拒绝组合、宿主/容器变量交接、Mesa安装路由、GNOME/XFCE/Hyprland进程/socket与失败清理，随后构建签名ARM64 APK并核对资产。只有真实设备启动、图像、输入证据才能标记对应设备通过；缺少兼容真机保留Pending。
产品文档Required/Pending，docs/product/desktops.md最终接受后维护；HTTP文档N/A。严格Harness脚本和OpenSpec CLI不存在，完整门禁Unavailable。本轮不自动执行Git提交、推送、外部发布、部署或归档。

用户补充并授权：创建页仅展示受支持桌面候选卡片；当前底层发行版固定Debian，不展示发行版选择或Coming soon占位。Ubuntu GNOME的独立发行版问题另行核对，不作为已支持项目显示。

## KWin PRoot 后续修正约定

用户已确认 fix-kwin-proot-renderer-selection。本文原始 Plasma 同名配对限制由该活动变更的独立四组合、默认 System EGL/LLVMpipe 和实际后端验证方案取代；其他桌面白名单与历史验证记录保持。
