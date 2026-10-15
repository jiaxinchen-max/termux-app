package com.termux.x11.controller;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.termux.x11.controller.contentdialog.ContentDialog;
import com.termux.x11.controller.core.AppUtils;
import com.termux.x11.controller.core.Callback;
import com.termux.x11.controller.core.FileUtils;
import com.termux.x11.controller.core.HttpUtils;
import com.termux.x11.controller.inputcontrols.ControlsProfile;
import com.termux.x11.controller.inputcontrols.ExternalController;
import com.termux.x11.controller.inputcontrols.InputControlsManager;
import com.termux.x11.controller.widget.InputControlsView;
import com.termux.x11.controller.widget.SeekBar;
import com.termux.x11.controller.widget.TouchpadView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import com.termux.x11.MainActivity;
import com.termux.x11.R;

public class InputControlsFragment extends Fragment {
    private static final String INPUT_CONTROLS_URL = "http://cdn4.52emu.cn/wlt/v10/input_controls/%s";
    private InputControlsManager manager;
    private ControlsProfile currentProfile;
    private Runnable updateLayout;
    private Callback<ControlsProfile> importProfileCallback;
    private int selectedProfileId;

    public InputControlsFragment() {}

    public static InputControlsFragment newInstance(int selectedProfileId) {
        InputControlsFragment fragment = new InputControlsFragment();
        Bundle args = new Bundle();
        args.putInt("selectedProfileId", selectedProfileId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        selectedProfileId = getArguments() != null ? getArguments().getInt("selectedProfileId", 0) : 0;
        manager = new InputControlsManager(getContext());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // 终端侧 InputControllerActivity 使用 NoActionBar 主题且未设置 Toolbar，
        // getSupportActionBar() 为 null，此处需判空，否则进入页面即 NPE 闪退
        ActionBar actionBar = ((AppCompatActivity)getActivity()).getSupportActionBar();
        if (actionBar != null) actionBar.setTitle(R.string.input_controls);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            try {
                ControlsProfile importedProfile = manager.importProfile(new JSONObject(FileUtils.readString(getContext(), data.getData())));
                if (importProfileCallback != null) importProfileCallback.call(importedProfile);
            }
            catch (Exception e) {
                AppUtils.showToast(getContext(), R.string.unable_to_import_profile);
            }
            importProfileCallback = null;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.input_controls_fragment, container, false);
        final Context context = getContext();
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        currentProfile = selectedProfileId > 0 ? manager.getProfile(selectedProfileId) : null;

        final Spinner sProfile = view.findViewById(R.id.SProfile);
        loadProfileSpinner(sProfile);

        final SeekBar sbCursorSpeed = view.findViewById(R.id.SBCursorSpeed);
        sbCursorSpeed.setOnValueChangeListener((seekBar, value) -> {
            if (currentProfile != null) {
                currentProfile.setCursorSpeed(value / 100.0f);
                currentProfile.save();
            }
        });

        CheckBox cbDisableMouseInput = view.findViewById(R.id.CBDisableMouseInput);
        cbDisableMouseInput.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (currentProfile != null) {
                currentProfile.setDisableMouseInput(isChecked);
                currentProfile.save();
            }
        });

        final Spinner sTouchpadMode = view.findViewById(R.id.STouchpadMode);
        final boolean[] isUserTouchpadModeChange = {false};
        sTouchpadMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (currentProfile != null) {
                    currentProfile.setTouchpadMode((byte)position);
                    // Only reset advanced settings when user actively changes the mode
                    if (isUserTouchpadModeChange[0]) {
                        if (position == TouchpadView.TOUCHPAD_MODE_V2) {
                            currentProfile.setTwoFingersDrag(false);
                            currentProfile.setTwoFingersRightClick(false);
                            currentProfile.setLongPressRightClick(true);
                            currentProfile.setPinchZoomEnabled(true);
                            currentProfile.setShortDragEnabled(true);
                        } else {
                            currentProfile.setTwoFingersDrag(true);
                            currentProfile.setTwoFingersRightClick(true);
                            currentProfile.setLongPressRightClick(true);
                            currentProfile.setPinchZoomEnabled(false);
                            currentProfile.setShortDragEnabled(false);
                        }
                    }
                    currentProfile.save();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        sTouchpadMode.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                isUserTouchpadModeChange[0] = true;
            }
            return false;
        });

        CheckBox cbMoveCursorToTouchpoint = view.findViewById(R.id.CBMoveCursorToTouchpoint);
        cbMoveCursorToTouchpoint.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (currentProfile != null) {
                currentProfile.setMoveCursorToTouchpoint(isChecked);
                currentProfile.save();
            }
        });

        view.findViewById(R.id.BTTouchpadSettings).setOnClickListener((v) -> showTouchpadSettingsDialog());

        updateLayout = () -> {
            isUserTouchpadModeChange[0] = false;
            if (currentProfile != null) {
                sbCursorSpeed.setValue(currentProfile.getCursorSpeed() * 100);
                cbDisableMouseInput.setChecked(currentProfile.isDisableMouseInput());
                sTouchpadMode.setSelection(currentProfile.getTouchpadMode(), false);
                cbMoveCursorToTouchpoint.setChecked(currentProfile.isMoveCursorToTouchpoint());
            }
            else {
                sbCursorSpeed.setValue(100);
                cbDisableMouseInput.setChecked(false);
                sTouchpadMode.setSelection(0, false);
                cbMoveCursorToTouchpoint.setChecked(false);
            }
            loadExternalControllers(view);
        };

        updateLayout.run();

        SeekBar sbOverlayOpacity = view.findViewById(R.id.SBOverlayOpacity);
        sbOverlayOpacity.setOnValueChangeListener((seekBar, value) -> preferences.edit().putFloat("overlay_opacity", value / 100.0f).apply());
        sbOverlayOpacity.setValue(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY) * 100);

        view.findViewById(R.id.BTAddProfile).setOnClickListener((v) -> ContentDialog.prompt(context, R.string.profile_name, null, (name) -> {
            currentProfile = manager.createProfile(name);
            loadProfileSpinner(sProfile);
            updateLayout.run();
        }));

        view.findViewById(R.id.BTEditProfile).setOnClickListener((v) -> {
            if (currentProfile != null) {
                ContentDialog.prompt(context, R.string.profile_name, currentProfile.getName(), (name) -> {
                    currentProfile.setName(name);
                    currentProfile.save();
                    loadProfileSpinner(sProfile);
                });
            }
            else AppUtils.showToast(context, R.string.no_profile_selected);
        });

        view.findViewById(R.id.BTDuplicateProfile).setOnClickListener((v) -> {
            if (currentProfile != null) {
                ContentDialog.confirm(context, R.string.do_you_want_to_duplicate_this_profile, () -> {
                    currentProfile = manager.duplicateProfile(currentProfile);
                    loadProfileSpinner(sProfile);
                    updateLayout.run();
                });
            }
            else AppUtils.showToast(context, R.string.no_profile_selected);
        });

        view.findViewById(R.id.BTRemoveProfile).setOnClickListener((v) -> {
            if (currentProfile != null) {
                ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_profile, () -> {
                    manager.removeProfile(currentProfile);
                    currentProfile = null;
                    loadProfileSpinner(sProfile);
                    updateLayout.run();
                });
            }
            else AppUtils.showToast(context, R.string.no_profile_selected);
        });

        view.findViewById(R.id.BTImportProfile).setOnClickListener((v) -> {
            PopupMenu popupMenu = new PopupMenu(context, v);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) popupMenu.setForceShowIcon(true);
            popupMenu.inflate(R.menu.open_file_popup_menu);
            popupMenu.setOnMenuItemClickListener((menuItem) -> {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.menu_item_open_file) {
                    openProfileFile(sProfile);
                }
                else if (itemId == R.id.menu_item_download_file) {
                    downloadProfileList(sProfile);
                }
                return true;
            });
            popupMenu.show();
        });

        view.findViewById(R.id.BTExportProfile).setOnClickListener((v) -> {
            if (currentProfile != null) {
                File exportedFile = manager.exportProfile(currentProfile);
                if (exportedFile != null) {
                    String path = exportedFile.getPath().substring(exportedFile.getPath().indexOf(Environment.DIRECTORY_DOWNLOADS));
                    AppUtils.showToast(context, context.getString(R.string.profile_exported_to)+" "+path);
                }
            }
            else AppUtils.showToast(context, R.string.no_profile_selected);
        });

        view.findViewById(R.id.BTControlsEditor).setOnClickListener((v) -> {
            if (currentProfile != null) {
                Intent intent = new Intent(context, ControlsEditorActivity.class);
                intent.putExtra("profile_id", currentProfile.id);
                startActivity(intent);
            }
            else AppUtils.showToast(context, R.string.no_profile_selected);
        });

        return view;
    }

    private void openProfileFile(Spinner sProfile) {
        importProfileCallback = (importedProfile) -> {
            currentProfile = importedProfile;
            loadProfileSpinner(sProfile);
            updateLayout.run();
        };
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_FILE_REQUEST_CODE);
    }

    private void downloadSelectedProfiles(final Spinner sProfile, String[] items, final ArrayList<Integer> positions) {
        final InputControllerActivity activity = (InputControllerActivity)getActivity();
        activity.preloaderDialog.show(R.string.downloading_file);
        currentProfile = null;
        final AtomicInteger processedItemCount = new AtomicInteger();

        for (int position : positions) {
            HttpUtils.download(String.format(INPUT_CONTROLS_URL, items[position]), (content) -> {
                try {
                    if (content != null) manager.importProfile(new JSONObject(content));
                }
                catch (JSONException e) {}
                if (processedItemCount.incrementAndGet() == positions.size()) {
                    activity.runOnUiThread(() -> {
                        activity.preloaderDialog.close();
                        loadProfileSpinner(sProfile);
                        updateLayout.run();
                    });
                }
            });
        }
    }

    private void downloadProfileList(final Spinner sProfile) {
        final InputControllerActivity activity = (InputControllerActivity)getActivity();
        activity.preloaderDialog.show(R.string.loading);
        HttpUtils.download(String.format(INPUT_CONTROLS_URL, "index.txt"), (content) -> activity.runOnUiThread(() -> {
            activity.preloaderDialog.close();
            if (content != null) {
                final String[] items = content.split("\n");
                ContentDialog.showSelectionList(activity, R.string.import_profile, items, true, (positions) -> {
                    if (!positions.isEmpty()) {
                        ContentDialog.confirm(activity, R.string.do_you_want_to_download_the_selected_profiles, () -> downloadSelectedProfiles(sProfile, items, positions));
                    }
                });
            }
            else AppUtils.showToast(activity, R.string.a_network_error_occurred);
        }));
    }

    @Override
    public void onStart() {
        super.onStart();
        if (updateLayout != null) updateLayout.run();
    }

    private void loadProfileSpinner(Spinner spinner) {
        final ArrayList<ControlsProfile> profiles = manager.getProfiles();
        ArrayList<String> values = new ArrayList<>();
        values.add("-- "+getString(R.string.select_profile)+" --");

        int selectedPosition = 0;
        for (int i = 0; i < profiles.size(); i++) {
            ControlsProfile profile = profiles.get(i);
            if (profile == currentProfile) selectedPosition = i + 1;
            values.add(profile.getName());
        }

        spinner.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setSelection(selectedPosition, false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentProfile = position > 0 ? profiles.get(position - 1) : null;
                updateLayout.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadExternalControllers(final View view) {
        LinearLayout container = view.findViewById(R.id.LLExternalControllers);
        container.removeAllViews();
        Context context = getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        ArrayList<ExternalController> connectedControllers = ExternalController.getControllers();

        ArrayList<ExternalController> controllers = currentProfile != null ? currentProfile.loadControllers() : new ArrayList<>();
        for (ExternalController controller : connectedControllers) {
            if (!controllers.contains(controller)) controllers.add(controller);
        }

        if (!controllers.isEmpty()) {
            view.findViewById(R.id.TVEmptyText).setVisibility(View.GONE);
            String bindingsText = context.getString(R.string.bindings).toLowerCase(Locale.ENGLISH);
            for (final ExternalController controller : controllers) {
                View itemView = inflater.inflate(R.layout.external_controller_list_item, container, false);
                ((TextView)itemView.findViewById(R.id.TVTitle)).setText(controller.getName());

                int controllerBindingCount = controller.getControllerBindingCount();
                ((TextView)itemView.findViewById(R.id.TVSubtitle)).setText(controllerBindingCount+" "+bindingsText);

                ImageView imageView = itemView.findViewById(R.id.ImageView);
                int tintColor = AppUtils.getThemeColor(context, controller.isConnected() ? R.attr.colorAccent : R.attr.colorError);
                ImageViewCompat.setImageTintList(imageView, ColorStateList.valueOf(tintColor));

                if (controllerBindingCount > 0) {
                    ImageButton removeButton = itemView.findViewById(R.id.BTRemove);
                    removeButton.setVisibility(View.VISIBLE);
                    removeButton.setOnClickListener((v) -> ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_controller, () -> {
                        currentProfile.removeController(controller);
                        currentProfile.save();
                        loadExternalControllers(view);
                    }));
                }

                itemView.setOnClickListener((v) -> {
                    if (currentProfile != null) {
                        Intent intent = new Intent(getContext(), ExternalControllerBindingsActivity.class);
                        intent.putExtra("profile_id", currentProfile.id);
                        intent.putExtra("controller_id", controller.getId());
                        startActivity(intent);
                    }
                    else AppUtils.showToast(getContext(), R.string.no_profile_selected);
                });

                container.addView(itemView);
            }
        }
        else view.findViewById(R.id.TVEmptyText).setVisibility(View.VISIBLE);
    }

    private void showTouchpadSettingsDialog() {
        if (currentProfile == null) {
            AppUtils.showToast(getContext(), R.string.no_profile_selected);
            return;
        }

        Context context = getContext();
        BottomSheetDialog dialog = new BottomSheetDialog(context, R.style.BottomSheetDialogTheme);
        View dialogView = LayoutInflater.from(context).inflate(R.layout.touchpad_settings_dialog, null);
        dialog.setContentView(dialogView);

        byte mode = currentProfile.getTouchpadMode();
        String[] modeNames = getResources().getStringArray(R.array.touchpad_mode_entries);
        String title = mode < modeNames.length ? modeNames[mode] + " " + getString(R.string.settings) : getString(R.string.settings);
        ((TextView) dialogView.findViewById(R.id.TVTouchpadSettingsTitle)).setText(title);

        CheckBox cbTwoFingersDrag = dialogView.findViewById(R.id.CBTwoFingersDrag);
        CheckBox cbTwoFingersScroll = dialogView.findViewById(R.id.CBTwoFingersScroll);
        CheckBox cbTwoFingersRightClick = dialogView.findViewById(R.id.CBTwoFingersRightClick);
        CheckBox cbLongPressRightClick = dialogView.findViewById(R.id.CBLongPressRightClick);
        CheckBox cbPinchZoomEnabled = dialogView.findViewById(R.id.CBPinchZoomEnabled);
        CheckBox cbShortDragEnabled = dialogView.findViewById(R.id.CBShortDragEnabled);

        cbTwoFingersDrag.setChecked(currentProfile.isTwoFingersDrag());
        cbTwoFingersScroll.setChecked(currentProfile.isTwoFingersScroll());
        cbTwoFingersRightClick.setChecked(currentProfile.isTwoFingersRightClick());
        cbLongPressRightClick.setChecked(currentProfile.isLongPressRightClick());
        cbPinchZoomEnabled.setChecked(currentProfile.isPinchZoomEnabled());
        cbShortDragEnabled.setChecked(currentProfile.isShortDragEnabled());

        cbTwoFingersDrag.setOnCheckedChangeListener((buttonView, isChecked) -> {
            currentProfile.setTwoFingersDrag(isChecked);
            currentProfile.save();
        });

        cbTwoFingersScroll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            currentProfile.setTwoFingersScroll(isChecked);
            currentProfile.save();
        });

        cbTwoFingersRightClick.setOnCheckedChangeListener((buttonView, isChecked) -> {
            currentProfile.setTwoFingersRightClick(isChecked);
            currentProfile.save();
        });

        cbLongPressRightClick.setOnCheckedChangeListener((buttonView, isChecked) -> {
            currentProfile.setLongPressRightClick(isChecked);
            currentProfile.save();
        });

        cbPinchZoomEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            currentProfile.setPinchZoomEnabled(isChecked);
            currentProfile.save();
        });

        cbShortDragEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            currentProfile.setShortDragEnabled(isChecked);
            currentProfile.save();
        });

        dialog.show();

        // 强制底部弹窗展开更多内容
        FrameLayout bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) {
            BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setPeekHeight(0);
        }
    }
}
