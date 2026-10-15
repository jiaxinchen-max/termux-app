package com.termux.x11.controller.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.os.VibrationAttributes;
import android.media.AudioManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.x11.controller.core.AppUtils;
import com.termux.x11.controller.inputcontrols.Binding;
import com.termux.x11.controller.inputcontrols.ControlElement;
import com.termux.x11.controller.inputcontrols.ControlsProfile;
import com.termux.x11.controller.inputcontrols.ExternalController;
import com.termux.x11.controller.inputcontrols.ExternalControllerBinding;
import com.termux.x11.controller.inputcontrols.GamepadState;
import com.termux.x11.controller.math.Mathf;
import com.termux.x11.controller.winhandler.WinHandler;
import com.termux.x11.controller.xserver.Pointer;
import com.termux.x11.controller.xserver.XKeycode;
import com.termux.x11.LorieView;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class InputControlsView extends View {
    public static final float DEFAULT_OVERLAY_OPACITY = 0.4f;
    private boolean editMode = false;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ColorFilter lightColorFilter;
    private ColorFilter darkColorFilter;
    private final Point cursor = new Point();
    private boolean readyToDraw = false;
    private boolean moveCursor = false;
    private boolean moveElement = false;
    private int snappingSize;
    private float startX;
    private float startY;
    private float offsetX;
    private float offsetY;
    private ControlElement selectedElement;
    private ControlsProfile profile;
    private float overlayOpacity = DEFAULT_OVERLAY_OPACITY;
    private TouchpadView touchpadView;
    private LorieView xServer;
    private final HashMap<Byte, Bitmap> icons = new HashMap<>();
    private Timer mouseMoveTimer;
    private final PointF mouseMoveOffset = new PointF();
    // 手柄摇杆各轴对鼠标移动的独立贡献,索引与 processJoystickInput 的 axes 一致。
    // 按轴分离:左摇杆 AXIS_X/Y 与右摇杆 AXIS_Z/RZ 分别累加,避免右摇杆回中时
    // 把左摇杆刚写入的偏移清零(两者默认绑定同一组 MOUSE_MOVE_* 动作)
    private final float[] joystickOffsetX = new float[6];
    private final float[] joystickOffsetY = new float[6];
    // 手柄映射到虚拟手柄摇杆(GAMEPAD_*_THUMB_*)的各来源独立贡献。索引 0~5 与 processJoystickInput 的 axes 一致,
    // 索引 6 留给非轴来源(按键/触控元素),与 gamepadDpadMask 的 bit6 对称。
    // 按来源分离:例如左摇杆 AXIS_Y 与十字键 HAT_Y 都绑定"左摇杆上下"时,十字键回中的释放事件
    // 不能把摇杆正在推动的值清零;按键与摇杆同时作用则是叠加而非互相覆盖
    private final float[] gamepadThumbLX = new float[7];
    private final float[] gamepadThumbLY = new float[7];
    private final float[] gamepadThumbRX = new float[7];
    private final float[] gamepadThumbRY = new float[7];
    // 虚拟手柄十字键按方向记录"哪些轴正按住"(bit 位对应 axes 索引,bit6 留给按键/触控元素),
    // 任一轴仍按住就不因另一轴回中而松开
    private final short[] gamepadDpadMask = new short[4];
    private boolean showTouchscreenControls = true;
    private boolean touchHapticFeedbackEnabled = false;
    private Vibrator vibrator;
    private InputManager inputManager;
    // 手柄掉电/断开时不会再有回中事件,残留的各轴贡献会让鼠标一直朝一个方向漂移,
    // 故设备移除即清零(键盘/鼠标拔出同样触发,归零偏移无副作用)
    private final InputManager.InputDeviceListener inputDeviceListener = new InputManager.InputDeviceListener() {
        @Override
        public void onInputDeviceAdded(int deviceId) {}

        @Override
        public void onInputDeviceChanged(int deviceId) {}

        @Override
        public void onInputDeviceRemoved(int deviceId) {
            post(InputControlsView.this::resetStuckInputState);
        }
    };

    public InputControlsView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setHapticFeedbackEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager)context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = manager != null ? manager.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        setBackgroundColor(0x00000000);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
    }

    public boolean isEditMode() {
        return editMode;
    }

    public void setOverlayOpacity(float overlayOpacity) {
        this.overlayOpacity = overlayOpacity;
    }

    public float getOverlayOpacity() {
        return overlayOpacity;
    }

    public int getSnappingSize() {
        return snappingSize;
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();

        if (width == 0 || height == 0) {
            readyToDraw = false;
            return;
        }

        snappingSize = Math.max(width, height) / 100;
        readyToDraw = true;

        if (editMode) {
            drawGrid(canvas);
            drawCursor(canvas);
        }

        if (profile != null) {
            if (!profile.isElementsLoaded()) profile.loadElements(this);
            List<ControlElement> elements = profile.getElements();
            if (touchpadView != null && elements.isEmpty()) touchpadView.setPointerButtonRightEnabled(true);
            if (showTouchscreenControls) for (ControlElement element : elements) element.draw(canvas);
        }

        super.onDraw(canvas);
    }

    private void drawGrid(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(snappingSize * 0.0625f);
        paint.setColor(0xff000000);
        canvas.drawColor(Color.BLACK);

        paint.setAntiAlias(false);
        paint.setColor(0xff303030);

        int width = getMaxWidth();
        int height = getMaxHeight();

        for (int i = 0; i < width; i += snappingSize) {
            canvas.drawLine(i, 0, i, height, paint);
            canvas.drawLine(0, i, width, i, paint);
        }

        float cx = Mathf.roundTo(width * 0.5f, snappingSize);
        float cy = Mathf.roundTo(height * 0.5f, snappingSize);
        paint.setColor(0xff424242);

        for (int i = 0; i < width; i += snappingSize * 2) {
            canvas.drawLine(cx, i, cx, i + snappingSize, paint);
            canvas.drawLine(i, cy, i + snappingSize, cy, paint);
        }

        paint.setAntiAlias(true);
    }

    private void drawCursor(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(snappingSize * 0.0625f);
        paint.setColor(0xffc62828);

        paint.setAntiAlias(false);
        canvas.drawLine(0, cursor.y, getMaxWidth(), cursor.y, paint);
        canvas.drawLine(cursor.x, 0, cursor.x, getMaxHeight(), paint);

        paint.setAntiAlias(true);
    }

    public synchronized boolean addElement() {
        if (editMode && profile != null) {
            ControlElement element = new ControlElement(this);
            element.setX(cursor.x);
            element.setY(cursor.y);
            profile.addElement(element);
            profile.save();
            selectElement(element);
            return true;
        }
        else return false;
    }

    public synchronized boolean removeElement() {
        if (editMode && selectedElement != null && profile != null) {
            profile.removeElement(selectedElement);
            selectedElement = null;
            profile.save();
            invalidate();
            return true;
        }
        else return false;
    }

    public ControlElement getSelectedElement() {
        return selectedElement;
    }

    private synchronized void deselectAllElements() {
        selectedElement = null;
        if (profile != null) {
            for (ControlElement element : profile.getElements()) element.setSelected(false);
        }
    }

    private void selectElement(ControlElement element) {
        deselectAllElements();
        if (element != null) {
            selectedElement = element;
            selectedElement.setSelected(true);
        }
        invalidate();
    }

    public synchronized ControlsProfile getProfile() {
        return profile;
    }

    public synchronized void setProfile(ControlsProfile profile) {
        // 先按旧配置清理:残留的摇杆偏移会让鼠标在新配置下继续漂移,
        // 卡住的扳机绑定也借旧配置的 controller 补发松开;
        // 计时器快照了旧配置的 cursorSpeed,取消后下次按下会按新配置重建
        cancelMouseMoveTimer();
        resetStuckInputState();

        this.profile = profile;
        if (profile != null) deselectAllElements();
    }

    /**
     * 归零手柄残留的输入状态:鼠标移动偏移、各轴对鼠标/虚拟手柄摇杆的贡献与十字键按下掩码,
     * 并把虚拟手柄状态复位后上报。用于手柄断开、窗口失焦、视图分离与切换配置,
     * 否则推摇杆期间掉线会让鼠标永久朝一个方向漂移。
     */
    public void resetStuckInputState() {
        mouseMoveOffset.set(0, 0);
        for (int i = 0; i < joystickOffsetX.length; i++) {
            joystickOffsetX[i] = 0;
            joystickOffsetY[i] = 0;
        }
        for (int i = 0; i < gamepadThumbLX.length; i++) {
            gamepadThumbLX[i] = 0;
            gamepadThumbLY[i] = 0;
            gamepadThumbRX[i] = 0;
            gamepadThumbRY[i] = 0;
        }
        for (int i = 0; i < gamepadDpadMask.length; i++) gamepadDpadMask[i] = 0;

        if (profile != null) {
            // 扳机按住时断开/失焦,不会再有松开事件:补发松开,否则鼠标键引用计数卡死、按键永久按住
            for (ExternalController controller : profile.getControllers()) {
                boolean[] triggerBindingState = controller.getTriggerBindingState();
                for (int i = 0; i < triggerBindingState.length; i++) {
                    if (!triggerBindingState[i]) continue;
                    triggerBindingState[i] = false;
                    int keyCode = i == 0 ? KeyEvent.KEYCODE_BUTTON_L2 : KeyEvent.KEYCODE_BUTTON_R2;
                    ExternalControllerBinding binding = controller.getControllerBinding(keyCode);
                    // xServer 尚未注入时不可能有已下发的按下,只清边缘状态
                    if (binding != null && xServer != null) handleInputEvent(binding.getBinding(), false);
                }

                byte[] axisState = controller.getJoystickAxisState();
                for (int i = 0; i < axisState.length; i++) axisState[i] = 0;
            }

            GamepadState state = profile.getGamepadState();
            state.thumbLX = 0;
            state.thumbLY = 0;
            state.thumbRX = 0;
            state.thumbRY = 0;
            state.triggerL = 0;
            state.triggerR = 0;
            state.buttons = 0;
            for (int i = 0; i < state.dpad.length; i++) state.dpad[i] = false;

            WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
            if (winHandler != null) winHandler.sendGamepadState();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (inputManager == null) inputManager = (InputManager)getContext().getSystemService(Context.INPUT_SERVICE);
        if (inputManager != null) inputManager.registerInputDeviceListener(inputDeviceListener, null);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (inputManager != null) {
            inputManager.unregisterInputDeviceListener(inputDeviceListener);
            inputManager = null;
        }
        resetStuckInputState();
        cancelMouseMoveTimer();
        super.onDetachedFromWindow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        // 失焦后摇杆回中事件与按键 ACTION_UP 可能收不到,恢复焦点前先清掉残留状态
        if (!hasWindowFocus) resetStuckInputState();
    }

    public boolean isShowTouchscreenControls() {
        return showTouchscreenControls;
    }

    public void setShowTouchscreenControls(boolean showTouchscreenControls) {
        this.showTouchscreenControls = showTouchscreenControls;
    }

    public boolean isTouchHapticFeedbackEnabled() {
        return touchHapticFeedbackEnabled;
    }

    public void setTouchHapticFeedbackEnabled(boolean touchHapticFeedbackEnabled) {
        this.touchHapticFeedbackEnabled = touchHapticFeedbackEnabled;
    }

    public void performTouchHapticFeedback() {
        if (touchHapticFeedbackEnabled && vibrator != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(VibrationEffect.createOneShot(30, 200), new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_MEDIA).build());
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(30, 200));
            } else {
                vibrator.vibrate(30);
            }
        }
    }

    private synchronized ControlElement intersectElement(float x, float y) {
        if (profile != null) {
            for (ControlElement element : profile.getElements()) {
                if (element.containsPoint(x, y)) return element;
            }
        }
        return null;
    }

    public Paint getPaint() {
        return paint;
    }

    public ColorFilter getLightColorFilter() {
        if (lightColorFilter == null) lightColorFilter = new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN);
        return lightColorFilter;
    }

    public ColorFilter getDarkColorFilter() {
        if (darkColorFilter == null) darkColorFilter = new PorterDuffColorFilter(0xff000000, PorterDuff.Mode.SRC_IN);
        return darkColorFilter;
    }

    public TouchpadView getTouchpadView() {
        return touchpadView;
    }

    public void setTouchpadView(TouchpadView touchpadView) {
        this.touchpadView = touchpadView;
    }

    public LorieView getXServer() {
        return xServer;
    }

    public void setXServer(LorieView xServer) {
        this.xServer = xServer;
        createMouseMoveTimer();
    }

    public int getMaxWidth() {
        return (int)Mathf.roundTo(getWidth(), snappingSize);
    }

    public int getMaxHeight() {
        return (int)Mathf.roundTo(getHeight(), snappingSize);
    }

    private void createMouseMoveTimer() {
        if (profile != null && mouseMoveTimer == null) {
            final float cursorSpeed = profile.getCursorSpeed();
            mouseMoveTimer = new Timer();
            mouseMoveTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    float jx = 0, jy = 0;
                    for (int i = 0; i < joystickOffsetX.length; i++) {
                        jx += joystickOffsetX[i];
                        jy += joystickOffsetY[i];
                    }
                    int dx = (int)((mouseMoveOffset.x + jx) * 10 * cursorSpeed);
                    int dy = (int)((mouseMoveOffset.y + jy) * 10 * cursorSpeed);
                    if (dx != 0 || dy != 0) xServer.injectPointerMoveDelta(dx, dy);
                }
            }, 0, 1000 / 60);
        }
    }

    private void cancelMouseMoveTimer() {
        if (mouseMoveTimer != null) {
            mouseMoveTimer.cancel();
            mouseMoveTimer.purge();
            mouseMoveTimer = null;
        }
    }

    private boolean processJoystickInput(ExternalController controller) {
        ExternalControllerBinding controllerBinding;
        final int[] axes = {MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y};
        GamepadState state = controller.getGamepadState();
        final float[] values = {state.thumbLX, state.thumbLY, state.thumbRX, state.thumbRY, state.getDPadX(), state.getDPadY()};
        boolean consumed = false;

        // 每轴状态:0 = 死区内(未触发), -1/1 = 已在该方向触发过振动。
        // 状态随 controller 存放,手柄断开即随之释放
        byte[] axisState = controller.getJoystickAxisState();

        for (byte i = 0; i < axes.length; i++) {
            boolean beyondDeadZone = Math.abs(values[i]) > ControlElement.STICK_DEAD_ZONE;
            if (beyondDeadZone) {
                byte sign = Mathf.sign(values[i]);
                controllerBinding = controller.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(axes[i], sign));
                if (controllerBinding != null) {
                    // 仅在跨越死区(静止→推动)或左右/上下换向时震一次;
                    // 持续推动期间不再重复,避免以刷新率连续振动
                    if (axisState[i] != sign) {
                        performTouchHapticFeedback();
                        axisState[i] = sign;
                    }
                    handleInputEvent(controllerBinding.getBinding(), true, values[i], i);
                    consumed = true;
                }
            }
            else {
                axisState[i] = 0;
                controllerBinding = controller.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(axes[i], (byte) 1));
                if (controllerBinding != null) {
                    handleInputEvent(controllerBinding.getBinding(), false, values[i], i);
                    consumed = true;
                }
                controllerBinding = controller.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(axes[i], (byte)-1));
                if (controllerBinding != null) {
                    handleInputEvent(controllerBinding.getBinding(), false, values[i], i);
                    consumed = true;
                }
            }
        }
        return consumed;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (!editMode && profile != null) {
            ExternalController controller = profile.getController(event.getDeviceId());
            if (controller != null && controller.updateStateFromMotionEvent(event)) {
                GamepadState state = controller.getGamepadState();
                ExternalControllerBinding controllerBinding;
                boolean consumed = false;

                // 扳机按下判定要叠加轴值:部分手柄只以 AXIS_BRAKE/AXIS_GAS 上报扳机,不发按键事件。
                // 轴值需过死区才算按下(只减过 EPSILON 的话,静止噪声会让边缘反复跳变)。
                // 且只在状态变化时下发绑定:按住扳机期间运动事件持续到来,重复下发按下会使
                // LorieView 鼠标键引用计数不断 +1,松手后按键卡死
                boolean[] triggerBindingState = controller.getTriggerBindingState();
                boolean l2Pressed = state.isPressed(ExternalController.IDX_BUTTON_L2) || state.triggerL > ControlElement.STICK_DEAD_ZONE;
                boolean r2Pressed = state.isPressed(ExternalController.IDX_BUTTON_R2) || state.triggerR > ControlElement.STICK_DEAD_ZONE;
                if (controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2) != null ||
                    controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2) != null) consumed = true;

                if (l2Pressed != triggerBindingState[0]) {
                    triggerBindingState[0] = l2Pressed;
                    controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2);
                    if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(), l2Pressed);
                }

                if (r2Pressed != triggerBindingState[1]) {
                    triggerBindingState[1] = r2Pressed;
                    controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2);
                    if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(), r2Pressed);
                }

                // 未绑定任何轴/扳机时返回 false,让事件继续流向 winHandler 走手柄透传,
                // 与 onKeyEvent 的条件消费语义保持一致
                if (processJoystickInput(controller)) consumed = true;
                return consumed;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (editMode && readyToDraw) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    startX = event.getX();
                    startY = event.getY();

                    ControlElement element = intersectElement(startX, startY);
                    moveCursor = true;
                    moveElement = false;
                    if (element != null) {
                        offsetX = startX - element.getX();
                        offsetY = startY - element.getY();
                        moveCursor = false;
                    }

                    selectElement(element);
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (selectedElement != null) {
                        float dx = Math.abs(event.getX() - startX);
                        float dy = Math.abs(event.getY() - startY);

                        if (dx >= TouchpadView.MAX_TAP_TRAVEL_DISTANCE || dy >= TouchpadView.MAX_TAP_TRAVEL_DISTANCE) moveElement = true;

                        if (moveElement) {
                            selectedElement.setX((int)Mathf.roundTo(event.getX() - offsetX, snappingSize));
                            selectedElement.setY((int)Mathf.roundTo(event.getY() - offsetY, snappingSize));
                            invalidate();
                        }
                    }
                    break;
                }
                case MotionEvent.ACTION_UP: {
                    if (selectedElement != null && profile != null && moveElement) profile.save();
                    if (moveCursor) cursor.set((int)Mathf.roundTo(event.getX(), snappingSize), (int)Mathf.roundTo(event.getY(), snappingSize));
                    invalidate();
                    break;
                }
            }
        }

        if (!editMode && profile != null) {
            int actionIndex = event.getActionIndex();
            int pointerId = event.getPointerId(actionIndex);
            int actionMasked = event.getActionMasked();
            boolean handled = false;

            switch (actionMasked) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    float x = event.getX(actionIndex);
                    float y = event.getY(actionIndex);

                    touchpadView.setPointerButtonLeftEnabled(true);
                    for (ControlElement element : profile.getElements()) {
                        if (element.handleTouchDown(pointerId, x, y)) handled = true;
                        //可能为了防误触，以后鼠标触摸扩展需要关掉这个
                        //if (element.getBindingAt(0) == Binding.MOUSE_LEFT_BUTTON && element.getLastBindingIndex() == 0) {
                        //    touchpadView.setPointerButtonLeftEnabled(false);
                        //}
                    }
                    if (!handled) touchpadView.onTouchEvent(event);
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    for (byte i = 0, count = (byte)event.getPointerCount(); i < count; i++) {
                        float x = event.getX(i);
                        float y = event.getY(i);
                        int movePointerId = event.getPointerId(i);

                        handled = false;
                        for (ControlElement element : profile.getElements()) {
                            if (element.handleTouchMove(movePointerId, x, y)) handled = true;
                        }
                        if (!handled) touchpadView.onTouchEvent(event);
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float x = event.getX(actionIndex);
                    float y = event.getY(actionIndex);
                    for (ControlElement element : profile.getElements()) {
                        if (element.handleTouchUp(pointerId, x, y)) handled = true;
                    }
                    if (!handled) touchpadView.onTouchEvent(event);
                    break;
                }
            }
        }
        return true;
    }

    public boolean onKeyEvent(KeyEvent event) {
        if (profile != null && event.getRepeatCount() == 0) {
            ExternalController controller = profile.getController(event.getDeviceId());
            if (controller != null) {
                // L2/R2 按键事件到来时同步边缘状态,避免同时上报扳机按键与轴事件的手柄
                // 在 onGenericMotionEvent 中再边缘触发一次重复按下/松开
                int buttonIdx = ExternalController.getButtonIdxByKeyCode(event.getKeyCode());
                if (buttonIdx == ExternalController.IDX_BUTTON_L2 || buttonIdx == ExternalController.IDX_BUTTON_R2) {
                    controller.getTriggerBindingState()[buttonIdx - ExternalController.IDX_BUTTON_L2] = event.getAction() == KeyEvent.ACTION_DOWN;
                }

                ExternalControllerBinding controllerBinding = controller.getControllerBinding(event.getKeyCode());
                if (controllerBinding != null) {
                    int action = event.getAction();

                    if (action == KeyEvent.ACTION_DOWN) {
                        performTouchHapticFeedback();
                        handleInputEvent(controllerBinding.getBinding(), true);
                    }
                    else if (action == KeyEvent.ACTION_UP) {
                        handleInputEvent(controllerBinding.getBinding(), false);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** 写入/清除某来源对虚拟手柄摇杆的贡献并返回各来源合计(axisIndex<0 的按键/触控元素落入末位槽位) */
    private static float updateThumbContribution(float[] contributions, byte axisIndex, boolean isActionDown, float offset) {
        int slot = axisIndex < 0 ? contributions.length - 1 : axisIndex;
        contributions[slot] = isActionDown ? offset : 0;
        float sum = 0;
        for (float value : contributions) sum += value;
        // 多来源同向相加可能超过 1,上报时 thumb * Short.MAX_VALUE 强转 short 会溢出成反向
        return Mathf.clamp(sum, -1.0f, 1.0f);
    }

    /** 按轴位掩码记录虚拟手柄十字键的按下状态,返回该方向是否仍被任一轴/来源按住 */
    private boolean updateDpadContribution(byte dpadIndex, byte axisIndex, boolean isActionDown) {
        int bit = 1 << (axisIndex < 0 ? 6 : axisIndex);
        short mask = gamepadDpadMask[dpadIndex];
        if (isActionDown) mask |= bit;
        else mask &= ~bit;
        gamepadDpadMask[dpadIndex] = mask;
        return mask != 0;
    }

    public void handleInputEvent(Binding[] bindings, boolean isActionDown) {
        for (Binding binding : bindings) {
            if (binding != Binding.NONE) handleInputEvent(binding, isActionDown);
        }
    }

    public void handleInputEvent(Binding binding, boolean isActionDown) {
        // 二值来源(按键/开关/快捷点击)没有模拟量,绑到虚拟手柄摇杆方向时按满偏 ±1 处理,
        // 否则写入 0 等于没绑(与 MOUSE_MOVE_* 的 fallback 语义一致)。
        // 模拟量来源(触控摇杆/触控板)走带 offset 的三参重载,居中时传 0 是正确语义,不能兜底
        handleInputEvent(binding, isActionDown, isActionDown ? getBinaryThumbOffset(binding) : 0);
    }

    /** 按键等二值来源绑定虚拟手柄摇杆方向时的满偏值;其余绑定返回 0,行为不变 */
    private static float getBinaryThumbOffset(Binding binding) {
        switch (binding) {
            case GAMEPAD_LEFT_THUMB_UP:
            case GAMEPAD_LEFT_THUMB_LEFT:
            case GAMEPAD_RIGHT_THUMB_UP:
            case GAMEPAD_RIGHT_THUMB_LEFT:
                return -1.0f;
            case GAMEPAD_LEFT_THUMB_DOWN:
            case GAMEPAD_LEFT_THUMB_RIGHT:
            case GAMEPAD_RIGHT_THUMB_DOWN:
            case GAMEPAD_RIGHT_THUMB_RIGHT:
                return 1.0f;
            default:
                return 0;
        }
    }

    public void handleInputEvent(Binding binding, boolean isActionDown, float offset) {
        handleInputEvent(binding, isActionDown, offset, (byte)-1);
    }

    public void handleInputEvent(Binding binding, boolean isActionDown, float offset, byte axisIndex) {
        if (binding.isGamepad()) {
            WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
            GamepadState state = profile.getGamepadState();

            int buttonIdx = binding.ordinal() - Binding.GAMEPAD_BUTTON_A.ordinal();
            if (buttonIdx <= 11) {
                state.setPressed(buttonIdx, isActionDown);
            }
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_UP || binding == Binding.GAMEPAD_LEFT_THUMB_DOWN) {
                state.thumbLY = updateThumbContribution(gamepadThumbLY, axisIndex, isActionDown, offset);
            }
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_LEFT || binding == Binding.GAMEPAD_LEFT_THUMB_RIGHT) {
                state.thumbLX = updateThumbContribution(gamepadThumbLX, axisIndex, isActionDown, offset);
            }
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_UP || binding == Binding.GAMEPAD_RIGHT_THUMB_DOWN) {
                state.thumbRY = updateThumbContribution(gamepadThumbRY, axisIndex, isActionDown, offset);
            }
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT || binding == Binding.GAMEPAD_RIGHT_THUMB_RIGHT) {
                state.thumbRX = updateThumbContribution(gamepadThumbRX, axisIndex, isActionDown, offset);
            }
            else if (binding == Binding.GAMEPAD_DPAD_UP || binding == Binding.GAMEPAD_DPAD_RIGHT ||
                     binding == Binding.GAMEPAD_DPAD_DOWN || binding == Binding.GAMEPAD_DPAD_LEFT) {
                byte dpadIndex = (byte)(binding.ordinal() - Binding.GAMEPAD_DPAD_UP.ordinal());
                state.dpad[dpadIndex] = updateDpadContribution(dpadIndex, axisIndex, isActionDown);
            }

            if (winHandler != null) winHandler.sendGamepadState();
        }
        else {
            float fallback;
            if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                fallback = binding == Binding.MOUSE_MOVE_LEFT ? -1 : 1;
                if (axisIndex >= 0) joystickOffsetX[axisIndex] = isActionDown ? (offset != 0 ? offset : fallback) : 0;
                else mouseMoveOffset.x = isActionDown ? (offset != 0 ? offset : fallback) : 0;
                if (isActionDown) createMouseMoveTimer();
            }
            else if (binding == Binding.MOUSE_MOVE_DOWN || binding == Binding.MOUSE_MOVE_UP) {
                fallback = binding == Binding.MOUSE_MOVE_UP ? -1 : 1;
                if (axisIndex >= 0) joystickOffsetY[axisIndex] = isActionDown ? (offset != 0 ? offset : fallback) : 0;
                else mouseMoveOffset.y = isActionDown ? (offset != 0 ? offset : fallback) : 0;
                if (isActionDown) createMouseMoveTimer();
            }
            else if (binding == Binding.MOUSE_SWAPL_R_BUTTONS) {
                if (isActionDown && touchpadView != null) touchpadView.setSwapMouseButtons();
            }
            else if (binding == Binding.MOUSE_SHOW_INPUT_METHOD) {
                if (isActionDown) AppUtils.showKeyboard((AppCompatActivity)getContext());
            }
            else if (binding.keycode.isCustomKey()) {
                if (!isActionDown) handleCommandKeyEvent(binding);
            }
            else {
                Pointer.Button pointerButton = binding.getPointerButton();
                if (isActionDown) {
                    if (pointerButton != null) {
                        xServer.injectPointerButtonPress(pointerButton);
                    }
                    else xServer.injectKeyPress(binding.keycode);
                }
                else {
                    if (pointerButton != null) {
                        xServer.injectPointerButtonRelease(pointerButton);
                    }
                    else xServer.injectKeyRelease(binding.keycode);
                }
            }
        }
    }

    public Bitmap getIcon(byte id) {
        Bitmap bitmap = icons.get(id);
        if (bitmap == null) {
            Context context = getContext();
            try (InputStream is = context.getAssets().open("inputcontrols/icons/"+id+".png")) {
                bitmap = BitmapFactory.decodeStream(is);
                if (bitmap != null) icons.put(id, bitmap);
            }
            catch (IOException e) {}
        }
        return bitmap;
    }

    private void handleCommandKeyEvent(Binding binding) {
        Context context = getContext();
        if (binding == Binding.KEY_VOL_UP || binding == Binding.KEY_VOL_DOWN) {
            AudioManager audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            if (binding == Binding.KEY_VOL_UP) {
                audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_PLAY_SOUND);
            }
            else audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_PLAY_SOUND);
        }
    }
}
