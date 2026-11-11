# Native GNOME 运行时依赖

## 新增要求：JavaScript 引擎与 ICU 的真实 ABI 匹配

Native GNOME 的 JavaScript 引擎必须与目标 ICU 主版本匹配，并满足既有 GJS 的真实共享库名称及符号要求。包管理器只声明包名不能代替 ELF 依赖核验。

### 场景：ICU79设备启动GNOME

- 当设备使用ICU79、GJS需要libmozjs-128.so时，提供的SpiderMonkey128必须链接真实ICU79共享库。
- 不允许通过降级全局ICU、跨ABI假链接、升级不同主版本引擎或切换渲染后端掩盖加载失败。
- 候选包必须保留既有Android Bionic目标、前缀、定制补丁及已使用的GJS符号。

### 场景：发布与验收

- 同名资产更新前后保持现有包版本、文件名和URL，不新增自动版本升级或历史缓存迁移。
- 静态检查、公开资产验证及APK构建通过不能记为Native GNOME正式App UI验收通过。
- APK维持原文件名和签名且不标记testOnly，大文件由用户手动下载安装。
