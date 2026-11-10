# 组件资产命名

## ADDED Requirements
### Requirement: Box64分发名称明确对应后端和版本
当前Box64下载资产 MUST 使用<组件ID>.tar.xz名称，其中Native使用glibc_box64_<版本>，PRoot使用rootfs_box64_<版本>。
#### Scenario: Native下载
- **WHEN** 下载Native Box64 0.2.7或0.4.4
- **THEN** 当前索引指向glibc_box64_0.2.7.tar.xz或glibc_box64_0.4.4.tar.xz，不使用含proot或不含程序版本的文件名
#### Scenario: PRoot下载
- **WHEN** 下载PRoot Box64 0.4.5
- **THEN** 保持现有rootfs_box64_0.4.5.tar.xz名称
### Requirement: 名称调整不改变组件内容与身份
重命名 MUST 保持原包字节、组件ID、程序版本、索引修订及实际运行目录，不触发已安装组件自动升级，不增加旧地址回退逻辑。
#### Scenario: 发布与构建
- **WHEN** 新名称包发布并构建APK
- **THEN** 新包与当前来源大小及摘要一致，APK仍使用原名且支持正常安装
