# APK 完整重打包

用户指出 APK 增大。对比上一版本：APK 增加 648580 字节，ZIP 有效压缩内容仅增加 1593 字节，其余来自 Android 增量打包遗留空洞。仅移走 packageDebug 中间状态及输出后完整重打包，未改业务源码。新包为 45465161 字节，较本轮先前包减少 1891943 字节；2486 项 ZIP 条目内容全部逐字节一致，77 项源码资源、原证书 v1/v2 签名、zipalign 和 HTTP 完整下载通过。保持原始文件名；旧包备份于交付目录 .previous/before-compact-repackage/。未部署设备，产品验收仍 Pending。
