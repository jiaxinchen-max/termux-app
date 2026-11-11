# 安装与打包设计

build_termux_package继续作为唯一生成入口，版本1.04.10、revision0，保留Depends: xkeyboard-config且无版本限定。使用当前源码编译shell-loader后打包；修正生成包的启动器执行权限，postinst安装失败即停。Pacman产物保留在构建输出，不再放入只支持APT的APK资产。

根目录install作为APK安装脚本源，生成时复制到assets。脚本set -eu，清理仅限本次暂存文件；先apt-get update，失败不进行安装；随后apt-get install -y --reinstall --allow-downgrades 本地绝对路径deb xkeyboard-config。显式请求无版本号的xkeyboard-config以使已安装旧版也更新到当前软件源/策略的最新候选；依赖仍在deb元数据中声明。软件源不可用时返回失败，不使用内嵌旧键盘包，不预先卸载桥接包。基础bootstrap仅有主源，因此缺少x11-repo时自动安装它并再次刷新索引，再安装桥接包；已安装则不增加该步骤。

终端重建入口和程序首次安装入口都只复制新deb及统一install。程序入口保留现有文件可用性判断，不根据版本自动升级；将固定两分钟等待替换为Process.waitFor，响应中断且保留非零状态诊断。无目录迁移、签名/摘要运行时校验或版本兼容。

验证使用临时本地APT仓库与隔离状态目录：真实APT更新和安装模拟验证首次安装、同版重装、升级、降级、旧键盘依赖升级；模拟仅安装步骤，避免写宿主dpkg。故障注入验证更新失败阻止安装、安装错误传播和暂存清理。核对生成deb版本、依赖、脚本/loader、权限，以及最终APK只含新deb。正常构建并核对原签名和非testOnly。

影响路径为生成脚本、安装脚本/资产及两个Java接线和相关测试；仓库protected-path策略及严格Harness脚本缺失，不能声称门禁通过。回滚恢复本变更文件和旧包，不删除用户数据；与在途启动脚本/偏好设置排序变更保持独立，不提交、推送或部署。
