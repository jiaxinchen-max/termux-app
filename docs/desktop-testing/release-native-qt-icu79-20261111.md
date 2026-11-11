# Native Qt ICU79 发布与 APK 构建记录（2026-11-11）

Native KWin 启动失败的已确认原因是 Qt6.12.0 链接 ICU78，而当前 Termux 使用 ICU79。用户在指定编译机使用原 `/workspace/termux-packages/build-package.sh` 完成 Qt 重编，本次仅接入其产物、发布并构建 APK。

## 发布内容

原发布位置：<https://github.com/jiaxinchen-max/kwin/releases/tag/termux-hwaccel-6.6-r28>。

- Qt 主包：13,052,568 字节；`ac6a509819084af9601db572d88d02e384a034cbc27923f8c9b0780dd6a29cfb`。
- GTK 插件包：55,356 字节；`88174bce8ca6f5f5034c6a9b14360e1d1bc7f8e5af014cfbcd2440840897c622`。
- 原 cross-tools 的文件及链接全部一致；重生成包混入其他 Qt 模块和旧版本文件，故保留原发布包，未上传膨胀包。
- 包版本维持 `6.12.0-1+termuxkwin1`，现有文件名及 URL 不变。主包依赖改为 `libicu (>= 79), libicu (<< 80)`。
- 当前资产索引、SHA256SUMS及验证记录已同步；历史 r28 构建输入归档保持不变并明确其历史范围。

## 核验范围

75个目标 ELF 通过架构、Bionic 加载器及依赖检查。Qt 共享库导出集合未变，NEEDED仅将两个ICU78依赖替换为ICU79；原popup RTTI补丁与Qt功能配置保留。已有定制Native组件未发现直接ICU78依赖。暂存上传后先验证服务器摘要，再交换名称；所有发布文件通过原公开URL下载核对后才移除旧资产。

桌面脚本、39项恢复依赖用例、非交互安装检查通过。GNOME扩展检查发现六项旧断言仍要求已取消的摘要校验；只同步测试与注释，六项重跑通过，其余32项原已通过。保留初次失败及修正后日志，不恢复运行时校验。

APK通过assembleDebug，原签名、arm64-v8a、非testOnly，保持 `termux-app_apt-android-7-debug_arm64-v8a.apk`，45,227,266字节。实际APK中的安装脚本与当前源文件一致。

已有桌面的Qt不会仅凭同版本资产替换而自动升级；需显式重装两份新版Qt包。桌面安装缓存 `${TMPDIR:-$PREFIX/tmp}/desktop-debs-termux-hwaccel-6.6-r28/` 中如存在同名旧Qt主包与GTK包，须先删除对应旧包缓存。未增加自动升级或缓存迁移逻辑。

## 待验收

用户手动安装APK，并通过正式App启动Native Plasma。静态结果不代表UI已验证。未执行ADB安装、Git提交推送或归档。严格Harness脚本缺失，不声明通过。

本机完整证据位于 `/home/user/workspace/artifacts/rebuild-native-qt-icu79/`，包含编译产物、原发布元数据、当前发布元数据、公开下载副本、测试日志和APK验证结果。
