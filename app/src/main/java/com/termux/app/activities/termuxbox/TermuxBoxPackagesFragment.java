package com.termux.app.activities.termuxbox;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.content.res.ColorStateList;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class TermuxBoxPackagesFragment extends Fragment {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<String[]> localInstallLauncher;

    private TermuxBoxRepository repository;
    private LinearLayout packageListContainer;
    private String pendingLocalInstallPackageName;
    private String selectedPackageName;
    private String activeTaskTarget;
    private String activeTaskMessage;
    private int activeTaskProgress = -1;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        localInstallLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            this::handleLocalInstallDocument);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull android.view.LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        repository = navigator().getRepository();
        repository.ensureDirs();

        NestedScrollView root = new NestedScrollView(requireContext());
        root.setBackgroundColor(0xFFEFF3F6);
        root.setFillViewport(true);

        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(sectionTitle(R.string.termux_box_packages_title));
        content.addView(sectionSubtitle(R.string.termux_box_packages_summary));
        content.addView(buildBox64Card());
        content.addView(buildPackageListHeader());

        packageListContainer = new LinearLayout(requireContext());
        packageListContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listParams.topMargin = dp(8);
        content.addView(packageListContainer, listParams);

        renderPackages();
        return root;
    }

    private View buildBox64Card() {
        MaterialCardView card = baseCard();

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView headline = new TextView(requireContext());
        headline.setText(R.string.termux_box_packages_box64_title);
        headline.setTextColor(0xFF24323F);
        headline.setTypeface(Typeface.DEFAULT_BOLD);
        headline.setTextSize(15f);
        body.addView(headline);

        TextView summary = new TextView(requireContext());
        summary.setTextColor(0xFF60707E);
        summary.setTextSize(13f);
        summary.setText(R.string.termux_box_packages_box64_summary);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = dp(6);
        body.addView(summary, summaryParams);

        View taskInfo = buildTaskInfoView("box64");
        LinearLayout.LayoutParams taskInfoParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        taskInfoParams.topMargin = dp(10);
        body.addView(taskInfo, taskInfoParams);

        LinearLayout actions = new LinearLayout(requireContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(12);
        body.addView(actions, actionsParams);

        actions.addView(actionButton("MAR3", v -> runTask(R.string.termux_box_packages_applying_build, "box64", listener -> repository.applyBox64Build("mar3"))), weightedParams());
        actions.addView(space(dp(10)), fixedParams(dp(10), 1));
        actions.addView(actionButton("FEB14", v -> runTask(R.string.termux_box_packages_applying_build, "box64", listener -> repository.applyBox64Build("feb14"))), weightedParams());

        return card;
    }

    private View buildPackageListHeader() {
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(16), 0, dp(6));
        header.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(requireContext());
        title.setText(R.string.termux_box_packages_all);
        title.setTextColor(0xFF24323F);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(15f);

        MaterialButton refresh = actionButtonSmall(R.string.termux_box_packages_refresh, v -> renderPackages());
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleParams);
        header.addView(refresh);

        return header;
    }

    private void renderPackages() {
        if (packageListContainer == null || repository == null) {
            return;
        }
        ensureSelection();
        packageListContainer.removeAllViews();

        List<TermuxBoxPackageSpec> packages = repository.getPackages();
        for (TermuxBoxPackageSpec spec : packages) {
            packageListContainer.addView(buildPackageRow(spec));
        }
    }

    private View buildPackageRow(TermuxBoxPackageSpec spec) {
        boolean selected = spec.name.equals(selectedPackageName);
        boolean installed = repository.isInstalled(spec);
        boolean upToDate = repository.isUpToDate(spec);
        MaterialCardView card = baseCard();
        card.setCardBackgroundColor(0xFFFFFFFF);
        card.setStrokeWidth(dp(1));
        card.setOnClickListener(v -> {
            selectedPackageName = selected ? null : spec.name;
            renderPackages();
        });

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleRow = new LinearLayout(requireContext());
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(titleRow);

        TextView title = new TextView(requireContext());
        title.setText(spec.name);
        title.setTextColor(0xFF24323F);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(16f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleRow.addView(title, titleParams);

        LinearLayout details = new LinearLayout(requireContext());
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(selected ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailsParams.topMargin = dp(10);
        body.addView(details, detailsParams);

        TextView summary = new TextView(requireContext());
        summary.setTextColor(0xFF60707E);
        summary.setTextSize(12f);
        summary.setText(packageStatus(spec));
        details.addView(summary);

        ChipGroup chips = new ChipGroup(requireContext());
        chips.setChipSpacing(dp(8));
        chips.setSingleLine(true);
        LinearLayout.LayoutParams chipsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipsParams.topMargin = dp(10);
        details.addView(chips, chipsParams);

        chips.addView(stateChip("v" + spec.version, 0xFFF2F5F7, 0xFF60707E));
        chips.addView(stateChip(installed ? getString(R.string.termux_box_packages_installed, repository.getInstalledVersionText(spec))
                : getString(R.string.termux_box_packages_not_installed),
            installed ? 0xFFE8F5E9 : 0xFFF9E2E2,
            installed ? 0xFF2E7D32 : 0xFFC62828));
        if (spec.wine) {
            chips.addView(stateChip("WINE", 0xFFF3ECFF, 0xFF7B4BD8));
        }

        View taskInfo = buildTaskInfoView(spec.name);
        LinearLayout.LayoutParams taskInfoParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        taskInfoParams.topMargin = dp(10);
        details.addView(taskInfo, taskInfoParams);

        LinearLayout actions = new LinearLayout(requireContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(12);
        details.addView(actions, actionsParams);

        actions.addView(actionButtonCompact(installed && !upToDate ? R.string.termux_box_packages_update : R.string.termux_box_packages_network_install, v -> runTask(installed && !upToDate ? R.string.termux_box_packages_updating : R.string.termux_box_packages_installing, spec.name,
            listener -> repository.syncPackage(spec, false, listener))), weightedParams());
        actions.addView(space(dp(8)), fixedParams(dp(8), 1));
        actions.addView(actionButtonCompact(R.string.termux_box_packages_local_install, v -> {
            pendingLocalInstallPackageName = spec.name;
            localInstallLauncher.launch(new String[] {"*/*"});
        }), weightedParams());
        actions.addView(space(dp(8)), fixedParams(dp(8), 1));
        actions.addView(actionButtonCompact(R.string.termux_box_packages_verify, v -> runTask(R.string.termux_box_packages_verifying, spec.name, listener -> repository.validatePackage(spec, listener))), weightedParams());
        actions.addView(space(dp(8)), fixedParams(dp(8), 1));
        actions.addView(actionButtonCompact(R.string.termux_box_packages_uninstall, v -> runTask(R.string.termux_box_packages_uninstalling, spec.name, listener -> repository.removePackage(spec))), weightedParams());

        return card;
    }

    private String packageStatus(TermuxBoxPackageSpec spec) {
        StringBuilder state = new StringBuilder();
        state.append(repository.isInstalled(spec)
            ? getString(R.string.termux_box_packages_installed, repository.getInstalledVersionText(spec))
            : getString(R.string.termux_box_packages_not_installed));
        return state.toString();
    }

    private void handleLocalInstallDocument(@Nullable Uri uri) {
        if (uri == null) {
            pendingLocalInstallPackageName = null;
            return;
        }
        String packageName = pendingLocalInstallPackageName;
        pendingLocalInstallPackageName = null;
        if (TextUtils.isEmpty(packageName)) {
            Toast.makeText(requireContext(), R.string.termux_box_packages_no_target, Toast.LENGTH_SHORT).show();
            return;
        }
        TermuxBoxPackageSpec spec = repository.findPackage(packageName);
        if (spec == null) {
            Toast.makeText(requireContext(), R.string.termux_box_packages_target_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        runTask(R.string.termux_box_packages_local_installing, spec.name, listener -> {
            File archive = repository.newTempPackageArchive(spec.name);
            copyUriToFile(uri, archive);
            repository.installPackageFromArchive(spec, archive, listener);
        });
    }

    private void runTask(int initialMessageResId, @Nullable String target, ProgressTask task) {
        updateTaskState(target, getString(initialMessageResId), 0);
        executor.execute(() -> {
            try {
                task.run(new TermuxBoxRepository.ProgressListener() {
                    @Override
                    public void onMessage(String message) {
                        updateTaskState(target, message, -1);
                    }

                    @Override
                    public void onProgress(int progress) {
                        updateTaskState(target, null, progress);
                    }
                });
                clearTaskState(target);
                showTaskToast(R.string.termux_box_packages_done);
            } catch (Exception e) {
                clearTaskState(target);
                showTaskToast(getString(R.string.termux_box_packages_failed, e.getMessage()));
            } finally {
                if (isAdded()) {
                    requireActivity().runOnUiThread(this::renderPackages);
                }
            }
        });
    }

    private View buildTaskInfoView(@NonNull String target) {
        LinearLayout info = new LinearLayout(requireContext());
        info.setOrientation(LinearLayout.VERTICAL);
        info.setVisibility(isTaskVisible(target) ? View.VISIBLE : View.GONE);

        TextView message = new TextView(requireContext());
        message.setTextColor(0xFF60707E);
        message.setTextSize(13f);
        message.setText(isTaskVisible(target) ? activeTaskMessage : "");
        info.addView(message);

        ProgressBar bar = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = dp(8);
        info.addView(bar, barParams);
        bar.setVisibility(isTaskVisible(target) ? View.VISIBLE : View.GONE);
        if (isTaskVisible(target) && activeTaskProgress >= 0) {
            bar.setProgress(activeTaskProgress);
        }

        return info;
    }

    private void copyUriToFile(@NonNull Uri uri, @NonNull File destination) throws Exception {
        try (InputStream input = requireContext().getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(destination, false)) {
            if (input == null) {
                throw new IllegalStateException(getString(R.string.termux_box_packages_cannot_open_file));
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private void ensureSelection() {
        if (selectedPackageName == null) {
            return;
        }
        if (repository.findPackage(selectedPackageName) == null) {
            selectedPackageName = null;
        }
    }

    private void updateTaskState(@Nullable String target, @Nullable String message, int progress) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            activeTaskTarget = target;
            if (message != null) {
                activeTaskMessage = message;
            }
            if (progress >= 0) {
                activeTaskProgress = progress;
            }
            renderPackages();
        });
    }

    private void clearTaskState(@Nullable String target) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            if (target == null || target.equals(activeTaskTarget)) {
                activeTaskTarget = null;
                activeTaskMessage = null;
                activeTaskProgress = -1;
            }
            renderPackages();
        });
    }

    private boolean isTaskVisible(@NonNull String target) {
        return target.equals(activeTaskTarget);
    }

    private void showTaskToast(@NonNull String message) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show());
    }

    private void showTaskToast(int messageResId) {
        showTaskToast(getString(messageResId));
    }

    private MaterialButton actionButton(String label, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        button.setCornerRadius(dp(5));
        button.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    private MaterialButton actionButtonSmall(String label, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        button.setCornerRadius(dp(5));
        button.setMinHeight(0);
        button.setTextSize(12f);
        button.setPadding(dp(10), dp(6), dp(10), dp(6));
        return button;
    }

    private MaterialButton actionButtonCompact(String label, View.OnClickListener listener) {
        MaterialButton button = actionButtonSmall(label, listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return button;
    }

    private MaterialButton actionButton(int labelResId, View.OnClickListener listener) {
        return actionButton(getString(labelResId), listener);
    }

    private MaterialButton actionButtonSmall(int labelResId, View.OnClickListener listener) {
        return actionButtonSmall(getString(labelResId), listener);
    }

    private MaterialButton actionButtonCompact(int labelResId, View.OnClickListener listener) {
        return actionButtonCompact(getString(labelResId), listener);
    }

    private Chip stateChip(String label, int backgroundColor, int textColor) {
        Chip chip = new Chip(requireContext());
        chip.setText(label);
        chip.setTextColor(textColor);
        chip.setChipBackgroundColor(ColorStateList.valueOf(backgroundColor));
        chip.setChipCornerRadius(dp(5));
        chip.setChipStrokeColor(ColorStateList.valueOf(0xFFD3DEE8));
        chip.setChipStrokeWidth(dp(1));
        chip.setCloseIconVisible(false);
        chip.setClickable(false);
        chip.setCheckable(false);
        return chip;
    }

    private View space(int width) {
        View space = new View(requireContext());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, 1);
        space.setLayoutParams(params);
        return space;
    }

    private LinearLayout.LayoutParams fixedParams(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private LinearLayout.LayoutParams weightedParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private MaterialCardView baseCard() {
        MaterialCardView card = new MaterialCardView(requireContext());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);
        card.setCardBackgroundColor(0xFFFFFFFF);
        card.setCardElevation(0f);
        card.setRadius(dp(5));
        card.setStrokeColor(0xFFD3DEE8);
        card.setStrokeWidth(dp(1));
        return card;
    }

    private TextView sectionTitle(int textResId) {
        TextView view = new TextView(requireContext());
        view.setText(textResId);
        view.setTextColor(0xFF24323F);
        view.setTextSize(24f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView sectionSubtitle(int textResId) {
        TextView view = new TextView(requireContext());
        view.setText(textResId);
        view.setTextColor(0xFF60707E);
        view.setTextSize(13f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
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

    private interface ProgressTask {
        void run(TermuxBoxRepository.ProgressListener listener) throws Exception;
    }
}
