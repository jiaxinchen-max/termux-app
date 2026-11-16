# 四类 PRoot 桌面的外接 USB 存储

## 目标与待确认范围

用户提出“这些容器能支持一下外部 USB 设备么”。设备类别尚待答复，本提案先按候选首期范围规划：Ubuntu 26.04 的 XFCE、Plasma、Ubuntu GNOME、Hyprland PRoot 支持外接 USB 存储只读浏览。当前为待确认方案，不代表用户已选择存储，也不代表功能已经实现。

复用主 APK 内置 Android USB 授权与现有 USB bridge。设备须由 Android USB Host 枚举并获得用户授权；首期限现有解析器支持的 BOT/SCSI、FAT/exFAT 存储。只读卷可浏览并将文件复制进容器，不能写回设备。

## 用户可见行为

- 四类 PRoot 的“外接存储”页可启用只读模式，在安装/保存阶段准备组件；桌面运行期间不能更改模式。
- XFCE、Hyprland 通过 Thunar，Ubuntu GNOME 通过 Files 浏览 USB 卷。
- Plasma 增加独立的“USB 文件”入口，使用配套 Thunar 浏览；Dolphin 的 Solid/UDisks2 设备栏集成不作为本次承诺。
- 未授权设备显示待授权；用户在 App 前台发起系统授权。拒绝、拔出、格式不支持均显示真实状态，不伪装成空盘或成功挂载。
- USB 关闭时不启动桥接；启用时随桌面启动/停止会话服务，不等待插入设备或完成用户授权才显示桌面。

## 非目标与兼容边界

摄像头、采集卡、手柄、USB 串口、打印机、通用设备透传、写盘和内核块设备均不在此候选首期。现有桥接中的相机/SDL 模块不等于所有容器应用已支持这些设备。Android 已挂载并占用的设备能否由 USB Host 接管，须在实机验证，不承诺抢占或自动卸载 Android 卷。

Native 保持现有能力；废弃的独立 GNOME PRoot 不恢复。沿用导出前关闭 USB 的条件，归档不保存设备授权、口令或运行 socket。无旧数据迁移承诺。

## 文档、风险与回退

api_docs 为 N/A：复用已有 USB FD 协议、会话内控制协议和本机鉴权 WebDAV，不新增或更改 HTTP 合约。product_docs 为 Required，验收后拟创建 `docs/product/desktop-external-devices.md`；一致性 Pending，当前不固化为产品基线。

主要风险是 PRoot 路径/ABI 混用、会话总线生命周期、Android 占用设备和物理设备覆盖不足。实现须保留 rootfs 内宿主 PREFIX 的 000 隔离权限。回退使用 USB off 并在保存阶段重新生成会话；代码可回退本次实现，安装的配套依赖不自动删除。详细验证及边界见 design 和 tasks。
