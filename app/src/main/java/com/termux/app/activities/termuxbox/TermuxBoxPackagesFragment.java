package com.termux.app.activities.termuxbox;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.content.res.ColorStateList;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

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

public class TermuxBoxPackagesFragment extends Fragment {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TermuxBoxRepository repository;
    private LinearLayout packageListContainer;
    private TextView statusView;
    private ProgressBar progressBar;
    private TextView selectedTitleView;
    private TextView selectedSummaryView;
    private ChipGroup selectedStateChips;
    private MaterialButton installButton;
    private MaterialButton verifyButton;
    private MaterialButton uninstallButton;
    private MaterialButton verifyAllButton;
    private MaterialButton refreshButton;
    private MaterialButton selectWineButton;
    private String selectedPackageName;

    @Nullable
    @Override
    public View onCreateView(@NonNull android.view.LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        repository = navigator().getRepository();
        repository.ensureDirs();
        if (selectedPackageName == null) {
            String currentWine = repository.getCurrentWineContainerName();
            if (currentWine != null && repository.findPackage(currentWine) != null) {
                selectedPackageName = currentWine;
            }
        }

        NestedScrollView root = new NestedScrollView(requireContext());
        root.setBackgroundColor(0xFFEFF3F6);
        root.setFillViewport(true);

        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(sectionTitle("软件包"));
        content.addView(sectionSubtitle("安装、校验、卸载由上方共享操作完成，下面只负责选包和看状态。"));
        content.addView(buildStatusCard());
        content.addView(buildSelectedActionCard());
        content.addView(buildPackageListHeader());

        packageListContainer = new LinearLayout(requireContext());
        packageListContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listParams.topMargin = dp(8);
        content.addView(packageListContainer, listParams);

        renderPackages();
        return root;
    }

    private View buildStatusCard() {
        MaterialCardView card = baseCard();

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView headline = new TextView(requireContext());
        headline.setText("状态");
        headline.setTextColor(0xFF24323F);
        headline.setTypeface(Typeface.DEFAULT_BOLD);
        headline.setTextSize(15f);
        body.addView(headline);

        progressBar = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.topMargin = dp(12);
        body.addView(progressBar, progressParams);

        statusView = new TextView(requireContext());
        statusView.setTextColor(0xFF60707E);
        statusView.setTextSize(13f);
        statusView.setText("Ready");
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(8);
        body.addView(statusView, statusParams);

        return card;
    }

    private View buildSelectedActionCard() {
        MaterialCardView card = baseCard();

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(requireContext());
        title.setText("当前选中");
        title.setTextColor(0xFF24323F);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(15f);
        body.addView(title);

        selectedTitleView = new TextView(requireContext());
        selectedTitleView.setTextColor(0xFF24323F);
        selectedTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        selectedTitleView.setTextSize(20f);
        selectedTitleView.setPadding(0, dp(8), 0, 0);
        body.addView(selectedTitleView);

        selectedSummaryView = new TextView(requireContext());
        selectedSummaryView.setTextColor(0xFF60707E);
        selectedSummaryView.setTextSize(13f);
        selectedSummaryView.setPadding(0, dp(6), 0, 0);
        body.addView(selectedSummaryView);

        selectedStateChips = new ChipGroup(requireContext());
        selectedStateChips.setChipSpacing(dp(8));
        selectedStateChips.setSingleLine(true);
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipParams.topMargin = dp(10);
        body.addView(selectedStateChips, chipParams);

        LinearLayout primaryActions = new LinearLayout(requireContext());
        primaryActions.setOrientation(LinearLayout.HORIZONTAL);
        primaryActions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        primaryParams.topMargin = dp(14);
        body.addView(primaryActions, primaryParams);

        installButton = actionButton("安装", v -> runSelectedPackageTask("安装", (spec, listener) -> repository.syncPackage(spec, false, listener)));
        verifyButton = actionButton("校验", v -> runSelectedPackageTask("校验", (spec, listener) -> repository.validatePackage(spec, listener)));
        uninstallButton = actionButton("卸载", v -> runSelectedPackageTask("卸载", (spec, listener) -> repository.removePackage(spec)));

        primaryActions.addView(installButton, weightedParams());
        primaryActions.addView(space(dp(10)), fixedParams(dp(10), 1));
        primaryActions.addView(verifyButton, weightedParams());
        primaryActions.addView(space(dp(10)), fixedParams(dp(10), 1));
        primaryActions.addView(uninstallButton, weightedParams());

        LinearLayout secondaryActions = new LinearLayout(requireContext());
        secondaryActions.setOrientation(LinearLayout.HORIZONTAL);
        secondaryActions.setGravity(Gravity.START);
        LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secondaryParams.topMargin = dp(10);
        body.addView(secondaryActions, secondaryParams);

        refreshButton = actionButtonSmall("刷新", v -> renderPackages());
        verifyAllButton = actionButtonSmall("全部校验", v -> runTask("Validating packages", listener -> repository.validateAll(listener)));
        selectWineButton = actionButtonSmall("设为当前容器", v -> {
            TermuxBoxPackageSpec spec = selectedSpec();
            if (spec != null && spec.wine) {
                runSelectedPackageTask("选择容器", (target, listener) -> repository.setWineContainer(target));
            }
        });

        secondaryActions.addView(refreshButton);
        secondaryActions.addView(space(dp(10)), fixedParams(dp(10), 1));
        secondaryActions.addView(verifyAllButton);
        secondaryActions.addView(space(dp(10)), fixedParams(dp(10), 1));
        secondaryActions.addView(selectWineButton);

        return card;
    }

    private View buildPackageListHeader() {
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(16), 0, dp(6));

        TextView title = new TextView(requireContext());
        title.setText("全部包");
        title.setTextColor(0xFF24323F);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(15f);
        header.addView(title);

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
        refreshSelectionPanel();
    }

    private View buildPackageRow(TermuxBoxPackageSpec spec) {
        boolean selected = spec.name.equals(selectedPackageName);
        MaterialCardView card = baseCard();
        card.setCardBackgroundColor(0xFFFFFFFF);
        card.setStrokeColor(selected ? 0xFF2D8CDB : 0xFFD3DEE8);
        card.setStrokeWidth(dp(1));
        card.setOnClickListener(v -> {
            selectedPackageName = spec.name;
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

        if (selected) {
            Chip selectedChip = stateChip("已选", 0xFFEAF2FF, 0xFF2D8CDB);
            titleRow.addView(selectedChip);
        }

        TextView summary = new TextView(requireContext());
        summary.setTextColor(0xFF60707E);
        summary.setTextSize(12f);
        summary.setPadding(0, dp(6), 0, 0);
        summary.setText(packageStatus(spec));
        body.addView(summary);

        ChipGroup chips = new ChipGroup(requireContext());
        chips.setChipSpacing(dp(8));
        chips.setSingleLine(true);
        LinearLayout.LayoutParams chipsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipsParams.topMargin = dp(10);
        body.addView(chips, chipsParams);

        chips.addView(stateChip("v" + spec.version, 0xFFF2F5F7, 0xFF60707E));
        chips.addView(stateChip(repository.isInstalled(spec) ? "已安装" : "未安装",
            repository.isInstalled(spec) ? 0xFFE8F5E9 : 0xFFF9E2E2,
            repository.isInstalled(spec) ? 0xFF2E7D32 : 0xFFC62828));
        if (spec.wine) {
            chips.addView(stateChip("WINE", 0xFFF3ECFF, 0xFF7B4BD8));
        }

        return card;
    }

    private void refreshSelectionPanel() {
        TermuxBoxPackageSpec spec = selectedSpec();
        if (spec == null) {
            selectedTitleView.setText("未选择");
            selectedSummaryView.setText("请选择一个包再执行操作。");
            selectedStateChips.removeAllViews();
            installButton.setEnabled(false);
            verifyButton.setEnabled(false);
            uninstallButton.setEnabled(false);
            selectWineButton.setVisibility(View.GONE);
            return;
        }

        boolean installed = repository.isInstalled(spec);
        boolean upToDate = repository.isUpToDate(spec);
        selectedTitleView.setText(spec.name);
        selectedSummaryView.setText((installed ? "已安装 " + repository.getInstalledVersionText(spec) : "未安装")
            + " · " + (upToDate ? "已是最新" : "有可更新版本"));

        selectedStateChips.removeAllViews();
        selectedStateChips.addView(stateChip("v" + spec.version, 0xFFF2F5F7, 0xFF60707E));
        selectedStateChips.addView(stateChip(installed ? "已安装" : "未安装",
            installed ? 0xFFE8F5E9 : 0xFFF9E2E2,
            installed ? 0xFF2E7D32 : 0xFFC62828));
        if (spec.wine) {
            selectedStateChips.addView(stateChip("WINE", 0xFFF3ECFF, 0xFF7B4BD8));
        }

        installButton.setText(installed && !upToDate ? "更新" : "安装");
        installButton.setEnabled(true);
        verifyButton.setEnabled(true);
        uninstallButton.setEnabled(true);
        selectWineButton.setVisibility(spec.wine ? View.VISIBLE : View.GONE);
    }

    private String packageStatus(TermuxBoxPackageSpec spec) {
        StringBuilder state = new StringBuilder();
        state.append(repository.isInstalled(spec) ? "installed " + repository.getInstalledVersionText(spec) : "not installed");
        if (spec.wine) {
            String currentWine = repository.getCurrentWineContainerName();
            if (currentWine != null && currentWine.equals(spec.name)) {
                state.append(" · current");
            }
        }
        return state.toString();
    }

    private void runSelectedPackageTask(String initialMessage, PackageAction task) {
        TermuxBoxPackageSpec spec = selectedSpec();
        if (spec == null) {
            Toast.makeText(requireContext(), "没有可操作的包", Toast.LENGTH_SHORT).show();
            return;
        }
        runTask(initialMessage + " " + spec.name, listener -> task.run(spec, listener));
    }

    private void runTask(String initialMessage, ProgressTask task) {
        setStatus(initialMessage, 0);
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
                    requireActivity().runOnUiThread(this::renderPackages);
                }
            }
        });
    }

    private void ensureSelection() {
        if (selectedPackageName != null && repository.findPackage(selectedPackageName) != null) {
            return;
        }
        List<TermuxBoxPackageSpec> packages = repository.getPackages();
        if (!packages.isEmpty()) {
            selectedPackageName = packages.get(0).name;
        }
    }

    private TermuxBoxPackageSpec selectedSpec() {
        ensureSelection();
        return selectedPackageName == null ? null : repository.findPackage(selectedPackageName);
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
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        button.setCornerRadius(dp(5));
        return button;
    }

    private MaterialButton actionButtonSmall(String label, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        button.setCornerRadius(dp(5));
        return button;
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

    private LinearLayout.LayoutParams weightedParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams fixedParams(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
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

    private TextView sectionTitle(String text) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextColor(0xFF24323F);
        view.setTextSize(24f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView sectionSubtitle(String text) {
        TextView view = new TextView(requireContext());
        view.setText(text);
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

    private interface PackageAction {
        void run(TermuxBoxPackageSpec spec, TermuxBoxRepository.ProgressListener listener) throws Exception;
    }
}
