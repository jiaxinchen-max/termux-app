# Proton控制器面板

## MODIFIED Requirements

### Requirement: 不支持可选力反馈不能阻止输入设备枚举

Proton控制面板必须允许不支持DIPROP_AUTOCENTER的有效DirectInput设备进入测试列表。不能因此宣称设备支持力反馈，也不能放宽真实输入初始化错误。

#### Scenario: 无力反馈的虚拟手柄
- 当设备创建、数据格式与协作级别设置成功，而设置AUTOCENTER返回不支持
- 则设备仍加入DInput列表，可继续采集状态。

#### Scenario: 必需输入步骤失败
- 当创建设备、数据格式或协作级别设置失败
- 则设备不加入可测试列表，不能伪造输入可用。

#### Scenario: 安装更新
- 当修复资产可用
- 则原组件版本和ID保持不变，用户通过现有重装入口取得修复，不触发自动升级或prefix重建。
