package com.termux.x11.controller;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.termux.x11.R;
import com.termux.x11.controller.core.AppUtils;
import com.termux.x11.controller.core.Callback;
import com.termux.x11.controller.core.PreloaderDialog;

public class InputControllerActivity extends AppCompatActivity {

    public static final int EDIT_INPUT_CONTROLS_REQUEST_CODE = 103;
    public static final byte PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 1;
    public static final byte OPEN_FILE_REQUEST_CODE = 2;
    public static final byte OPEN_DIRECTORY_REQUEST_CODE = 4;
    public final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
    private boolean editInputControls = false;
    private int selectedProfileId;
    private Callback<Uri> openFileCallback;
    private int orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 本页布局使用了 ?attr/colorPrimaryText、?attr/toolboxBackground 等 winlator 自定义主题属性，
        // 必须与其它 termux-x11 页面一样先套用 AppTheme/AppThemeLight，否则主题里取不到这些属性，
        // 布局 inflate 时 View 构造器解析 background 会抛 UnsupportedOperationException 直接闪退。
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_input_controller);
        Intent intent = getIntent();
        orientation = intent.getIntExtra("set_orientation", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        editInputControls = intent.getBooleanExtra("edit_input_controls", false);
        if (editInputControls) {
            selectedProfileId = intent.getIntExtra("selected_profile_id", 0);
        } else {
            int selectedMenuItemId = intent.getIntExtra("selected_menu_item_id", 0);
            int menuItemId = selectedMenuItemId > 0 ? selectedMenuItemId : R.id.main_menu_containers;
        }
        if (savedInstanceState == null) {
            initFragment();
        }
    }
    private boolean initFragment() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
        show(InputControlsFragment.newInstance(selectedProfileId));
        return true;
    }
    private void show(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
            .replace(R.id.container, fragment)
            .commitNow();
    }

    @Override
    protected void onResume() {
        setRequestedOrientation(orientation);
        super.onResume();
    }
}
