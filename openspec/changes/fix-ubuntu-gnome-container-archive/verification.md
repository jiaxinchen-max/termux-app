# 验证记录

## 本地自动验证

- Android 桌面模块 129 项 JVM 测试通过，包含真实文件归档往返、名称冲突、发行版矩阵、恢复参数及正式启动配置发布。新增 4 项启动配置测试覆盖 XFCE/Plasma/Ubuntu GNOME 正式配置、目标已存在、发布竞态及归属登记失败；不覆盖其他文件，失败不留下自有临时配置。
- `sh termux-ui/scripts/test-desktop-restore-prerequisites.sh`：60 组宿主前置夹具通过。包管理器及网络为显式替身，验证 GNOME 核心与共享支持组件分别来自原发布源、全新宿主标准依赖补齐、无关桌面包不下载、无容器安装命令、损坏下载和安装失败不能成功。
- `python3 termux-ui/scripts/tests/test_desktop_restore_local.py`：12 项通过，验证新身份参数、清理工作区不跟随符号链接、用户配置/应用不变及既有 DNS/Plasma/XFCE 恢复。
- Ubuntu GNOME 安装回归 18 项、Ubuntu Plasma 11 项、保存阶段 6 项、私有 D-Bus 会话回归 15 项通过。
- 合计 251 项/组通过，失败、错误、跳过均为 0。Python/shell 语法和 `git diff --check` 通过。

执行 Android 验证命令：

```sh
JAVA_HOME=/home/user/.local/jdks/temurin-17 \
ANDROID_HOME=/home/user/.local/android-sdk \
ANDROID_SDK_ROOT=/home/user/.local/android-sdk \
GRADLE_USER_HOME=/home/user/.cache/gradle \
./gradlew :termux-ui:testDebugUnitTest --tests 'com.termux.ui.desktop.*' \
  :app:assembleDebug -Pandroid.injected.build.abi=arm64-v8a \
  -Pandroid.injected.testOnly=false --max-workers=2 --console=plain
```

## 设备验证

模拟器 `120.24.191.156:8057`，原容器 `ubuntu-ubuntu-gnome-1`，本轮独占恢复身份 `archive-verify-20261116`。测试前 force-stop App；没有安装或替换设备 APK，没有覆盖原容器及用户原备份。

1. 生产 GNOME 宿主前置完成：`state=succeeded / exitCode=0`。组件已满足，没有网络/apt 更新；登记原 APK 配套的 termux-api/upower，释放 launcher。
2. 生产 Java 引擎通过只读 DEX 在 App UID 下完成真实完整导出、inspect 和独立目录恢复：56,076 个条目，归档 996,839,497 字节，SHA256 `df246598e784027de0b8ddca6f0da7aca8dbd2b65baa1ca7ee4136d73a476b43`。首次夹具因 Android 15 拒绝可写 DEX 而未载入；JAR 设为只读后正常执行，没有改变 App 或系统策略。
3. 生产 Python restore 本地初始化成功：宿主 System EGL、容器 Mesa26/llvmpipe；prepared-session 及 runtime 链接都使用最终新身份，ready 文件与正式 pid 路径一致，日志确认无安装或下载。
4. 包数据库、gnome-shell、nautilus、dconf、Ubuntu profile 标记、libglycin 六个文件在原容器与恢复容器的 SHA256 一致；应用及用户配置保留。
5. 生产 DesktopEnvironment.isInstalled 通过，生产 repository 成功写入独立桌面记录。设备检查发现恢复路径遗漏正式启动 spec；本轮改为初始化完成后、库存发布前原子写入 spec，设备调用生产 helper 发布成功。失败清理按 dev/inode 归属处理，不修改启动路径。
6. 最终启动未通过：旧 APK 的 UI 调度及 ADB 有卡顿，直接运行生产启动脚本后 GNOME 日志明确为 Android `termux-render` 连接被拒绝，随后 Wayland display readiness 失败；没有得到 ready 文件或成功桌面截图。该测试环境缺少可连接的 Android 渲染服务，不能把此轮备份往返成功说成新 APK 的 UI/画面验收通过。日志完整保留，不在启动路径添加检查、修复或 fallback。

测试数据清理结果见同目录证据，原容器和用户原备份保留。

## APK

固定名称 `termux-app_apt-android-7-debug_arm64-v8a.apk`。构建成功；已清理 ZIP 增量残留并对齐、原调试签名重签。v1/v2 签名通过，未设置 testOnly，仅 arm64-v8a，71 个受 Git 管理的 local-games 资产逐字节一致。

大小 44,525,010 字节（约 42.46 MiB），SHA256 `f1eecef493ab9ca1f2de9fa39c7569835ef0d3fbbbc38cdd2cef90310e3b67ba`。相比上一 UI 交付包增加 4,096 字节，没有新增大型桌面资产、组件 DEB 或容器镜像。HTTP 下载服务 HEAD 已返回 200，Content-Length 一致；新 APK 未替换模拟器 App，交用户手动安装验收。

证据目录：`/home/user/workspace/artifacts/ubuntu-gnome-archive-audit-20261116/`；APK 与签名清单：`/home/user/workspace/artifacts/ubuntu-gnome-archive-apk-20261116/`。

## 边界

仓库没有规定的 Harness 指令文件、verify.sh、基线 specs 和 OpenSpec CLI，因此不声称严格门禁通过。产品文档一致性仍为 Pending，等待用户接受，不归档；保留已有七个第三方子模块脏改动。代码提交和推送沿用用户任务结束保存进度的明确授权。
