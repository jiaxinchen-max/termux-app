# 桌面备份恢复与共享存储修复进度（2026-11-15）

## 问题与行为

恢复时归档成功解包，但新容器 alias 缺少宿主渲染凭据，UI 判断未安装并进入完整安装器。实际日志包含 15 个宿主包重装；容器 DNS 指向已排除的运行时 /run 链接，apt 失败后安装器又清除了已恢复的安装标记。

现在恢复在解包完成与桌面记录发布之间执行独立本地初始化：恢复 DNS 运行时配置、验证已有桌面组件及所选 EGL 渲染器、为新 alias 生成宿主渲染凭据；只有最终环境检查通过才发布成功。该阶段不进入完整安装器，不调用包管理、下载或目录刷新。宿主依赖确实缺失时仍需独立补齐；已有满足依赖时不会联网。

DNS 修复不跟随归档链接写入其他位置，保留有效普通自定义 DNS，临时文件冲突时保留原文件。凭据使用禁止覆盖的原子发布，异常回滚按持久化设备号及 inode 判断所有权。

同批保存此前已批准但尚未提交的修复：Ubuntu Plasma 导出身份、snapd/void 运行时挂载占位排除、不可读路径诊断、全部共享存储入口授权等待与链接补齐、Discover 首次目录预初始化。行为细节及未完成项见两个活动 OpenSpec 变更：fix-desktop-export-ui-storage、fix-proot-discover-first-launch-catalog。

## 本地验证

最终聚焦运行通过：88 项 Java（App 29、termux-ui 59）、9 项 Python 恢复测试、6 项 Discover 目录测试、39 个宿主预检夹具，共 142 项检查。归档覆盖往返、路径安全与失败清理；恢复覆盖成功/失败真实 shell 结果、操作身份隔离、包及用户数据保留、DNS 链接和临时文件冲突。git diff --check 通过。

clean APK 构建成功，arm64-v8a，testOnly=false，签名与此前版本相同。43.28 MiB；相对上一交付增加约 18 KB，无新增资产或原生库，唯一变化的资产为 desktop_initialize.py。HTTP 下载 SHA256 已核对。

## 实机验证及边界

先 force-stop 清除旧 App 进程，再在实际已恢复的 Ubuntu 容器执行本轮初始化。exit 0，真实 System EGL/llvmpipe 验证成功，未执行装包/下载。随后补回成功验证的 alias 凭据与被失败重装删除的标记，保留用户容器及备份。

旧 APK 冷启动两次发生 Android startup ANR；完成系统 dex 编译缓存后，默认 UI 冷启动已进入桌面列表，该恢复项显示 Ready/Open。通过 UI 点击 Open，宿主 kwin_wayland、容器 plasmashell 已运行，当前 supervisor PID 对应的 plasma-session 与 plasma-ready token 一致。截图传输超时，未完成可视画面核验。该结果验证已有恢复容器的本地状态修复和启动就绪，不能代替新版 APK 从头导入到启动的完整验收。

尚未完成：新版 APK 完整 UI 导入到桌面、Discover 首次首页及网络验证、外部存储各交互场景实机验收。run-as 下 socket 返回 EPERM，该诊断不代表实际 App 网络状况。产品文档一致性仍 Pending，两个 OpenSpec 均未归档。仓库缺少指定 Harness strict 门禁脚本，不能宣称严格门禁通过。

## 交付

APK 文件名：termux-app_apt-android-7-debug_arm64-v8a.apk。
SHA256：1cf323dbd3ad821d001469b9aab6edb5588001140562518ffa30b3de8dbd46af。

7 个原生 submodule 工作区存在构建系统自动应用的已跟踪补丁，已用构建对应的 GNU patch 反向 dry-run 检查；没有更改 submodule 指针，也没有将展开后的修改另行提交到供应商仓库。
