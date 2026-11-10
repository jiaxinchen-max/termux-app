# 设计

当前组件ID已明确区分glibc与rootfs，采用<组件ID>.tar.xz，不增加另一套别名。两个Native包发布标签分别为glibc_box64_0.2.7、glibc_box64_0.4.4，文件同名；PRoot地址已有rootfs前缀无需改动。历史构建输入的说明保持真实来源，当前输出示例及索引使用新名称。

从当前索引源读取真实归档，记录大小与摘要，只复制字节，不重新压缩、不重编译。先发布两个规范名称包并逐个公开下载比对，再更新APK索引地址，保持ID、version、size、sha256及profileValue不变。APK仍为termux-app_apt-android-7-debug_arm64-v8a.apk且testOnly=false。

验证覆盖两个实际包公开下载、索引解析、APK内索引和源资产一致、签名、ABI及testOnly。复用现有测试，不为字符串替换新增镜像测试。Harness引导文件、严格脚本及OpenSpec CLI当前缺失，记录Unavailable，不声称严格门禁通过。

回滚仅将当前索引及当前输出示例改回原地址/名称；原始包仍可审计。无运行布局及设备数据删除，不触及其他组件。Git操作与设备验证不在本次授权范围。
