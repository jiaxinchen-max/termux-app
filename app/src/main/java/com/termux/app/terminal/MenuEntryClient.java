package com.termux.app.terminal;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.x11.controller.core.ImageUtils;
import com.termux.x11.controller.core.TermuxConfigFiles;

import java.io.File;

import android.util.TypedValue;

public class MenuEntryClient {
    private static final int PICK_TOOLBOX_ICON_REQUEST_CODE = 104;
    private static final int TOOLBOX_MENU_LAUNCH_ID = 0;
    private static final int TOOLBOX_MENU_UPDATE_ID = 1;
    private static final int TOOLBOX_MENU_REMOVE_ID = 2;
    private static final String DEFAULT_ICON_PATH = "default";

    private final TermuxActivity mTermuxActivity;
    private final TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;
    private final MenuEntry mMenuEntry;
    private LinearLayout mToolboxContainer;
    private GridLayout mGridLayout;
    private CheckBox mDInputCheckBox;
    private CheckBox mXInputCheckBox;
    private IconSelectionCallback mIconSelectionCallback;

    private interface IconSelectionCallback {
        void onIconSelected(String iconId);
    }

    public MenuEntryClient(TermuxActivity activity, TermuxTerminalSessionActivityClient termuxTerminalSessionActivityClient) {
        mTermuxActivity = activity;
        mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient;
        mMenuEntry = new MenuEntry();
        mMenuEntry.loadMenuItems();
        setToolboxView();
    }

    private void setToolboxView() {
        View container = mTermuxActivity.findViewById(R.id.toolbox_container);
        if (!(container instanceof LinearLayout))
            return;

        mToolboxContainer = (LinearLayout) container;
        mToolboxContainer.removeAllViews();

        LinearLayout inputModeRow = new LinearLayout(mTermuxActivity);
        inputModeRow.setOrientation(LinearLayout.HORIZONTAL);
        inputModeRow.setGravity(Gravity.CENTER_VERTICAL);
        inputModeRow.setPadding(dp(4), 0, dp(4), 0);
        mDInputCheckBox = new CheckBox(mTermuxActivity);
        mDInputCheckBox.setText(R.string.set_dinput);
        mXInputCheckBox = new CheckBox(mTermuxActivity);
        mXInputCheckBox.setText(R.string.set_xinput);
        inputModeRow.addView(mDInputCheckBox, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        inputModeRow.addView(mXInputCheckBox, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        mToolboxContainer.addView(inputModeRow, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        ScrollView toolboxScrollView = new ScrollView(mTermuxActivity);
        LinearLayout content = new LinearLayout(mTermuxActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        toolboxScrollView.addView(content, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        mGridLayout = new GridLayout(mTermuxActivity);
        mGridLayout.setColumnCount(3);
        mGridLayout.setPadding(dp(4), dp(2), dp(4), dp(2));
        content.addView(mGridLayout, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        updateMenuItems();

        mToolboxContainer.addView(toolboxScrollView, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
    }

    private void updateMenuItems() {
        if (mGridLayout == null)
            return;

        mGridLayout.removeAllViews();
        int itemSize = dp(42);

        LinearLayout recover = createImageButton("script", "setMoBoxEnv", DEFAULT_ICON_PATH, itemSize);
        recover.setOnClickListener(v -> mTermuxActivity.reInstallCustomStartScript(getSelectedInputModeFlags()));
        mGridLayout.addView(recover);

        for (int i = 0; i < mMenuEntry.getStartItemList().size(); i++) {
            MenuEntry.Entry entry = mMenuEntry.getStartItemList().get(i);
            LinearLayout button = createImageButton(entry.getType(), entry.getFileName(), entry.getIconPath(), itemSize);
            setToolboxItemActions(button, entry, i);
            mGridLayout.addView(button);
        }

        LinearLayout addButton = createImageButton("add", mTermuxActivity.getString(com.termux.x11.R.string.add), DEFAULT_ICON_PATH, itemSize);
        addButton.setOnClickListener(v -> showMenuItemDialog(null, -1));
        mGridLayout.addView(addButton);
    }

    private Integer getSelectedInputModeFlags() {
        int flags = 0;
        if (mDInputCheckBox != null && mDInputCheckBox.isChecked())
            flags |= 0b0001;
        if (mXInputCheckBox != null && mXInputCheckBox.isChecked())
            flags |= 0b0010;
        return flags == 0 ? null : flags;
    }

    private void setToolboxItemActions(View button, MenuEntry.Entry entry, int index) {
        button.setClickable(true);
        button.setLongClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> launchMenuEntry(entry));

        final int touchSlop = ViewConfiguration.get(mTermuxActivity).getScaledTouchSlop();
        final float[] downX = new float[1];
        final float[] downY = new float[1];
        final boolean[] menuShown = {false};
        final Runnable[] longPressRunnable = new Runnable[1];
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getX();
                    downY[0] = event.getY();
                    menuShown[0] = false;
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    longPressRunnable[0] = () -> {
                        menuShown[0] = true;
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        showToolboxItemMenu(v, index);
                    };
                    v.postDelayed(longPressRunnable[0], ViewConfiguration.getLongPressTimeout());
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - downX[0]) > touchSlop || Math.abs(event.getY() - downY[0]) > touchSlop) {
                        if (longPressRunnable[0] != null)
                            v.removeCallbacks(longPressRunnable[0]);
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return menuShown[0];
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (longPressRunnable[0] != null)
                        v.removeCallbacks(longPressRunnable[0]);
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    return menuShown[0];
            }
            return false;
        });
    }

    private void showToolboxItemMenu(View anchor, int index) {
        if (index < 0 || index >= mMenuEntry.getStartItemList().size())
            return;

        PopupMenu popupMenu = new PopupMenu(mTermuxActivity, anchor);
        popupMenu.getMenu().add(0, TOOLBOX_MENU_LAUNCH_ID, 0, com.termux.x11.R.string.launch_button_text);
        popupMenu.getMenu().add(0, TOOLBOX_MENU_UPDATE_ID, 1, R.string.update);
        popupMenu.getMenu().add(0, TOOLBOX_MENU_REMOVE_ID, 2, R.string.remove);
        popupMenu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == TOOLBOX_MENU_LAUNCH_ID) {
                if (index < mMenuEntry.getStartItemList().size())
                    launchMenuEntry(mMenuEntry.getStartItemList().get(index));
                return true;
            }
            if (item.getItemId() == TOOLBOX_MENU_UPDATE_ID) {
                if (index < mMenuEntry.getStartItemList().size())
                    showMenuItemDialog(mMenuEntry.getStartItemList().get(index), index);
                return true;
            }
            if (item.getItemId() == TOOLBOX_MENU_REMOVE_ID) {
                if (index < mMenuEntry.getStartItemList().size()) {
                    mMenuEntry.getStartItemList().remove(index);
                    mMenuEntry.saveMenuItems();
                    updateMenuItems();
                    Toast.makeText(mTermuxActivity, R.string.remove, Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            return false;
        });
        popupMenu.show();
    }

    private void launchMenuEntry(MenuEntry.Entry entry) {
        if (entry == null)
            return;

        String command = entry.getCommand();
        if (command == null)
            command = entry.getPath();
        if (command == null || command.isEmpty())
            return;

        String sessionName = TextUtils.isEmpty(entry.getTitlle()) ? entry.getFileName() : entry.getTitlle();
        mTermuxTerminalSessionActivityClient.addNewSessionAndRunCommand(command, sessionName);
    }

    private void showMenuItemDialog(MenuEntry.Entry entryToUpdate, int updateIndex) {
        boolean updating = entryToUpdate != null && updateIndex >= 0;
        LinearLayout content = new LinearLayout(mTermuxActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(4), dp(12), 0);

        LinearLayout commandRow = new LinearLayout(mTermuxActivity);
        commandRow.setOrientation(LinearLayout.HORIZONTAL);
        commandRow.setGravity(Gravity.CENTER_VERTICAL);

        EditText command = createCompactEditText(R.string.executable_file);
        if (updating) {
            String existingCommand = entryToUpdate.getCommand();
            command.setText(existingCommand == null ? entryToUpdate.getPath() : existingCommand);
            command.setSelection(command.getText().length());
        }
        ImageButton fileButton = new ImageButton(mTermuxActivity);
        fileButton.setBackground(mTermuxActivity.getDrawable(com.termux.x11.R.drawable.ic_file_browser_shape));
        commandRow.addView(command, new LinearLayout.LayoutParams(0, dp(36), 1));
        commandRow.addView(fileButton, new LinearLayout.LayoutParams(dp(40), dp(36)));
        content.addView(commandRow, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        LinearLayout titleRow = new LinearLayout(mTermuxActivity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        EditText title = createCompactEditText(com.termux.x11.R.string.title);
        if (updating) {
            title.setText(entryToUpdate.getFileName());
            title.setSelection(title.getText().length());
        }
        ImageButton iconButton = new ImageButton(mTermuxActivity);
        iconButton.setBackground(mTermuxActivity.getDrawable(R.drawable.icon_button_click));
        iconButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iconButton.setPadding(dp(4), dp(4), dp(4), dp(4));
        iconButton.setContentDescription(mTermuxActivity.getString(com.termux.x11.R.string.icon));
        String initialIconId = updating ? entryToUpdate.getIconPath() : DEFAULT_ICON_PATH;
        if (initialIconId == null || initialIconId.isEmpty())
            initialIconId = DEFAULT_ICON_PATH;
        setIconPickerButtonImage(iconButton, initialIconId);
        final String[] selectedIconId = {initialIconId};
        iconButton.setOnClickListener(v -> pickToolboxIcon(iconId -> {
            selectedIconId[0] = iconId == null ? DEFAULT_ICON_PATH : iconId;
            setIconPickerButtonImage(iconButton, selectedIconId[0]);
        }));

        titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));
        titleRow.addView(iconButton, new LinearLayout.LayoutParams(dp(40), dp(36)));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        titleParams.topMargin = dp(4);
        content.addView(titleRow, titleParams);

        final FileBrowser[] fileBrowserHolder = new FileBrowser[1];
        fileBrowserHolder[0] = new FileBrowser(mTermuxActivity, fileInfo -> {
            command.setText(fileInfo.getPath());
            command.setSelection(command.getText().length());
            title.setText(fileInfo.getName());
            title.setSelection(title.getText().length());
            if (fileBrowserHolder[0] != null)
                fileBrowserHolder[0].hideFileBrowser();
        }, R.layout.toolbox_file_bowser, R.layout.toolbox_item_file);
        FileBrowser fileBrowser = fileBrowserHolder[0];
        fileBrowser.init();
        View fileBrowserView = fileBrowser.getView();
        fileBrowserView.setVisibility(View.GONE);
        LinearLayout.LayoutParams fileBrowserParams = new LinearLayout.LayoutParams(MATCH_PARENT, dp(160));
        fileBrowserParams.topMargin = dp(6);
        content.addView(fileBrowserView, fileBrowserParams);

        fileButton.setOnClickListener(v -> {
            if (fileBrowserView.getVisibility() == View.VISIBLE) {
                fileBrowser.hideFileBrowser();
            } else {
                fileBrowser.showFileBrowser(v);
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(mTermuxActivity)
            .setTitle(updating ? R.string.update : com.termux.x11.R.string.add)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(com.termux.x11.R.string.ok, null)
            .create();

        dialog.setOnShowListener(dialogInterface -> {
            Button okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            okButton.setTextColor(resolveThemeColor(android.R.attr.textColorPrimary));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(resolveThemeColor(android.R.attr.textColorSecondary));
            okButton.setOnClickListener(v -> {
                String commandText = command.getText().toString();
                String titleText = title.getText().toString();
                if (commandText.isEmpty() || titleText.isEmpty()) {
                    Toast.makeText(mTermuxActivity, R.string.invalid_config, Toast.LENGTH_LONG).show();
                    return;
                }

                MenuEntry.Entry entry = new MenuEntry.Entry();
                entry.setPath(commandText);
                entry.setFileName(titleText);
                entry.setIconPath(selectedIconId[0]);
                entry.setTitlle(titleText);
                entry.setCommand(commandText);
                entry.setType(updating && entryToUpdate.getType() != null ? entryToUpdate.getType() : "executable");
                if (updating && updateIndex < mMenuEntry.getStartItemList().size()) {
                    mMenuEntry.getStartItemList().set(updateIndex, entry);
                } else {
                    mMenuEntry.addMenuEntry(entry);
                }
                mMenuEntry.saveMenuItems();
                updateMenuItems();
                dialog.dismiss();
            });
        });
        dialog.show();
        if (dialog.getWindow() != null)
            dialog.getWindow().setLayout(getAddDialogWidth(), WRAP_CONTENT);
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_TOOLBOX_ICON_REQUEST_CODE)
            return false;

        IconSelectionCallback callback = mIconSelectionCallback;
        mIconSelectionCallback = null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || callback == null)
            return true;

        String iconId = saveToolboxIcon(data.getData());
        if (iconId == null) {
            Toast.makeText(mTermuxActivity, R.string.invalid_config, Toast.LENGTH_SHORT).show();
            return true;
        }

        callback.onIconSelected(iconId);
        return true;
    }

    private EditText createCompactEditText(int hintResId) {
        EditText editText = new EditText(mTermuxActivity);
        editText.setHint(hintResId);
        editText.setSingleLine(true);
        editText.setMinHeight(0);
        editText.setMinimumHeight(0);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        editText.setPadding(dp(4), 0, dp(4), 0);
        editText.setTextColor(resolveThemeColor(android.R.attr.textColorPrimary));
        editText.setHintTextColor(resolveThemeColor(android.R.attr.textColorHint));
        return editText;
    }

    private LinearLayout createImageButton(String type, String title, String iconPath, int size) {
        LinearLayout layout = new LinearLayout(mTermuxActivity);
        GridLayout.LayoutParams param = new GridLayout.LayoutParams();
        param.width = size;
        param.height = size;
        param.setMargins(dp(2), dp(2), dp(2), dp(2));
        layout.setLayoutParams(param);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);

        ImageView icon = new ImageView(mTermuxActivity);
        LinearLayout.LayoutParams iconParam = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 2);
        iconParam.gravity = Gravity.CENTER_VERTICAL;
        icon.setLayoutParams(iconParam);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        setIconButtonImage(icon, iconPath, type);

        TextView text = new TextView(mTermuxActivity);
        LinearLayout.LayoutParams textParam = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1);
        text.setGravity(Gravity.CENTER);
        text.setSingleLine(true);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        text.setLayoutParams(textParam);
        text.setText(title);
        layout.addView(icon);
        layout.addView(text);
        return layout;
    }

    private void setIconButtonImage(ImageView icon, String iconPath, String type) {
        File customIconFile = getToolboxIconFile(iconPath);
        if (customIconFile != null) {
            Bitmap bitmap = BitmapFactory.decodeFile(customIconFile.getPath());
            if (bitmap != null) {
                icon.setImageBitmap(bitmap);
                return;
            }
        }

        switch (type) {
            case "script":
                icon.setImageDrawable(mTermuxActivity.getDrawable(R.drawable.ic_script_click));
                break;
            case "executable":
                icon.setImageDrawable(mTermuxActivity.getDrawable(R.drawable.ic_executable_click));
                break;
            case "short_cut":
                icon.setImageDrawable(mTermuxActivity.getDrawable(R.drawable.ic_shortcut_click));
                break;
            case "terminal":
                icon.setImageDrawable(mTermuxActivity.getDrawable(R.drawable.ic_terminal_click));
                break;
            case "add":
                icon.setImageDrawable(mTermuxActivity.getDrawable(R.drawable.ic_add_click));
                break;
            default:
                icon.setImageDrawable(mTermuxActivity.getDrawable(R.drawable.ic_code_click));
        }
    }

    private void setIconPickerButtonImage(ImageView icon, String iconPath) {
        File customIconFile = getToolboxIconFile(iconPath);
        if (customIconFile != null) {
            Bitmap bitmap = BitmapFactory.decodeFile(customIconFile.getPath());
            if (bitmap != null) {
                icon.setImageBitmap(bitmap);
                return;
            }
        }

        icon.setImageDrawable(mTermuxActivity.getDrawable(com.termux.x11.R.drawable.icon_image_picker));
    }

    private void pickToolboxIcon(IconSelectionCallback callback) {
        mIconSelectionCallback = callback;
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        try {
            mTermuxActivity.startActivityForResult(intent, PICK_TOOLBOX_ICON_REQUEST_CODE);
        } catch (ActivityNotFoundException e) {
            mIconSelectionCallback = null;
            Toast.makeText(mTermuxActivity, R.string.invalid_config, Toast.LENGTH_SHORT).show();
        }
    }

    private String saveToolboxIcon(Uri uri) {
        Bitmap bitmap = ImageUtils.getBitmapFromUri(mTermuxActivity, uri, 1280);
        if (bitmap == null)
            return null;

        String md5;
        try {
            md5 = ImageUtils.getFileMD5(mTermuxActivity, uri);
        } catch (Exception e) {
            return null;
        }
        if (md5 == null || md5.isEmpty())
            return null;

        File iconsDir = getToolboxIconsDir();
        if (!iconsDir.isDirectory() && !iconsDir.mkdirs())
            return null;

        File iconFile = new File(iconsDir, md5 + ".png");
        if (iconFile.isFile())
            return md5;

        return ImageUtils.save(bitmap, iconFile, Bitmap.CompressFormat.PNG, 100) ? md5 : null;
    }

    private File getToolboxIconFile(String iconPath) {
        if (iconPath == null || iconPath.isEmpty() || DEFAULT_ICON_PATH.equals(iconPath))
            return null;

        File iconFile = new File(iconPath);
        if (iconFile.isAbsolute())
            return iconFile.isFile() ? iconFile : null;

        String fileName = iconPath.endsWith(".png") ? iconPath : iconPath + ".png";
        iconFile = new File(getToolboxIconsDir(), fileName);
        return iconFile.isFile() ? iconFile : null;
    }

    private File getToolboxIconsDir() {
        return TermuxConfigFiles.buttonIconsDir(mTermuxActivity);
    }

    private int dp(int value) {
        return Math.round(value * mTermuxActivity.getResources().getDisplayMetrics().density);
    }

    private int getAddDialogWidth() {
        DisplayMetrics metrics = new DisplayMetrics();
        mTermuxActivity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        return Math.min(metrics.widthPixels, metrics.heightPixels) * 4 / 5;
    }

    private int resolveThemeColor(int attr) {
        TypedValue typedValue = new TypedValue();
        if (mTermuxActivity.getTheme().resolveAttribute(attr, typedValue, true)) {
            if (typedValue.resourceId != 0)
                return ContextCompat.getColor(mTermuxActivity, typedValue.resourceId);
            return typedValue.data;
        }
        return Color.BLACK;
    }
}
