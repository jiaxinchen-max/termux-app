# 设计

## 证据与边界

启动器desktop_gnome_launch.py的core策略已经强制要求ca.desrt.dconf、org.gtk.vfs.Daemon、org.gnome.Nautilus。Native安装命令没有gvfs，固定Nautilus包也没有依赖gvfs。设备真实日志在启动Shell前退出。本次保留启动器原有服务要求，不跳过失败检查。

## 实现

1. desktop_install.sh的native/gnome apt命令新增gvfs，复用既有包管理与错误处理；安装完成前验证三个必需服务文件存在，缺失则失败并明确服务名，不能输出安装完成。
2. DesktopEnvironment.isInstalled只在Native GNOME分支增加同样三项服务文件检查，沿用现有“未安装”与安装入口；PRoot服务归容器管理，不要求宿主拥有GVfs。
3. 脚本测试通过真实安装脚本和受控apt模拟验证gvfs被请求，并验证apt成功但服务缺失时仍失败。Java测试验证服务逐项缺失时未就绪，补齐后就绪；PRoot保持既有测试。

## 风险、回滚与验证

安装路径属于高影响文件；已在诊断候选中公开该修复范围，并由当前用户指令确认。风险为软件源依赖变化，安装包管理失败时保留既有错误处理和日志，不删除桌面、Prefix或数据。回滚为恢复本变更的安装脚本与安装状态检查并重编APK；已安装的gvfs不自动删除，避免影响其他桌面。

先运行复现测试，实施最小修复，再运行GNOME安装脚本测试、DesktopEnvironment及状态解析单测、脚本语法和ARM64 APK构建。核对签名、非testOnly及内嵌脚本。安卓依赖安装、APK安装和正式App界面验收在具体操作获授权后执行；本次不以后台桌面启动代替正式验收。

仓库缺少Harness脚本、工作流及OpenSpec CLI，采用现有仓库同类人工规划和命令证据，不宣称严格门禁通过。
