# 修复桌面安装等待配置文件输入

用户在持续安装修复验证中报告Native XFCE4安装卡在openssl.cnf的dpkg配置文件确认。截图明确显示等待Y/I/N/O/D/Z；当前桌面宿主安装仅传-y，没有非交互配置策略。本次按已授权安装问题修复范围，消除这一阻塞。

桌面安装及容器宿主依赖补齐统一设置DEBIAN_FRONTEND=noninteractive，并为每处宿主包安装增加dpkg默认配置决策及无默认时保留已有配置。保留安装日志和原失败退出码，不修改全局APT配置或自动修复失败。容器已有相同配置策略保持原样。

api_docs为N/A：无HTTP契约变更。product_docs为Required：目标docs/product/desktops.md，用户界面验收前一致性Pending。以隔离真实dpkg重现配置冲突，验证无用户输入完成并保留旧配置；构建原名原签名非testOnly APK供手动验收。
