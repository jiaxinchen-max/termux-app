package com.termux.x11.controller.inputcontrols;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.Nullable;

import com.termux.x11.controller.core.ArrayUtils;
import com.termux.x11.controller.math.Mathf;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

public class ExternalController implements GamepadSlot {
    public static final byte IDX_BUTTON_A = 0;
    public static final byte IDX_BUTTON_B = 1;
    public static final byte IDX_BUTTON_X = 2;
    public static final byte IDX_BUTTON_Y = 3;
    public static final byte IDX_BUTTON_L1 = 4;
    public static final byte IDX_BUTTON_R1 = 5;
    public static final byte IDX_BUTTON_SELECT = 6;
    public static final byte IDX_BUTTON_START = 7;
    public static final byte IDX_BUTTON_L3 = 8;
    public static final byte IDX_BUTTON_R3 = 9;
    public static final byte IDX_BUTTON_L2 = 10;
    public static final byte IDX_BUTTON_R2 = 11;
    private String name;
    private short vendorId;
    private short productId;
    private String id;
    private int deviceId = -1;
    private final ArrayList<ExternalControllerBinding> controllerBindings = new ArrayList<>();
    private final GamepadState state = new GamepadState();
    private GamepadVibration vibration;
    // 各摇杆轴的振动触发状态,索引与 InputControlsView.processJoystickInput 的 axes 一致。
    // 0 = 死区内未触发,-1/1 = 已在该方向触发过。摇杆是连续事件,振动需边缘触发,
    // 否则持续推动会以屏幕刷新率狂震。
    private final byte[] joystickAxisState = new byte[6];
    // L2/R2 动作上一次下发的按下状态。扳机按住期间运动事件会持续到来,绑定必须按边缘触发,
    // 否则鼠标键的引用计数(LorieView.injectPointerButtonPress)会被反复 +1,松手后按键卡死。
    // 索引 0 = L2,1 = R2
    private final boolean[] triggerBindingState = new boolean[2];
    private boolean processTriggerButtonOnMotionEvent = true;

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public short getVendorId() {
        return vendorId;
    }

    public void setVendorId(short vendorId) {
        this.vendorId = vendorId;
    }

    @Override
    public short getProductId() {
        return productId;
    }

    public void setProductId(short productId) {
        this.productId = productId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getDeviceId() {
        // 蓝牙手柄休眠重连后 descriptor 不变但 deviceId 会变,缓存必须校验:
        // 否则 profile.getController(deviceId) 永远匹配不上,动作绑定整体失效直到重启应用
        if (this.deviceId != -1) {
            InputDevice device = InputDevice.getDevice(this.deviceId);
            if (device == null || !device.getDescriptor().equals(id)) this.deviceId = -1;
        }
        if (this.deviceId == -1) {
            for (int deviceId : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(deviceId);
                if (device != null && device.getDescriptor().equals(id)) {
                    this.deviceId = deviceId;
                    break;
                }
            }
        }
        return this.deviceId;
    }

    public boolean isConnected() {
        for (int deviceId : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(deviceId);
            if (device != null && device.getDescriptor().equals(id)) return true;
        }
        return false;
    }

    public ExternalControllerBinding getControllerBinding(int keyCode) {
        for (ExternalControllerBinding controllerBinding : controllerBindings) {
            if (controllerBinding.getKeyCodeForAxis() == keyCode) return controllerBinding;
        }
        return null;
    }

    public ExternalControllerBinding getControllerBindingAt(int index) {
        return controllerBindings.get(index);
    }

    public void addControllerBinding(ExternalControllerBinding controllerBinding) {
        if (getControllerBinding(controllerBinding.getKeyCodeForAxis()) == null) controllerBindings.add(controllerBinding);
    }

    public int getPosition(ExternalControllerBinding controllerBinding) {
        return controllerBindings.indexOf(controllerBinding);
    }

    public void removeControllerBinding(ExternalControllerBinding controllerBinding) {
        controllerBindings.remove(controllerBinding);
    }

    public int getControllerBindingCount() {
        return controllerBindings.size();
    }

    public JSONObject toJSONObject() {
        try {
            if (controllerBindings.isEmpty()) return null;
            JSONObject controllerJSONObject = new JSONObject();
            controllerJSONObject.put("id", id);
            controllerJSONObject.put("name", name);

            JSONArray controllerBindingsJSONArray = new JSONArray();
            for (ExternalControllerBinding controllerBinding : controllerBindings) controllerBindingsJSONArray.put(controllerBinding.toJSONObject());
            controllerJSONObject.put("controllerBindings", controllerBindingsJSONArray);

            return controllerJSONObject;
        }
        catch (JSONException e) {
            return null;
        }
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return obj instanceof ExternalController ? ((ExternalController)obj).id.equals(this.id) : super.equals(obj);
    }

    @Override
    public GamepadState getGamepadState() {
        return state;
    }

    public byte[] getJoystickAxisState() {
        return joystickAxisState;
    }

    public boolean[] getTriggerBindingState() {
        return triggerBindingState;
    }

    @Override
    public GamepadVibration getGamepadVibration() {
        if (vibration == null) vibration = new GamepadVibration(id);
        return vibration;
    }

    private void processJoystickInput(MotionEvent event, int historyPos) {
        state.thumbLX = getCenteredAxis(event, MotionEvent.AXIS_X, historyPos);
        state.thumbLY = getCenteredAxis(event, MotionEvent.AXIS_Y, historyPos);
        state.thumbRX = getCenteredAxis(event, MotionEvent.AXIS_Z, historyPos);
        state.thumbRY = getCenteredAxis(event, MotionEvent.AXIS_RZ, historyPos);

        if (historyPos == -1) {
            float axisX = getCenteredAxis(event, MotionEvent.AXIS_HAT_X, historyPos);
            float axisY = getCenteredAxis(event, MotionEvent.AXIS_HAT_Y, historyPos);

            state.dpad[0] = axisY == -1.0f && Math.abs(state.thumbLY) < ControlElement.STICK_DEAD_ZONE;
            state.dpad[1] = axisX ==  1.0f && Math.abs(state.thumbLX) < ControlElement.STICK_DEAD_ZONE;
            state.dpad[2] = axisY ==  1.0f && Math.abs(state.thumbLY) < ControlElement.STICK_DEAD_ZONE;
            state.dpad[3] = axisX == -1.0f && Math.abs(state.thumbLX) < ControlElement.STICK_DEAD_ZONE;
        }
    }

    private void processTriggerButton(MotionEvent event) {
        state.triggerL = Mathf.clamp(Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)) - Mathf.EPSILON, 0.0f, 1.0f);
        state.triggerR = Mathf.clamp(Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)) - Mathf.EPSILON, 0.0f, 1.0f);
        // 注意:此处不写 state.buttons 的 L2/R2 位。XInput 模式的上报报文只通过 triggerL/R 浮点通道
        // 传递扳机(标准 DInput 模式在 GamepadHandler.writeStateToBuffer 里按需并入),
        // 扳机是否"按下"由读取侧叠加轴值判断,见 InputControlsView.onGenericMotionEvent
    }

    public boolean updateStateFromMotionEvent(MotionEvent event) {
        if (isJoystickDevice(event)) {
            if (processTriggerButtonOnMotionEvent) processTriggerButton(event);
            int historySize = event.getHistorySize();
            for (int i = 0; i < historySize; i++) processJoystickInput(event, i);
            processJoystickInput(event, -1);
            return true;
        }
        return false;
    }

    public boolean updateStateFromKeyEvent(KeyEvent event) {
        boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;
        int keyCode = event.getKeyCode();
        int buttonIdx = getButtonIdxByKeyCode(keyCode);
        if (buttonIdx != -1) {
            if (buttonIdx == IDX_BUTTON_L2 || buttonIdx == IDX_BUTTON_R2) {
                // 按住期间以按键事件为准(避免按键与轴双报把扳机值来回覆盖),
                // 松开后必须恢复轴读取,否则渐变扳机会退化成数字量、XInput 油门失效
                processTriggerButtonOnMotionEvent = !pressed;
            }
            state.setPressed(buttonIdx, pressed);
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                state.dpad[0] = pressed && Math.abs(state.thumbLY) < ControlElement.STICK_DEAD_ZONE;
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                state.dpad[1] = pressed && Math.abs(state.thumbLX) < ControlElement.STICK_DEAD_ZONE;
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                state.dpad[2] = pressed && Math.abs(state.thumbLY) < ControlElement.STICK_DEAD_ZONE;
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                state.dpad[3] = pressed && Math.abs(state.thumbLX) < ControlElement.STICK_DEAD_ZONE;
                return true;
        }
        return false;
    }

    public static ArrayList<ExternalController> getControllers() {
        int[] deviceIds = InputDevice.getDeviceIds();
        ArrayList<ExternalController> controllers = new ArrayList<>();
        for (int i = deviceIds.length-1; i >= 0; i--) {
            InputDevice device = InputDevice.getDevice(deviceIds[i]);
            if (isGameController(device)) {
                ExternalController controller = new ExternalController();
                controller.setId(device.getDescriptor());
                controller.setName(device.getName());
                controller.setVendorId((short)device.getVendorId());
                controller.setProductId((short)device.getProductId());
                controllers.add(controller);
            }
        }
        return controllers;
    }

    public static ExternalController getController(int deviceId) {
        for (ExternalController controller : getControllers()) {
            if (controller.getDeviceId() == deviceId) return controller;
        }
        return null;
    }

    public static ExternalController getController(String id) {
        for (ExternalController controller : getControllers()) if (controller.getId().equals(id)) return controller;
        return null;
    }

    public static void updateConnectedControllers(ArrayList<ExternalController> connectedControllers) {
        int[] deviceIds = InputDevice.getDeviceIds();
        for (int i = connectedControllers.size()-1; i >= 0; i--) {
            ExternalController controller = connectedControllers.get(i);
            boolean connected = ArrayUtils.contains(deviceIds, controller.getDeviceId());
            if (!connected) connectedControllers.remove(i);
        }

        for (int deviceId : deviceIds) {
            boolean skip = false;
            for (ExternalController controller : connectedControllers) {
                if (controller.deviceId == deviceId) {
                    skip = true;
                    break;
                }
            }
            if (skip) continue;

            InputDevice device = InputDevice.getDevice(deviceId);
            if (isGameController(device)) {
                ExternalController controller = new ExternalController();
                controller.deviceId = deviceId;
                controller.setId(device.getDescriptor());
                controller.setName(device.getName());
                controller.setVendorId((short)device.getVendorId());
                controller.setProductId((short)device.getProductId());
                connectedControllers.add(controller);
            }
        }
    }

    public static boolean isGameController(InputDevice device) {
        if (device == null) return false;
        String name = device.getName();
        if (name != null) {
            String lowerName = name.toLowerCase();
            if (lowerName.contains("uinput-fpc") || lowerName.contains("goodix_fp") || lowerName.contains("uinput-")) {
                return false;
            }
        }
        int sources = device.getSources();
        return !device.isVirtual() && ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
    }

    public static float getCenteredAxis(MotionEvent event, int axis, int historyPos) {
        if (axis == MotionEvent.AXIS_HAT_X || axis == MotionEvent.AXIS_HAT_Y) {
            float value = event.getAxisValue(axis);
            if (Math.abs(value) == 1.0f) return value;
        }
        else {
            InputDevice device = event.getDevice();
            InputDevice.MotionRange range = device.getMotionRange(axis, event.getSource());
            if (range != null) {
                float value = historyPos < 0 ? event.getAxisValue(axis) : event.getHistoricalAxisValue(axis, historyPos);
                // 摇杆轴统一用 STICK_DEAD_ZONE 做回中死区,与 dpad 门控阈值一致,
                // 避免轻微数值残留造成摇杆与方向键在边界上判定不一致
                if (axis == MotionEvent.AXIS_X || axis == MotionEvent.AXIS_Y
                        || axis == MotionEvent.AXIS_Z || axis == MotionEvent.AXIS_RZ) {
                    if (Math.abs(value) >= ControlElement.STICK_DEAD_ZONE) return value;
                }
                else if (Math.abs(value) > range.getFlat()) return value;
            }
        }
        return 0;
    }

    public static boolean isJoystickDevice(MotionEvent event) {
        return (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && event.getAction() == MotionEvent.ACTION_MOVE;
    }

    public static int getButtonIdxByKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return IDX_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_B:
                return IDX_BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_X:
                return IDX_BUTTON_X;
            case KeyEvent.KEYCODE_BUTTON_Y:
                return IDX_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_L1:
                return IDX_BUTTON_L1;
            case KeyEvent.KEYCODE_BUTTON_R1:
                return IDX_BUTTON_R1;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return IDX_BUTTON_SELECT;
            case KeyEvent.KEYCODE_BUTTON_START:
                return IDX_BUTTON_START;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return IDX_BUTTON_L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return IDX_BUTTON_R3;
            case KeyEvent.KEYCODE_BUTTON_L2:
                return IDX_BUTTON_L2;
            case KeyEvent.KEYCODE_BUTTON_R2:
                return IDX_BUTTON_R2;
            default:
                return -1;
        }
    }

    public static int getButtonIdxByName(String name) {
        switch (name) {
            case "A":
                return IDX_BUTTON_A;
            case "B":
                return IDX_BUTTON_B;
            case "X":
                return IDX_BUTTON_X;
            case "Y":
                return IDX_BUTTON_Y;
            case "L1":
                return IDX_BUTTON_L1;
            case "R1":
                return IDX_BUTTON_R1;
            case "SELECT":
                return IDX_BUTTON_SELECT;
            case "START":
                return IDX_BUTTON_START;
            case "L3":
                return IDX_BUTTON_L3;
            case "R3":
                return IDX_BUTTON_R3;
            case "L2":
                return IDX_BUTTON_L2;
            case "R2":
                return IDX_BUTTON_R2;
            default:
                return -1;
        }
    }
}
