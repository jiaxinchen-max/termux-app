# 修复全新 Ubuntu Plasma 的 Discover 首次应用目录初始化

## 问题与证据
用户报告 Discover 仍提示无法联网。指定模拟器 UID 10206 / desktop-48ff3708 上，容器 DNS 与可信 HTTPS 正常；真实 PackageKit NetworkState=2，Resolve 和 GetRepoList 成功，RefreshCache 在约 24.9 秒后成功结束。AppStream CLI 读到约 2500 个组件。
原会话首页显示 Unable to load applications / Please verify Internet connectivity；重开正常 Discover 后能搜索 KCalc，普通首页也显示 VLC、Kate、Krita 等应用。上游 v6.6.6 源码确认该网络文案是空首页的通用解释；应用池在构造时加载，刷新完成仅获取更新，不重新加载池。OS catalog 缓存时间 11:22:06 晚于原桌面 11:21:08 启动，符合首次刷新晚于初次加载的候选机制；缺少原实例初始池计数，不能声称已精确重放全部竞态。

## 实施范围（用户已批准）
- 仅 Ubuntu PRoot Plasma：显式准备 AppStream，在桌面包安装之后执行已配置的可信 HTTPS 索引刷新与发行版 AppStream 缓存建立，再完成安装初始化和成功标记。
- 失败保留原错误并停止，不写成功标记；不在每次桌面启动时增加网络等待。
- 验证首次干净启动即可展示首页并搜索真实应用，无需重开 Discover。

## 边界
保留现有 Ubuntu 底包、软件源、TLS 和签名验证、渲染选择及 native 路径。此轮不重编 Discover deb，不在 APK 添加主题/证书/软件包资产。
仍出现的 Update Issue 弹窗、fwupd/snapd 不存在、KNS 部分源初始化超时，以及一次音频健康请求超时退出，单独记录，不宣称已修复或把它们都归因为断网。

## 文档与授权
无 HTTP API 合约变更，api_docs=N/A。安装完成条件及首次体验变化，product_docs=Required，拟复用 docs/product/desktop-proot-plasma.md，验收前 Pending。用户已于本轮回复“批准”，确认实现及既定模拟器验证；不包含提交、推送、APK 交付或归档授权。
