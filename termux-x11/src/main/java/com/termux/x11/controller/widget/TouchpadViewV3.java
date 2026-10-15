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

public class TouchpadViewV3 extends View implements View.OnCapturedPointerListener {
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
    // 特性总开关：暂时关闭“单指长按→右键”。双指已承担右键/右键拖拽，长按右键与之冲突，先停用；
    // 需要恢复时改回 true（不影响 Profile/UI 的 longPressRightClick 链路）。
    private static final boolean LONG_PRESS_RIGHT_CLICK_ENABLED = false;

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

    private boolean shortDragEnabled = false;
    private boolean twoFingersScroll = true;

    // 反向缩放：将 X server 坐标还原为缩放前对应的位置
    private float unzoom(float coord, float anchor) {
        if (pinchZoomEnabled && currentPinchZoom > 1.0f) {
            return (coord - anchor) / currentPinchZoom + anchor;
        }
        return coord;
    }

    // V3: 按下时立即触发按键，抬起时释放
    private boolean leftPressedOnDown = false;  // 标记左键是否在按下时已触发
    private boolean rightPressedOnDown = false; // 标记右键是否在按下时已触发（双指）
    private boolean twoFingersRightGesture = false; // 本次手势是否触发过双指右键，用于抬起时抑制单指 tap 补发点击

    public TouchpadViewV3(Context context, LorieView xServer, boolean capturePointerOnExternalMouse) {
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
                            // 已有缩放：保持现有锚点，避免画面跳动
                            pinchCenterX = xServer.getRenderer().getPinchAnchorX();
                            pinchCenterY = xServer.getRenderer().getPinchAnchorY();
                        } else {
                            // 新缩放：以双指中点为锚点
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
                // V3: 按下时立即触发按键
                handleFingerDown(fingers[pointerId]);
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
                leftPressedOnDown = false;
                rightPressedOnDown = false;
                twoFingersRightGesture = false;
                break;
        }
        return true;
    }

    // V3 新增：按下时处理按键触发
    private void handleFingerDown(Finger finger1) {
        if (numFingers == 1) {
            // 单指按下不触发按键，点击/拖拽在移动与抬起时判定
        } else if (numFingers == 2) {
            Finger finger2 = findSecondFinger(finger1);
            // 双指按下：跟手（点击定位）模式下先把指针跳到本指触点并立即触发右键（或左键，取决于swap），
            // 以支持双指右键绝对拖拽；普通模式下先记录触发指，延迟到移动/抬起时判定
            if (finger2 != null && twoFingersRightClick && !pinchZoomEnabled) {
                if (moveCursorToTouchpoint) {
                    xServer.injectPointerMove(finger1.x, finger1.y);
                    if (swapMouseButtons) pressPointerButtonLeft(finger1);
                    else pressPointerButtonRight(finger1);
                    rightPressedOnDown = true;
                    twoFingersRightGesture = true;
                } else {
                    fingerPointerButtonRight = finger1;
                    twoFingersRightGesture = true;
                }
            }
        }
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
                final boolean fromTwoFingers = twoFingersRightGesture;
                twoFingersRightGesture = false;
                // 普通模式下抬起的是按下时记录的触发指时，落入 case 2 的共享逻辑处理
                if (moveCursorToTouchpoint || fingerPointerButtonRight == null || fingerPointerButtonRight != finger1) {
                    if (moveCursorToTouchpoint) {
                        float f1x = unzoom(finger1.x, pinchCenterX);
                        float f1y = unzoom(finger1.y, pinchCenterY);
                        // 双指右键手势已处理点击，最后一指抬起不再补发单指 tap 点击
                        if (!fromTwoFingers && finger1.isTap()) {
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
                        if (LONG_PRESS_RIGHT_CLICK_ENABLED && !fromTwoFingers && finger1.isLongPress() && longPressRightClick) {
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
                        if (swapMouseButtons) {
                            pressPointerButtonRight(finger1);
                            releasePointerButtonRight(finger1);
                        } else {
                            pressPointerButtonLeft(finger1);
                            releasePointerButtonLeft(finger1);
                        }
                    }
                    break;
                }
                // fall through：触发指抬起，由 case 2 补发右键点击或释放左键
            case 2:
                // 普通模式：抬起的是按下时记录的触发指时，未拖拽则补发右键点击，已拖拽则释放左键
                if (!moveCursorToTouchpoint && fingerPointerButtonRight != null && fingerPointerButtonRight == finger1) {
                    if (fingerPointerButtonLeft != finger1) {
                        pressPointerButtonRight(finger1);
                        releasePointerButtonRight(finger1);
                        return;
                    }
                    fingerPointerButtonRight = null;
                    releasePointerButtonLeft(finger1);
                    return;
                }
                // 双指按键已在按下时触发，这里按“实际按下者”释放：
                // releasePointerButton* 要求 f == fingerPointerButtonX 才生效，
                // 若先抬起的正是触发按键的那根手指，用抬起指去释放会落空，导致按键卡死。
                if (rightPressedOnDown) {
                    if (swapMouseButtons) {
                        if (fingerPointerButtonLeft != null) releasePointerButtonLeft(fingerPointerButtonLeft);
                    }
                    else if (fingerPointerButtonRight != null) releasePointerButtonRight(fingerPointerButtonRight);
                    rightPressedOnDown = false;
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
            if (!moveCursorToTouchpoint && fingerPointerButtonRight != null) {
                // 普通模式双指：按下时记录了触发指（此时未按右键），此处做左键拖拽判定——
                // 触发指移动量领先另一指 30px 以上时按下左键，否则本次移动不处理
                if (fingerPointerButtonRight == finger1) {
                    if (finger1.travelDistance() - finger2.travelDistance() >= 30) {
                        pressPointerButtonLeft(finger1);
                    } else {
                        return;
                    }
                }
            } else {
                float currDist = (float) Math.hypot(finger1.x - finger2.x, finger1.y - finger2.y) * resolutionScale;
                boolean rightHeld = xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT);
                if (!rightHeld && twoFingersScroll && currDist < MAX_TWO_FINGERS_SCROLL_DISTANCE) {
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
                } else if (twoFingersDrag && rightHeld) {
                    // 双指右键拖拽：受 twoFingersDrag 开关控制；按住右键时指针绝对跟随“触发右键的那根指”，
                    // 而不是 findSecondFinger 动态返回的另一指，否则任一指移动都会把鼠标带到另一指处。
                    // 任意两指间距均生效；右键指基本不动则不移动，保持纯双指右键点击。
                    Finger target = (fingerPointerButtonRight != null) ? fingerPointerButtonRight : finger2;
                    if (target.travelDistance() >= MAX_TAP_TRAVEL_DISTANCE) {
                        xServer.injectPointerMove(target.x, target.y);
                        skipPointerMove = true;
                    }
                }
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
                    // 双指右键手势未结束（twoFingersRightGesture 在最后一指抬起才清零）时，
                    // 剩余单指继续移动不再自动按左键，避免“右键拖完变左键拖”
                    if (!twoFingersRightGesture && finger1.travelDistance() > MAX_TAP_TRAVEL_DISTANCE && !isLongDrag) {
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
            case MotionEvent.ACTION_CANCEL:
                fingers[0] = null;
                numFingers = 0;
                scrolling = false;
                isShortDrag = false;
                isLongDrag = false;
                leftPressedOnDown = false;
                rightPressedOnDown = false;
                twoFingersRightGesture = false;
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