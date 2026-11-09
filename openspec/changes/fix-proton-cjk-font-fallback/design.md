# 设计

## 分析结论的边界
通过公开资产比对和实际 TTF cmap 检查确认缺口。上游 Valve bleeding-edge win32u/font.c 表明 Replacements 在字体不存在时提供整个字体家族映射，不能把它理解为中文缺字自动回退；上游源码仅解释机制，不声称与打包二进制完全一致。现有 ensure_games_wine_fonts 只检查 Noto 文件及两行注册项，不能证明 GDI/DirectWrite 已正确加载。

## 计划实现
在资产构建阶段规范化 Proton 注册表：Segoe UI/Segoe UI Semibold 使用现有 Noto Sans CJK，保留已有 Verdana 等字体的拉丁字形并为相关无衬线字体加入 Noto Sans CJK 链接，为 Times New Roman/Palatino 加入 Noto Serif CJK 链接。核对所用字体文件名、family 名、32/64 位注册视图和 steamuser 配置。清理构建机和 PRoot 绝对字体路径，仅保留实际打包或共享提供的有效映射，不靠启动时修补。禁止笼统删除有效字体或引入完整字体/LLVM 软件包。

产出独立新版 Proton prefix 资产，更新其目录版本及 APK bundle 描述；不改变 Wine 9.3 资产，不将压缩 prefix 嵌入 APK。若实际运行证据显示字体后端加载失败，记录并修复具体依赖或路径，不能仅凭静态检查宣称解决。

## 验证和交付
对真实新模板进行合并后的注册表解析、字体目标解析和 cmap 检查，覆盖 Segoe UI、Tahoma、Arial、Verdana、Times New Roman、中英文与数字。执行既有 Native 模板恢复/启动回归及相关目录测试。构建固定名称、arm64、非 test-only APK。用户手动验证同一游戏 Wine 9.3/Proton、桌面/菜单/游戏正文；若仍方块则保持未解决，按日志确定实际字体请求和加载失败。安装和设备测试不由代理执行。

## 风险与回退
字体映射影响字宽和排版，衬线/无衬线分开处理。新模板不自动替换历史 prefix，因此验证使用新导入游戏。发现失败立即终止本次验证，不切换运行时、不 wineboot。通过恢复旧 Proton 描述/资产版本撤销本次改动，不触碰用户游戏。发布资产需要用户确认具体发布范围。

## 实施发现
恢复器曾把 Native prefix 组件凭据版本固定为 1。为接入新版字体资产，模板声明 prefixComponentVersion=2，恢复器依此检查版本；未改动的其他模板保留 schema 3 的既有默认版本 1。这是当前受支持资产格式的版本解析，不新增历史 prefix 迁移或重建。

## 发布授权更新
用户回复“直接替换吧”，覆盖之前保留旧发布资产的候选安排：新包替换既有 native_prefix_proton_11.0-v1 下载文件，App 目录版本为 2；无新增 Release。先核验临时上传再替换，最终公网下载一致，旧字节仅留本地恢复。旧 prefix 仍不迁移。Native 游戏实际共用 default 容器，验收前需由用户手动清理该容器的 Proton prefix，再保存配置从新版模板恢复；重新导入不能独自达到这一目的。
