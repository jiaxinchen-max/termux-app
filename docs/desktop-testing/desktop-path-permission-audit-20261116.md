# 桌面容器路径权限检查（2026-11-16）

## 范围与结论
用户询问 XFCE 的权限问题是否影响其他桌面。检查当前 87357f8a 的安装、保存和恢复入口及会话挂载路径；其他三个桌面当前支持的配置未发现同类错误。本轮不修改业务代码，不重建 APK。

| 桌面 | 容器路径 | 检查结论 |
| --- | --- | --- |
| XFCE | /tmp/tusb-<hash>、/home/termux/.local/share/termux-xfce-<id> | 前轮已修复并在实际容器验证 |
| Plasma | /run/wl；辅助脚本 /tmp | 不向 guest 宿主 PREFIX 占位目录写入 |
| Hyprland | /tmp/hyprland-runtime；辅助脚本 /tmp | 准备配置写入 guest /usr/local、/run、普通用户配置目录，避开隔离目录 |
| Ubuntu GNOME | /tusb-gnome、/run/tusb-gnome、/home/termux | 挂载宿主 PREFIX 供原生合成器使用；准备只确认占位目录存在，不在其内部创建配置 |

GNOME 的 rootfs + USB read-only 旧分支仍包含在 guest PREFIX/tmp 下创建占位目录的写法；当前 Java DesktopRendererPolicy 和 Python GNOME 参数验证均禁止 Ubuntu GNOME 的 USB read-only，独立 GNOME PRoot 已退役，该分支在当前支持组合中不可达。未来若开放该组合，需要先修正此路径。

## 验证证据
- 模拟器只读检查发现当前保留 ubuntu-xfce4-1、ubuntu-kwin-plasma-1 和此前独立底包测试容器；前两者 guest 宿主 PREFIX 占位目录均为 000。桌面安装日志中的同类 Permission denied 仅见已修复的 XFCE 记录。未启动/停止 App 或改动现有容器。
- 隔离复现脚本 audit.py 将 fixture guest 中的宿主 PREFIX 设为 000，运行实际 GNOME 和 Hyprland 准备逻辑；文件操作真实执行，外部发行版/签名验证和程序执行使用替身。两者准备成功且隔离权限保持 000。GNOME USB read-only 按现有契约提前拒绝。
- 执行实际 Plasma 入口并截获其监督器参数，确认所有 guest 绑定目的地都在 /run/wl 或 /tmp；未启动真实 Plasma。4 项路径审计全部通过。
- Plasma 启动监督相关 10 项和普通用户会话 5 项既有测试通过；本轮合计 19 项检查通过。
- 源码对照确认安装、保存和恢复均复用这些准备实现，没有另外向 guest PREFIX 深层目录写入的对应路径。Native 没有 PRoot 隔离占位目录，不受此次问题影响。

## 限制与进度
这次是同类路径错误检查，不是三个桌面的完整安装/GUI 验收；当前模拟器没有 GNOME 和 Hyprland PRoot 桌面，未声称它们设备端安装通过。GNOME 软件包/真实硬件与密码交易等其他问题不由本检查排除。严格 Harness 验证脚本仍缺失，不能声明通过；产品资料验收保持 Pending。

脚本、源码 SHA256、测试日志保存在 artifacts/desktop-permission-audit-20261116/。沿用用户任务结束保存进度授权，仅提交本检查记录，保留七个原有 dirty C/C++ 子模块。
