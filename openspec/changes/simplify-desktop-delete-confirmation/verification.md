# 验证记录

- :termux-ui:testDebugUnitTest 与 :app:assembleDebug 构建成功，796 个单元测试全部通过（0 失败/错误/跳过）。
- 中英文 strings.xml 解析通过，Android 资源打包及 DesktopActivity 编译通过。
- 从现有 test-desktop-scripts.sh 原样抽取第 27 节移除夹具独立运行通过：成功、原始失败码 42、失败保留 rootfs、不重试、不递归兜底删除、缺工具 127、五类非法契约 64 且不调用删除工具。
- 完整 test-desktop-scripts.sh 未通过：启动场景等待 pid 文件超时，尚未运行删除部分，不能宣称完整桌面脚本套件通过。启动及渲染代码本轮未修改。
- 自审确认取消按钮无执行回调；PRoot 只调用既有 deleteAndRemoveContainer，native 只调用 delete；既有 desktop_remove.sh 只针对受控 alias 调用 proot-distro remove，没有删除外部导出文件的路径。
- 尚未进行新 APK 实机对话框验收或删除真实容器；保留用户全部容器、备份。本轮不归档，产品文档一致性待验收。
- 指定 Harness 脚本与规则文件、OpenSpec CLI 缺失，未执行严格门禁。

原始日志：/home/user/workspace/artifacts/build-restore-apk/delete-confirmation-build.log、delete-confirmation-scripts.log、delete-removal-fixture.log。

代码提交 a4e25e19 已推送 master-x11-submodule-tmp；新 APK 构建可供人工验证。
