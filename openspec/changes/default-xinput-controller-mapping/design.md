# 设计

RuntimeProfilePresets.create 的 COMPATIBILITY 分支从 dinput 改为 xinput，与 RECOMMENDED、STABLE 保持一致。Native 导入使用推荐预设，PRoot 导入继承该预设的 inputProfileId；这两条路径无需业务代码调整。

接口是否启用与轴键布局独立。RuntimeProfile 构造器继续默认启用两个接口；控制器 DLL 加载顺序继续为 native,builtin。不得遍历已有配置写入新默认，不增加迁移、回退或初始化逻辑。CUSTOM 继续保留输入配置；已有 DirectInput 配置继续有效。

验证现有预设配置、Native／PRoot 导入、显式映射持久化与下次启动快照测试。仅扩展现有默认配置测试，不增加重复测试。构建原名 arm64 调试 APK，保持原签名及非 testOnly 属性，供用户手动安装，不通过 ADB 安装。

无受保护运行时资产变更。回滚只需恢复兼容预设原默认值及配套断言；不会改写任何存量用户数据。用户“好，默认配置按照你这个建议整”授权本方案实施，后续“推送一下代码”授权本变更提交并推送到原开发分支；产品验收和归档仍待单独授权。
