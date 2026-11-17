# Ubuntu Wine 迁移当前状态

本轮明确批准的四候选制品发布、资产仓库索引推送和原名APK构建/交付已完成。用户最新“推送代码”额外授权主应用stage/commit/push，本提交承载已核验的迁移代码及本run记录，推送回执写入外部artifacts/ubuntu-wine-rootfs/main-application-push-result.json。未安装APK、未归档。单一builder从零完整复现及最终App/GPU验收仍Pending，整体变更保持活动。

## 实际交付

- 四个全新prerelease已发布，匿名完整下载全部大小/SHA一致；旧制品保留。镜像421418301字节、展开2886156797字节、15448成员，SHA256 `656b0a9214f2188c3f8b48c9d218c3275e97753690f827b52784536300bb8877`。最终归档全流逐成员核对通过，未再次完整写盘还原；旧版本实际还原后，两份私有根已集成最终媒体并验证。
- 三个Ubuntu前缀归档共9172804字节，各自绑定独立Wine来源、新组件ID和v13配方，源码bundle/manifest/delta与归档一致。Hangover为ARM64定制Wine，Wine10/Proton由独立Box64翻译，模块和前缀不混用。
- APK内索引43→47项，资产仓库21→25项；各只新增四项、既有条目不变。资产仓库仅提交repository/index-v1.json，提交 `7d2bb960ee1847ee92e93b76e06363d739239ecc` 已推送main，公开main和固定commit索引核对通过。
- 原名 `termux-app_apt-android-7-debug_arm64-v8a.apk` 为45485383字节，较前版增1081字节，SHA256 `5cdf36d6ed9ed957ee9f389d7ec0da207dd43480e68d10d2811d3c8c5c61ceb3`。原证书v1/v2、arm64、testOnly=false、zipalign、源码资产/47项索引和完整HTTP下载校验通过。APK仅带15307字节的v13配方与描述，完整镜像及前缀载荷外置。已更新原8182下载位置并保留旧包备份；未安装到设备。
- APK构建基线为572b115151160c509b6d27a86d9278c2a4ca8d48及已核验工作树；本次提交不重写APK原始构建来源。七个native子模块既有补丁摘要不变，未改wrapper索引。

## 来源与运行验证

官方Ubuntu26.04 ARM64 OCI、351个APT版本和264个deb摘要已锁定。媒体补充包从24个未修改Ubuntu原包选取15个必要插件及运行库；v4为21217588字节，SHA256 `550dec5e25de30abfd5c8bea956179f3d2e0d520dcd3ee756135d79d2c171942`，主机和设备从原deb字节一致重现。插件进入默认扫描目录，其他库/版权文件进入usr/local，不覆盖冲突系统文件，不伪造dpkg安装记录。

构建根和还原根各1511项原生ELF无直接缺依赖，14个关键GStreamer工厂加载通过。三类PE32/PE64功能、公开DirectShow PCM/WMV2共18项通过，12次媒体释放Completed、18个原始退出码均0。X13/Pulse4714已退出，无测试Wine会话残留。

v13身份及拒绝旧身份、重建先停会话、暂存和previous回滚已实现。安装误复用旧Debian根的错误成功已修复，未恢复取消的整文件SHA gate。构建边界14、还原/身份28、事务15、停止/前缀Java21、元数据Java14及发布后Java48项验证通过（有覆盖重叠，不累计作独立用例）。裁剪此前58项通过。快照测试旧43项/Debian摘要预期已更新，原失败保留。strict再次实际尝试exit127，仓库缺脚本，不能称完整门禁通过。

## 保留限制

此前缺插件、私有winedmo回调失败、Wine10 COM释放死锁和Wine10/Proton wrapper124原始证据保留；本轮成功不声称修复上游竞争。仅探针含10秒cleanup watchdog，本轮未触发，未修改生产退出、JIT或App主动停止行为；独立CLI137不作为迁移阻塞。

此前外置全模块201项仍有19项问题/raw exit1，媒体模块直接缺依赖为0；不全部称外设或宣称全模块闭包通过。真实App界面、硬件GPU及完整游戏兼容未验收。候选来自锁定OCI/APT分阶段构建，单一builder CLI尚未从零完整跑过。Rust coreutils去重试验已撤销，115份原SHA恢复，只保留zstd32MiB窗口；664016764字节为裁剪回收量，不是根剩余大小。

产品一致性Pending，未获最终用户接受或归档授权。原始证据位于 /home/user/workspace/artifacts/ubuntu-wine-rootfs/，当前证据索引见 verification-evidence.json，发布授权及范围见 publication-authorization.json，交付链接见apk-download-verification.json。
