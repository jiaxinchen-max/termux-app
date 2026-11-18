# 提交前验证与发布证据

本轮用户明确要求“提交推送,编译出apk给我”，批准提交当前变更并推送至既有 master-x11-submodule-tmp 分支，随后构建普通原名 arm64 debug APK。原有七个 native 子模块的本地修改保持，不纳入本轮提交。

## 源码与回归

- 直接源码自审覆盖 GNU provider 替换、APT 移除白名单、独立构建根、锁定 OCI 缓存校验、精确裁剪、主/辅助缓存事务及回滚、完整打包批次与下载元数据绑定，未发现本轮待修复阻断项。
- 分发端125项测试通过；内置打包/还原29项测试通过。两份裁剪脚本逐字节一致，两处打包入口均严格比较当前策略的完整批次清单；打包器其余既有差异保持。
- 5个Java身份测试类共33项通过，无失败、错误或跳过；当前Gradle校验确认结果无需重跑。
- 首次测试调用未设置已有的宿主zstd/Python辅助包搜索路径，环境补全后通过，未因此修改产品代码。
- Harness strict脚本及四个引导文件缺失，既有调用exit127，不能宣称strict gate通过。

## 已原地发布的制品

镜像为344435653字节（328.48MiB）。三份前缀包与镜像均沿用原tag、文件名和URL，逐份匿名完整下载核对大小及SHA后最后更新索引。

原始21项严格检查结果（7项通过）及全部失败、超时和退出码保留。用户要求“先忽略gstream”，本轮排除12项PCM/WMV2媒体检查，排除不等于通过。图形/Unicode/中文字体/WinMM与实际800×600 explorer桌面共9项能力检查通过，来自8次不同执行；Proton64图形由同次桌面内smoke64成功报告补证，原plain CLI失败保留。CLI137沿用既有功能与清理证据口径，未修改生产退出/JIT。

- 镜像SHA256：`fc6ad4f58e9136639768edccaf27245bbea48fc20d403724c05035f72e9dcf99`。
- 资产索引提交：`f1205279d131df700b1b3aae2ad20c28bbac72ee`；索引SHA256：`d1d783bb6908cd4dae603d90124848fe3194cf7fa46e3f9e5a64a971c4b55ee1`。
- 原始运行报告SHA256：`dbbd0d83dabb7bbbfe7f8dd133150af71ded60d420fa22b3463c6b97ab8909b4`。
- 本轮范围资格SHA256：`fbe186b2a72db544ddbdbfff001ee46483c1158f914545fadf09145c94d52933`。
- 发布事务SHA256：`ad467230dd7ebb0ad9c225d6f0cf28513da196a4019c088c7c7d51081d057361`。

## 验证日志摘要

- `precommit-python-configured-tests.log`：SHA256 `39c573a0e7af6bb37481f94a063b02d705f61e5a5c742155e722304722c1c2fe`。
- `precommit-bundled-configured-tests.log`：SHA256 `8cfcda8f8ae78aadd74a0e8a6b33497aa15ffd4dfd0ebe784615aabb369b9f34`。
- `precommit-java-tests.log`：SHA256 `c3dd7743c02aeaac70e32307d3702e83d68c02cc1c3988c48667ac4f8d6a3d71`。

完整日志和回滚备份保留在本次任务artifacts。App界面、硬件GPU、产品文档一致性与最终验收保持Pending；本次不安装APK、不归档。
