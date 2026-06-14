package com.termux.app.activities.termuxbox;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TermuxBoxBox64Fragment extends Fragment {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TermuxBoxRepository repository;
    private TextView statusView;
    private ProgressBar progressBar;

    @Nullable
    @Override
    public View onCreateView(@NonNull android.view.LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        repository = navigator().getRepository();

        NestedScrollView root = new NestedScrollView(requireContext());
        root.setBackgroundColor(0xFF121212);
        root.setFillViewport(true);

        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(sectionTitle("Box64 Build"));
        content.addView(sectionSubtitle("These archives live inside the app and are extracted directly into glibc/bin."));

        progressBar = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.topMargin = dp(16);
        content.addView(progressBar, progressParams);

        statusView = new TextView(requireContext());
        statusView.setTextColor(0xFFBDBDBD);
        statusView.setTextSize(13f);
        statusView.setText("Ready");
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(8);
        content.addView(statusView, statusParams);

        LinearLayout actions = new LinearLayout(requireContext());
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dp(16), 0, 0);
        content.addView(actions);

        actions.addView(actionButton("Apply mar3 build", v ->
            runTask("Applying mar3", listener -> repository.applyBox64Build("mar3"))));
        actions.addView(actionButton("Apply feb14 build", v ->
            runTask("Applying feb14", listener -> repository.applyBox64Build("feb14"))));

        TextView note = new TextView(requireContext());
        note.setTextColor(0xFFBDBDBD);
        note.setTextSize(12f);
        note.setPadding(0, dp(16), 0, 0);
        note.setText("The archive content is a single box64 binary. Extraction overwrites glibc/bin/box64.");
        content.addView(note);

        return root;
    }

    private void runTask(String message, Box64Task task) {
        setStatus(message, 0);
        executor.execute(() -> {
            try {
                task.run(new TermuxBoxRepository.ProgressListener() {
                    @Override
                    public void onMessage(String message) {
                        setStatus(message, -1);
                    }

                    @Override
                    public void onProgress(int progress) {
                        setStatus(null, progress);
                    }
                });
                setStatus("Done", 100);
            } catch (Exception e) {
                setStatus("Failed: " + e.getMessage(), 0);
            }
        });
    }

    private void setStatus(@Nullable String message, int progress) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            if (message != null && statusView != null) {
                statusView.setText(message);
            }
            if (progressBar != null && progress >= 0) {
                progressBar.setProgress(progress);
            }
        });
    }

    private MaterialButton actionButton(String label, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(label);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        button.setLayoutParams(params);
        return button;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextColor(0xFFFFFFFF);
        view.setTextSize(28f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView sectionSubtitle(String text) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextColor(0xFFBDBDBD);
        view.setTextSize(14f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        view.setLayoutParams(params);
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

    private interface Box64Task {
        void run(TermuxBoxRepository.ProgressListener listener) throws Exception;
    }
}
