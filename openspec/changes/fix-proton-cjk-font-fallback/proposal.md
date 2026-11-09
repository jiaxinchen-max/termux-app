# 修复 Proton 中文字体回退

## 问题与证据
用户确认上一轮画面位置已正常，新增报告 Wine 9.3 文字正常而 Proton 文字为方块。用户尚未说明是否仅中文异常。下载当前目录中的两份已发布 Native prefix 资产并只读比较：两者均声明复制 Noto Sans/Serif CJK；Proton user.reg 额外将 Segoe UI、Segoe UI Semibold、Verdana、Palatino Linotype 替换为 Times New Roman，Wine 9.3 无此替换。实际 Times.TTF 包含 A/1 字形、不含“中文测试”字形；这些字体及 Times New Roman 没有对应的 FontLink。两份模板也残留构建机/PRoot 字体绝对路径。以上证明配置缺口，不等于已复现或确定唯一根因。

## 目标及范围
修正 Proton 预构建模板的字体替换和中文链接，移除其指向不存在位置的字体注册，保留有效本地字体和现有 Noto 资产。Wine 9.3 不变。保持独立 prefix，启动不执行 wineboot、重建或后端回退，不扩大 APK 内字体负载，不恢复摘要/签名运行门禁。旧 prefix 不做迁移；设备由用户手动以新导入游戏验证。

## 文档与授权
api_docs=N/A：无 HTTP 契约变化。product_docs=Required：规划 docs/product/runtime-components.md 的 Proton 字体能力说明，consistency=Pending，用户接受后才定稿。用户已回复“批准执行”，讨论补字体后进一步确认“那还是重映射吧”，本次实施原已批准方案；不连接设备，不 stage/commit/push/发布。
