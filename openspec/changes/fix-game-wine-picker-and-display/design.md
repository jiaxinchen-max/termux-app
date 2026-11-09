# 排查与修复设计

## Wine 选择：已确认的错误路由
用户截图显示管理模式而非游戏版本选择模式。当前 GameRuntimeOptionsView.renderRoot 经 LocalGames.createComponentsIntent(gameId) 进入 LocalGamesActivity，后者只读取 backend/container，再由 openComponentVersions 无条件 createManageIntent。管理模式下已安装行监听为 null，用户看到的两项均为 Installed，既不能选也看不出当前游戏值。

修复游戏编辑入口的组件选择上下文传递：携带当前游戏及编辑中尚未保存的 Wine ID，选择版本后通过 ActivityResult 返回原编辑视图；中间组件页如保留则中继选择结果，不能直接持久化或丢失编辑草稿。选择页用明确的当前 Wine 摘要、已选中勾选及强调样式区分“已安装”和“当前选择”；返回编辑页显示确切所选版本，只有 Save 才写入配置。列表重建/页面重建保留选择上下文；取消和失败不更换。

全局组件管理继续仅管理安装，不生成全局选中版本；从游戏进入则为选择上下文。Backend 由原游戏固定，工具栏继续显示 Native/PRoot，不提供切换。

验收需新增真实错误路由回归，不能仅测试 renderBase 的另一条正确路径。两后端均覆盖：游戏编辑 → 组件入口 → Wine 版本 → 选中标记 → 返回编辑 → Save；另测取消、重建、未保存 Launch 门禁及全局管理隔离。

## 画面
GameSessionActivity 从 frozen LaunchSpec 绑定 X11SessionView；后者设置 custom 分辨率、关闭拉伸和方向自适应。LorieView.updateViewport 按屏幕可用区域等比居中，renderer.c 按视口显示整幅源缓冲；两个启动脚本也给 Wine explorer 传递同一分辨率。legacy X 服务从 1280x1024 启动，再通过视图回调更新大小，独立 Xlorie 接受启动 screen 参数；这只是潜在时序差异，尚未证实为此次根因。不得直接给 legacy 添加它不支持的 -screen 参数。

先通过用户界面现象区分虚拟桌面与游戏窗口，再复现 Surface 初次创建、旋转或全屏尺寸变更、连接和首帧的尺寸传播。若为视口问题，修复尺寸更新与居中，并同步触摸映射；若为游戏窗口问题，仅修改确认影响该窗口的虚拟桌面/窗口处理。不强制拉伸所有游戏，不随意修改全局 X11 默认设置，不替换运行时或资产。

## 风险、回滚和验证
显示代码与输入映射共用坐标，可能影响终端/桌面容器，只有确认具体失效点并确认方案后才修改；原生 renderer 或子模块修改不预先授权。回滚仅撤销本变更，保留其他未提交修改。定向测试覆盖两后端、窄屏与侧栏入口、版本结果、未保存 Launch 门禁及尺寸/坐标场景。保持标准 arm64 APK 名称和非 test-only。真实渲染只能由用户手动验收。

Harness 引导文件、verify.sh、OpenSpec CLI 和产品文档基线缺失，记录不可用，不声称严格门禁通过。Wine 范围已获用户确认并实现；画面范围继续待定位。

## 本次显示复现与 prefix 复用核对

用户修改所用 Wine 后再次提供截图：包含任务栏的整个虚拟桌面位于左上方，右方和下方黑色。该证据排除“仅游戏窗口未最大化”的简单解释，但尚不能区分大 X 根窗口内的小 Wine 桌面与渲染视口尺寸错误。继续核对切换前后 Wine、所配分辨率、连接后尺寸重发和外部缓冲尺寸；未确认根因前不改 renderer，不声称修复。

当前 Native 模板按 Wine ID 独立，运行 prefix 按容器和 Wine ID 独立。首次使用新 Wine 会恢复其模板；同版本已安装模板由 RootfsRuntimeComponentPreparer 直接复用，已经准备好的运行 prefix 由 PrefixSetupForegroundService 直接复用。Native 模板压缩下载约 3.7–4.0 MiB，展开时的大量 DLL 从本地已安装 Wine 复制，不能把展开体积当成网络流量。用户所报“每次网络下载”尚无日志证据确认。公共骨架可复用，但同架构不意味着含运行 DLL 和注册表的可写 prefix 在所有版本间无需处理即可互换；Wine/Proton 继续遵守此前独立管理要求。本次仅记录调查结果，跨版本缓存/资产重组尚未批准实施。

## Wine 9.3 切换 Proton 11 的实施补充（用户已批准并实施）

已确认触发版本，但配置分辨率和故障时 X/渲染实际尺寸仍未知。源码明确存在两个不同步窗口：start_termux_box_game.sh 在最终 resolve_resolution 之前启动 X 服务；X11ServerConnector 已有连接分支和外部渲染连接通知不保证重发当前会话尺寸。它们是可独立验证的同步缺口，尚不能等同于截图根因。

第一步把 Native 的最终分辨率解析移到 X 启动之前，确保 X_SERVER_SCREEN 与 explorer /desktop 使用同一解析结果；PRoot 核对同一约束。第二步在当前会话连接成功/重连时从绑定视图重新计算 viewport 并发送尺寸，避免循环调用连接通知或由后台旧视图覆盖当前会话尺寸。第三步加入内部诊断日志并覆盖连接前/后尺寸到达、Wine 切换重启、横竖屏/Surface 重建以及触摸映射。诊断仅写日志，不让用户配置技术参数。实际 X 根尺寸查询采用 Python 标准库通过 X11 本地 socket 读取协议响应，避免 Bionic/GLIBC 动态链接混用，查询失败明确记录，不额外下载工具、不触发 Wine 初始化。

涉及路径为 Native 启动脚本及 termux-x11 的 Java 视图/连接层和相应测试。共用 X11 Java 代码有终端与桌面容器回归风险，用户已明确批准此范围；本阶段不改 C/C++ renderer、缓冲协议或嵌套子模块。若日志证实需要修改这些范围，另列具体影响后确认。回滚为撤销本阶段增量，不触碰先前 Wine 选择修复和其他工作区改动。验证包括可控连接顺序的本地测试、脚本分辨率传播测试、既有游戏编辑回归和正常 arm64 APK；真实显示与输入由用户通过 App 手动验收。

## 本地验证结果

225 项 JVM/Robolectric 测试、4 项 X11 协议测试，以及 Native legacy、Native Xlorie、PRoot 启动脚本验证通过。新增连接顺序/后台视图/旋转输入回归；同一包默认值夹具使用旧启动脚本得到 1024x768 的 X 启动参数，所选 Wine 桌面为 1280x720；修复后两者均为 1280x720。这证明启动顺序缺陷，不等同于设备截图根因全部确认。原文件名 arm64 非 test-only APK 已构建检查；用户手动验收和产品文档一致性仍 Pending。
