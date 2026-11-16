# PRoot 桌面统一底包规范（规划中）

## Requirement: 统一保留桌面底包
系统必须为 Plasma、Ubuntu GNOME、XFCE 和 Hyprland 容器客户端使用 ubuntu:resolute；创建和启动验证应与镜像元数据一致，不得回退到 Debian 或 Noble。

### Scenario: 新建受支持桌面
- 当用户新建任一保留的 PRoot 桌面时
- 则使用 Ubuntu 26.04 LTS 基础镜像并验证 ubuntu/resolute，桌面程序以普通用户 termux 运行，初始密码 termux。

## Requirement: 废弃独立 GNOME 容器
应用不得创建、启动或导入独立 GNOME 类型的 PRoot 桌面，必须在适用入口明确说明该类型已废弃，提示新建 Ubuntu GNOME；不得隐式改名或转换现存数据。

### Scenario: 选择与直接调用
- 当用户查看 PRoot 创建列表时，不显示独立 GNOME，但显示 Ubuntu GNOME。
- 当脚本、存量记录或旧备份请求 PROOT+GNOME 时，明确拒绝并保持原数据可清理。
- 当用户选择 Native GNOME 时，维持原支持行为。

## Requirement: 发行版相关组件真实匹配
系统必须核对 Resolute 的 GNOME、Yaru、扩展、Mesa 和 USB GIO 文件布局及 ABI，下载资产必须有可校验的身份和哈希；不将 Debian 构建伪装成 Ubuntu 构建。

### Scenario: 组件不兼容
- 当缺少实际匹配的包或 ABI 校验失败时，安装明确失败并记录原因；不得通过跳过校验或静默切换底包制造成功状态。

## Requirement: 非破坏性变更
系统不得自动升级/迁移/删除现存容器或备份。旧发行版在不支持的执行入口提示重建，保持可删除清理。完整图形、授权和支持的设备桥能力经新容器实测后方可宣称通过。
