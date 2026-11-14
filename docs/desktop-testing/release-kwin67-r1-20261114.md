# KWin 6.7.5 r1 候选组件发布

用户在候选交付后明确回复“发布”，授权当前 KWin 组件 prerelease 和 App 下载配置同步。发布仓库 jiaxinchen-max/kwin，tag `termux-hwaccel-6.7-r1`，源码提交 `21ee6d4953ceba9553787e0c2e52a88a98fcbba6`。仅推送组件源码 tag，没有推送 App 工作树或构建 APK。

发布地址：<https://github.com/jiaxinchen-max/kwin/releases/tag/termux-hwaccel-6.7-r1>。

## 资产与源码

- KWin：`kwin_6.7.5-1_aarch64.deb`，9,049,096字节，SHA256 `f5ca59c8b1ea120facf174ac4951725a275d08329376c903584f05baee4adcc4`。
- 新增依赖：`libei_1.6.0_aarch64.deb`，86,752字节，SHA256 `be94458e11fe2f0eceec657a67d3da0f2286ff1f92ecc49775a8382c5f1a7b8b`。
- App固定的其余8个共享deb均从原r28 release下载并按原哈希复制，包含定制Qt；全部10个安装包可在新tag获取。Xlorie/GNOME/USB/Hyprland独立资产来源保持原配置。
- 直接取回编译机 candidate3 完整源码归档，SHA256 `a5257985175a7a0fd91b1498047450478ebcbff2b15cd98af6c8710a370cb705`；与发布源码提交全部3324个受控文件逐字节匹配，无额外普通文件。
- 附带原编译配方与使用公开源码URL的配方、补丁、新增工具源码、审阅材料和SHA256SUMS；没有重新编译或修改交付deb。原r28 release保留供回滚。

## App配置

`DesktopLaunchSpec.RELEASE_TAG` 与 `desktop_install.sh` 白名单同步为新tag；DEBS替换KWin版本/哈希并加入libei，现有六份安装/启动测试夹具同步tag或文件名。当前修改仅在App本地工作树，未混入其他未提交改动进行推送。已安装桌面不会因GitHub发布自动升级。

## 验证与限制

- 发布前远端29个资产的大小与GitHub SHA256 digest全部等于本地文件；源码tag提交及Java/安装器tag一致。公开后29个资产全部完成无鉴权HTTP GET，状态200、大小和SHA256均通过（publication/public-download-verification.json）。
- App非交互安装10项、Hyprland6项通过，shell语法和diff空白检查通过。
- restore-prerequisites、desktop-scripts、ubuntu-gnome、gnome-usb-install四组回归失败；复制本次发布修改前的安装器和夹具、保留此前已有业务修改后复跑，失败用例/首个错误相同。包括prepare-plasma的Python模拟器不识别、pid等待超时、GNOME渲染参数及初始化夹具差异。完整日志见publication/app-test-results.json及相邻日志，不能宣称完整App回归通过。
- 编译及包静态检查沿用已完成候选证据；源码审阅包/BUILD.md是发布前快照，以本报告和release notes表述发布状态。
- 模拟器未安装候选；真实虚拟桌面v4操作、Ubuntu旧客户端、面板/Dolphin/输入/停止重启及实际回滚未验收。不能宣称修复启动慢或完整Kubuntu兼容。
- Harness strict-run脚本缺失，保持Unavailable；产品文档和最终兼容性验收保持Pending，未归档。

## 回滚

App配置可恢复旧tag、旧KWin包/哈希并移除新增libei行。设备替换尚未执行；将来若需回滚，停止候选并安装原r28完整包、恢复备份配置，保留容器和UI默认入口，不自动删除依赖。
