package com.termux.app.activities;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.app.activities.termuxbox.TermuxBoxBox64Fragment;
import com.termux.app.activities.termuxbox.TermuxBoxContainerFragment;
import com.termux.app.activities.termuxbox.TermuxBoxHomeFragment;
import com.termux.app.activities.termuxbox.TermuxBoxNavigator;
import com.termux.app.activities.termuxbox.TermuxBoxNotesFragment;
import com.termux.app.activities.termuxbox.TermuxBoxPackagesFragment;
import com.termux.app.activities.termuxbox.TermuxBoxRepository;
import com.termux.app.activities.termuxbox.TermuxBoxSection;
import com.termux.app.terminal.utils.CommandUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.theme.NightMode;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;

public class TermuxBoxActivity extends AppCompatActivity implements TermuxBoxNavigator {

    private static final String STATE_SECTION = "termux_box_section";

    private final TermuxBoxRepository repository = new TermuxBoxRepository();
    private MaterialToolbar toolbar;
    private FloatingActionButton fab;
    private TermuxBoxSection currentSection = TermuxBoxSection.HOME;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
        setContentView(R.layout.activity_termux_box);
        toolbar = findViewById(R.id.termux_box_toolbar);
        fab = findViewById(R.id.termux_box_fab);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(true);
        }
        toolbar.setNavigationIcon(R.drawable.ic_menu_hamburger);
        toolbar.setNavigationOnClickListener(this::showSectionMenu);
        fab.setOnClickListener(v -> onPrimaryAction());

        if (savedInstanceState == null) {
            openSection(TermuxBoxSection.HOME);
        } else {
            String sectionName = savedInstanceState.getString(STATE_SECTION, TermuxBoxSection.HOME.name());
            try {
                currentSection = TermuxBoxSection.valueOf(sectionName);
            } catch (IllegalArgumentException ignored) {
                currentSection = TermuxBoxSection.HOME;
            }
            if (getSupportFragmentManager().findFragmentById(R.id.termux_box_content) == null) {
                openSection(currentSection);
                return;
            }
            updateChromeForSection(currentSection);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    public void onBackPressed() {
        if (currentSection != TermuxBoxSection.HOME) {
            openSection(TermuxBoxSection.HOME);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void openSection(@NonNull TermuxBoxSection section) {
        Fragment fragment;
        switch (section) {
            case PACKAGES:
                fragment = new TermuxBoxPackagesFragment();
                break;
            case CONTAINERS:
                fragment = new TermuxBoxContainerFragment();
                break;
            case BOX64:
                fragment = new TermuxBoxBox64Fragment();
                break;
            case NOTES:
                fragment = new TermuxBoxNotesFragment();
                break;
            case HOME:
            default:
                fragment = new TermuxBoxHomeFragment();
                break;
        }
        currentSection = section;
        getSupportFragmentManager()
            .beginTransaction()
            .replace(R.id.termux_box_content, fragment)
            .commit();
        updateChromeForSection(section);
    }

    @Override
    public void startWine() {
        File script = new File(TermuxConstants.TERMUX_FILES_DIR_PATH + "/usr/glibc/opt/scripts/start-tfm");
        if (!script.exists()) {
            Toast.makeText(this, "start-tfm not found", Toast.LENGTH_SHORT).show();
            return;
        }
        script.setExecutable(true, false);
        CommandUtils.execInPath(this, "start-tfm", null, "/glibc/opt/scripts/");
    }

    @Override
    public TermuxBoxRepository getRepository() {
        return repository;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SECTION, currentSection.name());
    }

    private void showSectionMenu(View anchor) {
        PopupMenu popupMenu = new PopupMenu(this, anchor);
        popupMenu.getMenuInflater().inflate(R.menu.menu_termux_box, popupMenu.getMenu());
        popupMenu.setOnMenuItemClickListener(this::onSectionMenuItemClick);
        popupMenu.show();
    }

    private boolean onSectionMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_termux_box_start_wine:
                startWine();
                return true;
            case R.id.action_termux_box_home:
                openSection(TermuxBoxSection.HOME);
                return true;
            case R.id.action_termux_box_packages:
                openSection(TermuxBoxSection.PACKAGES);
                return true;
            case R.id.action_termux_box_containers:
                openSection(TermuxBoxSection.CONTAINERS);
                return true;
            case R.id.action_termux_box_box64:
                openSection(TermuxBoxSection.BOX64);
                return true;
            case R.id.action_termux_box_notes:
                openSection(TermuxBoxSection.NOTES);
                return true;
            default:
                return false;
        }
    }

    private void onPrimaryAction() {
        if (currentSection == TermuxBoxSection.HOME) {
            openSection(TermuxBoxSection.CONTAINERS);
            return;
        }
        Toast.makeText(this, "当前页面暂无确认操作", Toast.LENGTH_SHORT).show();
    }

    private void updateChromeForSection(TermuxBoxSection section) {
        if (toolbar != null) {
            toolbar.setTitle(titleFor(section));
            toolbar.setSubtitle(subtitleFor(section));
        }
        if (fab != null) {
            fab.setVisibility(section == TermuxBoxSection.HOME ? View.VISIBLE : View.GONE);
        }
    }

    private String titleFor(TermuxBoxSection section) {
        switch (section) {
            case PACKAGES:
                return "软件包";
            case CONTAINERS:
                return "容器设置";
            case BOX64:
                return "Box64 构建";
            case NOTES:
                return "补丁记录";
            case HOME:
            default:
                return getString(R.string.title_activity_termux_box);
        }
    }

    private String subtitleFor(TermuxBoxSection section) {
        switch (section) {
            case HOME:
                return "容器概览";
            case PACKAGES:
                return "安装、校验、卸载";
            case CONTAINERS:
                return "统一参数入口";
            case BOX64:
                return "替换 box64 二进制";
            case NOTES:
                return "变更摘要";
            default:
                return null;
        }
    }
}
