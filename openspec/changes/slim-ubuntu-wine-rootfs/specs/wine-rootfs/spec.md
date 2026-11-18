# Wine镜像体积控制

## ADDED Requirements

### Requirement: 官方GNU基础命令

镜像构建 MUST 固定 Ubuntu 官方 GNU provider 与deb摘要，移除旧Rust provider和重复载荷，保留标准核心命令。运行时 MUST 沿用现有安全归档协议，不接纳不受校验的硬链接。

#### Scenario: 从官方OCI准备新候选

- **WHEN** 在私有构建根按锁定包集安装
- **THEN** 只有 coreutils-from-uutils 和 rust-coreutils 可被移除；其他移除、包版本漂移、GNU命令缺失或Cargo载荷残留均阻止打包。

### Requirement: 精确裁剪非目标GPU

构建 MUST 将 asahi、intel、powervr_mesa 的Vulkan库及注册文件纳入现有可回滚裁剪，不扩大到媒体、字体、LLVM/Gallium或目标GPU。

#### Scenario: 裁剪及回滚

- **WHEN** 新GPU批次执行
- **THEN** 精确移除目标库与JSON，目标渲染链路文件保持；若有受保护依赖则失败，回滚恢复原内容。

### Requirement: 候选与发布分离

#### Scenario: 完成构建优化

- **WHEN** 生成候选与大小报告
- **THEN** 沿用现有资产文件名、Release及URL，在私有目录重新生成描述和manifest；完整验证通过后依据用户授权原地替换资产并同步大小、SHA及身份绑定，替换前备份旧资产和索引。静态验证、命令验证与Wine设备验收分别记录；未经验收的候选不得声称已上线。

### Requirement: 精确移除无运行消费者的附加内容

构建 MUST 仅将已批准的Perl开发头文件/独立POD、usr/bin/pebble、ARM64 Highway contrib/test版本库及链接纳入新增批次；MUST 保留Perl解释器、运行模块、Highway核心、许可与受保护来源。

#### Scenario: 移除附加库并重建缓存

- **WHEN** 删除Highway辅助库
- **THEN** 旧主/辅助缓存先隔离，新主缓存及目标ldconfig生成的辅助缓存均在独立阶段生成并记录后安装；保留JPEG XL解码可用。缓存/字体生成清单累加，失败回滚恢复原字节、模式、时间和链接，生成物被修改则拒绝覆盖。无库删除不触碰缓存。

#### Scenario: 范围与资格记录

- **WHEN** 完成本轮验证
- **THEN** 分别报告119项有效载荷节省与缓存差额，不将展开字节当压缩收益，不改systemd/udev和Flite，不宣称完整候选或Wine全部运行资格通过。

#### Scenario: 打包完整裁剪结果

- **WHEN** 构建器请求打包
- **THEN** 回执批次必须与当前已验证策略清单完全一致；缺失、重复或未知批次阻止产生压缩包，不使用固定五批次计数。

#### Scenario: 验证真实OCI缓存原始文档

- **WHEN** PRoot-distro缓存附加内部媒体类型标记
- **THEN** 构建核对锁定digest对应的原始manifest字节SHA及完整字段，仅允许移除值与锁定mediaType相同的_ct；引用、架构、原始字节或其他字段不同均拒绝，不修改锁和缓存。


## 2026-11-18 用户明确调整验收范围

用户最新指示“先忽略gstream”，本轮原地发布排除三类Wine的32/64位PCM及WMV2共12项GStreamer媒体检查；保留原始失败、超时和GTK诊断，不将排除项标记为通过。其余6项图形/Unicode/中文字体/WinMM检查及3项800×600虚拟桌面检查须满足真实功能报告、180秒限时和清理完成。Proton64采用已执行explorer桌面内原始smoke64成功报告补证，原plain CLI失败继续保留；同次执行支持图形和桌面两项能力，不伪称9次独立执行。CLI137沿用既有已批准诊断口径，不改变生产退出/JIT。

继续执行此前批准的四份原名Release资产和索引原地更新，不新增tag/文件名/URL。更新必要绑定元数据、运行Java身份测试并构建普通原名arm64 debug APK，testOnly=false。先核备份及候选SHA，后上传与匿名完整下载复核，最后CAS更新索引。App界面及硬件GPU验收保持Pending；产品文档一致性与归档继续待最终验收。本轮未新增主应用Git推送或APK安装授权。
