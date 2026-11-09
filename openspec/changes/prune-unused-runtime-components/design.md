# 设计

## 引用事实
审计当前30项运行组件。基础镜像是所有PRoot运行的直接依赖。fonts是Native fontconfig及prefix恢复的真实输入。scripts包含21个开始菜单文件与marlett等，prefix-apps提供主题及DirectX文件；五套Native manifest均有引用，不能因“预构建prefix”而误认为已内嵌全部内容。
Native Box64 0.4.4是自编译版本（2f130fa），ELF解释器与RUNPATH均指向Termux glibc。GameRuntimeOptionsView有逐游戏Box64选择入口，GAMES_CUSTOM_BOX64_ID贯穿配置与启动；当前内置目录版本没有独立选择ID，安装覆盖共享bin/box64。这是接线缺口，不能视为无用资产；默认依赖仍为0.2.7。独立Hangover包只服务旧配方与构建来源，v12设备仅解压预装镜像。线上组件目录落后于APK。

## 目录与选择器
运行目录只移除独立Hangover下载项后为29项。预装Hangover由预装策略显式提供选择行，不依赖可下载ComponentDescriptor，继续显示镜像未就绪/准备不完整/可用状态。Native Box64保留实际默认运行组件和自定义安装选择，保留0.4.4目录入口及探测分支。
RootfsSetupRecipe只接受当前v12；删除runtime-rootfs与runtime-rootfs-v11这两个APK资产目录与旧配方分支。专属旧流程测试替换为拒绝旧版本与当前流程回归；不删除当前恢复测试仍用到的构建/测试输入。

## 组件仓库清理
实现时获取组件仓库可写检出并读取该仓库规则。以当前APK目录减独立Hangover一项生成完整目录，更新catalog-v1.json及repository/index-v1.json；构建来源独立记录Hangover URL、版本、来源摘要。保留Hangover源包、符号、构建证据及其他有用途的资产。
远端Box64删除清单已撤销；保留0.4.4自编译资产。当前没有其他经证实无用的远端归档，不进行猜测性删除。目录同步按用户的Git/发布授权范围处理。

## 影响、回滚、验证
高风险点为资产目录和运行依赖变化。先保留本次修改前的局部快照，确保不覆盖已有未提交工作；保留远端资产ID/URL及待删除文件的备份，删除失败明确报告。回滚恢复本次目录及分支差异，并可从备份恢复同名资产；不通过运行时回退掩盖失败。
核对29项全部有运行/选择依赖，预装Hangover不出现在独立下载列表，Native字体和五套prefix逐文件引用闭包完整。执行相关JVM、当前Native/PRoot恢复与启动脚本回归；构建固定原名arm64普通debug APK并检查testOnly=false。用户手动安装验收。
严格Harness脚本、OpenSpec CLI和产品基线缺失，记录不可用；当前gh未认证，远端写入前检查已有凭据是否可用，需要时报告准确缺项。

## 用户补充与执行授权
用户明确要求清除过时资产，并将自动完成安装初始化的内部依赖从安装条目移除，强调开箱即用。按此授权实施原清理方案，并将fonts、scripts、prefix-apps、默认Native Box64及PRoot基础镜像标为内部自动依赖；保留Wine、图形和自定义Box64管理分类。Native网络构建只负责主机基础环境，保存游戏时自动准备运行组件与prefix；PRoot网络构建恢复基础镜像，游戏保存按选择补齐外置Wine/图形/prefix。隐藏不改变依赖解析。
