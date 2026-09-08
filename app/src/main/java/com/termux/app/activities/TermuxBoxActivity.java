package com.termux.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.app.activities.termuxbox.TermuxBoxContainerFragment;
import com.termux.app.activities.termuxbox.TermuxBoxNavigator;
import com.termux.app.activities.termuxbox.TermuxBoxPackagesFragment;
import com.termux.app.activities.termuxbox.TermuxBoxRepository;
import com.termux.app.activities.termuxbox.TermuxBoxSection;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.theme.NightMode;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

public class TermuxBoxActivity extends AppCompatActivity implements TermuxBoxNavigator {

    public static final String EXTRA_INITIAL_SECTION = "termux_box_initial_section";
    public static final String SECTION_CONTAINERS = "containers";
    public static final String SECTION_PACKAGES = "packages";

    private static final String STATE_SECTION = "termux_box_section";

    private TermuxBoxRepository repository;
    private MaterialToolbar toolbar;
    private FloatingActionButton fab;
    private TermuxBoxSection currentSection = TermuxBoxSection.CONTAINERS;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = new TermuxBoxRepository(this);
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
            openSection(getInitialSection());
        } else {
            String sectionName = savedInstanceState.getString(STATE_SECTION, TermuxBoxSection.CONTAINERS.name());
            try {
                currentSection = TermuxBoxSection.valueOf(sectionName);
            } catch (IllegalArgumentException ignored) {
                currentSection = TermuxBoxSection.CONTAINERS;
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
        if (currentSection != TermuxBoxSection.CONTAINERS) {
            openSection(TermuxBoxSection.CONTAINERS);
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
            default:
                fragment = new TermuxBoxContainerFragment();
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
            case R.id.action_termux_box_containers:
                openSection(TermuxBoxSection.CONTAINERS);
                return true;
            case R.id.action_termux_box_packages:
                openSection(TermuxBoxSection.PACKAGES);
                return true;
            default:
                return false;
        }
    }

    private void onPrimaryAction() {
        Toast.makeText(this, R.string.termux_box_no_confirm_action, Toast.LENGTH_SHORT).show();
    }

    private TermuxBoxSection getInitialSection() {
        Intent intent = getIntent();
        String section = intent == null ? null : intent.getStringExtra(EXTRA_INITIAL_SECTION);
        if (SECTION_CONTAINERS.equals(section)) {
            return TermuxBoxSection.CONTAINERS;
        }
        if (SECTION_PACKAGES.equals(section)) {
            return TermuxBoxSection.PACKAGES;
        }
        return TermuxBoxSection.CONTAINERS;
    }

    private void updateChromeForSection(TermuxBoxSection section) {
        if (toolbar != null) {
            toolbar.setTitle(titleFor(section));
            toolbar.setSubtitle(subtitleFor(section));
        }
        if (fab != null) {
            fab.setVisibility(View.GONE);
        }
    }

    private String titleFor(TermuxBoxSection section) {
        switch (section) {
            case PACKAGES:
                return getString(R.string.termux_box_packages_title);
            case CONTAINERS:
            default:
                return getString(R.string.termux_box_container_title);
        }
    }

    private String subtitleFor(TermuxBoxSection section) {
        switch (section) {
            case PACKAGES:
                return getString(R.string.termux_box_toolbar_packages_subtitle);
            case CONTAINERS:
                return getString(R.string.termux_box_toolbar_container_subtitle);
            default:
                return null;
        }
    }
}
