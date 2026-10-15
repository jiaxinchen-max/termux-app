package com.termux.x11.controller;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.x11.controller.core.ImageUtils;
import com.termux.x11.controller.core.LocaleHelper;
import com.termux.x11.controller.inputcontrols.Binding;
import com.termux.x11.controller.inputcontrols.ControlElement;
import com.termux.x11.controller.inputcontrols.ControlsProfile;
import com.termux.x11.controller.inputcontrols.IconPackManager;
import com.termux.x11.controller.inputcontrols.InputControlsManager;
import com.termux.x11.controller.math.Mathf;
import com.termux.x11.controller.core.AppUtils;
import com.termux.x11.controller.core.FileUtils;
import com.termux.x11.controller.core.UnitUtils;
import com.termux.x11.controller.contentdialog.ContentDialog;
import com.termux.x11.controller.widget.ColorPickerView;
import com.termux.x11.controller.widget.InputControlsView;
import com.termux.x11.controller.widget.NumberPicker;
import com.termux.x11.controller.widget.SeekBar;
import com.termux.x11.controller.winhandler.MIDIHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import com.termux.x11.R;

public class ControlsEditorActivity extends AppCompatActivity implements View.OnClickListener {
    private InputControlsView inputControlsView;
    private ControlsProfile profile;
    private View toolbox;
    private boolean isPortraitMode = false;
    private static final int OPEN_CUSTOM_ICON_REQUEST_CODE = 1001;
    private static final int IMPORT_ICON_PACK_REQUEST_CODE = 1002;
    private static final int EXPORT_ICON_PACK_REQUEST_CODE = 1003;
    private static final int CREATE_ICON_PACK_REQUEST_CODE = 1004;
    private IconPackManager iconPackManager;
    private PopupWindow elementSettingsPopup;
    private ControlElement editingElement;
    private LinearLayout editingIconList;
    private ImageView editingCustomIconPreview;
    private byte editingSelectedIconId;
    private String editingCustomIconData = "";
    private String pendingExportPackId;
    private String pendingCreatePackName;

    @Override
    public void onCreate(Bundle bundle) {
        AppUtils.setActivityTheme(this);
        super.onCreate(bundle);
        AppUtils.hideSystemUI(this);
        setContentView(R.layout.controls_editor_activity);

        inputControlsView = new InputControlsView(this);
        inputControlsView.setEditMode(true);
        inputControlsView.setOverlayOpacity(0.6f);

        iconPackManager = new IconPackManager(this);

        profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, getIntent().getIntExtra("profile_id", 0)));
        // 配置已被删除或 id 失效时 loadProfile 返回 null，直接往下走会在 getName() 处 NPE 闪退
        if (profile == null) {
            AppUtils.showToast(this, R.string.no_profile_selected);
            finish();
            return;
        }
        ((TextView)findViewById(R.id.TVProfileName)).setText(profile.getName());
        inputControlsView.setProfile(profile);

        FrameLayout container = findViewById(R.id.FLContainer);
        container.addView(inputControlsView, 0);

        container.findViewById(R.id.BTAddElement).setOnClickListener(this);
        container.findViewById(R.id.BTRemoveElement).setOnClickListener(this);
        container.findViewById(R.id.BTElementSettings).setOnClickListener(this);
        container.findViewById(R.id.BTIconPackManager).setOnClickListener(this);
        container.findViewById(R.id.BTRotateScreen).setOnClickListener(this);

        toolbox = container.findViewById(R.id.Toolbox);

        final PointF startPoint = new PointF();
        final boolean[] isActionDown = {false};
        container.findViewById(R.id.BTMove).setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startPoint.x = event.getX();
                    startPoint.y = event.getY();
                    isActionDown[0] = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isActionDown[0]) {
                        float newX = toolbox.getX() + (event.getX() - startPoint.x);
                        float newY = toolbox.getY() + (event.getY() - startPoint.y);
                        moveToolbox(newX, newY);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    isActionDown[0] = false;
                    break;
            }
            return true;
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateToolboxLayout();
        inputControlsView.post(() -> recalculateElementCoordinates());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshEditingIconList();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == OPEN_CUSTOM_ICON_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            if (editingCustomIconPreview == null) return;
            ArrayList<String> filePaths = data.getStringArrayListExtra(ImageFilePickerActivity.EXTRA_SELECTED_FILES);
            if (filePaths == null || filePaths.isEmpty()) return;

            Bitmap bitmap = BitmapFactory.decodeFile(filePaths.get(0));
            if (bitmap == null) {
                AppUtils.showToast(this, R.string.unable_to_load_image);
                return;
            }

            Bitmap normalizedBitmap = normalizeIconBitmap(bitmap, (int)UnitUtils.dpToPx(64));
            editingCustomIconData = encodeBitmapToBase64(normalizedBitmap);
            editingSelectedIconId = 0;
            clearBuiltinIconSelection();
            updateCustomIconPreview(normalizedBitmap);
        }
        else if (requestCode == IMPORT_ICON_PACK_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            try {
                IconPackManager.StoredIconPack pack = iconPackManager.importPack(data.getData());
                if (pack != null) {
                    AppUtils.showToast(this, getString(R.string.icon_pack_imported, pack.name));
                    refreshEditingIconList();
                }
                else AppUtils.showToast(this, R.string.unable_to_import_icon_pack);
            }
            catch (Exception e) {
                e.printStackTrace();
                AppUtils.showToast(this, getString(R.string.unable_to_import_icon_pack_with_reason, getImportErrorMessage(e)));
            }
        }
        else if (requestCode == EXPORT_ICON_PACK_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            try {
                IconPackManager.StoredIconPack pack = iconPackManager.getActivePack();
                if (pack != null && pendingExportPackId != null && pendingExportPackId.equals(pack.id) && iconPackManager.exportPack(pack, data.getData())) {
                    AppUtils.showToast(this, getString(R.string.icon_pack_exported, pack.name));
                }
                else AppUtils.showToast(this, R.string.unable_to_export_icon_pack);
            }
            catch (Exception e) {
                AppUtils.showToast(this, R.string.unable_to_export_icon_pack);
            }
            pendingExportPackId = null;
        }
        else if (requestCode == CREATE_ICON_PACK_REQUEST_CODE) {
            try {
                if (resultCode != Activity.RESULT_OK || data == null) return;

                ArrayList<Uri> selectedUris = getSelectedImageUris(data);
                ArrayList<IconPackManager.SourceIcon> sourceIcons = loadIconPackSourceIcons(selectedUris);
                if (sourceIcons.isEmpty()) {
                    AppUtils.showToast(this, R.string.unable_to_load_image);
                    return;
                }

                IconPackManager.StoredIconPack pack = iconPackManager.createPack(pendingCreatePackName, "", sourceIcons);
                if (pack != null) {
                    AppUtils.showToast(this, getString(R.string.icon_pack_created, pack.name));
                    refreshEditingIconList();
                }
                else AppUtils.showToast(this, R.string.unable_to_create_icon_pack);
            }
            catch (Exception e) {
                AppUtils.showToast(this, R.string.unable_to_create_icon_pack);
            }
            finally {
                pendingCreatePackName = null;
            }
        }
    }

    private void updateToolboxLayout() {
        LinearLayout toolboxLayout = (LinearLayout)toolbox;
        int orientation = getResources().getConfiguration().orientation;
        float density = getResources().getDisplayMetrics().density;

        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            toolboxLayout.setOrientation(LinearLayout.VERTICAL);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            params.gravity = android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP;
            toolboxLayout.setLayoutParams(params);
            toolboxLayout.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

            View separator = toolboxLayout.findViewById(R.id.VSeparator);
            if (separator != null) {
                LinearLayout.LayoutParams sepParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int)(2 * density));
                sepParams.setMargins((int)(8 * density), (int)(4 * density), (int)(8 * density), (int)(4 * density));
                separator.setLayoutParams(sepParams);
            }

            LinearLayout profileInfo = (LinearLayout)toolboxLayout.getChildAt(1);
            if (profileInfo != null) {
                profileInfo.setOrientation(LinearLayout.HORIZONTAL);
                profileInfo.setPadding((int)(4 * density), 0, (int)(4 * density), 0);
            }
        } else {
            toolboxLayout.setOrientation(LinearLayout.HORIZONTAL);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            params.gravity = android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP;
            toolboxLayout.setLayoutParams(params);
            toolboxLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);

            View separator = toolboxLayout.findViewById(R.id.VSeparator);
            if (separator != null) {
                LinearLayout.LayoutParams sepParams = new LinearLayout.LayoutParams((int)(2 * density), LinearLayout.LayoutParams.MATCH_PARENT);
                sepParams.setMargins((int)(8 * density), (int)(2 * density), (int)(8 * density), (int)(2 * density));
                separator.setLayoutParams(sepParams);
            }

            LinearLayout profileInfo = (LinearLayout)toolboxLayout.getChildAt(1);
            if (profileInfo != null) {
                profileInfo.setOrientation(LinearLayout.VERTICAL);
                profileInfo.setPadding((int)(8 * density), 0, (int)(8 * density), 0);
            }
        }
    }

    private void recalculateElementCoordinates() {
        if (profile == null || !profile.isElementsLoaded()) return;
        int maxWidth = inputControlsView.getMaxWidth();
        int maxHeight = inputControlsView.getMaxHeight();
        if (maxWidth == 0 || maxHeight == 0) return;

        for (ControlElement element : profile.getElements()) {
            element.setX((int)(element.getPercentX() * maxWidth));
            element.setY((int)(element.getPercentY() * maxHeight));
        }
        inputControlsView.invalidate();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    private void moveToolbox(float x, float y) {
        final int padding = (int)UnitUtils.dpToPx(8);
        ViewGroup parent = (ViewGroup)toolbox.getParent();
        int width = toolbox.getWidth();
        int height = toolbox.getHeight();
        int parentWidth = parent.getWidth();
        int parentHeight = parent.getHeight();
        x = Mathf.clamp(x, padding, parentWidth - padding - width);
        y = Mathf.clamp(y, padding, parentHeight - padding - height);
        toolbox.setX(x);
        toolbox.setY(y);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.BTAddElement) {
            if (!inputControlsView.addElement()) {
                AppUtils.showToast(this, R.string.no_profile_selected);
            }
        }
        else if (id == R.id.BTRemoveElement) {
            if (!inputControlsView.removeElement()) {
                AppUtils.showToast(this, R.string.no_control_element_selected);
            }
        }
        else if (id == R.id.BTElementSettings) {
            ControlElement selectedElement = inputControlsView.getSelectedElement();
            if (selectedElement != null) {
                showControlElementSettings(v);
            }
            else AppUtils.showToast(this, R.string.no_control_element_selected);
        }
        else if (id == R.id.BTIconPackManager) {
            startActivity(new Intent(this, IconPackManagerActivity.class));
        }
        else if (id == R.id.BTRotateScreen) {
            isPortraitMode = !isPortraitMode;
            setRequestedOrientation(isPortraitMode ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
        }
    }

    private void showControlElementSettings(View anchorView) {
        final ControlElement element = inputControlsView.getSelectedElement();
        final View view = LayoutInflater.from(this).inflate(R.layout.control_element_settings, null);

        final Runnable updateLayout = () -> {
            ControlElement.Type type = element.getType();
            view.findViewById(R.id.LLShape).setVisibility(View.GONE);
            view.findViewById(R.id.CBToggleSwitch).setVisibility(View.GONE);
            view.findViewById(R.id.CBMouseMoveMode).setVisibility(View.GONE);
            view.findViewById(R.id.LLCustomTextIcon).setVisibility(View.GONE);
            view.findViewById(R.id.LLRangeOptions).setVisibility(View.GONE);
            view.findViewById(R.id.LLMIDIKeyOptions).setVisibility(View.GONE);
            view.findViewById(R.id.LLRadialMenuOptions).setVisibility(View.GONE);
            view.findViewById(R.id.LLMouseBtn).setVisibility(View.GONE);
            view.findViewById(R.id.LLDrift).setVisibility(View.GONE);

            switch (type) {
                case BUTTON:
                    view.findViewById(R.id.LLShape).setVisibility(View.VISIBLE);
                    view.findViewById(R.id.CBToggleSwitch).setVisibility(View.VISIBLE);
                    view.findViewById(R.id.CBMouseMoveMode).setVisibility(View.VISIBLE);
                    view.findViewById(R.id.LLCustomTextIcon).setVisibility(View.VISIBLE);
                    break;
                case RANGE_BUTTON:
                    view.findViewById(R.id.LLRangeOptions).setVisibility(View.VISIBLE);
                    break;
                case MIDI_KEY:
                    view.findViewById(R.id.LLMIDIKeyOptions).setVisibility(View.VISIBLE);
                    break;
                case RADIAL_MENU:
                    ((NumberPicker)view.findViewById(R.id.NPBindings)).setValue(element.getBindingCount());
                    view.findViewById(R.id.LLRadialMenuOptions).setVisibility(View.VISIBLE);
                    break;
                case TRACKPAD:
                case TAP_TRACKPAD:
                    view.findViewById(R.id.LLMouseBtn).setVisibility(View.VISIBLE);
                    if (type == ControlElement.Type.TAP_TRACKPAD) {
                        view.findViewById(R.id.LLDrift).setVisibility(View.VISIBLE);
                    }
                    break;
            }

            loadBindingSpinners(element, view);
            loadMouseBtnSpinner(element, view.findViewById(R.id.SMouseBtn));
            loadDriftSeekBars(element, view.findViewById(R.id.SBXDrift), view.findViewById(R.id.SBYDrift));
            loadDeadZoneSeekBar(element, view.findViewById(R.id.SBDeadZone));
        };

        loadTypeSpinner(element, view.findViewById(R.id.SType), updateLayout);
        loadShapeSpinner(element, view.findViewById(R.id.SShape));
        loadRangeSpinner(element, view.findViewById(R.id.SRange));
        loadNoteSpinner(element, view.findViewById(R.id.SNote));
        loadMouseBtnSpinner(element, view.findViewById(R.id.SMouseBtn));
        loadDriftSeekBars(element, view.findViewById(R.id.SBXDrift), view.findViewById(R.id.SBYDrift));
        loadDeadZoneSeekBar(element, view.findViewById(R.id.SBDeadZone));

        RadioGroup rgOrientation = view.findViewById(R.id.RGOrientation);
        rgOrientation.check(element.getOrientation() == 1 ? R.id.RBVertical : R.id.RBHorizontal);
        rgOrientation.setOnCheckedChangeListener((group, checkedId) -> {
            element.setOrientation((byte)(checkedId == R.id.RBVertical ? 1 : 0));
            profile.save();
            inputControlsView.invalidate();
        });

        NumberPicker npColumns = view.findViewById(R.id.NPColumns);
        npColumns.setValue(element.getBindingCount());
        npColumns.setOnValueChangeListener((numberPicker, value) -> {
            element.setBindingCount(value);
            profile.save();
            inputControlsView.invalidate();
        });

        NumberPicker npBindings = view.findViewById(R.id.NPBindings);
        npBindings.setValue(element.getBindingCount());
        npBindings.setOnValueChangeListener((numberPicker, value) -> {
            element.setBindingCount(value);
            loadBindingSpinners(element, view);
            profile.save();
            inputControlsView.invalidate();
        });

        SeekBar sbScale = view.findViewById(R.id.SBScale);
        sbScale.setOnValueChangeListener((seekBar, value) -> {
            element.setScale(value / 100.0f);
            profile.save();
            inputControlsView.invalidate();
        });
        sbScale.setValue(element.getScale() * 100);

        SeekBar sbOpacity = view.findViewById(R.id.SBOpacity);
        sbOpacity.setOnValueChangeListener((seekBar, value) -> {
            element.setOpacity(value / 100.0f);
            profile.save();
            inputControlsView.invalidate();
        });
        sbOpacity.setValue(element.getOpacity() * 100);

        CheckBox cbToggleSwitch = view.findViewById(R.id.CBToggleSwitch);
        cbToggleSwitch.setChecked(element.isToggleSwitch());
        cbToggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            element.setToggleSwitch(isChecked);
            profile.save();
        });

        CheckBox cbMouseMoveMode = view.findViewById(R.id.CBMouseMoveMode);
        cbMouseMoveMode.setChecked(element.isMouseMoveMode());
        cbMouseMoveMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            element.setMouseMoveMode(isChecked);
            profile.save();
        });

        final EditText etCustomText = view.findViewById(R.id.ETCustomText);
        etCustomText.setText(element.getText());

        final SeekBar sbIconScale = view.findViewById(R.id.SBIconScale);
        sbIconScale.setValue(element.getIconScale() * 100);
        sbIconScale.setOnValueChangeListener((seekBar, value) -> {
            element.setIconScale(value / 100.0f);
            profile.save();
            inputControlsView.invalidate();
        });

        final SeekBar sbIconOpacity = view.findViewById(R.id.SBIconOpacity);
        sbIconOpacity.setValue(element.getIconOpacity() * 100);
        sbIconOpacity.setOnValueChangeListener((seekBar, value) -> {
            element.setIconOpacity(value / 100.0f);
            profile.save();
            inputControlsView.invalidate();
        });

        final ColorPickerView cpvPressedColor = view.findViewById(R.id.CPVPressedColor);
        cpvPressedColor.setPalette(0x000000, 0xffffff, 0xd32f2f, 0xff6f00, 0xffea00, 0x2e7d32, 0x00838f, 0x1565c0);
        cpvPressedColor.setColor(element.getPressedColor());

        final ColorPickerView cpvTextColor = view.findViewById(R.id.CPVTextColor);
        cpvTextColor.setPalette(0xffffff, 0x000000, 0xd32f2f, 0xff6f00, 0xffea00, 0x2e7d32, 0x00838f, 0x1565c0);
        cpvTextColor.setColor(element.getTextColor() != -1 ? element.getTextColor() : 0xffffff);
        final int initialTextColor = cpvTextColor.getColor();

        // 全局批量配色：把当前选择的颜色应用到配置里的所有元素
        view.findViewById(R.id.BTApplyColorsToAll).setOnClickListener((v) -> {
            int pressed = cpvPressedColor.getColor();
            int text = cpvTextColor.getColor();
            for (ControlElement e : inputControlsView.getProfile().getElements()) {
                e.setPressedColor(pressed);
                if (text != initialTextColor) e.setTextColor(text);
            }
            profile.save();
            inputControlsView.invalidate();
            AppUtils.showToast(this, R.string.colors_applied);
        });

        editingElement = element;
        editingIconList = view.findViewById(R.id.LLIconList);
        editingCustomIconPreview = view.findViewById(R.id.IVCustomIconPreview);
        editingSelectedIconId = element.hasCustomIcon() ? 0 : element.getIconId();
        editingCustomIconData = element.hasCustomIcon() ? element.getCustomIconData() : "";

        updateCustomIconPreview(element.hasCustomIcon() ? element.getCustomIcon() : null);
        view.findViewById(R.id.BTBrowseCustomIcon).setOnClickListener((v) -> {
            Intent intent = new Intent(this, ImageFilePickerActivity.class);
            intent.putExtra(ImageFilePickerActivity.EXTRA_MODE, ImageFilePickerActivity.MODE_IMAGE);
            intent.putExtra(ImageFilePickerActivity.EXTRA_ALLOW_MULTIPLE, false);
            startActivityForResult(intent, OPEN_CUSTOM_ICON_REQUEST_CODE);
        });
        view.findViewById(R.id.BTClearCustomIcon).setOnClickListener((v) -> {
            editingCustomIconData = "";
            editingSelectedIconId = 0;
            clearBuiltinIconSelection();
            updateCustomIconPreview(null);
        });
        loadIcons(editingIconList, editingSelectedIconId, editingCustomIconData);

        updateLayout.run();

        int screenWidthDp = (int)UnitUtils.pxToDp(AppUtils.getScreenWidth());
        int popupWidthDp = Math.min(340, screenWidthDp - 40);
        elementSettingsPopup = AppUtils.showPopupWindow(anchorView, view, popupWidthDp, 0);
        elementSettingsPopup.setOnDismissListener(() -> {
            // 颜色设置对所有元素类型生效
            element.setPressedColor(cpvPressedColor.getColor());
            int textColor = cpvTextColor.getColor();
            if (textColor != initialTextColor) element.setTextColor(textColor);

            if (element.getType() == ControlElement.Type.BUTTON) {
                String text = etCustomText.getText().toString().trim();
                element.setText(text);
                element.setCustomIconData(editingCustomIconData);
                element.setIconId(editingCustomIconData.isEmpty() ? editingSelectedIconId : 0);
            }
            profile.save();
            inputControlsView.invalidate();
            clearEditingState();
        });
    }

    private void loadTypeSpinner(final ControlElement element, Spinner spinner, final Runnable callback) {
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Type.names(this)));
        spinner.setSelection(element.getType().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ControlElement.Type newType = ControlElement.Type.values()[position];
                if (newType == element.getType()) return;
                element.setType(newType);
                profile.save();
                callback.run();
                inputControlsView.invalidate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadShapeSpinner(final ControlElement element, Spinner spinner) {
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Shape.names(this)));
        spinner.setSelection(element.getShape().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setShape(ControlElement.Shape.values()[position]);
                profile.save();
                inputControlsView.invalidate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadBindingSpinners(ControlElement element, View view) {
        LinearLayout container = view.findViewById(R.id.LLBindings);
        container.removeAllViews();

        ControlElement.Type type = element.getType();
        if (type == ControlElement.Type.BUTTON) {
            byte first = element.getFirstBindingIndex();
            for (byte i = 0, count = 0; i < element.getBindingCount(); i++) {
                if (i <= first || element.getBindingAt(i) != Binding.NONE) {
                    loadBindingSpinner(element, container, i, count++ == 0 ? R.string.binding : 0);
                }
            }
        }
        else if (type == ControlElement.Type.D_PAD || type == ControlElement.Type.STICK || type == ControlElement.Type.TRACKPAD || type == ControlElement.Type.TAP_TRACKPAD) {
            loadBindingSpinner(element, container, 0, R.string.binding_up);
            loadBindingSpinner(element, container, 1, R.string.binding_right);
            loadBindingSpinner(element, container, 2, R.string.binding_down);
            loadBindingSpinner(element, container, 3, R.string.binding_left);
        }
        else if (type == ControlElement.Type.RADIAL_MENU) {
            for (byte i = 0; i < element.getBindingCount(); i++) loadBindingSpinner(element, container, i, 0);
        }
    }

    private void loadBindingSpinner(final ControlElement element, final LinearLayout container, final int index, int titleResId) {
        View view = LayoutInflater.from(this).inflate(R.layout.binding_field, container, false);

        LinearLayout titleBar = view.findViewById(R.id.LLTitleBar);
        if (titleResId > 0) {
            titleBar.setVisibility(View.VISIBLE);
            ((TextView)view.findViewById(R.id.TVTitle)).setText(titleResId);
        }
        else titleBar.setVisibility(View.GONE);

        final Spinner sBindingType = view.findViewById(R.id.SBindingType);
        final Spinner sBinding = view.findViewById(R.id.SBinding);

        ControlElement.Type type = element.getType();
        if (type == ControlElement.Type.BUTTON || type == ControlElement.Type.RADIAL_MENU) {
            ImageButton addButton = view.findViewById(R.id.BTAdd);
            addButton.setVisibility(View.VISIBLE);
            addButton.setOnClickListener((v) -> {
                int nextIndex = container.getChildCount();
                if (nextIndex < element.getBindingCount()) loadBindingSpinner(element, container, nextIndex, 0);
            });
        }

        Runnable update = () -> {
            String[] bindingEntries = null;
            switch (sBindingType.getSelectedItemPosition()) {
                case 0:
                    bindingEntries = Binding.keyboardBindingLabels(this);
                    break;
                case 1:
                    bindingEntries = Binding.mouseBindingLabels(this);
                    break;
                case 2:
                    bindingEntries = Binding.gamepadBindingLabels(this);
                    break;
            }

            sBinding.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, bindingEntries));
            AppUtils.setSpinnerSelectionFromValue(sBinding, element.getBindingAt(index).getDisplayName(this));
        };

        sBindingType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                update.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        Binding selectedBinding = element.getBindingAt(index);
        if (selectedBinding.isKeyboard()) {
            sBindingType.setSelection(0, false);
        }
        else if (selectedBinding.isMouse()) {
            sBindingType.setSelection(1, false);
        }
        else if (selectedBinding.isGamepad()) {
            sBindingType.setSelection(2, false);
        }

        sBinding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Binding binding = Binding.NONE;
                switch (sBindingType.getSelectedItemPosition()) {
                    case 0:
                        binding = Binding.keyboardBindingValues()[position];
                        break;
                    case 1:
                        binding = Binding.mouseBindingValues()[position];
                        break;
                    case 2:
                        binding = Binding.gamepadBindingValues()[position];
                        break;
                }

                if (binding != element.getBindingAt(index)) {
                    element.setBindingAt(index, binding);
                    profile.save();
                    inputControlsView.invalidate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        update.run();
        container.addView(view);
    }

    private void loadRangeSpinner(final ControlElement element, Spinner spinner) {
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Range.names(this)));
        spinner.setSelection(element.getRange().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setRange(ControlElement.Range.values()[position]);
                profile.save();
                inputControlsView.invalidate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadNoteSpinner(final ControlElement element, Spinner spinner) {
        String[] notes = MIDIHandler.getNotes();
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, notes));
        AppUtils.setSpinnerSelectionFromValue(spinner, element.getText());
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setText(notes[position]);
                profile.save();
                inputControlsView.invalidate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadMouseBtnSpinner(final ControlElement element, Spinner spinner) {
        final ControlElement.MouseBtn[] mouseBtns = ControlElement.MouseBtn.values();
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.MouseBtn.names(this)));
        spinner.setSelection(element.getMouseBtn().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setMouseBtn(mouseBtns[position]);
                profile.save();
                inputControlsView.invalidate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadDriftSeekBars(final ControlElement element, SeekBar sbXDrift, SeekBar sbYDrift) {
        sbXDrift.setValue(element.getXDrift());
        sbXDrift.setOnValueChangeListener((seekBar, value) -> {
            element.setXDrift((short)value);
            profile.save();
            inputControlsView.invalidate();
        });

        sbYDrift.setValue(element.getYDrift());
        sbYDrift.setOnValueChangeListener((seekBar, value) -> {
            element.setYDrift((short)value);
            profile.save();
            inputControlsView.invalidate();
        });
    }

    private void loadDeadZoneSeekBar(final ControlElement element, SeekBar sbDeadZone) {
        sbDeadZone.setValue(element.getDeadZone());
        sbDeadZone.setOnValueChangeListener((seekBar, value) -> {
            element.setDeadZone((short)value);
            profile.save();
            inputControlsView.invalidate();
        });
    }

    private void loadIcons(final LinearLayout parent, byte selectedId, String selectedCustomIconData) {
        parent.removeAllViews();
        byte[] iconIds = new byte[0];
        try {
            String[] filenames = getAssets().list("inputcontrols/icons/");
            iconIds = new byte[filenames.length];
            for (int i = 0; i < filenames.length; i++) {
                iconIds[i] = Byte.parseByte(FileUtils.getBasename(filenames[i]));
            }
        }
        catch (IOException e) {}

        Arrays.sort(iconIds);

        int size = (int)UnitUtils.dpToPx(40);
        int margin = (int)UnitUtils.dpToPx(2);
        int padding = (int)UnitUtils.dpToPx(4);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(margin, 0, margin, 0);

        for (final byte id : iconIds) {
            ImageView imageView = new ImageView(this);
            imageView.setLayoutParams(params);
            imageView.setPadding(padding, padding, padding, padding);
            imageView.setBackgroundResource(R.drawable.icon_background);
            imageView.setTag(id);
            imageView.setSelected(id == selectedId && selectedCustomIconData.isEmpty());
            imageView.setOnClickListener((v) -> {
                for (int i = 0; i < parent.getChildCount(); i++) parent.getChildAt(i).setSelected(false);
                imageView.setSelected(true);
                editingSelectedIconId = id;
                editingCustomIconData = "";
                updateCustomIconPreview(null);
            });

            try (InputStream is = getAssets().open("inputcontrols/icons/"+id+".png")) {
                imageView.setImageBitmap(BitmapFactory.decodeStream(is));
            }
            catch (IOException e) {}

            parent.addView(imageView);
        }

        for (IconPackManager.StoredIconPack activePack : iconPackManager.getActivePacks()) {
            for (final IconPackManager.PackIcon packIcon : activePack.icons) {
                final byte[] iconData = packIcon.readBytes();
                if (iconData == null || iconData.length == 0) continue;

                final String base64IconData = Base64.encodeToString(iconData, Base64.NO_WRAP);
                ImageView imageView = new ImageView(this);
                imageView.setLayoutParams(params);
                imageView.setPadding(padding, padding, padding, padding);
                imageView.setBackgroundResource(R.drawable.icon_background);
                imageView.setSelected(base64IconData.equals(selectedCustomIconData));
                imageView.setOnClickListener((v) -> {
                    for (int i = 0; i < parent.getChildCount(); i++) parent.getChildAt(i).setSelected(false);
                    imageView.setSelected(true);
                    editingSelectedIconId = 0;
                    editingCustomIconData = base64IconData;
                    Bitmap bitmap = BitmapFactory.decodeByteArray(iconData, 0, iconData.length);
                    updateCustomIconPreview(bitmap);
                });
                imageView.setImageBitmap(BitmapFactory.decodeByteArray(iconData, 0, iconData.length));
                parent.addView(imageView);
            }
        }
    }

    private void clearBuiltinIconSelection() {
        if (editingIconList == null) return;
        for (int i = 0; i < editingIconList.getChildCount(); i++) {
            editingIconList.getChildAt(i).setSelected(false);
        }
    }

    private void updateCustomIconPreview(Bitmap bitmap) {
        if (editingCustomIconPreview == null) return;

        if (bitmap != null) {
            editingCustomIconPreview.setImageBitmap(bitmap);
            editingCustomIconPreview.setSelected(true);
        }
        else {
            editingCustomIconPreview.setImageResource(R.drawable.icon_image_picker);
            editingCustomIconPreview.setSelected(false);
        }
    }

    private void clearEditingState() {
        elementSettingsPopup = null;
        editingElement = null;
        editingIconList = null;
        editingCustomIconPreview = null;
        editingSelectedIconId = 0;
        editingCustomIconData = "";
    }

    private void refreshEditingIconList() {
        if (editingIconList != null) loadIcons(editingIconList, editingSelectedIconId, editingCustomIconData);
    }

    private static Bitmap normalizeIconBitmap(Bitmap bitmap, int size) {
        Bitmap normalizedBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(normalizedBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float scale = Math.min((float)size / bitmap.getWidth(), (float)size / bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float left = (size - width) * 0.5f;
        float top = (size - height) * 0.5f;
        canvas.drawBitmap(bitmap, null, new RectF(left, top, left + width, top + height), paint);
        return normalizedBitmap;
    }

    private static byte[] encodeBitmapToBytes(Bitmap bitmap) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
        return outputStream.toByteArray();
    }

    private static String encodeBitmapToBase64(Bitmap bitmap) {
        return Base64.encodeToString(encodeBitmapToBytes(bitmap), Base64.NO_WRAP);
    }

    private ArrayList<Uri> getSelectedImageUris(Intent data) {
        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        }
        else if (data.getData() != null) uris.add(data.getData());
        return uris;
    }

    private ArrayList<IconPackManager.SourceIcon> loadIconPackSourceIcons(ArrayList<Uri> uris) {
        ArrayList<IconPackManager.SourceIcon> iconBytesList = new ArrayList<>();
        for (Uri uri : uris) {
            Bitmap bitmap = ImageUtils.getBitmapFromUri(this, uri, 256);
            if (bitmap == null) continue;

            Bitmap normalizedBitmap = normalizeIconBitmap(bitmap, 256);
            byte[] bytes = encodeBitmapToBytes(normalizedBitmap);
            if (bytes.length > 0) iconBytesList.add(new IconPackManager.SourceIcon("", uri.toString(), 0, bytes));
        }
        return iconBytesList;
    }

    private static String getImportErrorMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        return message;
    }
}
