package com.termux.ui.activity;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MenuItem;
import androidx.appcompat.view.SupportMenuInflater;
import com.termux.ui.R;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/** Five standard destinations and the optional IDE destination. */
public final class RootNavigationView extends BottomNavigationView {
    public RootNavigationView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    /** Inflate only when the portrait bar is needed, with one presenter update. */
    public void prepareMenu(boolean ideEnabled, int selected) {
        boolean empty = getMenu().size() == 0;
        MenuItem ide = getMenu().findItem(R.id.local_games_navigation_ide);
        MenuItem destination = getMenu().findItem(selected);
        if (!empty && ide.isVisible() == ideEnabled
            && destination != null && destination.isChecked()) return;
        getPresenter().setUpdateSuspended(true);
        try {
            // Unlike inflateMenu(), this does not force an intermediate presenter rebuild.
            if (empty) new SupportMenuInflater(getContext()).inflate(
                R.menu.local_games_bottom_navigation, getMenu());
            getMenu().findItem(R.id.local_games_navigation_ide).setVisible(ideEnabled);
            destination = getMenu().findItem(selected);
            if (destination != null && destination.isVisible()) destination.setChecked(true);
        } finally {
            getPresenter().setUpdateSuspended(false);
        }
        getPresenter().updateMenuView(true);
    }

    @Override
    public int getMaxItemCount() { return 6; }
}
