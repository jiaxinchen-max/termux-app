package com.termux.app.localgames;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import com.termux.ui.api.AppExperienceMode;

import java.util.ArrayList;
import java.util.List;

/** Keeps exactly one launcher alias enabled for the persisted default experience. */
public final class TermuxLauncherIcon {
    private TermuxLauncherIcon() { }

    public static void sync(Context context, AppExperienceMode mode) {
        if (mode == null) throw new IllegalArgumentException("app_experience_mode_required");
        PackageManager manager = context.getPackageManager();
        ComponentName terminal = new ComponentName(context.getPackageName(),
            context.getPackageName() + ".TerminalLauncher");
        ComponentName ui = new ComponentName(context.getPackageName(),
            context.getPackageName() + ".UiLauncher");
        ComponentName selected = mode == AppExperienceMode.TERMINAL ? terminal : ui;
        ComponentName other = mode == AppExperienceMode.TERMINAL ? ui : terminal;
        boolean enableSelected = manager.getComponentEnabledSetting(selected)
            != PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        boolean disableOther = manager.getComponentEnabledSetting(other)
            != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        if (!enableSelected && !disableOther) return;

        if (Build.VERSION.SDK_INT >= 33) {
            List<PackageManager.ComponentEnabledSetting> changes = new ArrayList<>();
            if (enableSelected) changes.add(new PackageManager.ComponentEnabledSetting(selected,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP));
            if (disableOther) changes.add(new PackageManager.ComponentEnabledSetting(other,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP));
            manager.setComponentEnabledSettings(changes);
        } else {
            // Enable the destination first so older Android versions never lose their entry point.
            if (enableSelected) manager.setComponentEnabledSetting(selected,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            if (disableOther) manager.setComponentEnabledSetting(other,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }
}
