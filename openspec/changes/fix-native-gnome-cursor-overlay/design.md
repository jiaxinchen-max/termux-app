# 设计

## 根因与证据
AndroidCursor.cursor_update 只实现纹理加载并返回 FALSE；上游 MetaCursorRenderer.meta_cursor_renderer_update_cursor 将返回值作为 needs_overlay，meta_cursor_renderer_update_stage_overlay 将其传入 meta_overlay_set_visible。上游 X11 在需要软件指针时返回 TRUE，与本地实现相反。源码证据位于 artifacts/native-gnome-cursor，静态根因已确认，设备复现待 ADB 授权。

## 修复
保留纹理加载，在需要软件指针时返回正确的启用值，并保持无指针精灵时的隐藏语义。不得强行全局显示指针，不改变 Mutter 对真实触摸输入的隐藏策略。重新构建 Mutter 组件并核对安装文件与来源，更新实际使用的组件版本/校验和，必要时生成普通 debug APK。

Activity、Service、任务状态、shell 启动、存储和输入协议无需改变。X11/PRoot 使用原路径；Android 原生 compositor 的软件图层负责指针。组件下载需验证真实包内容与校验和，不能仅修改脚本假定设备已更新。

## 风险及授权
潜在高风险路径：tools/termux-usb-bridge/packaging/gnome/mutter/android/meta-backend-android.c 及后续组件清单。仓库没有提供 protected policy 清单，policy id 无法确定，按共享渲染代码风险记录，实施前需人工确认。验证：构建成功、原生移动与点击、指针位置/形状、触摸后重新鼠标输入、PRoot 对照和冷启动。回滚源文件、组件版本和 APK。设备当前 ADB unauthorized，未完成运行验证；严格 Harness 脚本缺失，状态 Unavailable。
