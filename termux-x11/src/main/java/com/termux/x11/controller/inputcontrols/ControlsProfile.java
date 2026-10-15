package com.termux.x11.controller.inputcontrols;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.x11.controller.core.FileUtils;
import com.termux.x11.controller.widget.InputControlsView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ControlsProfile implements Comparable<ControlsProfile>, GamepadSlot {
    public final int id;
    private String name;
    private float cursorSpeed = 1.0f;
    private boolean disableMouseInput = false;
    private byte touchpadMode = 0;
    private boolean moveCursorToTouchpoint = false;
    private boolean twoFingersDrag = true;
    private boolean twoFingersRightClick = true;
    private boolean longPressRightClick = true;
    private boolean pinchZoomEnabled = false;
    private boolean shortDragEnabled = false;
    private boolean twoFingersScroll = true;
    private final ArrayList<ControlElement> elements = new ArrayList<>();
    private final ArrayList<ExternalController> controllers = new ArrayList<>();
    private final List<ControlElement> immutableElements = Collections.unmodifiableList(elements);
    private final List<ExternalController> immutableControllers = Collections.unmodifiableList(controllers);
    private boolean elementsLoaded = false;
    private boolean controllersLoaded = false;
    private boolean virtualGamepad = false;
    private final Context context;
    private GamepadState gamepadState;
    private GamepadVibration gamepadVibration;

    public ControlsProfile(Context context, int id) {
        this.context = context;
        this.id = id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public short getVendorId() {
        return 0x0001;
    }

    @Override
    public short getProductId() {
        return 0x0001;
    }

    public void setName(String name) {
        this.name = name;
    }

    public float getCursorSpeed() {
        return cursorSpeed;
    }

    public void setCursorSpeed(float cursorSpeed) {
        this.cursorSpeed = cursorSpeed;
    }

    public boolean isDisableMouseInput() {
        return disableMouseInput;
    }

    public void setDisableMouseInput(boolean disableMouseInput) {
        this.disableMouseInput = disableMouseInput;
    }

    public byte getTouchpadMode() {
        return touchpadMode;
    }

    public void setTouchpadMode(byte touchpadMode) {
        this.touchpadMode = touchpadMode;
    }

    public boolean isMoveCursorToTouchpoint() {
        return moveCursorToTouchpoint;
    }

    public void setMoveCursorToTouchpoint(boolean moveCursorToTouchpoint) {
        this.moveCursorToTouchpoint = moveCursorToTouchpoint;
    }

    public boolean isTwoFingersDrag() {
        return twoFingersDrag;
    }

    public void setTwoFingersDrag(boolean twoFingersDrag) {
        this.twoFingersDrag = twoFingersDrag;
    }

    public boolean isTwoFingersRightClick() {
        return twoFingersRightClick;
    }

    public void setTwoFingersRightClick(boolean twoFingersRightClick) {
        this.twoFingersRightClick = twoFingersRightClick;
    }

    public boolean isLongPressRightClick() {
        return longPressRightClick;
    }

    public void setLongPressRightClick(boolean longPressRightClick) {
        this.longPressRightClick = longPressRightClick;
    }

    public boolean isPinchZoomEnabled() {
        return pinchZoomEnabled;
    }

    public void setPinchZoomEnabled(boolean pinchZoomEnabled) {
        this.pinchZoomEnabled = pinchZoomEnabled;
    }

    public boolean isShortDragEnabled() {
        return shortDragEnabled;
    }

    public void setShortDragEnabled(boolean shortDragEnabled) {
        this.shortDragEnabled = shortDragEnabled;
    }

    public boolean isTwoFingersScroll() {
        return twoFingersScroll;
    }

    public void setTwoFingersScroll(boolean twoFingersScroll) {
        this.twoFingersScroll = twoFingersScroll;
    }

    public boolean isVirtualGamepad() {
        return virtualGamepad;
    }

    @Override
    public GamepadState getGamepadState() {
        if (gamepadState == null) gamepadState = new GamepadState();
        return gamepadState;
    }

    @Override
    public GamepadVibration getGamepadVibration() {
        if (gamepadVibration == null) gamepadVibration = new GamepadVibration(context);
        return gamepadVibration;
    }

    public ExternalController addController(String id) {
        ExternalController controller = getController(id);
        if (controller == null) {
            // 设备当前未连接时 ExternalController.getController(id) 返回 null，
            // 原实现会把 null 塞进 controllers，之后 save() 遍历 toJSONObject() 直接 NPE
            controller = ExternalController.getController(id);
            if (controller != null) controllers.add(controller);
        }
        controllersLoaded = true;
        return controller;
    }

    public void removeController(ExternalController controller) {
        if (!controllersLoaded) loadControllers();
        controllers.remove(controller);
    }

    public ExternalController getController(String id) {
        if (!controllersLoaded) loadControllers();
        for (ExternalController controller : controllers) if (controller.getId().equals(id)) return controller;
        return null;
    }

    public ExternalController getController(int deviceId) {
        if (!controllersLoaded) loadControllers();
        for (ExternalController controller : controllers) if (controller.getDeviceId() == deviceId) return controller;
        return null;
    }

    public List<ExternalController> getControllers() {
        return immutableControllers;
    }

    @NonNull
    @Override
    public String toString() {
        return name;
    }

    @Override
    public int compareTo(ControlsProfile o) {
        return Integer.compare(id, o.id);
    }

    public boolean isElementsLoaded() {
        return elementsLoaded;
    }

    public void save() {
        File file = getProfileFile(context, id);

        try {
            JSONObject data = new JSONObject();
            data.put("id", id);
            data.put("name", name);
            data.put("cursorSpeed", Float.valueOf(cursorSpeed));
            if (disableMouseInput) data.put("disableMouseInput", disableMouseInput);
            if (touchpadMode != 0) data.put("touchpadMode", touchpadMode);
            if (moveCursorToTouchpoint) data.put("moveCursorToTouchpoint", moveCursorToTouchpoint);
            if (!twoFingersDrag) data.put("twoFingersDrag", twoFingersDrag);
            if (!twoFingersRightClick) data.put("twoFingersRightClick", twoFingersRightClick);
            if (!longPressRightClick) data.put("longPressRightClick", longPressRightClick);
            if (pinchZoomEnabled) data.put("pinchZoomEnabled", pinchZoomEnabled);
            if (shortDragEnabled) data.put("shortDragEnabled", shortDragEnabled);
            if (!twoFingersScroll) data.put("twoFingersScroll", twoFingersScroll);
            
            JSONArray elementsJSONArray = new JSONArray();
            if (!elementsLoaded && file.isFile()) {
                JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
                elementsJSONArray = profileJSONObject.getJSONArray("elements");
            }
            else for (ControlElement element : elements) elementsJSONArray.put(element.toJSONObject());
            data.put("elements", elementsJSONArray);

            JSONArray controllersJSONArray = new JSONArray();
            if (!controllersLoaded && file.isFile()) {
                JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
                if (profileJSONObject.has("controllers")) controllersJSONArray = profileJSONObject.getJSONArray("controllers");
            }
            else {
                for (ExternalController controller : controllers) {
                    JSONObject controllerJSONObject = controller.toJSONObject();
                    if (controllerJSONObject != null) controllersJSONArray.put(controllerJSONObject);
                }
            }
            if (controllersJSONArray.length() > 0) data.put("controllers", controllersJSONArray);

            FileUtils.writeString(file, data.toString());
        }
        catch (JSONException e) {}
    }

    public static File getProfileFile(Context context, int id) {
        return new File(InputControlsManager.getProfilesDir(context), "controls-"+id+".icp");
    }

    public void addElement(ControlElement element) {
        elements.add(element);
        elementsLoaded = true;
    }

    public void removeElement(ControlElement element) {
        elements.remove(element);
        elementsLoaded = true;
    }

    public List<ControlElement> getElements() {
        return immutableElements;
    }

    public boolean isTemplate() {
        return name.toLowerCase(Locale.ENGLISH).contains("template");
    }

    public ArrayList<ExternalController> loadControllers() {
        controllers.clear();
        controllersLoaded = false;

        File file = getProfileFile(context, id);
        if (!file.isFile()) return controllers;

        try {
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            if (!profileJSONObject.has("controllers")) return controllers;
            JSONArray controllersJSONArray = profileJSONObject.getJSONArray("controllers");
            for (int i = 0; i < controllersJSONArray.length(); i++) {
                JSONObject controllerJSONObject = controllersJSONArray.getJSONObject(i);
                String id = controllerJSONObject.getString("id");
                ExternalController controller = new ExternalController();
                controller.setId(id);
                controller.setName(controllerJSONObject.getString("name"));

                JSONArray controllerBindingsJSONArray = controllerJSONObject.getJSONArray("controllerBindings");
                for (int j = 0; j < controllerBindingsJSONArray.length(); j++) {
                    JSONObject controllerBindingJSONObject = controllerBindingsJSONArray.getJSONObject(j);
                    ExternalControllerBinding controllerBinding = new ExternalControllerBinding();
                    controllerBinding.setKeyCode(controllerBindingJSONObject.getInt("keyCode"));
                    controllerBinding.setBinding(Binding.fromString(controllerBindingJSONObject.getString("binding")));
                    controller.addControllerBinding(controllerBinding);
                }
                controllers.add(controller);
            }
            controllersLoaded = true;
        }
        catch (JSONException e) {
            e.printStackTrace();
        }
        return controllers;
    }

    public void loadElements(InputControlsView inputControlsView) {
        elements.clear();
        elementsLoaded = false;
        virtualGamepad = false;

        File file = getProfileFile(context, id);
        if (!file.isFile()) return;

        try {
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            JSONArray elementsJSONArray = profileJSONObject.getJSONArray("elements");
            for (int i = 0; i < elementsJSONArray.length(); i++) {
                JSONObject elementJSONObject = elementsJSONArray.getJSONObject(i);

                ControlElement element = null;
                if (inputControlsView != null) {
                    element = new ControlElement(inputControlsView);
                    element.setType(ControlElement.Type.valueOf(elementJSONObject.getString("type")));
                    element.setShape(ControlElement.Shape.valueOf(elementJSONObject.getString("shape")));
                    element.setToggleSwitch(elementJSONObject.getBoolean("toggleSwitch"));
                    element.setX((int)(elementJSONObject.getDouble("x") * inputControlsView.getMaxWidth()));
                    element.setY((int)(elementJSONObject.getDouble("y") * inputControlsView.getMaxHeight()));
                    element.setPercentX((float)elementJSONObject.getDouble("x"));
                    element.setPercentY((float)elementJSONObject.getDouble("y"));
                    element.setScale((float)elementJSONObject.getDouble("scale"));
                    element.setText(elementJSONObject.getString("text"));
                    element.setIconId(elementJSONObject.getInt("iconId"));
                    if (elementJSONObject.has("mouseBtn")) element.setMouseBtn(ControlElement.MouseBtn.valueOf(elementJSONObject.getString("mouseBtn")));
                    if (elementJSONObject.has("xDrift")) element.setXDrift((short)elementJSONObject.getInt("xDrift"));
                    if (elementJSONObject.has("yDrift")) element.setYDrift((short)elementJSONObject.getInt("yDrift"));
                    if (elementJSONObject.has("deadZone")) element.setDeadZone((short)elementJSONObject.getInt("deadZone"));
                    if (elementJSONObject.has("customIconData")) element.setCustomIconData(elementJSONObject.getString("customIconData"));
                    if (elementJSONObject.has("pressedColor")) element.setPressedColor(elementJSONObject.getInt("pressedColor"));
                    if (elementJSONObject.has("textColor")) element.setTextColor(elementJSONObject.getInt("textColor"));
                    if (elementJSONObject.has("iconScale")) element.setIconScale((float)elementJSONObject.getDouble("iconScale"));
                    if (elementJSONObject.has("iconOpacity")) element.setIconOpacity((float)elementJSONObject.getDouble("iconOpacity"));
                    if (elementJSONObject.has("range")) element.setRange(ControlElement.Range.valueOf(elementJSONObject.getString("range")));
                    if (elementJSONObject.has("orientation")) element.setOrientation((byte)elementJSONObject.getInt("orientation"));
                    if (elementJSONObject.has("mouseMoveMode")) element.setMouseMoveMode(true);
                    if (elementJSONObject.has("opacity")) element.setOpacity((float)elementJSONObject.getDouble("opacity"));
                }

                boolean hasGamepadBinding = true;
                JSONArray bindingsJSONArray = elementJSONObject.getJSONArray("bindings");
                for (int j = 0; j < bindingsJSONArray.length(); j++) {
                    Binding binding = Binding.fromString(bindingsJSONArray.getString(j));
                    if (element != null) element.setBindingAt(j, Binding.fromString(bindingsJSONArray.getString(j)));
                    if (!binding.isGamepad()) hasGamepadBinding = false;
                }

                if (!virtualGamepad && hasGamepadBinding) virtualGamepad = true;
                if (element != null) elements.add(element);
            }
            elementsLoaded = true;
        }
        catch (JSONException e) {
            e.printStackTrace();
        }
    }
}
