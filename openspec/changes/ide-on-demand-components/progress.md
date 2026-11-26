# 2026-11-26 进度保存

用户已批准 tooling 按需组件、构建标准 APK 及提交推送。

## 已实现与验证

主 APK 保留 IDE 入口、欢迎页与 Sora 编辑器；tooling 首次同步时按需下载，复用应用私有组件缓存。固定摘要、大小和协议，取消/损坏/不兼容时不执行半成品；再次同步可重试。复用既有 ComponentDownloader，独立的 IDE 任务仓库不进入 Wine 门禁。

组件及对应源码发布到 https://github.com/jiaxinchen-max/termux-app-packages/releases/tag/ide-components 。公开下载重新校验通过：18,168,770字节，SHA256为983dc4968a5f1800226e391031a1da26a58784f41c681b254c2496f4714b59df。

54项IDE/设置宿主测试加1项JAXP测试通过。新增组件测试在Android26/34运行，覆盖首次下载、离线复用、安装损坏修复、协议不兼容、空清单拒绝及取消重试。标准 :app:assembleDebug 通过。

APK为termux-app_apt-android-7-debug_arm64-v8a.apk，46,335,908字节（44.19MiB），相较62,745,266字节缩减16,409,358字节（15.65MiB）；SHA256为cd1b637ff019995946ad802d05fec17e80a3c62b95a6b3779e1ad3cc51c7b838。minSdk21、target28、arm64-v8a，未添加testOnly，未自动安装。

## 未完成与恢复入口

用户人工安装后需要验证真实native同步、取消、离线复用。此前设备显示Termux无响应，但lastanr没有提供记录；尚未定位，不能声称已修复。Java/XML语言服务、项目索引消费、方法跳转/补全/诊断及设计器仍在port-complete-androidide任务清单内。项目模型持久化基础已接入，但配置变更失效检测和语言服务消费尚未完成。

继续以master-x11-submodule-tmp为开发分支，优先恢复设备同步回归，再接入固定上游语言服务；不要把临时词法颜色当成语义能力。APK只由用户手工安装，保留标准名称和类型。禁止提交CMake自动生成的七个嵌套子模块补丁；本次已恢复其原先干净状态。

产品文档一致性Pending，严格Harness脚本缺失，未归档。验证细节见evidence目录。

## 提交可复现性核对

Git索引核对发现并修正通用text=auto换行转换和build目录忽略规则对上游源码的影响，使用third-party/** -text保留固定源码字节，并显式纳入锁清单中的包路径文件。发布的对应源码压缩包同样按2316项锁清单核对，防止误排除com/android/build包路径。
