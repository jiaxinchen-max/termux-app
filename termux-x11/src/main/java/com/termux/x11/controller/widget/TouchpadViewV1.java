package com.termux.x11.controller.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.termux.x11.controller.core.AppUtils;
import com.termux.x11.controller.math.Mathf;
import com.termux.x11.controller.math.XForm;
import com.termux.x11.controller.renderer.ViewTransformation;
import com.termux.x11.controller.winhandler.MouseEventFlags;
import com.termux.x11.controller.winhandler.WinHandler;
import com.termux.x11.controller.xserver.Pointer;
import com.termux.x11.LorieView;

public class TouchpadViewV1 extends View implements View.OnCapturedPointerListener {
    private static final byte MAX_FINGERS = 4;
    private static final short MAX_TWO_FINGERS_SCROLL_DISTANCE = 350;
    public static final byte MAX_TAP_TRAVEL_DISTANCE = 10;
    public static final short MAX_TAP_MILLISECONDS = 200;
    public static final float CURSOR_ACCELERATION = 1.5f;
    public static final byte CURSOR_ACCELERATION_THRESHOLD = 6;
    private static final int EFFECTIVE_TOUCH_DISTANCE = 20;
    private static final int SHORT_DRAG_MAX_TIME = 100;
    private static final int LONG_DRAG_MIN_TIME = 120;
    private static final int MOVE_TO_CLICK_DELAY_MS = 30;
    private static final int UPDATE_FORM_DELAYED_TIME = 50;
    private static final int MAX_LONG_PRESS_MILLISECONDS = 250;
    private static final int MIN_MOVE_THRESHOLD = 4;
    private static final int MAX_SCROLL_ACCUM = 100;

    private final Finger[] fingers = new Finger[MAX_FINGERS];
    private byte numFingers = 0;
    private float sensitivity = 1.0f;
    private boolean pointerButtonLeftEnabled = true;
    private boolean pointerButtonRightEnabled = true;
    private boolean moveCursorToTouchpoint = false;
    private boolean twoFingersDrag = true;
    private boolean twoFingersRightClick = true;
    private boolean longPressRightClick = true;
    private static final float MIN_PINCH_SCALE_THRESHOLD = 0.005f;
    private static final int DOUBLE_TAP_MAX_MILLISECONDS = 300;
    private static final int DOUBLE_TAP_MAX_TRAVEL_DISTANCE = 30;

    private boolean pinchZoomEnabled = false;
    private float lastPinchDist = 0;
    private float currentPinchZoom = 1.0f;
    private float pinchCenterX = 0;
    private float pinchCenterY = 0;
    private boolean pinchAnchorLocked = false;
    private long lastSingleTapTime = 0;
    private int lastSingleTapX = 0;
    private int lastSingleTapY = 0;
    private Finger fingerPointerButtonLeft;
    private Finger fingerPointerButtonRight;
    private float scrollAccumY = 0;
    private boolean scrolling = false;
    private final LorieView xServer;
    private Runnable fourFingersTapCallback;
    private final float[] xform = XForm.getInstance();

    private boolean touchscreenMouseDisabled = false;
    private Runnable threeFingersTapCallback;
    private boolean swapMouseButtons = false;
    private boolean isShortDrag = false;
    private boolean isLongDrag = false;
    private int initialPointerX;
    private int initialPointerY;
    private int lastTouchedPosX;
    private int lastTouchedPosY;
    private float resolutionScale = 1.0f;
    private int displayOffsetX = 0;
    private int displayOffsetY = 0;
    private int displayWidth = 0;
    private int displayHeight = 0;

    private boolean shortDragEnabled = false;  // 短时拖动开关，默认关闭
    private boolean twoFingersScroll = true;  // 双指滚动开关，默认开启

    // 反向缩放：将 X server 坐标还原为缩放前对应的位置
    // 渲染器缩放公式: screen_pos = (xserver - anchor) * zoom + anchor
    // 反向: xserver_real = (xserver - anchor) / zoom + anchor
    private float unzoom(float coord, float anchor) {
        if (pinchZoomEnabled && currentPinchZoom > 1.0f) {
            return (coord - anchor) / currentPinchZoom + anchor;
        }
        return coord;
    }

    public TouchpadViewV1(Context context, LorieView xServer, boolean capturePointerOnExternalMouse) {
        super(context);
        this.xServer = xServer;
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setBackground(createTransparentBackground());
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(capturePointerOnExternalMouse);
        updateXform(AppUtils.getScreenWidth(), AppUtils.getScreenHeight(), xServer.screenInfo.screenWidth, xServer.screenInfo.screenHeight);

        setOnGenericMotionListener((v, event) -> {
            if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                return handleStylusHoverEvent(event);
            }
            return false;
        });
        if (capturePointerOnExternalMouse) {
            setOnCapturedPointerListener(this);
            setOnClickListener(view -> {
                if (requestFocus()) requestPointerCapture();
            });
        }
    }

    private static StateListDrawable createTransparentBackground() {
        StateListDrawable stateListDrawable = new StateListDrawable();
        ColorDrawable focusedDrawable = new ColorDrawable(Color.TRANSPARENT);
        ColorDrawable defaultDrawable = new ColorDrawable(Color.TRANSPARENT);
        stateListDrawable.addState(new int[]{android.R.attr.state_focused}, focusedDrawable);
        stateListDrawable.addState(new int[0], defaultDrawable);
        return stateListDrawable;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateXform(w, h, xServer.screenInfo.screenWidth, xServer.screenInfo.screenHeight);
        resolutionScale = 1000.0f / Math.min(xServer.screenInfo.screenWidth, xServer.screenInfo.screenHeight);
    }

    private void updateXform(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        ViewTransformation viewTransformation = new ViewTransformation();
        viewTransformation.update(outerWidth, outerHeight, innerWidth, innerHeight);
        float invAspect = 1.0f / viewTransformation.aspect;
        if (!xServer.getRenderer().isFullscreen()) {
            XForm.makeTranslation(xform, -viewTransformation.viewOffsetX, -viewTransformation.viewOffsetY);
            XForm.scale(xform, invAspect, invAspect);
            displayOffsetX = viewTransformation.viewOffsetX;
            displayOffsetY = viewTransformation.viewOffsetY;
            displayWidth = viewTransformation.viewWidth;
            displayHeight = viewTransformation.viewHeight;
        } else {
            XForm.makeScale(xform, (float) innerWidth / outerWidth, (float) innerHeight / outerHeight);
            displayOffsetX = 0;
            displayOffsetY = 0;
            displayWidth = outerWidth;
            displayHeight = outerHeight;
        }
    }

    private boolean isInDisplayArea(float x, float y) {
        return x >= displayOffsetX && x <= displayOffsetX + displayWidth &&
               y >= displayOffsetY && y <= displayOffsetY + displayHeight;
    }

    private class Finger {
        private int x, y, startX, startY, lastX, lastY;
        private final long touchTime;

        public Finger(float x, float y) {
            float[] tp = XForm.transformPoint(xform, x, y);
            this.x = this.startX = this.lastX = (int) tp[0];
            this.y = this.startY = this.lastY = (int) tp[1];
            touchTime = System.currentTimeMillis();
        }

        public void update(float x, float y) {
            lastX = this.x; lastY = this.y;
            float[] tp = XForm.transformPoint(xform, x, y);
            this.x = (int) tp[0];
            this.y = (int) tp[1];
        }

        private int deltaX() {
            float dx = (x - lastX) * sensitivity;
            if (Math.abs(dx) > CURSOR_ACCELERATION_THRESHOLD) dx *= CURSOR_ACCELERATION;
            return Mathf.roundPoint(dx);
        }
        private int deltaY() {
            float dy = (y - lastY) * sensitivity;
            if (Math.abs(dy) > CURSOR_ACCELERATION_THRESHOLD) dy *= CURSOR_ACCELERATION;
            return Mathf.roundPoint(dy);
        }
        private boolean isTap() {
            return (System.currentTimeMillis() - touchTime) < MAX_TAP_MILLISECONDS && travelDistance() < MAX_TAP_TRAVEL_DISTANCE;
        }
        private boolean isLongPress() {
            return (System.currentTimeMillis() - touchTime) > MAX_LONG_PRESS_MILLISECONDS && travelDistance() < MAX_TAP_TRAVEL_DISTANCE;
        }
        private float travelDistance() {
            return (float) Math.hypot(x - startX, y - startY);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int toolType = event.getToolType(0);
        if (touchscreenMouseDisabled && toolType != MotionEvent.TOOL_TYPE_STYLUS && !event.isFromSource(InputDevice.SOURCE_MOUSE))
            return true;
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS)
            return handleStylusEvent(event);

        int actionIndex = event.getActionIndex();
        int pointerId = event.getPointerId(actionIndex);
        int actionMasked = event.getActionMasked();
        if (pointerId >= MAX_FINGERS) return true;

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return true;
                if (moveCursorToTouchpoint && !isInDisplayArea(event.getX(actionIndex), event.getY(actionIndex))) {
                    return true;
                }
                scrollAccumY = 0;
                scrolling = false;
                fingers[pointerId] = new Finger(event.getX(actionIndex), event.getY(actionIndex));
                numFingers++;
                if (numFingers == 2 && pinchZoomEnabled) {
                    Finger f1 = null, f2 = null;
                    for (byte i = 0; i < MAX_FINGERS; i++) {
                        if (fingers[i] != null) {
                            if (f1 == null) f1 = fingers[i];
                            else { f2 = fingers[i]; break; }
                        }
                    }
                    if (f1 != null && f2 != null) {
                        currentPinchZoom = xServer.getRenderer().getPinchZoom();
                        if (currentPinchZoom > 1.0f) {
                            pinchCenterX = xServer.getRenderer().getPinchAnchorX();
                            pinchCenterY = xServer.getRenderer().getPinchAnchorY();
                        } else {
                            pinchCenterX = (f1.x + f2.x) * 0.5f;
                            pinchCenterY = (f1.y + f2.y) * 0.5f;
                        }
                        pinchAnchorLocked = true;
                        lastPinchDist = 0;
                    }
                } else {
                    lastPinchDist = 0;
                }
                if (moveCursorToTouchpoint && pointerId == 0) {
                    isShortDrag = false;
                    isLongDrag = false;
                    initialPointerX = xServer.pointer.getX();
                    initialPointerY = xServer.pointer.getY();
                    lastTouchedPosX = fingers[0].x;
                    lastTouchedPosY = fingers[0].y;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    float[] tp = XForm.transformPoint(xform, event.getX(), event.getY());
                    if (isEnabled()) xServer.injectPointerMove((int) tp[0], (int) tp[1]);
                } else {
                    for (byte i = 0; i < MAX_FINGERS; i++) {
                        if (fingers[i] != null) {
                            int idx = event.findPointerIndex(i);
                            if (idx >= 0) {
                                if (!moveCursorToTouchpoint || isInDisplayArea(event.getX(idx), event.getY(idx))) {
                                    fingers[i].update(event.getX(idx), event.getY(idx));
                                    handleFingerMove(fingers[i]);
                                }
                            } else {
                                handleFingerUp(fingers[i]);
                                fingers[i] = null;
                                numFingers--;
                            }
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (fingers[pointerId] != null) {
                    if (!moveCursorToTouchpoint || isInDisplayArea(event.getX(actionIndex), event.getY(actionIndex))) {
                        fingers[pointerId].update(event.getX(actionIndex), event.getY(actionIndex));
                    }
                    handleFingerUp(fingers[pointerId]);
                    fingers[pointerId] = null;
                    numFingers--;
                    if (numFingers <= 1 && pinchZoomEnabled) {
                        lastPinchDist = 0;
                        pinchAnchorLocked = false;
                        // 缩放持久化：不调用 resetPinchZoom()
                    }
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                for (byte i = 0; i < MAX_FINGERS; i++) fingers[i] = null;
                numFingers = 0;
                scrolling = false;
                isShortDrag = false;
                isLongDrag = false;
                lastPinchDist = 0;
                pinchAnchorLocked = false;
                scrollAccumY = 0;
                lastSingleTapTime = 0;
                if (pinchZoomEnabled) {
                    currentPinchZoom = 1.0f;
                    xServer.getRenderer().resetPinchZoom();
                }
                if (fingerPointerButtonLeft != null && xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT))
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                if (fingerPointerButtonRight != null && xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT))
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                fingerPointerButtonLeft = null;
                fingerPointerButtonRight = null;
                break;
        }
        return true;
    }

    private boolean handleStylusHoverEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) {
            float[] tp = XForm.transformPoint(xform, event.getX(), event.getY());
            xServer.injectPointerMove((int) tp[0], (int) tp[1]);
        }
        return true;
    }

    private boolean handleStylusEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int buttonState = event.getButtonState();
        float[] tp = XForm.transformPoint(xform, event.getX(), event.getY());
        int x = (int) tp[0], y = (int) tp[1];
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                xServer.injectPointerMove(x, y);
                if ((buttonState & MotionEvent.BUTTON_SECONDARY) != 0)
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                else
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                break;
            case MotionEvent.ACTION_MOVE:
                xServer.injectPointerMove(x, y);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                break;
        }
        return true;
    }

    private void handleFingerUp(Finger finger1) {
        switch (numFingers) {
            case 1:
                if (moveCursorToTouchpoint) {
                    float f1x = unzoom(finger1.x, pinchCenterX);
                    float f1y = unzoom(finger1.y, pinchCenterY);
                    if (finger1.isTap()) {
                        if (Math.hypot(f1x - xServer.pointer.getX(), f1y - xServer.pointer.getY()) >= MAX_TAP_TRAVEL_DISTANCE) {
                            xServer.injectPointerMove((int) f1x, (int) f1y);
                        }
                        postDelayed(() -> {
                            if (swapMouseButtons) {
                                pressPointerButtonRight(finger1);
                                releasePointerButtonRight(finger1);
                            } else {
                                pressPointerButtonLeft(finger1);
                                releasePointerButtonLeft(finger1);
                            }
                        }, MOVE_TO_CLICK_DELAY_MS);
                    }
                    if (finger1.isLongPress() && longPressRightClick) {
                        if (Math.hypot(f1x - xServer.pointer.getX(), f1y - xServer.pointer.getY()) >= MAX_TAP_TRAVEL_DISTANCE) {
                            xServer.injectPointerMove((int) f1x, (int) f1y);
                        }
                        postDelayed(() -> {
                            if (!swapMouseButtons) {
                                pressPointerButtonRight(finger1);
                                releasePointerButtonRight(finger1);
                            } else {
                                pressPointerButtonLeft(finger1);
                                releasePointerButtonLeft(finger1);
                            }
                        }, MOVE_TO_CLICK_DELAY_MS);
                    }
                    if (shortDragEnabled && isShortDrag) {
                        xServer.injectPointerMove(initialPointerX, initialPointerY);
                        isShortDrag = false;
                    }
                    if (isLongDrag) {
                        isLongDrag = false;
                        if (swapMouseButtons) releasePointerButtonRight(finger1);
                        else releasePointerButtonLeft(finger1);
                    }
                } else if (finger1.isTap()) {
                    if (swapMouseButtons) pressPointerButtonRight(finger1);
                    else pressPointerButtonLeft(finger1);
                }
                break;
            case 2:
                Finger finger2 = findSecondFinger(finger1);
                if (finger2 != null && finger1.isTap() && twoFingersRightClick && !pinchZoomEnabled) {
                    if (swapMouseButtons && !moveCursorToTouchpoint) pressPointerButtonLeft(finger1);
                    else pressPointerButtonRight(finger1);
                }
                break;
            case 3:
                if (threeFingersTapCallback != null) {
                    for (byte i = 0; i < MAX_FINGERS; i++)
                        if (fingers[i] != null && !fingers[i].isTap()) return;
                    threeFingersTapCallback.run();
                }
                break;
            case 4:
                if (fourFingersTapCallback != null) {
                    for (byte i = 0; i < MAX_FINGERS; i++)
                        if (fingers[i] != null && !fingers[i].isTap()) return;
                    fourFingersTapCallback.run();
                }
                break;
        }
        releasePointerButtonLeft(finger1);
        releasePointerButtonRight(finger1);
    }

    private void handleFingerMove(Finger finger1) {
        if (!isEnabled()) return;
        boolean skipPointerMove = false;
        Finger finger2 = numFingers == 2 ? findSecondFinger(finger1) : null;
        if (finger2 != null && pinchZoomEnabled) {
            float currDist = (float) Math.hypot(finger1.x - finger2.x, finger1.y - finger2.y) * resolutionScale;
            if (lastPinchDist > 0) {
                float scale = currDist / lastPinchDist;
                if (Math.abs(scale - 1.0f) >= MIN_PINCH_SCALE_THRESHOLD) {
                    currentPinchZoom *= scale;
                    xServer.getRenderer().setPinchZoom(currentPinchZoom, pinchCenterX, pinchCenterY);
                } else if (currentPinchZoom > 1.0f) {
                    // 双指平行移动：平移视口（仅在已缩放时生效，避免除以零）
                    // 每个手指贡献 center_delta/2，公式: -delta / (2*(zoom-1))
                    float panDx = -(finger1.x - finger1.lastX) / (2.0f * (currentPinchZoom - 1.0f));
                    float panDy = -(finger1.y - finger1.lastY) / (2.0f * (currentPinchZoom - 1.0f));
                    pinchCenterX += panDx;
                    pinchCenterY += panDy;
                    xServer.getRenderer().setPinchZoom(currentPinchZoom, pinchCenterX, pinchCenterY);
                }
            }
            lastPinchDist = currDist;
            scrolling = false;
            return;
        }
        if (finger2 != null) {
            float currDist = (float) Math.hypot(finger1.x - finger2.x, finger1.y - finger2.y) * resolutionScale;
            if (twoFingersScroll && currDist < MAX_TWO_FINGERS_SCROLL_DISTANCE) {
                scrollAccumY += ((finger1.y + finger2.y) * 0.5f) - ((finger1.lastY + finger2.lastY) * 0.5f);
                if (scrollAccumY < -MAX_SCROLL_ACCUM) {
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    scrollAccumY = 0;
                } else if (scrollAccumY > MAX_SCROLL_ACCUM) {
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                    scrollAccumY = 0;
                }
                scrolling = true;
            } else if (!moveCursorToTouchpoint && twoFingersDrag && currDist >= MAX_TWO_FINGERS_SCROLL_DISTANCE &&
                       !finger1.isTap() && !finger2.isTap() &&
                       !xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT) &&
                       finger2.travelDistance() < MAX_TAP_TRAVEL_DISTANCE) {
                pressPointerButtonLeft(finger1);
                skipPointerMove = true;
            }
        }
        if (!scrolling && numFingers <= 2 && !skipPointerMove) {
            if (moveCursorToTouchpoint) {
                long duration = System.currentTimeMillis() - finger1.touchTime;
                if (shortDragEnabled && duration < SHORT_DRAG_MAX_TIME && finger1.travelDistance() > MAX_TAP_TRAVEL_DISTANCE) {
                    if (!isShortDrag && !isLongDrag) {
                        isShortDrag = true;
                        moveCursorToEdge(finger1);
                    }
                } else if (duration >= LONG_DRAG_MIN_TIME && !isShortDrag) {
                    int f1x = (int) unzoom(finger1.x, pinchCenterX);
                    int f1y = (int) unzoom(finger1.y, pinchCenterY);
                    xServer.injectPointerMove(f1x, f1y);
                    if (finger1.travelDistance() > MAX_TAP_TRAVEL_DISTANCE && !isLongDrag) {
                        isLongDrag = true;
                        if (swapMouseButtons) pressPointerButtonRight(finger1);
                        else pressPointerButtonLeft(finger1);
                    }
                }
                if (shortDragEnabled && isShortDrag) moveCursorToEdge(finger1);
                return;
            }
            int dx = finger1.deltaX(), dy = finger1.deltaY();
            WinHandler wh = xServer.getWinHandler();
            if (xServer.isRelativeMouseMovement()) wh.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
            else xServer.injectPointerMoveDelta(dx, dy);
        }
    }

    private void moveCursorToEdge(Finger finger) {
        int w = xServer.screenInfo.screenWidth, h = xServer.screenInfo.screenHeight;
        int dx = finger.x - finger.lastX, dy = finger.y - finger.lastY;
        if (Math.abs(dx) < MIN_MOVE_THRESHOLD && Math.abs(dy) < MIN_MOVE_THRESHOLD) return;
        int tx = (dx > 0) ? 0 : w - 1;
        int ty = (dy > 0) ? 0 : h - 1;
        if (Math.abs(dx) > Math.abs(dy) * 2) ty = initialPointerY;
        else if (Math.abs(dy) > Math.abs(dx) * 2) tx = initialPointerX;
        xServer.injectPointerMove(tx, ty);
    }

    public void mouseMove(float x, float y, int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                fingers[0] = new Finger(x, y);
                numFingers = 1;
                break;
            case MotionEvent.ACTION_MOVE:
                if (fingers[0] != null) {
                    fingers[0].update(x, y);
                    handleFingerMove(fingers[0]);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:   // 增加 CANCEL 处理
                fingers[0] = null;
                numFingers = 0;
                scrolling = false;
                isShortDrag = false;
                isLongDrag = false;
                break;
        }
    }

    private Finger findSecondFinger(Finger finger) {
        for (byte i = 0; i < MAX_FINGERS; i++)
            if (fingers[i] != null && fingers[i] != finger) return fingers[i];
        return null;
    }

    private void pressPointerButtonLeft(Finger f) {
        if (isEnabled() && pointerButtonLeftEnabled && !xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
            fingerPointerButtonLeft = f;
        }
    }
    private void pressPointerButtonRight(Finger f) {
        if (isEnabled() && pointerButtonRightEnabled && !xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
            fingerPointerButtonRight = f;
        }
    }
    private void releasePointerButtonLeft(final Finger f) {
        if (isEnabled() && pointerButtonLeftEnabled && f == fingerPointerButtonLeft && xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            postDelayed(() -> { xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT); fingerPointerButtonLeft = null; }, 30);
        }
    }
    private void releasePointerButtonRight(final Finger f) {
        if (isEnabled() && pointerButtonRightEnabled && f == fingerPointerButtonRight && xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
            postDelayed(() -> { xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT); fingerPointerButtonRight = null; }, 30);
        }
    }

    public void setSensitivity(float s) { sensitivity = s; }
    public void setPointerButtonLeftEnabled(boolean b) { pointerButtonLeftEnabled = b; }
    public void setPointerButtonRightEnabled(boolean b) { pointerButtonRightEnabled = b; }
    public void setFourFingersTapCallback(Runnable r) { fourFingersTapCallback = r; }
    public void setThreeFingersTapCallback(Runnable r) { threeFingersTapCallback = r; }
    public void setMoveCursorToTouchpoint(boolean b) { moveCursorToTouchpoint = b; }
    public void setTouchscreenMouseDisabled(boolean b) { touchscreenMouseDisabled = b; }
    public void setSwapMouseButtons() { swapMouseButtons = !swapMouseButtons; }
    public void setSwapMouseButtons(boolean b) { swapMouseButtons = b; }
    public boolean isSwapMouseButtons() { return swapMouseButtons; }
    public void setTwoFingersDrag(boolean b) { twoFingersDrag = b; }
    public boolean isTwoFingersDrag() { return twoFingersDrag; }
    public void setTwoFingersRightClick(boolean b) { twoFingersRightClick = b; }
    public boolean isTwoFingersRightClick() { return twoFingersRightClick; }
    public void setLongPressRightClick(boolean b) { longPressRightClick = b; }
    public boolean isLongPressRightClick() { return longPressRightClick; }
    public void setPinchZoomEnabled(boolean b) {
        pinchZoomEnabled = b;
        if (!b) {
            currentPinchZoom = 1.0f;
            xServer.getRenderer().resetPinchZoom();
        }
    }
    public boolean isPinchZoomEnabled() { return pinchZoomEnabled; }
    public void setShortDragEnabled(boolean b) { shortDragEnabled = b; }
    public boolean isShortDragEnabled() { return shortDragEnabled; }
    public void setTwoFingersScroll(boolean b) { twoFingersScroll = b; }
    public boolean isTwoFingersScroll() { return twoFingersScroll; }

    public boolean onExternalMouseEvent(MotionEvent event) {
        if (!isEnabled() || !event.isFromSource(InputDevice.SOURCE_MOUSE)) return false;
        int btn = event.getActionButton();
        switch (event.getAction()) {
            case MotionEvent.ACTION_BUTTON_PRESS:
                if (btn == MotionEvent.BUTTON_PRIMARY) xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                else if (btn == MotionEvent.BUTTON_SECONDARY) xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                return true;
            case MotionEvent.ACTION_BUTTON_RELEASE:
                if (btn == MotionEvent.BUTTON_PRIMARY) xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                else if (btn == MotionEvent.BUTTON_SECONDARY) xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                return true;
            case MotionEvent.ACTION_HOVER_MOVE:
                float[] tp = XForm.transformPoint(xform, event.getX(), event.getY());
                xServer.injectPointerMove((int) tp[0], (int) tp[1]);
                return true;
            case MotionEvent.ACTION_SCROLL:
                float sy = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (sy <= -1) { xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN); xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN); }
                else if (sy >= 1) { xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP); xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP); }
                return true;
        }
        return false;
    }

    public float[] computeDeltaPoint(float lastX, float lastY, float x, float y) {
        float[] res = new float[2];
        XForm.transformPoint(xform, lastX, lastY, res);
        lastX = res[0]; lastY = res[1];
        XForm.transformPoint(xform, x, y, res);
        res[0] = res[0] - lastX;
        res[1] = res[1] - lastY;
        return res;
    }

    @Override
    public boolean onCapturedPointer(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() * sensitivity;
            if (Math.abs(dx) > CURSOR_ACCELERATION_THRESHOLD) dx *= CURSOR_ACCELERATION;
            float dy = event.getY() * sensitivity;
            if (Math.abs(dy) > CURSOR_ACCELERATION_THRESHOLD) dy *= CURSOR_ACCELERATION;
            xServer.injectPointerMoveDelta(Mathf.roundPoint(dx), Mathf.roundPoint(dy));
            return true;
        } else {
            event.setSource(event.getSource() | InputDevice.SOURCE_MOUSE);
            return onExternalMouseEvent(event);
        }
    }

    public void toggleFullscreen() {
        new Handler().postDelayed(() -> updateXform(getWidth(), getHeight(), xServer.screenInfo.screenWidth, xServer.screenInfo.screenHeight), UPDATE_FORM_DELAYED_TIME);
    }
}
