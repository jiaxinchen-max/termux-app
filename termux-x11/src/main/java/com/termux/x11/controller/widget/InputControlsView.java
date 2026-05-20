package com.termux.x11.controller.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.termux.x11.controller.inputcontrols.Binding;
import com.termux.x11.controller.inputcontrols.ControlElement;
import com.termux.x11.controller.inputcontrols.ControlsProfile;
import com.termux.x11.controller.inputcontrols.ExternalController;
import com.termux.x11.controller.inputcontrols.ExternalControllerBinding;
import com.termux.x11.controller.inputcontrols.GamepadState;
import com.termux.x11.controller.core.TermuxConfigFiles;
import com.termux.x11.controller.math.Mathf;
import com.termux.x11.controller.winhandler.WinHandler;
import com.termux.x11.controller.xserver.Pointer;
import com.termux.x11.LorieView;
import com.termux.x11.controller.xserver.XKeycode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class InputControlsView extends View {
    public interface PassthroughTouchDispatcher {
        boolean dispatchTouchEvent(MotionEvent event);
    }

    public static final float DEFAULT_OVERLAY_OPACITY = 0.4f;
    public static final byte MAX_TAP_TRAVEL_DISTANCE = 10;
    public static final short MAX_TAP_MILLISECONDS = 200;
    public static final float CURSOR_ACCELERATION = 1.25f;
    public static final byte CURSOR_ACCELERATION_THRESHOLD = 6;
    private boolean editMode = false;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final ColorFilter colorFilter = new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN);
    private final Point cursor = new Point();
    private boolean readyToDraw = false;
    private boolean moveCursor = false;
    private int snappingSize;
    private int controlLayoutWidth;
    private int controlLayoutHeight;
    private float controlLayoutScale = 1.0f;
    private float controlLayoutOffsetX;
    private float controlLayoutOffsetY;
    private float offsetX;
    private float offsetY;
    private ControlElement selectedElement;
    private ControlsProfile profile;
    private float overlayOpacity = DEFAULT_OVERLAY_OPACITY;
    private TouchpadView touchpadView;
    private LorieView xServer;
    private PassthroughTouchDispatcher passthroughTouchDispatcher;
    private final Bitmap[] icons = new Bitmap[17];
    private Timer mouseMoveTimer;
    private final PointF mouseMoveOffset = new PointF();
    private boolean showTouchscreenControls = true;
    private Map<String, Integer> counterMap = new HashMap<>();
    private int controlPointerIdBits = 0;

    public void counterMapIncrease(String iconId) {
        Integer v = counterMap.get(iconId);
        if (v == null) {
            v = new Integer(0);
        }
        v++;
        counterMap.put(iconId, v);
    }

    public void counterMapDecrease(String iconId) {
        Integer v = counterMap.get(iconId);
        if (v != null) {
            v--;
            counterMap.put(iconId, v);
        }
    }

    public boolean counterMapZero(String iconId) {
        Integer v = counterMap.get(iconId);
        if (v == null) {
            return true;
        }
        if (v <= 0) {
            return true;
        }
        return false;
    }

    public InputControlsView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(0x00000000);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
    }

    public void setOverlayOpacity(float overlayOpacity) {
        this.overlayOpacity = overlayOpacity;
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
        updateControlLayoutGeometry(width, height);

        readyToDraw = true;

        canvas.save();
        applyControlLayoutTransform(canvas);
        if (editMode) {
            drawGrid(canvas);
            drawCursor(canvas);
        }
        if (profile != null) {
            if (!profile.isElementsLoaded()) {
                profile.loadElements(this);
            }
            if (showTouchscreenControls)
                for (ControlElement element : profile.getElements()) element.draw(canvas);
        }
        canvas.restore();

        super.onDraw(canvas);
    }

    private void updateControlLayoutGeometry(int width, int height) {
        int previousSnappingSize = snappingSize;
        int previousLayoutWidth = controlLayoutWidth;
        int previousLayoutHeight = controlLayoutHeight;

        controlLayoutWidth = Math.max(width, height);
        controlLayoutHeight = Math.min(width, height);
        snappingSize = Math.max(1, controlLayoutWidth / 100);
        controlLayoutWidth = Math.max(snappingSize, (int) Mathf.roundTo(controlLayoutWidth, snappingSize));
        controlLayoutHeight = Math.max(snappingSize, (int) Mathf.roundTo(controlLayoutHeight, snappingSize));
        controlLayoutScale = Math.min((float) width / controlLayoutWidth, (float) height / controlLayoutHeight);
        controlLayoutOffsetX = (width - controlLayoutWidth * controlLayoutScale) * 0.5f;
        controlLayoutOffsetY = (height - controlLayoutHeight * controlLayoutScale) * 0.5f;

        if (profile != null && profile.isElementsLoaded()
            && (snappingSize != previousSnappingSize
            || controlLayoutWidth != previousLayoutWidth
            || controlLayoutHeight != previousLayoutHeight)) {
            for (ControlElement element : profile.getElements()) {
                element.invalidateGeometry();
            }
        }
    }

    private void ensureControlLayoutGeometry() {
        int width = getWidth();
        int height = getHeight();
        if (width > 0 && height > 0)
            updateControlLayoutGeometry(width, height);
    }

    private void applyControlLayoutTransform(Canvas canvas) {
        canvas.translate(controlLayoutOffsetX, controlLayoutOffsetY);
        canvas.scale(controlLayoutScale, controlLayoutScale);
    }

    private float toControlLayoutX(float x) {
        return (x - controlLayoutOffsetX) / controlLayoutScale;
    }

    private float toControlLayoutY(float y) {
        return (y - controlLayoutOffsetY) / controlLayoutScale;
    }

    private float toViewX(float x) {
        return x * controlLayoutScale + controlLayoutOffsetX;
    }

    private float toViewY(float y) {
        return y * controlLayoutScale + controlLayoutOffsetY;
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
        } else return false;
    }

    public synchronized boolean removeElement() {
        if (editMode && selectedElement != null && profile != null) {
            profile.removeElement(selectedElement);
            selectedElement = null;
            profile.save();
            invalidate();
            return true;
        } else return false;
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
        if (profile != null) {
            this.profile = profile;
            deselectAllElements();
        } else this.profile = null;
    }

    public boolean isShowTouchscreenControls() {
        return showTouchscreenControls;
    }

    public void setShowTouchscreenControls(boolean showTouchscreenControls) {
        this.showTouchscreenControls = showTouchscreenControls;
    }

    public int getPrimaryColor() {
        return Color.argb((int) (overlayOpacity * 255), 255, 255, 255);
    }

    public int getSecondaryColor() {
        return Color.argb((int) (overlayOpacity * 255), 2, 119, 189);
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

    public Path getPath() {
        return path;
    }

    public ColorFilter getColorFilter() {
        return colorFilter;
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

    public void setPassthroughTouchDispatcher(PassthroughTouchDispatcher passthroughTouchDispatcher) {
        this.passthroughTouchDispatcher = passthroughTouchDispatcher;
    }

    private boolean dispatchPassthroughTouchEvent(MotionEvent event) {
        if (touchpadView != null && touchpadView.handlesPassthroughInput()) {
            return touchpadView.onTouchEvent(event);
        }
        if (passthroughTouchDispatcher != null) {
            return passthroughTouchDispatcher.dispatchTouchEvent(event);
        }
        return touchpadView != null && touchpadView.onTouchEvent(event);
    }

    private boolean dispatchPassthroughTouchEvent(MotionEvent event, int pointerIdBits) {
        if (pointerIdBits == 0) return false;

        int eventPointerIdBits = 0;
        for (int i = 0, count = event.getPointerCount(); i < count; i++)
            eventPointerIdBits |= pointerIdBit(event.getPointerId(i));

        if (pointerIdBits == eventPointerIdBits)
            return dispatchPassthroughTouchEvent(event);

        MotionEvent splitEvent = createPassthroughEvent(event, pointerIdBits);
        if (splitEvent == null) return false;
        try {
            return dispatchPassthroughTouchEvent(splitEvent);
        } finally {
            splitEvent.recycle();
        }
    }

    private MotionEvent createPassthroughEvent(MotionEvent event, int pointerIdBits) {
        int pointerCount = event.getPointerCount();
        int passthroughPointerCount = 0;
        int actionIndex = event.getActionIndex();
        int actionPointerId = event.getPointerId(actionIndex);
        boolean actionPointerIncluded = false;
        int passthroughActionIndex = 0;

        for (int i = 0; i < pointerCount; i++) {
            int bit = pointerIdBit(event.getPointerId(i));
            if (bit != 0 && (pointerIdBits & bit) != 0) {
                if (event.getPointerId(i) == actionPointerId) {
                    actionPointerIncluded = true;
                    passthroughActionIndex = passthroughPointerCount;
                }
                passthroughPointerCount++;
            }
        }
        if (passthroughPointerCount == 0) return null;

        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[passthroughPointerCount];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[passthroughPointerCount];
        for (int i = 0, out = 0; i < pointerCount; i++) {
            int bit = pointerIdBit(event.getPointerId(i));
            if (bit == 0 || (pointerIdBits & bit) == 0) continue;

            properties[out] = new MotionEvent.PointerProperties();
            event.getPointerProperties(i, properties[out]);
            coords[out] = new MotionEvent.PointerCoords();
            event.getPointerCoords(i, coords[out]);
            out++;
        }

        int actionMasked = event.getActionMasked();
        int action = actionMasked;
        if (actionMasked == MotionEvent.ACTION_POINTER_DOWN || actionMasked == MotionEvent.ACTION_POINTER_UP) {
            if (!actionPointerIncluded) {
                action = MotionEvent.ACTION_MOVE;
            } else if (passthroughPointerCount == 1) {
                action = actionMasked == MotionEvent.ACTION_POINTER_DOWN ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP;
            } else {
                action = actionMasked | (passthroughActionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            }
        }

        return MotionEvent.obtain(event.getDownTime(), event.getEventTime(), action, passthroughPointerCount,
            properties, coords, event.getMetaState(), event.getButtonState(), event.getXPrecision(), event.getYPrecision(),
            event.getDeviceId(), event.getEdgeFlags(), event.getSource(), event.getFlags());
    }

    private int pointerIdBit(int pointerId) {
        return pointerId >= 0 && pointerId < 32 ? 1 << pointerId : 0;
    }

    private boolean isControlPointer(int pointerId) {
        int bit = pointerIdBit(pointerId);
        return bit != 0 && (controlPointerIdBits & bit) != 0;
    }

    private void setControlPointer(int pointerId, boolean controlPointer) {
        int bit = pointerIdBit(pointerId);
        if (bit == 0) return;
        if (controlPointer)
            controlPointerIdBits |= bit;
        else
            controlPointerIdBits &= ~bit;
    }

    private int getPassthroughPointerIdBits(MotionEvent event) {
        int pointerIdBits = 0;
        for (int i = 0, count = event.getPointerCount(); i < count; i++) {
            int pointerId = event.getPointerId(i);
            int bit = pointerIdBit(pointerId);
            if (bit != 0 && !isControlPointer(pointerId))
                pointerIdBits |= bit;
        }
        return pointerIdBits;
    }

    private boolean handleControlTouchDown(int pointerId, float x, float y) {
        boolean handled = false;
        touchpadView.setPointerButtonLeftEnabled(true);
        for (ControlElement element : profile.getElements()) {
            if (element.handleTouchDown(pointerId, x, y)) {
                handled = true;
                if (element.getBindingAt(0) == Binding.MOUSE_LEFT_BUTTON)
                    touchpadView.setPointerButtonLeftEnabled(false);
            }
        }
        return handled;
    }

    private boolean handleControlTouchMove(int pointerId, float x, float y) {
        boolean handled = false;
        for (ControlElement element : profile.getElements()) {
            if (element.handleTouchMove(pointerId, x, y))
                handled = true;
        }
        return handled;
    }

    private boolean handleControlTouchUp(int pointerId, float x, float y) {
        boolean handled = false;
        for (ControlElement element : profile.getElements()) {
            if (element.handleTouchUp(pointerId, x, y))
                handled = true;
        }
        return handled;
    }

    public int getMaxWidth() {
        return controlLayoutWidth;
    }

    public int getMaxHeight() {
        return controlLayoutHeight;
    }

    public float[] computeTouchpadDeltaPoint(float lastX, float lastY, float x, float y) {
        return touchpadView.computeDeltaPoint(toViewX(lastX), toViewY(lastY), toViewX(x), toViewY(y));
    }

    private void createMouseMoveTimer() {
        if (profile != null && mouseMoveTimer == null) {
            final float cursorSpeed = profile.getCursorSpeed();
            mouseMoveTimer = new Timer();
            mouseMoveTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    xServer.injectPointerMoveDelta((int) (mouseMoveOffset.x * 10 * cursorSpeed), (int) (mouseMoveOffset.y * 10 * cursorSpeed));
                }
            }, 0, 1000 / 60);
        }
    }

    private void processJoystickInput(ExternalController controller) {
        ExternalControllerBinding controllerBinding;
        final int[] axes = {MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y};
        final float[] values = {controller.state.thumbLX, controller.state.thumbLY, controller.state.thumbRX, controller.state.thumbRY, controller.state.getDPadX(), controller.state.getDPadY()};

        for (byte i = 0; i < axes.length; i++) {
            if (Math.abs(values[i]) > ControlElement.STICK_DEAD_ZONE) {
                controllerBinding = controller.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(axes[i], Mathf.sign(values[i])));
                if (controllerBinding != null)
                    handleInputEvent(controllerBinding.getBinding(), true, values[i]);
            } else {
                controllerBinding = controller.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(axes[i], (byte) 1));
                if (controllerBinding != null)
                    handleInputEvent(controllerBinding.getBinding(), false, values[i]);
                controllerBinding = controller.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(axes[i], (byte) -1));
                if (controllerBinding != null)
                    handleInputEvent(controllerBinding.getBinding(), false, values[i]);
            }
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (!editMode && profile != null) {
            ExternalController controller = profile.getController(event.getDeviceId());
            if (controller != null && controller.updateStateFromMotionEvent(event)) {
                ExternalControllerBinding controllerBinding;
                controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2);
                if (controllerBinding != null)
                    handleInputEvent(controllerBinding.getBinding(), controller.state.isPressed(ExternalController.IDX_BUTTON_L2));

                controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2);
                if (controllerBinding != null)
                    handleInputEvent(controllerBinding.getBinding(), controller.state.isPressed(ExternalController.IDX_BUTTON_R2));

                processJoystickInput(controller);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onHoverEvent(MotionEvent event) {
        return dispatchPassthroughTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        ensureControlLayoutGeometry();
        if (editMode && readyToDraw) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    float x = toControlLayoutX(event.getX());
                    float y = toControlLayoutY(event.getY());

                    ControlElement element = intersectElement(x, y);
                    moveCursor = true;
                    if (element != null) {
                        offsetX = x - element.getX();
                        offsetY = y - element.getY();
                        moveCursor = false;
                    }
                    selectElement(element);
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (selectedElement != null) {
                        selectedElement.setX((int) Mathf.roundTo(toControlLayoutX(event.getX()) - offsetX, snappingSize));
                        selectedElement.setY((int) Mathf.roundTo(toControlLayoutY(event.getY()) - offsetY, snappingSize));
                        invalidate();
                    }
                    break;
                }
                case MotionEvent.ACTION_UP: {
                    if (selectedElement != null && profile != null) profile.save();
                    if (moveCursor)
                        cursor.set((int) Mathf.roundTo(toControlLayoutX(event.getX()), snappingSize), (int) Mathf.roundTo(toControlLayoutY(event.getY()), snappingSize));
                    invalidate();
                    break;
                }
            }
        }
        if (!editMode) {
            return handleTouchEvent(event);
        }
        return true;
    }

    public boolean handleTouchEvent(MotionEvent event) {
        ensureControlLayoutGeometry();
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)){
            return dispatchPassthroughTouchEvent(event);
        }
        if (!editMode && profile != null) {
            int actionIndex = event.getActionIndex();
            int pointerId = event.getPointerId(actionIndex);
            int actionMasked = event.getActionMasked();
            boolean handled = false;
            boolean passthroughHandled = false;
//            Log.d("handleTouchEvent",String.valueOf(event.getAction()));
            switch (actionMasked) {
                case MotionEvent.ACTION_DOWN: {
                    controlPointerIdBits = 0;
                    float x = toControlLayoutX(event.getX(actionIndex));
                    float y = toControlLayoutY(event.getY(actionIndex));
                    handled = showTouchscreenControls && handleControlTouchDown(pointerId, x, y);
                    setControlPointer(pointerId, handled);
                    if (!handled)
                        passthroughHandled = dispatchPassthroughTouchEvent(event);
                    break;
                }
                case MotionEvent.ACTION_POINTER_DOWN: {
                    float x = toControlLayoutX(event.getX(actionIndex));
                    float y = toControlLayoutY(event.getY(actionIndex));
                    handled = showTouchscreenControls && handleControlTouchDown(pointerId, x, y);
                    setControlPointer(pointerId, handled);
                    if (!handled)
                        passthroughHandled = dispatchPassthroughTouchEvent(event, getPassthroughPointerIdBits(event));
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    for (byte i = 0, count = (byte) event.getPointerCount(); i < count; i++) {
                        int movePointerId = event.getPointerId(i);
                        float x = toControlLayoutX(event.getX(i));
                        float y = toControlLayoutY(event.getY(i));
                        if (isControlPointer(movePointerId)) {
                            handleControlTouchMove(movePointerId, x, y);
                            handled = true;
                        }
                    }
                    passthroughHandled = dispatchPassthroughTouchEvent(event, getPassthroughPointerIdBits(event));
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    if (isControlPointer(pointerId)) {
                        float x = toControlLayoutX(event.getX(actionIndex));
                        float y = toControlLayoutY(event.getY(actionIndex));
                        handled = handleControlTouchUp(pointerId, x, y);
                        setControlPointer(pointerId, false);
                    } else {
                        passthroughHandled = dispatchPassthroughTouchEvent(event, getPassthroughPointerIdBits(event));
                    }
                    if (actionMasked == MotionEvent.ACTION_UP)
                        controlPointerIdBits = 0;
                    break;
                }
                case MotionEvent.ACTION_CANCEL:
                    for (byte i = 0, count = (byte) event.getPointerCount(); i < count; i++) {
                        int cancelPointerId = event.getPointerId(i);
                        if (isControlPointer(cancelPointerId))
                            handleControlTouchUp(cancelPointerId, toControlLayoutX(event.getX(i)), toControlLayoutY(event.getY(i)));
                    }
                    passthroughHandled = dispatchPassthroughTouchEvent(event, getPassthroughPointerIdBits(event));
                    controlPointerIdBits = 0;
                    break;
            }
            return handled || passthroughHandled;
        }
        return dispatchPassthroughTouchEvent(event);
    }

    public void handleInputEvent(Binding binding, boolean isActionDown) {
        handleInputEvent(binding, isActionDown, 0);
    }

    public void handleInputEvent(Binding binding, boolean isActionDown, float offset) {
        if (binding.isGamepad()) {
            WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
            GamepadState state = profile.getGamepadState();
            int buttonIdx = binding.ordinal() - Binding.GAMEPAD_BUTTON_A.ordinal();
            if (buttonIdx <= 11) {
                state.setPressed(buttonIdx, isActionDown);
//                if (winHandler != null) winHandler.saveGamepadState(state);
            } else if (binding == Binding.GAMEPAD_LEFT_THUMB_UP || binding == Binding.GAMEPAD_LEFT_THUMB_DOWN) {
                state.thumbLY = isActionDown ? offset : 0;
            } else if (binding == Binding.GAMEPAD_LEFT_THUMB_LEFT || binding == Binding.GAMEPAD_LEFT_THUMB_RIGHT) {
                state.thumbLX = isActionDown ? offset : 0;
            } else if (binding == Binding.GAMEPAD_RIGHT_THUMB_UP || binding == Binding.GAMEPAD_RIGHT_THUMB_DOWN) {
                state.thumbRY = isActionDown ? offset : 0;
            } else if (binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT || binding == Binding.GAMEPAD_RIGHT_THUMB_RIGHT) {
                state.thumbRX = isActionDown ? offset : 0;
            } else if (binding == Binding.GAMEPAD_DPAD_UP || binding == Binding.GAMEPAD_DPAD_RIGHT ||
                binding == Binding.GAMEPAD_DPAD_DOWN || binding == Binding.GAMEPAD_DPAD_LEFT) {
                state.dpad[binding.ordinal() - Binding.GAMEPAD_DPAD_UP.ordinal()] = isActionDown;
            }

            if (winHandler != null) {
                ExternalController controller = winHandler.getCurrentController();
                if (controller != null) {
                    controller.state.copy(state);
                }
                winHandler.sendGamepadState();
            }
        } else {
            if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
//                Log.d("handleInputEvent","<binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT>:"+binding.toString());
                mouseMoveOffset.x = isActionDown ? (offset != 0 ? offset : (binding == Binding.MOUSE_MOVE_LEFT ? -1 : 1)) : 0;
                if (isActionDown) createMouseMoveTimer();
            } else if (binding == Binding.MOUSE_MOVE_DOWN || binding == Binding.MOUSE_MOVE_UP) {
//                Log.d("handleInputEvent","<binding == Binding.MOUSE_MOVE_DOWN || binding == Binding.MOUSE_MOVE_UP> "+binding.toString());
                mouseMoveOffset.y = isActionDown ? (offset != 0 ? offset : (binding == Binding.MOUSE_MOVE_UP ? -1 : 1)) : 0;
                if (isActionDown) createMouseMoveTimer();
            } else {
                Pointer.Button pointerButton = binding.getPointerButton();
                if (isActionDown) {
//                    Log.d("handleInputEvent","<isActionDown> "+binding.toString());
                    if (pointerButton != null) {
                        xServer.injectPointerButtonPress(pointerButton);
                    } else {
                        xServer.injectKeyPress(binding.keycode);
                    }
                } else {
//                    Log.d("handleInputEvent","<isActionUp> "+binding.toString());
                    if (pointerButton != null) {
                        xServer.injectPointerButtonRelease(pointerButton);
                    } else {
                        xServer.injectKeyRelease(binding.keycode);
                    }
                }
            }
        }
    }

    public void sendText(String text) {
        xServer.injectText(text);
        xServer.injectKeyPress(XKeycode.KEY_ENTER);
        xServer.injectKeyRelease(XKeycode.KEY_ENTER);
    }

    public Bitmap getIcon(byte id) {
        if (icons[id] == null) {
            Context context = getContext();
            try (InputStream is = context.getAssets().open("inputcontrols/icons/" + id + ".png")) {
                icons[id] = BitmapFactory.decodeStream(is);
            } catch (IOException e) {
            }
        }
        return icons[id];
    }

    public Bitmap getCustomIcon(String iconId) {
        final File buttonIconFile = new File(TermuxConfigFiles.buttonIconsDir(getContext()), iconId + ".png");
        if (!buttonIconFile.exists()) {
            return null;
        }
        return BitmapFactory.decodeFile(buttonIconFile.getPath());
    }

    public Bitmap clipBitmap(Bitmap bitmap, boolean isCircular) {
        Bitmap clippedBitmap = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(clippedBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        paint.setShader(shader);
        if (isCircular) {
            int centerX = bitmap.getWidth() / 2;
            int centerY = bitmap.getHeight() / 2;
            int radius = Math.min(centerX, centerY);
            canvas.drawCircle(centerX, centerY, radius, paint);
        } else {
            RectF rect = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
            canvas.drawRect(rect, paint);
        }
        return clippedBitmap;
    }

    public static Bitmap createShapeBitmap(float width, float height, int color, boolean isCircular) {
        Bitmap bitmap = Bitmap.createBitmap((int) width, (int) height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);

        if (isCircular) {
            int radius = (int) (Math.min(width, height) / 2);
            canvas.drawCircle(width / 2, height / 2, radius, paint);
        } else {
            RectF rect = new RectF(0, 0, width, height);
            canvas.drawRect(rect, paint);
        }
        return bitmap;
    }
}
