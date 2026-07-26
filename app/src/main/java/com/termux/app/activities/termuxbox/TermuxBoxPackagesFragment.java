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
    private LinearLayout othersListContainer;
    private LinearLayout wineProgressContainer;
    private LinearLayout othersProgressContainer;
    private String pendingLocalInstallPackageName;
    private String pendingTaskTarget;
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

        content.addView(buildBox64SectionHeader());
        content.addView(buildBox64Card());
        content.addView(buildWineSectionHeader());

        // Shared progress area for wine add operations
        wineProgressContainer = new LinearLayout(requireContext());
        wineProgressContainer.setOrientation(LinearLayout.VERTICAL);
        wineProgressContainer.setVisibility(View.GONE);
        content.addView(wineProgressContainer);

        packageListContainer = new LinearLayout(requireContext());
        packageListContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listParams.topMargin = dp(8);
        content.addView(packageListContainer, listParams);

        // Others section
        content.addView(buildOthersSectionHeader());

        othersProgressContainer = new LinearLayout(requireContext());
        othersProgressContainer.setOrientation(LinearLayout.VERTICAL);
        othersProgressContainer.setVisibility(View.GONE);
        content.addView(othersProgressContainer);

        othersListContainer = new LinearLayout(requireContext());
        othersListContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams othersListParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        othersListParams.topMargin = dp(8);
        content.addView(othersListContainer, othersListParams);

        renderPackages();
        return root;
    }

    private View buildBox64Card() {
        boolean installed = repository.isBox64Installed();
        String currentBuild = repository.getCurrentBox64Build();
        List<String> availableBuilds = repository.getAvailableBox64Builds();

        MaterialCardView card = baseCard();
        if (installed) {
            card.setCardBackgroundColor(0xFFE8F5E9);
            card.setStrokeWidth(dp(2));
            card.setStrokeColor(0xFF4CAF50);
        }

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Title row with installed status
        LinearLayout titleRow = new LinearLayout(requireContext());
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(titleRow);

        TextView headline = new TextView(requireContext());
        headline.setText(R.string.termux_box_packages_box64_title);
        headline.setTextColor(installed ? 0xFF2E7D32 : 0xFF24323F);
        headline.setTypeface(Typeface.DEFAULT_BOLD);
        headline.setTextSize(15f);
        LinearLayout.LayoutParams headlineParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleRow.addView(headline, headlineParams);

        // Status chips
        ChipGroup chips = new ChipGroup(requireContext());
        chips.setChipSpacing(dp(8));
        chips.setSingleLine(true);
        titleRow.addView(chips);

        if (installed) {
            chips.addView(stateChip(getString(R.string.termux_box_packages_in_use), 0xFFE8F5E9, 0xFF2E7D32));
            if (currentBuild != null) {
                chips.addView(stateChip(currentBuild, 0xFFE8F5E9, 0xFF2E7D32));
            }
        } else {
            chips.addView(stateChip(getString(R.string.termux_box_packages_not_installed), 0xFFF9E2E2, 0xFFC62828));
        }

        // Available builds — dynamically show each build found in the box directory
        if (!availableBuilds.isEmpty()) {
            for (String build : availableBuilds) {
                boolean isCurrent = build.equals(currentBuild);
                MaterialCardView buildRow = buildCard();
                LinearLayout buildBody = new LinearLayout(requireContext());
                buildBody.setOrientation(LinearLayout.HORIZONTAL);
                buildBody.setGravity(Gravity.CENTER_VERTICAL);
                buildBody.setPadding(dp(10), dp(10), dp(10), dp(10));
                buildRow.addView(buildBody, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                TextView buildName = new TextView(requireContext());
                buildName.setText(build);
                buildName.setTextColor(isCurrent ? 0xFF2E7D32 : 0xFF24323F);
                buildName.setTypeface(Typeface.DEFAULT_BOLD);
                buildName.setTextSize(14f);
                LinearLayout.LayoutParams buildNameParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                buildBody.addView(buildName, buildNameParams);

                if (isCurrent) {
                    buildBody.addView(stateChip(getString(R.string.termux_box_packages_in_use), 0xFFE8F5E9, 0xFF2E7D32));
                } else {
                    buildBody.addView(actionButtonSmall(R.string.termux_box_packages_apply,
                        v -> runTask(R.string.termux_box_packages_applying_build, "box64",
                            listener -> repository.applyBox64Build(build))));
                }

                body.addView(buildRow);
            }
        }

        View taskInfo = buildTaskInfoView("box64");
        LinearLayout.LayoutParams taskInfoParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        taskInfoParams.topMargin = dp(10);
        body.addView(taskInfo, taskInfoParams);

        return card;
    }

    private View buildBox64SectionHeader() {
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, dp(16), 0, dp(6));
        header.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleRow = new LinearLayout(requireContext());
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(requireContext());
        title.setText(R.string.termux_box_packages_box64_version_section);
        title.setTextColor(0xFF24323F);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(15f);

        MaterialButton addButton = actionButtonSmall(R.string.termux_box_packages_add, v ->
            showPackageDownloadDialog(repository.getBox64OnlyPackages(), "box64", R.string.termux_box_packages_download_box64_title));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleRow.addView(title, titleParams);
        titleRow.addView(addButton);
        header.addView(titleRow);

        TextView path = new TextView(requireContext());
        path.setTextColor(0xFF60707E);
        path.setTextSize(12f);
        path.setText(getString(R.string.termux_box_packages_detection_path, "usr/glibc/bin/"));
        path.setPadding(0, dp(4), 0, 0);
        header.addView(path);

        return header;
    }

    private View buildWineSectionHeader() {
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, dp(16), 0, dp(6));
        header.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleRow = new LinearLayout(requireContext());
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(requireContext());
        title.setText(R.string.termux_box_packages_wine_section);
        title.setTextColor(0xFF24323F);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(15f);

        MaterialButton addButton = actionButtonSmall(R.string.termux_box_packages_add, v ->
            showPackageDownloadDialog(repository.getWinePackages(), "wine-add", R.string.termux_box_packages_download_wine_title));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleRow.addView(title, titleParams);
        titleRow.addView(addButton);
        header.addView(titleRow);

        TextView path = new TextView(requireContext());
        path.setTextColor(0xFF60707E);
        path.setTextSize(12f);
        path.setText(getString(R.string.termux_box_packages_detection_path, "usr/glibc/"));
        path.setPadding(0, dp(4), 0, 0);
        header.addView(path);

        return header;
    }

    private View buildOthersSectionHeader() {
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, dp(16), 0, dp(6));
        header.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleRow = new LinearLayout(requireContext());
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(requireContext());
        title.setText(R.string.termux_box_packages_others_section);
        title.setTextColor(0xFF24323F);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(15f);

        MaterialButton addButton = actionButtonSmall(R.string.termux_box_packages_add, v ->
            showPackageDownloadDialog(repository.getOtherPackages(), "others-add", R.string.termux_box_packages_download_others_title));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleRow.addView(title, titleParams);
        titleRow.addView(addButton);
        header.addView(titleRow);

        TextView path = new TextView(requireContext());
        path.setTextColor(0xFF60707E);
        path.setTextSize(12f);
        path.setText(getString(R.string.termux_box_packages_detection_path, "usr/glibc/"));
        path.setPadding(0, dp(4), 0, 0);
        header.addView(path);

        return header;
    }

    private void renderPackages() {
        if (packageListContainer == null || repository == null) {
            return;
        }
        packageListContainer.removeAllViews();

        // Determine the active wine package from the current container
        TermuxBoxContainerSpec currentContainer = repository.getCurrentContainer();
        String activeWinePackage = currentContainer != null ? currentContainer.wineVersion : null;

        // Use getInstalledWinePackages() which checks the actual glibc/<wineName> directory
        List<TermuxBoxPackageSpec> installedWines = repository.getInstalledWinePackages();
        for (TermuxBoxPackageSpec spec : installedWines) {
            boolean isActive = spec.name.equals(activeWinePackage);
            packageListContainer.addView(buildPackageRow(spec, isActive));
        }

        if (installedWines.isEmpty()) {
            packageListContainer.addView(buildEmptyHint());
        }

        // Update wine progress container for add operations
        updateWineProgress();

        // Render Others section
        if (othersListContainer != null) {
            othersListContainer.removeAllViews();
            List<TermuxBoxPackageSpec> installedOthers = repository.getInstalledOtherPackages();
            for (TermuxBoxPackageSpec spec : installedOthers) {
                othersListContainer.addView(buildPackageRow(spec, false));
            }
            if (installedOthers.isEmpty()) {
                othersListContainer.addView(buildEmptyHint());
            }
        }
        updateOthersProgress();
    }

    private void updateWineProgress() {
        if (wineProgressContainer == null) {
            return;
        }
        wineProgressContainer.removeAllViews();
        boolean active = "wine-add".equals(activeTaskTarget);
        wineProgressContainer.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) {
            wineProgressContainer.addView(buildTaskInfoView("wine-add"));
        }
    }

    private void updateOthersProgress() {
        if (othersProgressContainer == null) {
            return;
        }
        othersProgressContainer.removeAllViews();
        boolean active = "others-add".equals(activeTaskTarget);
        othersProgressContainer.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) {
            othersProgressContainer.addView(buildTaskInfoView("others-add"));
        }
    }

    private View buildEmptyHint() {
        TextView hint = new TextView(requireContext());
        hint.setText(R.string.termux_box_packages_not_installed);
        hint.setTextColor(0xFF60707E);
        hint.setTextSize(13f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(24), 0, dp(24));
        return hint;
    }

    private View buildPackageRow(TermuxBoxPackageSpec spec, boolean isActive) {
        MaterialCardView card = baseCard();
        if (isActive) {
            card.setCardBackgroundColor(0xFFE3F2FD);
            card.setStrokeWidth(dp(2));
            card.setStrokeColor(0xFF42A5F5);
        } else {
            card.setCardBackgroundColor(0xFFF5F5F5);
            card.setStrokeWidth(dp(1));
            card.setStrokeColor(0xFFD3DEE8);
        }

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Top row: name + in-use chip + remove button
        LinearLayout topRow = new LinearLayout(requireContext());
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(topRow);

        TextView title = new TextView(requireContext());
        title.setText(spec.name);
        title.setTextColor(isActive ? 0xFF1565C0 : 0xFF24323F);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(16f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topRow.addView(title, titleParams);

        if (isActive) {
            topRow.addView(stateChip(getString(R.string.termux_box_packages_in_use), 0xFFE3F2FD, 0xFF1565C0));
            topRow.addView(space(dp(8)));
        }

        topRow.addView(actionButtonSmall(R.string.termux_box_packages_uninstall, v -> runTask(R.string.termux_box_packages_uninstalling, spec.name, listener -> repository.removePackage(spec))));

        // Task progress (for remove operations)
        View taskInfo = buildTaskInfoView(spec.name);
        LinearLayout.LayoutParams taskInfoParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        taskInfoParams.topMargin = dp(8);
        body.addView(taskInfo, taskInfoParams);

        return card;
    }

    private void handleLocalInstallDocument(@Nullable Uri uri) {
        if (uri == null) {
            pendingLocalInstallPackageName = null;
            pendingTaskTarget = null;
            return;
        }
        String packageName = pendingLocalInstallPackageName;
        String taskTarget = pendingTaskTarget != null ? pendingTaskTarget : "wine-add";
        pendingLocalInstallPackageName = null;
        pendingTaskTarget = null;
        if (TextUtils.isEmpty(packageName)) {
            Toast.makeText(requireContext(), R.string.termux_box_packages_no_target, Toast.LENGTH_SHORT).show();
            return;
        }
        TermuxBoxPackageSpec spec = repository.findPackage(packageName);
        if (spec == null) {
            spec = new TermuxBoxPackageSpec(packageName, 1, true);
        }
        final TermuxBoxPackageSpec finalSpec = spec;
        final Uri finalUri = uri;
        final String finalTarget = taskTarget;
        runTask(R.string.termux_box_packages_local_installing, finalTarget, listener -> {
            File archive = repository.newTempPackageArchive(finalSpec.name);
            copyUriToFile(finalUri, archive);
            repository.installPackageFromArchive(finalSpec, archive, listener);
        });
    }

    private void showPackageDownloadDialog(List<TermuxBoxPackageSpec> packages, String taskTarget, int titleResId) {
        if (packages.isEmpty()) {
            Toast.makeText(requireContext(), R.string.termux_box_packages_no_packages_available, Toast.LENGTH_SHORT).show();
            return;
        }

        final TermuxBoxPackageSpec[] specs = packages.toArray(new TermuxBoxPackageSpec[0]);
        String[] names = new String[specs.length];
        for (int i = 0; i < specs.length; i++) {
            names[i] = specs[i].name + " (v" + specs[i].version + ")";
        }

        final android.widget.Spinner spinner = new android.widget.Spinner(requireContext());
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, names);
        spinner.setAdapter(adapter);

        MaterialButton browseButton = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        browseButton.setText(R.string.termux_box_packages_select_local);
        browseButton.setAllCaps(false);
        browseButton.setCornerRadius(dp(5));
        browseButton.setTextSize(13f);

        LinearLayout dialogLayout = new LinearLayout(requireContext());
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(dp(16), dp(20), dp(16), dp(8));
        dialogLayout.addView(spinner);

        LinearLayout.LayoutParams browseParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        browseParams.topMargin = dp(12);
        dialogLayout.addView(browseButton, browseParams);

        final android.app.AlertDialog[] dialogRef = {null};
        browseButton.setOnClickListener(v -> {
            int index = spinner.getSelectedItemPosition();
            if (index >= 0 && index < specs.length) {
                pendingLocalInstallPackageName = specs[index].name;
                pendingTaskTarget = taskTarget;
            }
            if (dialogRef[0] != null) {
                dialogRef[0].dismiss();
            }
            localInstallLauncher.launch(new String[]{"*/*"});
        });

        dialogRef[0] = new android.app.AlertDialog.Builder(requireContext())
            .setTitle(titleResId)
            .setView(dialogLayout)
            .setPositiveButton(R.string.termux_box_packages_download, (dialog, which) -> {
                int index = spinner.getSelectedItemPosition();
                if (index >= 0 && index < specs.length) {
                    TermuxBoxPackageSpec selected = specs[index];
                    runTask(R.string.termux_box_packages_installing, taskTarget,
                        listener -> repository.syncPackage(selected, true, listener));
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
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

    /** A compact card variant for inner rows (no bottom margin, tighter styling). */
    private MaterialCardView buildCard() {
        MaterialCardView card = new MaterialCardView(requireContext());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        card.setLayoutParams(params);
        card.setCardBackgroundColor(0xFFF8FAFC);
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