package com.termux.app.activities.termuxbox;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

public class TermuxBoxNotesFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull android.view.LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        TermuxBoxRepository repository = navigator().getRepository();

        NestedScrollView root = new NestedScrollView(requireContext());
        root.setBackgroundColor(0xFF121212);
        root.setFillViewport(true);

        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(sectionTitle("Patch Notes"));

        TextView body = new TextView(requireContext());
        body.setTextColor(0xFFE0E0E0);
        body.setTextSize(14f);
        body.setPadding(0, dp(16), 0, 0);
        body.setText(repository.getPatchNotes());
        content.addView(body);

        return root;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextColor(0xFFFFFFFF);
        view.setTextSize(28f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * requireContext().getResources().getDisplayMetrics().density);
    }

    private TermuxBoxNavigator navigator() {
        if (getActivity() instanceof TermuxBoxNavigator) {
            return (TermuxBoxNavigator) getActivity();
        }
        throw new IllegalStateException("TermuxBoxNavigator not attached");
    }
}
