# 设计

## 目录与选择
目录新增两项，版本号固定0.3.8，组件修订号1，使用已发布的独立Release；大小分别5196672和6185748字节。沿用现有目录字段，不引入新的摘要或签名校验逻辑。NativeBox64Versions新增精确ID；PRoot集中识别内置0.3.8和0.4.5版本，供编辑、选择验证、绑定解析与维护共用。

## 安装和运行
Native复用现有按组件ID安装到usr/glibc/components/<id>的流程，补全安装探测与native_runtime_components.sh识别。PRoot按ID独立安装并绑定到/opt/box64/<id>；更新ProotComponentBinding、ProotBindingResolver、CustomComponentSelection、ComponentMaintenance及脚本白名单。检查start_rootfs_game.sh和rootfs_prefix_warmup.sh中配套库路径，显式选择0.3.8时必须随选中程序绑定对应版本的lib/x86_64-linux-gnu。无显式选择时沿用原默认值。

## 风险、回滚和边界
风险是UI可选但运行仍指向旧版，以及不正确的后端混用。以针对版本选择、绑定、库路径及维护的测试覆盖；保留其他修改，不改默认值。回滚仅撤销本次新增项和识别分支，已发布资产保留。本轮不修改prefix、不新增兼容迁移，不自动重建或回退。没有仓库保护路径规则文件，启动脚本与绑定代码作为需重点审阅的运行关键路径。

## 验证
核对目录与已发布包的元数据、后端过滤、保存与回读、缺包失败、对应路径、安装卸载重装隔离；运行相关Java与脚本测试及arm64 APK构建，核对候选资产已进入APK以及testOnly=false。不声称本轮已验证设备游戏运行。
