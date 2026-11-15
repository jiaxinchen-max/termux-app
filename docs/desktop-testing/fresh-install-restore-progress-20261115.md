# 全新安装导入历史备份排查进度

## 现场状态
用户反馈全新安装 APK 后导入历史备份失败。2026-11-15 连接 120.24.191.156:8057 返回 failed to authenticate，adb devices 显示 unauthorized。已请求在模拟器允许当前电脑调试或提供失败错误码；本轮尚未读取现场归档任务及安装日志，实际失败原因未确认。未停止、修改或删除真实设备进程、容器及备份。

## 已确认的独立缺陷
host-prerequisites 只核对固定组件、PulseAudio、proot 和 proot-distro，未检查 eglinfo。完整安装 initialize_desktop 会安装 python/mesa-demos，但恢复刻意跳过完整安装，随后 desktop_initialize.py 的 plasma_rendering（宿主 Zink/llvmpipe）或非 Plasma Mesa 宿主探测直接运行 $PREFIX/bin/eglinfo，因此新宿主可以前置检查成功、稍后因缺工具失败。

使用既有宿主依赖夹具复现：host=llvmpipe，eglinfo 不存在，旧检查成功且无 apt 安装。新增缺工具测试在修复前失败。历史备份的实际宿主模式尚不明确，不能将此独立缺陷冒充现场根因，尤其 System EGL 不经此工具。

## 修复范围
沿用已批准的恢复前补齐必要宿主依赖范围，仅对显式宿主 Zink/llvmpipe 检查可执行 eglinfo，缺少时安装 mesa-demos 并复查。System EGL 保持无此依赖；工具已存在时不因本补丁更新索引或装包。不改变备份格式、容器已装包、DNS 或回滚规则。

## 验证
- 9 项本地恢复初始化 Python 回归通过。
- arm64 APK clean 构建通过，43.28 MiB；签名有效、testOnly=false、APK 内安装脚本与源码一致。
- 新增宿主夹具覆盖两种桌面与两种 Mesa 宿主渲染、System EGL 无需工具、不可执行文件与安装未修复工具；全部 47 个宿主依赖夹具通过（原 39 个加 8 个新增），新增测试在补丁前失败、补丁后通过。
- 指定 Harness 规则及 strict verify 脚本仍缺失，未宣称严格门禁通过。

## 待完成
读取 archive-task.properties 和 restore-prerequisites 下本次任务 install.log/result.properties，确认失败位于元数据检查、宿主装包、解包或离线初始化。ADB 恢复授权后，功能验证前强制停止 App，从 UiLauncher 干净启动；任何恢复重试须使用新身份，不覆盖原备份/容器。此项未验收、未归档。

## 现场后续确认：API 入口被覆盖（本次根因）
ADB 已获授权。任务 cae5c744-d667-42ee-a449-dc109a97e452 的 host-only 阶段 result 成功、日志以 Host prerequisites finished 结束，容器已解包；initialize 子目录只有脚本/spec/request，无 result/install.log。归档任务记录 api_component_initialization_failed，清理初期又被 dispatch_pending 阻塞。

设备 dpkg-query 显示 termux-api 0.60.0，上游 libexec/termux-api -> termux-api-broadcast；APK 清单要求该入口为 21016 字节普通 ELF，SHA-256 e21c22eff39058ac3d4b3865dda87d52b36a0cb6386e264441537979b17d59ff。宿主依赖装包把原 APK 入口替换成链接，下一次初始化派发前 EmbeddedApiInstaller 的安全路径检查直接拒绝，故 Python 恢复初始化没有启动。此前 mesa-demos 缺口是独立修复，不能解释此错误码。

修复只允许 libexec/termux-api 的最终链接作为事务原文件存在；检查不读链接目标，发布及回滚均 rename 链接本身，临时备份清理仅 unlink；父目录、其他 API 路径和归档链接限制保持。5 项新复现用例在旧实现全部失败，补丁后 API 安装器 20 项回归（含全部 ABI 已发布组件）及恢复派发 3 项回归全部通过，clean arm64 APK 构建及签名通过，体积 43.28 MiB。

设备已先 force-stop 再开始功能验证；旧 APK 冷启动出现 ANR，正在处理启动阻塞，尚未宣称新 APK 完整历史备份导入验收通过。原备份未改动，没有清除 App 数据或删除用户容器。

### 修复后真实宿主离线验证
先 force-stop App，在既有失败宿主用此次源码编译的小型 dex 调用真实 RuntimeApiComponents（Android Os 平台实现，已安装 APK 提供归档依赖）。使用现有缓存并禁止 Downloader 联网，结果 BEFORE_MATCH=false、AFTER_MATCH=true、exit 0；确认该入口冲突可由补丁安全修复，不需要卸载或重建容器。之后运行原失败任务尚未派发的 restore-initialize 包装脚本，结果另行记录。完整 UI 新导入尚未重新执行。

原任务 restore-initialize 实际运行成功：宿主 Android System EGL、容器 llvmpipe LLVM 21.1.8 验证通过，result.properties 为 state=succeeded / exitCode=0，日志明确 no package installation or download。验证只覆盖真实 API 修复与原失败恢复的后续初始化，未把手工运行子任务等同于 UI 整体导入成功；未发布桌面记录或伪造归档任务成功状态。原归档仍保留，待新 APK UI 完整导入验收。

## 定制包管理补全（用户授权“完善”）
恢复宿主预检与完整桌面安装统一调用 register_embedded_host_packages；完整安装将该步骤移到初始化工具 apt 之后。检查本地 DEB 包名、版本，dpkg 安装后核对 configured 状态和版本，再 apt-mark hold，已有 UPower（包括 hold 状态）也恢复定制包。明确由 APK 更新流程替换定制 DEB；普通软件源升级不再替换。手动 unhold 或显式允许改变 hold 仍可覆盖，后续安装/恢复重新登记；原 API 最终链接事务修复继续作为防线。

实际设备先 force-stop App，再运行从生产脚本提取的真实辅助函数：termux-api 从 0.60.0 登记为 0.60.0-1+embedded.1，upower 从 1.91.5 登记为 1.91.5-1；两者 showhold 可见，dpkg --audit 无输出，API 入口为 21016 字节普通文件，exit 0。未安装新 APK，未完整执行 UI 导入，未修改原备份/容器数据。

copy 排查：桌面安装脚本复制数个容器 apt/statx/Discover/USB 辅助文件到宿主 $PREFIX/tmp（容器通过 --shared-tmp 使用）；GNOME 启动脚本写入 $PREFIX/libexec/termux-gnome；CA 证书和软件源配置也有小范围复制。PRoot install.py 明确使用 archive rootfs extraction。归档引擎 copyEntry 将解压流写入新 rootfs；API 安装先解压到事务目录，校验后 rename 发布。未发现桌面安装时复制完整容器的步骤。现存失败任务日志未含用户所指 copy 行，因此不能把这些代码位置当成该行日志的逐项确认。

验证边界：恢复本地初始化 9 项通过；旧 GNOME 大套件 42 项有 33 个失败，都是旧渲染参数不满足当前入口要求。用 HEAD 原安装脚本复跑同套件，42 项同样有 33 个失败，非本轮新增失败；本轮只使用有效 v3 模式的专用测试作为验收依据。严格 Harness 脚本与 OpenSpec CLI 均缺失，未宣称对应门禁通过。

宿主恢复预检 54 个夹具全部通过，包含 7 个新增定制包登记/失败路径与原 47 个预检回归。

Ubuntu/Plasma 专用完整安装 9 项通过，明确验证最后 apt < 定制包登记 < 初始化派发、API hold；合计本轮 72 项聚焦检查通过（54+9+9）。本轮未构建新 APK。

额外重复实机登记尝试因 ADB shell 60 秒未返回而超时，未获得可判定结果，不计入通过项；不据此推断包管理失败或宣称实机重试通过。首次实机登记/hold/audit 的完整 exit 0 日志已保存。

## 导入后 KWin 主题崩溃
用户新导入任务 002202d7-3cdb-4d25-9ef7-1e33c87292c4 已 SUCCEEDED、published=true，宿主与容器渲染初始化成功。启动失败来自宿主 kwin_wayland SIGSEGV；Android crash buffer 两次栈一致。用设备 llvm-symbolizer 还原关键帧为 QGuiApplicationPrivate::handleThemeChanged、QApplicationPrivate::handleThemeChanged、QKdeTheme::createKdeTheme、createPlatformIntegration、KWin::Application 构造。

先强制停止 App，使用独立诊断 runtime 对实际 start-plasma 做 A/B：原环境每次在 kwin_wayland 主题初始化处段错误，QT_QPA_PLATFORMTHEME=generic 后进入 Android backend、发布 Wayland socket 并尝试连接显示服务。由于该隔离试验没有启动 App 显示服务，连接被拒绝并由 timeout 结束；此项仅证明绕过原崩溃，不代表桌面渲染成功。

实现只对 desktop_plasma_session.py 的 compositor Popen 构造 env 副本并设 generic；传给容器和父环境的主题保持不变，Native 启动流程不变。命令行会话测试在旧代码明确失败（期望 generic，实际 kde），修复后含两次启动/停止、容器主题隔离、父环境不变及既有就绪/清理的 10 项全部通过。初始化大套件 24 项执行，其中旧夹具 4 个子场景缺宿主 CA，另 1 项缺 Hyprland 主题 tgz，不能宣称全绿；未修改对应初始化实现。

clean arm64 APK 构建成功，签名、非 testOnly、仅 arm64 ABI、无 pycache、打包源码一致及 HTTP 下载哈希通过，43.28 MiB；位置 artifacts/apk-download/kwin-theme-fix/termux-app_apt-android-7-debug_arm64-v8a.apk。完整显示和 UI 导入后冷启动仍待最终验证；未删除备份或重装容器，未归档。

额外 UI 验证尝试：系统 dex speed 编译成功，应用冷启动到 TermuxActivity 可见且进程存活，但截图、UI 自动化以及诊断 Activity 派发多次超时。读取会话日志仍为原失败状态，未观察到新会话 ready。没有把诊断脚本发送成功或超时当作 UI 启动成功；不再重复大文件 ADB 传输。
