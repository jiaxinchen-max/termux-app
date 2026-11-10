# 统一组件下载和备份的外部存储

## 用户要求
组件下载统一使用共享存储 Download/Termux/components/。删除 front/components/downloads 的迁移复制逻辑；不回退到 Android/data 私有外部目录。无法写入时终止并明确提示用户授予 App 存储访问权限。

手动本地安装通过文件选择器读取用户选中的包，来源不限定目录，不要求放入 Download/component。取消下载服务对该固定目录的自动查找，不全盘扫描文件；已有目标下载缓存复用保留。不新增摘要或签名校验。

## 固定备份目录
RootFS与Wine C盘备份统一保存到共享存储Download/Termux/backups/，自动生成文件名，不提供保存位置或名称选择。恢复仅列出该固定目录内对应类型的备份供选择，不弹出目录浏览器，也不允许导航到其他路径。只有安装外部组件保留任意来源的文件选择。不可写时停止并提示授予存储访问权限，不回退其他位置、不覆盖已有备份。

## 边界
本次调整组件下载、手动来源目录限制、备份保存和恢复入口及权限提示，不改组件安装位置或Wine工作数据。组件没有独立备份功能，本次也不新增。不复制、移动或删除旧目录里的用户文件。

## 文档
api_docs=N/A，无HTTP契约变化。product_docs=Required，目标docs/product/component-management.md和docs/product/wine-storage.md；一致性Pending，用户最终验收后定稿。仓库Harness引导和严格脚本及OpenSpec CLI缺失，规划按已有本地文件结构记录。

## Wine 文件命名统一
用户要求原有game-xxx文件命名统一为wine-xxx。核对发现实际备份和模板主要使用games-前缀，因此两种前缀均纳入。对App管理的文件名统一采用wine-，例如games-base-backup-时间.tar.zst改为wine-base-backup-时间.tar.zst，games-rootfs-base.recipe改为wine-rootfs-base.recipe，games-rootfs-base-prefix-包名.tar.*改为wine-rootfs-base-prefix-包名.tar.*。同步内部管理文件及其读写、备份恢复、匹配和测试引用。仅修改文件命名，不对Java类名、业务字段、包ID、用户游戏文件或目录名称做无关替换；APK保持用户要求的固定文件名。压缩包内部文件与镜像预装文件如需更名，生产和恢复端必须配套更新，禁止只改读取端。继续不兼容旧布局和旧文件名。
