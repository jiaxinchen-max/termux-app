# KWin PRoot 渲染修复任务

## 1. 证据与方案确认

- [x] 1.1 获取实际安装日志与设备渲染配置；完成条件为记录失败层、缺少的宿主 Freedreno 文件及当前 Lavapipe 身份，不把文件存在等同于后端可用。
- [x] 1.2 确认 proposal/design/specs/tasks 及列出的高风险渲染路径；完成条件为用户明确确认独立四组合、默认 System EGL/LLVMpipe 和验证范围，确认前不改业务代码。
- [x] 1.3 同步 separate-desktop-host-guest-rendering 中 Plasma 原配对限制的取代关系；完成条件为新旧活动规划无矛盾且保留历史验证事实。

## 2. 选项和按层执行

- [x] 2.1 更新策略、创建默认、设置及中英文提示；完成条件为四组合可保存、已有显式宿主 LLVMpipe 不被改写、容器无 System、Java 策略测试通过。
- [ ] 2.2 同步 shell 白名单与宿主/容器依赖和环境交接；完成条件为四组合命令断言通过，不同层未互相覆盖，GNOME/XFCE/Hyprland 回归通过。

## 3. 初始化与错误恢复

- [x] 3.1 实现 KWin 专属明确 ICD 验证和诊断，适配已验证的模拟器 Zink 后端；完成条件为 System 不要求 Freedreno，Zink 实际 renderer/ABI/ICD 匹配，软件不误报硬件，坏 ICD/架构和探针失败测试通过。
- [x] 3.2 初始化与启动复用受验证的分层环境，保持失败标记和重试；完成条件为缺驱动或失败不写成功、不自动回退、不重建已有容器，相关恢复测试通过。
- [x] 3.3 审查修改及必要说明；完成条件为 review_findings_v1 无未解决 MUST_FIX，记录精确保护范围、回滚与设备待验收状态。

## 4. 集成交付

- [x] 4.1 构建并验证正常 ARM64 APK；完成条件为名称保持 termux-app_apt-android-7-debug_arm64-v8a.apk、无 testOnly=true、签名一致、资产不重复内嵌，记录 SHA/体积及下载回读校验。
- [x] 4.2 整理自动化和设备验证证据；完成条件为逐组合区分已验证、失败和未执行，未授权设备动作保持 Pending，缺失 Harness strict-run 保持 Unavailable，不冒充通过。

- [x] 4.3 检查其他发行版桌面的同类选项/配对和驱动假设；完成条件为逐桌面给出源码证据、保留的能力边界和已确认缺陷，不将未验证组合宣称可用。

## Workflow follow-up

- 设备安装 APK、运行桌面和任何远程状态修复，在有具体命令与影响后另行获得环境动作授权。
- 最终用户验收后维护 docs/product/desktops.md 的声明一致性；不自动提交、推送、发布或归档。


## 最新约束与验证边界

用户补充“不用考虑兼容旧数据”；不新增迁移或兼容旧格式。2.2 的当前格式策略和 Hyprland 回归通过，较早 GNOME/XFCE 脚本夹具尚有独立失败，详见运行证据，故该项不冒记全通过。设备窗口验收仍待具体动作授权。

2026-11-13 用户明确要求“推送代码”，授权本次会话改动的 stage、commit 与 push；仍不包含部署或归档。APK 为 45,354,395 字节，SHA256 c20bae8b1ab753fa2b617a4f44b1ef5cb4a4c1d22abfa8ae5f6bafa1a8c253e5，已验证 ARM64、非 testOnly、证书一致和 HTTP 完整回读。详见 verification.md。
