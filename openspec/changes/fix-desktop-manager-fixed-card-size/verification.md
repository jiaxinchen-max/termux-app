# 验证结果

管理页与创建页既有 Robolectric 回归共 12 项通过，失败、错误、跳过均为 0。管理网格测量改用原生图形模式，覆盖 240/360/840dp 可用宽度、1/2 倍字体、有限 660dp 视口；三类卡片均为 128 × 160dp、封面 52dp，启动按钮与文字保持卡片内。既有正文防误触、确认取消与点击防重、浅深主题交互及创建页类型联动通过。

此次修正了旧测试只用 UNSPECIFIED 测量单卡的缺口；旧图形替身的固定 41px 字高不适合按钮内容测量，改用真实字体测量后通过。构建中还发现 Wine 使用旧 action-card dimen，已保留该资源；没有修改 Wine 布局或共享主题。

验证命令：

```sh
JAVA_HOME=/home/user/.local/jdks/temurin-17 \
ANDROID_HOME=/home/user/.local/android-sdk \
ANDROID_SDK_ROOT=/home/user/.local/android-sdk \
GRADLE_USER_HOME=/home/user/.cache/gradle \
./gradlew :app:testDebugUnitTest \
  --tests 'com.termux.ui.desktop.activity.DesktopGalleryRefreshTest' \
  --tests 'com.termux.ui.desktop.activity.DesktopCreateRenderingTest' \
  :app:assembleDebug -Pandroid.injected.build.abi=arm64-v8a \
  -Pandroid.injected.testOnly=false --max-workers=2 --console=plain
```

APK 为 `termux-app_apt-android-7-debug_arm64-v8a.apk`，大小 44525010 字节（42.46 MiB），SHA256 `bfef554d8c5b38ca9c2540800331c8110d653fe6b07ed53e8ca00dc5e613fa4b`。v1/v2 签名验证通过，仅 arm64-v8a，无 testOnly；71 个原本的 local-games 资产逐字节一致，没有新增大型资产。下载服务 HEAD 返回 200，长度一致。

证据与 JUnit XML：`/home/user/workspace/artifacts/ubuntu-gnome-management-ui-20261116/`；APK 与签名清单：`/home/user/workspace/artifacts/desktop-manager-fixed-cards-apk-20261116/`。

本轮不替换设备 APK，不操作用户容器；实际设备视觉验收交用户下载安装后完成。源码沿用用户结束时推送保存进度的授权。七个既有脏子模块保留。Harness 指令文件、verify.sh、OpenSpec CLI 缺失，不声称严格门禁通过；产品文档一致性 Pending，不归档。
