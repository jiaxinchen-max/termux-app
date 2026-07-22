package com.termux.x11.controller.winhandler;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import com.termux.x11.controller.inputcontrols.ControlsProfile;
import com.termux.x11.controller.inputcontrols.ExternalController;
import com.termux.x11.controller.inputcontrols.GamepadSlot;
import com.termux.x11.controller.inputcontrols.GamepadState;
import com.termux.x11.controller.inputcontrols.GamepadVibration;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public class GamepadHandler {
    public static final byte DINPUT_MAPPER_TYPE_STANDARD = 0;
    public static final byte DINPUT_MAPPER_TYPE_XINPUT = 1;
    public static final byte AXIS_MODE_X_Y_Z_RZ = 0;
    public static final byte AXIS_MODE_X_Y_RX_RY_Z_RZ = 1;
    private static final byte GAMEPAD_MAX_COUNT = 4;
    private static final short PACKET_LENGTH = 256;
    private final WinHandler winHandler;
    private final List<Integer> gamepadClients = new CopyOnWriteArrayList<>();
    private byte dinputMapperType = DINPUT_MAPPER_TYPE_XINPUT;
    private final GamepadSlot[] gamepadSlots = new GamepadSlot[GAMEPAD_MAX_COUNT];
    private final ArrayList<ExternalController> connectedControllers = new ArrayList<>(GAMEPAD_MAX_COUNT);
    private GamepadPlayerConfig[] gamepadPlayerConfigs;
    private short[] gamepadModelIds;

    public static class GamepadModel {
        public final String name;
        public final short vendorId;
        public final short productId;

        public GamepadModel(String name, short vendorId, short productId) {
            this.name = name;
            this.vendorId = vendorId;
            this.productId = productId;
        }

        public String identifier() {
            return String.format(Locale.ENGLISH, "VID_%04X&PID_%04X", vendorId, productId);
        }

        @NonNull
        @Override
        public String toString() {
            return name;
        }
    }

    public GamepadHandler(WinHandler winHandler) {
        this.winHandler = winHandler;
    }

    private void updateGamepadSlots() {
        if (gamepadPlayerConfigs == null) {
            SharedPreferences preferences = winHandler.host.getActivity().getPreferences(Context.MODE_PRIVATE);
            gamepadPlayerConfigs = new GamepadPlayerConfig[GAMEPAD_MAX_COUNT];
            for (byte i = 0; i < GAMEPAD_MAX_COUNT; i++) {
                gamepadPlayerConfigs[i] = new GamepadPlayerConfig(preferences.getString("gamepad_player"+i, ""));
            }
        }

        if (gamepadModelIds == null) {
            SharedPreferences preferences = winHandler.host.getActivity().getPreferences(Context.MODE_PRIVATE);
            String gamepadModel = preferences.getString("gamepad_model", null);
            if (gamepadModel != null && gamepadModel.length() >= 18) {
                gamepadModelIds = new short[]{
                    (short)Integer.parseInt(gamepadModel.substring(4, 8), 16),
                    (short)Integer.parseInt(gamepadModel.substring(13, 17), 16),
                };
            }
            else gamepadModelIds = new short[0];
        }

        ControlsProfile profile = winHandler.host.getInputControlsView().getProfile();
        boolean useVirtualGamepad = profile != null && profile.isVirtualGamepad();

        for (byte i = 0; i < GAMEPAD_MAX_COUNT; i++) gamepadSlots[i] = null;

        synchronized (connectedControllers) {
            ExternalController.updateConnectedControllers(connectedControllers);
        }

        boolean autoAssign = true;
        for (byte i = 0; i < GAMEPAD_MAX_COUNT; i++) {
            GamepadPlayerConfig config = gamepadPlayerConfigs[i];
            if (config.name.isEmpty()) continue;
            if (config.mode == GamepadPlayerConfig.MODE_EXTERNAL_CONTROLLER) {
                for (ExternalController controller : connectedControllers) {
                    if (controller.getName().equals(config.name)) {
                        gamepadSlots[i] = controller;
                        autoAssign = false;
                        break;
                    }
                }
            }
            else if (useVirtualGamepad && profile.getName().equals(config.name)) {
                gamepadSlots[i] = profile;
                autoAssign = false;
            }
        }

        if (autoAssign) {
            if (useVirtualGamepad) gamepadSlots[0] = profile;
            int index = 0;
            for (byte i = 0; i < GAMEPAD_MAX_COUNT; i++) {
                if (gamepadSlots[i] != null) continue;
                gamepadSlots[i] = index < connectedControllers.size() ? connectedControllers.get(index) : null;
                index++;
            }
        }
    }

    private boolean isAnyGamepadConnected() {
        for (GamepadSlot gamepadSlot : gamepadSlots) if (gamepadSlot != null) return true;
        return false;
    }

    public void handleGetGamepadRequest(int port) {
        updateGamepadSlots();

        int clientIndex = gamepadClients.indexOf(port);
        if (isAnyGamepadConnected()) {
            if (clientIndex == -1) gamepadClients.add(port);
        }
        else if (clientIndex != -1) gamepadClients.remove(Integer.valueOf(port));

        winHandler.addAction(() -> {
            final ByteBuffer buffer = winHandler.sendData;
            buffer.rewind();
            buffer.put(RequestCodes.GET_GAMEPAD);

            int buttonCount = 0;
            int axisMode = -1;

            if (dinputMapperType == DINPUT_MAPPER_TYPE_XINPUT) {
                buttonCount = 10;
                axisMode = AXIS_MODE_X_Y_RX_RY_Z_RZ;
            }
            else if (dinputMapperType == DINPUT_MAPPER_TYPE_STANDARD) {
                buttonCount = 12;
                axisMode = AXIS_MODE_X_Y_Z_RZ;
            }

            for (byte i = 0; i < GAMEPAD_MAX_COUNT; i++) {
                buffer.position(i * 60 + 1);
                if (gamepadSlots[i] != null) {
                    buffer.put((byte)1);
                    buffer.put((byte)buttonCount);
                    buffer.put((byte)axisMode);
                    buffer.put((byte)(gamepadPlayerConfigs != null && gamepadPlayerConfigs[i].vibration ? 1 : 0));

                    if (gamepadModelIds.length == 2) {
                        buffer.putShort(gamepadModelIds[0]);
                        buffer.putShort(gamepadModelIds[1]);
                    }
                    else {
                        buffer.putShort(gamepadSlots[i].getVendorId());
                        buffer.putShort(gamepadSlots[i].getProductId());
                    }

                    String name = gamepadSlots[i].getName();
                    byte[] bytes = name.getBytes();
                    byte nameLength = (byte)Math.min((byte)bytes.length, 48);
                    buffer.put(nameLength);
                    buffer.put(bytes, 0, nameLength);
                }
                else {
                    buffer.put((byte)0);
                    buffer.put((byte)0);
                    buffer.put((byte)0);
                    buffer.put((byte)0);
                    buffer.putShort((short)0);
                    buffer.putShort((short)0);
                    buffer.put((byte)0);
                }
            }

            winHandler.sendPacket(port, PACKET_LENGTH);
        });
    }

    private void writeStateToBuffer(ByteBuffer buffer, GamepadState state) {
        if (dinputMapperType == DINPUT_MAPPER_TYPE_XINPUT) {
            buffer.putShort(state.buttons);
            buffer.put(state.getPovHat());
            buffer.putShort((short)(state.thumbLX * Short.MAX_VALUE));
            buffer.putShort((short)(state.thumbLY * Short.MAX_VALUE));
            buffer.putShort((short)(state.thumbRX * Short.MAX_VALUE));
            buffer.putShort((short)(state.thumbRY * Short.MAX_VALUE));
            buffer.putShort((short)(state.triggerL * Short.MAX_VALUE));
            buffer.putShort((short)(state.triggerR * Short.MAX_VALUE));
        }
        else if (dinputMapperType == DINPUT_MAPPER_TYPE_STANDARD) {
            short buttons = state.buttons;
            if (state.triggerL > 0) buttons |= (1<<ExternalController.IDX_BUTTON_L2);
            if (state.triggerR > 0) buttons |= (1<<ExternalController.IDX_BUTTON_R2);
            buffer.putShort(buttons);
            buffer.put(state.getPovHat());
            buffer.putShort((short)(state.thumbLX * Short.MAX_VALUE));
            buffer.putShort((short)(state.thumbLY * Short.MAX_VALUE));
            buffer.putShort((short)(state.thumbRX * Short.MAX_VALUE));
            buffer.putShort((short)(state.thumbRY * Short.MAX_VALUE));
        }
    }

    public void sendGamepadState(final GamepadSlot gamepadSlot) {
        if (!winHandler.initReceived || gamepadClients.isEmpty()) return;
        final byte slot = (byte)indexOfSlot(gamepadSlot);
        if (slot == -1) return;
        final GamepadState state = gamepadSlot.getGamepadState();
        final ByteBuffer buffer = winHandler.sendData;

        for (final int port : gamepadClients) {
            winHandler.addAction(() -> {
                buffer.rewind();
                buffer.put(RequestCodes.GET_GAMEPAD_STATE);
                buffer.put(slot);
                writeStateToBuffer(buffer, state);
                winHandler.sendPacket(port, PACKET_LENGTH);
            });
        }
    }

    private byte indexOfSlot(GamepadSlot slot) {
        for (byte i = 0; i < gamepadSlots.length; i++) {
            if (gamepadSlots[i] == slot) return i;
        }
        return -1;
    }

    public void handleGetGamepadStateRequest(int port) {
        final byte slot = winHandler.receiveData.get();
        winHandler.addAction(() -> {
            final ByteBuffer buffer = winHandler.sendData;
            buffer.rewind();
            buffer.put(RequestCodes.GET_GAMEPAD_STATE);

            if (slot >= 0 && slot < GAMEPAD_MAX_COUNT && gamepadSlots[slot] != null) {
                buffer.put((byte)1);
                writeStateToBuffer(buffer, gamepadSlots[slot].getGamepadState());
            }
            else {
                buffer.put((byte)0);
                buffer.position(buffer.position() + 15); // Skip empty state
            }

            winHandler.sendPacket(port, PACKET_LENGTH);
        });
    }

    public void handleReleaseGamepadRequest(int port) {
        int index = gamepadClients.indexOf(port);
        if (index != -1) gamepadClients.remove(Integer.valueOf(port));
    }

    public void handleSetGamepadStateRequest(int port) {
        final ByteBuffer buffer = winHandler.receiveData;
        byte slot = buffer.get();
        if (slot < 0 || slot >= GAMEPAD_MAX_COUNT) return;
        int leftMotorSpeed = buffer.getInt();
        int rightMotorSpeed = buffer.getInt();
        int durationMs = buffer.getInt();

        GamepadSlot gamepadSlot = gamepadSlots[slot];
        if (gamepadSlot == null) return;

        GamepadVibration vibration = gamepadSlot.getGamepadVibration();
        vibration.vibrate(leftMotorSpeed, rightMotorSpeed, durationMs);
    }

    private ExternalController getConnectedControllerById(int deviceId) {
        synchronized (connectedControllers) {
            for (ExternalController controller : connectedControllers) {
                if (controller.getDeviceId() == deviceId) return controller;
            }
            return null;
        }
    }

    public boolean onGenericMotionEvent(MotionEvent event) {
        boolean handled = false;
        ExternalController controller = getConnectedControllerById(event.getDeviceId());
        if (controller != null) {
            handled = controller.updateStateFromMotionEvent(event);
            if (handled) sendGamepadState(controller);
        }
        return handled;
    }

    public boolean onKeyEvent(KeyEvent event) {
        boolean handled = false;
        ExternalController controller = getConnectedControllerById(event.getDeviceId());
        if (controller != null && event.getRepeatCount() == 0) {
            int action = event.getAction();

            if (action == KeyEvent.ACTION_DOWN) {
                handled = controller.updateStateFromKeyEvent(event);
            }
            else if (action == KeyEvent.ACTION_UP) {
                handled = controller.updateStateFromKeyEvent(event);
            }

            if (handled) sendGamepadState(controller);
        }
        return handled;
    }

    public void sendGamepadStateForAllControllers() {
        if (!winHandler.initReceived || gamepadClients.isEmpty()) return;
        for (GamepadSlot slot : gamepadSlots) {
            if (slot != null) sendGamepadState(slot);
        }
    }

    public ExternalController getCurrentController() {
        for (GamepadSlot slot : gamepadSlots) {
            if (slot instanceof ExternalController) return (ExternalController) slot;
        }
        return null;
    }

    public byte getDInputMapperType() {
        return dinputMapperType;
    }

    public void setDInputMapperType(byte dinputMapperType) {
        this.dinputMapperType = dinputMapperType;
    }

    public static ArrayList<GamepadModel> loadGamepadModels(Context context) {
        ArrayList<GamepadModel> result = new ArrayList<>();
        result.add(new GamepadModel("Default (no override)", (short)0x0001, (short)0x0001));
        try {
            String json = com.termux.x11.controller.core.FileUtils.readString(context, "gamepad_models.json");
            JSONArray jsonArray = new JSONArray(json);

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject item = jsonArray.getJSONObject(i);
                short vendorId = (short)Integer.parseInt(item.getString("vid"), 16);
                short productId = (short)Integer.parseInt(item.getString("pid"), 16);
                result.add(new GamepadModel(item.getString("name"), vendorId, productId));
            }
        }
        catch (JSONException e) {}
        return result;
    }
}
