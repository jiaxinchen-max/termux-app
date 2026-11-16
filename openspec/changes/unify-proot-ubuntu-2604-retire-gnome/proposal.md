# 统一 PRoot Ubuntu 26.04 LTS 底包并废弃独立 GNOME 容器

## 用户需求
所有保留的 PRoot 桌面使用 Ubuntu 26.04 LTS（Resolute）基础镜像，废弃独立 GNOME 容器类型。延续此前不考虑旧数据兼容的约束，不自动转换、升级或删除已有容器。

## 范围
保留 KDE Plasma/Kubuntu、Ubuntu GNOME、XFCE、Hyprland 容器客户端，基础镜像全部为 ubuntu:resolute。移除独立 GNOME 的 PRoot 创建与执行支持，旧独立 GNOME 记录/备份明确提示不再支持并要求重建；不能默默映射成 Ubuntu GNOME。Native GNOME 的显示能力保留；根据用户追加授权，将配套 Mutter/GNOME Shell 升级到 50 系列并移植现有 Android 后端，其他 Native 桌面保持现状。保留普通用户 termux、初始密码 termux 及现有认证故障隔离。

## 需要同步修改
桌面创建目录/发行版与 codename 元数据、环境验证、安装和启动入口、Ubuntu GNOME 扩展/Yaru/补丁校验、Hyprland/Mesa、USB GIO 组件、归档校验和测试。禁止把 Debian 构建直接标成 Ubuntu，禁止删除现有容器或在运行时自动降级到 Debian/Noble。

## 文档分类
HTTP API 合同没有变化，api_docs 为 N/A。用户可见的桌面类型、系统版本及兼容规则变化，product_docs 为 Required，目标 docs/product/desktop-management.md，验收前 consistency 为 Pending。

## 当前状态
用户已通过“实施”批准原方案。已实施独立 GNOME PRoot 下线；发现 GNOME 50 移除现有 X11 后端，用户追加确认“升级现有组件进行支持，pipewire不用考虑”；显示改为移植 Android 原生后端。底包映射和安装代码已统一 Resolute，四类全新安装与 Native 完整组合仍待验收。仓库缺少 Harness 指令和 strict gate/OpenSpec CLI，不宣称完整门禁通过。

## 用户追加约束：保存与启动分离
用户明确要求所有初始化在保存／安装阶段完成；启动不执行依赖、版本、主题哈希预检，不写入默认设置，不自动修复或重试。启动失败退出并保留原始日志。此要求替代此前启动时重新验证静态安装内容的设计。

## 用户追加授权：发布组件
用户明确要求“发布相关组件到组件仓库，总结当前任务完成情况”。本次发布已验证的 GNOME 50 核心、Resolute GIO／Glycin 与 Native Ubuntu 50 外观，附源码、来源锁及校验清单，以公开集成验证版区分组件交付与完整 App 验收。App 继续使用既有固定 URL／哈希，不发布 APK、不归档。
