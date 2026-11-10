# 修复Proton控制面板DirectInput枚举

## 已核实的问题

设备Proton11的joy.cpl将设置可选力反馈属性DIPROP_AUTOCENTER的失败视为整个设备失败，因而移除不支持力反馈的虚拟手柄。Wine9.3面板没有这项强制检查；配置mapper无法修复该枚举分支。此前配置修复的实际验收失败，不能标记整个问题解决。

## 拟实施修复

回移Wine官方提交70ed59e3f4570e61aea2f36a4d18b9c6eaa0f922的一行修复，重新构建Native/PRoot Proton11的x86_64和i386面板。更新原运行时包，保留现有版本号、组件ID和手动安装/重装方式。不改手柄能力声明，不伪造力反馈，不重建prefix，不改Wine9.3。

API文档为N/A，无HTTP契约变更。产品文档为Required，验收后更新docs/product/game-controllers.md，当前一致性Pending。

证据：/home/user/workspace/artifacts/controller-runtime-investigation-20261110/findings.md；精确上游补丁位于同目录joy-cpl-autocenter-upstream.patch。
