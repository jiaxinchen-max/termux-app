package com.termux.app.activities.termuxbox;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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

public class TermuxBoxSettingsFragment extends Fragment {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TermuxBoxRepository repository;
    private TextView statusView;
    private ProgressBar progressBar;
    private TextView currentStateView;
    private EditText resolutionInput;
    private EditText localeInput;

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

        content.addView(sectionTitle("System Settings"));
        content.addView(sectionSubtitle("These values are written directly to mobox config files."));

        currentStateView = new TextView(requireContext());
        currentStateView.setTextColor(0xFFE0E0E0);
        currentStateView.setTextSize(13f);
        currentStateView.setPadding(0, dp(12), 0, 0);
        content.addView(currentStateView);

        progressBar = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.topMargin = dp(12);
        content.addView(progressBar, progressParams);

        statusView = new TextView(requireContext());
        statusView.setTextColor(0xFFBDBDBD);
        statusView.setTextSize(13f);
        statusView.setText("Ready");
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(8);
        content.addView(statusView, statusParams);

        content.addView(buildResolutionSection());
        content.addView(buildCoreSection());
        content.addView(buildLocaleSection());
        content.addView(buildHudSection());
        content.addView(buildTuDebugSection());
        content.addView(buildActionSection());

        refreshState();
        return root;
    }

    private View buildResolutionSection() {
        LinearLayout section = sectionBox("Fallback Resolution");
        resolutionInput = input("1280x720", InputType.TYPE_CLASS_TEXT);
        section.addView(resolutionInput, boxChildParams());
        MaterialButton save = actionButton("Save resolution", v ->
            runTask("Saving resolution", listener -> repository.setFallbackResolution(textOf(resolutionInput))));
        section.addView(save, boxChildParams());
        return section;
    }

    private View buildCoreSection() {
        LinearLayout section = sectionBox("Primary Cores");
        LinearLayout column = new LinearLayout(requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        section.addView(column, boxChildParams());
        addCoreButton(column, "2", 6, 7, 0, 5);
        addCoreButton(column, "3", 5, 7, 0, 4);
        addCoreButton(column, "4", 4, 7, 0, 3);
        addCoreButton(column, "5", 3, 7, 0, 2);
        addCoreButton(column, "6", 2, 7, 0, 1);
        addCoreButton(column, "7", 1, 7, 0, 1);
        addCoreButton(column, "8", 0, 7, 0, 1);
        TextView hint = hint("Matches the shell presets: 2..8.");
        section.addView(hint, boxChildParams());
        return section;
    }

    private View buildLocaleSection() {
        LinearLayout section = sectionBox("Locale");
        localeInput = input("en_US", InputType.TYPE_CLASS_TEXT);
        section.addView(localeInput, boxChildParams());
        section.addView(actionButton("Save locale", v ->
            runTask("Saving locale", listener -> repository.setLocale(textOf(localeInput)))), boxChildParams());
        return section;
    }

    private View buildHudSection() {
        LinearLayout section = sectionBox("HUD Presets");
        section.addView(horizontalButtons(
            new ButtonSpec("Off", v -> runTask("Saving HUD preset", listener -> repository.setHudPreset(1))),
            new ButtonSpec("FPS", v -> runTask("Saving HUD preset", listener -> repository.setHudPreset(2))),
            new ButtonSpec("Detailed", v -> runTask("Saving HUD preset", listener -> repository.setHudPreset(3)))
        ), boxChildParams());
        return section;
    }

    private View buildTuDebugSection() {
        LinearLayout section = sectionBox("TU_DEBUG");
        section.addView(horizontalButtons(
            new ButtonSpec("noconform", v -> runTask("Saving TU_DEBUG", listener -> repository.setTuDebugPreset(1))),
            new ButtonSpec("syncdraw", v -> runTask("Saving TU_DEBUG", listener -> repository.setTuDebugPreset(2))),
            new ButtonSpec("flushall", v -> runTask("Saving TU_DEBUG", listener -> repository.setTuDebugPreset(3)))
        ), boxChildParams());
        return section;
    }

    private View buildActionSection() {
        LinearLayout section = sectionBox("Actions");
        section.addView(sectionHint("Dynarec 已并入容器设置 → 高级"), boxChildParams());
        section.addView(actionButton("Reset to default", v ->
            runTask("Resetting system settings", listener -> repository.resetSystemSettings())), boxChildParams());
        return section;
    }

    private void refreshState() {
        if (currentStateView == null || repository == null) {
            return;
        }
        currentStateView.setText(
            "Fallback resolution: " + repository.getFallbackResolution() + "\n" +
            "Locale: " + repository.getLocale());
        if (resolutionInput != null) {
            resolutionInput.setText(repository.getFallbackResolution());
        }
        if (localeInput != null) {
            String locale = repository.getLocale();
            if (locale.endsWith(".utf8")) {
                locale = locale.substring(0, locale.length() - 5);
            }
            localeInput.setText(locale);
        }
    }

    private void runTask(String message, SettingsTask task) {
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
            } finally {
                if (isAdded()) {
                    requireActivity().runOnUiThread(this::refreshState);
                }
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

    private LinearLayout sectionBox(String title) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        box.setBackgroundColor(0xFF1C1C1C);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(16);
        box.setLayoutParams(params);

        TextView heading = new TextView(requireContext());
        heading.setText(title);
        heading.setTextColor(0xFFFFFFFF);
        heading.setTextSize(16f);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(heading);
        return box;
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

    private TextView hint(String text) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextColor(0xFFBDBDBD);
        view.setTextSize(12f);
        return view;
    }

    private TextView sectionHint(String text) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextColor(0xFFBDBDBD);
        view.setTextSize(12f);
        return view;
    }

    private EditText input(String hint, int inputType) {
        EditText editText = new EditText(requireContext());
        editText.setHint(hint);
        editText.setTextColor(0xFFFFFFFF);
        editText.setHintTextColor(0xFF888888);
        editText.setInputType(inputType);
        editText.setBackgroundColor(0xFF232323);
        editText.setPadding(dp(12), dp(12), dp(12), dp(12));
        return editText;
    }

    private MaterialButton actionButton(String label, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(label);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private LinearLayout horizontalButtons(ButtonSpec... specs) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.START);
        for (int i = 0; i < specs.length; i++) {
            row.addView(actionButton(specs[i].label, specs[i].listener));
        }
        return row;
    }

    private void addCoreButton(LinearLayout row, String label, int primaryStart, int primaryEnd, int secondaryStart, int secondaryEnd) {
        MaterialButton button = actionButton(label, v ->
            runTask("Saving cores", listener -> repository.setCorePreset(primaryStart, primaryEnd, secondaryStart, secondaryEnd)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        row.addView(button);
    }

    private LinearLayout.LayoutParams boxChildParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        return params;
    }

    private String textOf(EditText editText) {
        return editText == null || editText.getText() == null ? "" : editText.getText().toString().trim();
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

    private interface SettingsTask {
        void run(TermuxBoxRepository.ProgressListener listener) throws Exception;
    }

    private static final class ButtonSpec {
        final String label;
        final View.OnClickListener listener;

        ButtonSpec(String label, View.OnClickListener listener) {
            this.label = label;
            this.listener = listener;
        }
    }
}
