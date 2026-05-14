package com.termux.app.terminal;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxActivity;
import android.util.TypedValue;

public class MenuEntryClient implements FileBrowser.FileSlectedAdapter {
    private final TermuxActivity mTermuxActivity;
    private final TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;
    private final MenuEntry mMenuEntry;
    private FileBrowser mFileBrowser;
    private LinearLayout mToolboxContainer;
    private LinearLayout mConfigContainer;
    private ScrollView mToolboxScrollView;
    private GridLayout mGridLayout;
    private View mConfigView;
    private CheckBox mDInputCheckBox;
    private CheckBox mXInputCheckBox;

    public MenuEntryClient(TermuxActivity activity, TermuxTerminalSessionActivityClient termuxTerminalSessionActivityClient) {
        mTermuxActivity = activity;
        mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient;
        mMenuEntry = new MenuEntry();
        mMenuEntry.loadMenuItems();
        setToolboxConfig();
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

        mToolboxScrollView = new ScrollView(mTermuxActivity);
        LinearLayout content = new LinearLayout(mTermuxActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        mToolboxScrollView.addView(content, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        mGridLayout = new GridLayout(mTermuxActivity);
        mGridLayout.setColumnCount(3);
        mGridLayout.setPadding(dp(4), dp(2), dp(4), dp(2));
        content.addView(mGridLayout, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        updateMenuItems();

        if (mConfigContainer != null)
            content.addView(mConfigContainer, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        mToolboxContainer.addView(mToolboxScrollView, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
    }

    private void updateMenuItems() {
        if (mGridLayout == null)
            return;

        mGridLayout.removeAllViews();
        int itemSize = dp(38);

        LinearLayout recover = createImageButton("script", "setMoBoxEnv", itemSize);
        recover.setOnClickListener(v -> mTermuxActivity.reInstallCustomStartScript(getSelectedInputModeFlags()));
        mGridLayout.addView(recover);

        for (int i = 0; i < mMenuEntry.getStartItemList().size(); i++) {
            MenuEntry.Entry entry = mMenuEntry.getStartItemList().get(i);
            LinearLayout button = createImageButton(entry.getType(), entry.getFileName(), itemSize);
            String command = entry.getCommand();
            if (command == null)
                command = entry.getPath();
            final String cmd = command + "\n";
            button.setOnClickListener(v -> mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast().write(cmd));
            int idx = i;
            button.setOnLongClickListener(v -> {
                mMenuEntry.getStartItemList().remove(idx);
                mMenuEntry.saveMenuItems();
                updateMenuItems();
                Toast.makeText(mTermuxActivity, R.string.remove, Toast.LENGTH_SHORT).show();
                return true;
            });
            mGridLayout.addView(button);
        }

        LinearLayout addButton = createImageButton("add", mTermuxActivity.getString(com.termux.x11.R.string.add), itemSize);
        addButton.setOnClickListener(v -> showAddMenuItem());
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

    private void showAddMenuItem() {
        if (mConfigContainer == null)
            return;
        boolean show = mConfigContainer.getVisibility() != View.VISIBLE;
        mConfigContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show)
            scrollToolboxTo(mConfigContainer);
    }

    private void setToolboxConfig() {
        mConfigContainer = new LinearLayout(mTermuxActivity);
        mConfigContainer.setOrientation(LinearLayout.VERTICAL);
        mConfigContainer.setVisibility(View.GONE);

        mConfigView = mTermuxActivity.getLayoutInflater().inflate(R.layout.menu_launch_item, mConfigContainer, false);
        LinearLayout form = mConfigView.findViewById(R.id.LConfigStartItems);
        form.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        mConfigContainer.addView(mConfigView, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        mFileBrowser = new FileBrowser(mTermuxActivity, this, R.layout.toolbox_file_bowser, R.layout.toolbox_item_file);
        mFileBrowser.init();
        View fileBrowserView = mFileBrowser.getView();
        fileBrowserView.setVisibility(View.GONE);
        mConfigContainer.addView(fileBrowserView, new LinearLayout.LayoutParams(MATCH_PARENT, dp(180)));

        ImageButton addConfigButton = mConfigView.findViewById(R.id.BConfig_item);
        addConfigButton.setOnClickListener(v -> {
            View browser = mFileBrowser.getView();
            boolean show = browser.getVisibility() != View.VISIBLE;
            if (show) {
                mFileBrowser.showFileBrowser(v);
                scrollToolboxTo(browser);
            } else {
                mFileBrowser.hideFileBrowser();
            }
        });

        EditText command = mConfigView.findViewById(R.id.ETCommand);
        EditText title = mConfigView.findViewById(R.id.ETTitle);
        Button okButton = mConfigView.findViewById(R.id.BTOK);
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
            entry.setIconPath("default");
            entry.setTitlle(titleText);
            entry.setCommand(commandText);
            entry.setType("executable");
            mMenuEntry.addMenuEntry(entry);
            mMenuEntry.saveMenuItems();
            updateMenuItems();
            command.setText("");
            title.setText("");
            mFileBrowser.getView().setVisibility(View.GONE);
            mConfigContainer.setVisibility(View.GONE);
        });
    }

    private LinearLayout createImageButton(String type, String title, int size) {
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

    private int dp(int value) {
        return Math.round(value * mTermuxActivity.getResources().getDisplayMetrics().density);
    }

    private void scrollToolboxTo(View target) {
        if (mToolboxScrollView == null || target == null)
            return;
        mToolboxScrollView.post(() -> mToolboxScrollView.smoothScrollTo(0, target.getBottom()));
    }

    @Override
    public void onFileSelected(FileInfo fileInfo) {
        EditText command = mConfigView.findViewById(R.id.ETCommand);
        EditText title = mConfigView.findViewById(R.id.ETTitle);
        command.setText(fileInfo.getPath());
        title.setText(fileInfo.getName());
        mFileBrowser.getView().setVisibility(View.GONE);
    }
}
