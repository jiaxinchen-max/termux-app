package com.termux.localgames.activity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.View;
import android.widget.RadioButton;
import android.widget.Toast;
import android.widget.ArrayAdapter;

import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.radiobutton.MaterialRadioButton;
import com.termux.localgames.R;
import com.termux.localgames.api.LocalGames;
import com.termux.localgames.api.LocalGamesHost;
import com.termux.localgames.data.FileGameRepository;
import com.termux.localgames.data.FileGameContainerRepository;
import com.termux.localgames.data.FileRuntimeProfileRepository;
import com.termux.localgames.data.GameRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.data.RuntimeProfileRepository;
import com.termux.localgames.databinding.ActivityLocalGameImportBinding;
import com.termux.localgames.domain.Game;
import com.termux.localgames.domain.GameContainer;
import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.domain.RuntimeProfilePreset;
import com.termux.localgames.domain.RuntimeProfilePresets;
import com.termux.localgames.importer.CandidateSignal;
import com.termux.localgames.importer.ExecutableCandidate;
import com.termux.localgames.importer.FileGameDocumentTree;
import com.termux.localgames.importer.GameDirectoryScanner;
import com.termux.localgames.importer.GameDocumentTree;
import com.termux.localgames.importer.GameImportValidation;
import com.termux.localgames.importer.GameScanLimits;
import com.termux.localgames.importer.GameScanResult;
import com.termux.localgames.importer.LaunchArguments;
import com.termux.localgames.importer.SafGameDocumentTree;
import com.termux.localgames.importer.SafGameRootUri;
import com.termux.localgames.importer.SafPermissionManager;

import java.io.IOException;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** SAF-backed, read-only import flow for an installed Windows game directory. */
public final class GameImportActivity extends AppCompatActivity {

    public static final String EXTRA_IMPORTED_GAME_ID =
        "com.termux.localgames.extra.IMPORTED_GAME_ID";
    public static final String EXTRA_TREE_URI = "com.termux.localgames.extra.TREE_URI";

    private static final String STATE_TREE_URI = "local_game_import.tree_uri";
    private static final String STATE_EXECUTABLE = "local_game_import.executable";
    private static final String STATE_NAME = "local_game_import.name";
    private static final String STATE_WORKING_DIRECTORY = "local_game_import.working_directory";
    private static final String STATE_ARGUMENTS = "local_game_import.arguments";
    private static final String STATE_CONTAINER_ID = "local_game_import.container_id";

    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesDirectoryImport"));
    private final Map<Integer, Integer> candidateIndexes = new HashMap<>();
    private final ActivityResultLauncher<Intent> treePicker = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result ->
            handleTreeResult(result.getResultCode(), result.getData()));
    private final ActivityResultLauncher<String[]> directoryPermissions =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                if (allDirectoryPermissionsGranted()) launchTreePicker();
                else renderPosixAccessRequired();
            });

    private ActivityLocalGameImportBinding binding;
    private GameRepository gameRepository;
    private RuntimeProfileRepository profileRepository;
    private FileGameContainerRepository containerRepository;
    private LocalGamesHost appHost;
    private Uri treeUri;
    private GameScanResult scanResult;
    private int selectedCandidate = -1;
    private int operationGeneration;
    private boolean destroyed;

    private String restoredExecutable;
    private String restoredName;
    private String restoredWorkingDirectory;
    private String restoredArguments;
    private String restoredContainerId;
    private final Map<String, String> containerChoiceIds = new HashMap<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLocalGameImportBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        GameStoragePaths gamePaths = new GameStoragePaths(getFilesDir());
        gameRepository = new FileGameRepository(gamePaths.getLibraryDirectory());
        profileRepository = new FileRuntimeProfileRepository(gamePaths.getProfilesDirectory());
        containerRepository = new FileGameContainerRepository(gamePaths.getContainersDirectory());
        appHost = LocalGames.requireHost(this);

        binding.localGameImportToolbar.setNavigationOnClickListener(view -> finish());
        GamesHelpDialog.attach(binding.localGameImportToolbar, R.string.local_game_import_title,
            R.string.local_game_import_help);
        binding.localGameImportChooseDirectory.setOnClickListener(view -> chooseGameDirectory());
        binding.localGameImportConfirm.setOnClickListener(view -> confirmImport());

        if (savedInstanceState != null) {
            String uri = savedInstanceState.getString(STATE_TREE_URI);
            if (!TextUtils.isEmpty(uri)) treeUri = Uri.parse(uri);
            restoredExecutable = savedInstanceState.getString(STATE_EXECUTABLE);
            restoredName = savedInstanceState.getString(STATE_NAME);
            restoredWorkingDirectory = savedInstanceState.getString(STATE_WORKING_DIRECTORY);
            restoredArguments = savedInstanceState.getString(STATE_ARGUMENTS);
            restoredContainerId = savedInstanceState.getString(STATE_CONTAINER_ID);
        } else {
            String uri = getIntent().getStringExtra(EXTRA_TREE_URI);
            if (!TextUtils.isEmpty(uri)) treeUri = Uri.parse(uri);
        }
        loadContainerChoices();
        if (treeUri != null) {
            if (isLocalGameDirectory(treeUri)) {
                if (isPosixAccessible(treeUri)) startScan(treeUri);
                else renderPosixAccessRequired();
            } else if (SafPermissionManager.hasPersistedReadPermission(getContentResolver(), treeUri)) {
                if (isPosixAccessible(treeUri)) startScan(treeUri);
                else renderPosixAccessRequired();
            } else {
                renderPermissionLost();
            }
        }
    }

    private void chooseGameDirectory() {
        List<String> missing = new ArrayList<>();
        for (String permission : appHost.requiredGameDirectoryPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (missing.isEmpty()) launchTreePicker();
        else directoryPermissions.launch(missing.toArray(new String[0]));
    }

    private boolean allDirectoryPermissionsGranted() {
        for (String permission : appHost.requiredGameDirectoryPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void launchTreePicker() {
        treePicker.launch(SafPermissionManager.createOpenTreeIntent());
    }

    private void handleTreeResult(int resultCode, @Nullable Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri selected = data.getData();
        try {
            SafPermissionManager.persistReadPermission(getContentResolver(), selected,
                data.getFlags());
            if (treeUri != null) {
                String selectedRoot = SafGameRootUri.rootDocumentId(treeUri);
                if (!selectedRoot.equals(SafGameRootUri.rootDocumentId(selected))) {
                    selected = SafGameRootUri.forDocument(selected, DocumentsContract
                        .buildDocumentUriUsingTree(selected, selectedRoot));
                }
            }
            treeUri = selected;
            clearRestoredForm();
            if (isPosixAccessible(selected)) startScan(selected);
            else renderPosixAccessRequired();
        } catch (SecurityException | IOException error) {
            treeUri = selected;
            renderPermissionLost();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (treeUri != null) outState.putString(STATE_TREE_URI, treeUri.toString());
        ExecutableCandidate candidate = selectedExecutable();
        if (candidate != null) outState.putString(STATE_EXECUTABLE, candidate.getRelativePath());
        if (candidate != null) {
            outState.putString(STATE_NAME, textOf(binding.localGameImportName));
            outState.putString(STATE_WORKING_DIRECTORY,
                textOf(binding.localGameImportWorkingDirectory));
            outState.putString(STATE_ARGUMENTS, textOf(binding.localGameImportArguments));
        }
        String containerId = selectedContainerId();
        if (containerId != null) outState.putString(STATE_CONTAINER_ID, containerId);
        super.onSaveInstanceState(outState);
    }

    private void loadContainerChoices() {
        importExecutor.execute(() -> {
            List<GameContainer> containers = Collections.emptyList();
            try {
                containers = containerRepository.list();
            } catch (IOException ignored) { }
            List<GameContainer> loaded = containers;
            runOnUiThread(() -> {
                if (destroyed || binding == null) return;
                containerChoiceIds.clear();
                List<String> labels = new ArrayList<>();
                String global = getString(R.string.local_game_import_container_global);
                labels.add(global);
                containerChoiceIds.put(global, GameContainer.DEFAULT_ID);
                for (GameContainer container : loaded) {
                    if (GameContainer.DEFAULT_ID.equals(container.getId())) continue;
                    String label = getString(R.string.local_game_import_container_independent,
                        container.getName()) + " · " + container.getId();
                    labels.add(label);
                    containerChoiceIds.put(label, container.getId());
                }
                binding.localGameImportContainer.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_dropdown_item_1line, labels));
                String selectedLabel = global;
                if (restoredContainerId != null) {
                    for (Map.Entry<String, String> choice : containerChoiceIds.entrySet()) {
                        if (restoredContainerId.equals(choice.getValue())) {
                            selectedLabel = choice.getKey();
                            break;
                        }
                    }
                }
                binding.localGameImportContainer.setText(selectedLabel, false);
            });
        });
    }

    private void startScan(Uri selectedTree) {
        int generation = ++operationGeneration;
        scanResult = null;
        selectedCandidate = -1;
        binding.localGameImportDirectory.setText(selectedTree.toString());
        binding.localGameImportDirectory.setVisibility(View.VISIBLE);
        binding.localGameImportProgress.show();
        binding.localGameImportStatus.setText(R.string.local_game_import_scanning);
        binding.localGameImportError.setVisibility(View.GONE);
        binding.localGameImportForm.setVisibility(View.GONE);
        binding.localGameImportChooseDirectory.setEnabled(true);

        importExecutor.execute(() -> {
            GameScanResult result = null;
            String error = null;
            try {
                result = new GameDirectoryScanner(GameScanLimits.DEFAULT).scan(openDocumentTree(selectedTree));
            } catch (IOException | RuntimeException scanError) {
                error = safeMessage(scanError);
            }
            GameScanResult loaded = result;
            String failure = error;
            runOnUiThread(() -> {
                if (destroyed || binding == null || generation != operationGeneration) return;
                if (!selectedTree.equals(treeUri)) return;
                if (failure == null) renderScanResult(loaded);
                else renderScanFailure(failure);
            });
        });
    }

    private void renderScanResult(GameScanResult result) {
        scanResult = result;
        binding.localGameImportProgress.hide();
        binding.localGameImportStatus.setText("");
        binding.localGameImportScanSummary.setText(getString(
            R.string.local_game_import_scan_summary, result.getScannedDocuments(),
            result.getScannedDirectories(), result.getCandidates().size()));
        binding.localGameImportLimitWarning.setVisibility(
            result.isComplete() ? View.GONE : View.VISIBLE);
        if (result.getCandidates().isEmpty()) {
            binding.localGameImportForm.setVisibility(View.GONE);
            binding.localGameImportError.setText(R.string.local_game_import_no_candidates);
            binding.localGameImportError.setVisibility(View.VISIBLE);
            return;
        }

        binding.localGameImportError.setVisibility(View.GONE);
        binding.localGameImportForm.setVisibility(View.VISIBLE);
        candidateIndexes.clear();
        binding.localGameImportCandidateGroup.removeAllViews();
        int restoredIndex = 0;
        for (int index = 0; index < result.getCandidates().size(); index++) {
            ExecutableCandidate candidate = result.getCandidates().get(index);
            MaterialRadioButton button = new MaterialRadioButton(this);
            button.setId(View.generateViewId());
            button.setText(candidateLabel(candidate));
            button.setPadding(0, 8, 0, 8);
            candidateIndexes.put(button.getId(), index);
            binding.localGameImportCandidateGroup.addView(button);
            if (candidate.getRelativePath().equals(restoredExecutable)) restoredIndex = index;
        }
        binding.localGameImportCandidateGroup.setOnCheckedChangeListener((group, checkedId) -> {
            Integer index = candidateIndexes.get(checkedId);
            if (index != null) selectCandidate(index, false);
        });
        RadioButton button = (RadioButton) binding.localGameImportCandidateGroup
            .getChildAt(restoredIndex);
        button.setChecked(true);
        selectCandidate(restoredIndex, true);
        clearRestoredForm();
    }

    private void selectCandidate(int index, boolean applyRestoredForm) {
        selectedCandidate = index;
        ExecutableCandidate candidate = scanResult.getCandidates().get(index);
        String defaultName = defaultGameName(scanResult.getRootName(), candidate);
        binding.localGameImportName.setText(applyRestoredForm && restoredName != null
            ? restoredName : defaultName);
        binding.localGameImportWorkingDirectory.setText(
            applyRestoredForm && restoredWorkingDirectory != null
                ? restoredWorkingDirectory : candidate.getWorkingDirectory());
        if (applyRestoredForm && restoredArguments != null) {
            binding.localGameImportArguments.setText(restoredArguments);
        }
        clearInputErrors();
    }

    private void confirmImport() {
        ExecutableCandidate candidate = selectedExecutable();
        if (candidate == null || treeUri == null) return;
        if (!isLocalGameDirectory(treeUri) &&
            !SafPermissionManager.hasPersistedReadPermission(getContentResolver(), treeUri)) {
            renderPermissionLost();
            return;
        }
        if (!isPosixAccessible(treeUri)) {
            renderPosixAccessRequired();
            return;
        }
        clearInputErrors();
        String name = textOf(binding.localGameImportName).trim();
        String workingDirectory = textOf(binding.localGameImportWorkingDirectory).trim();
        String containerId = selectedContainerId();
        List<String> arguments;
        boolean valid = true;
        if (name.isEmpty()) {
            binding.localGameImportNameLayout.setError(
                getString(R.string.local_game_import_invalid_name));
            valid = false;
        }
        if (!GameImportValidation.isSafeRelativeDirectory(workingDirectory)) {
            binding.localGameImportWorkingDirectoryLayout.setError(
                getString(R.string.local_game_import_invalid_working_directory));
            valid = false;
        }
        if (containerId == null) {
            binding.localGameImportContainerLayout.setError(
                getString(R.string.local_game_import_invalid_container));
            valid = false;
        }
        try {
            arguments = LaunchArguments.parse(textOf(binding.localGameImportArguments));
        } catch (IllegalArgumentException error) {
            binding.localGameImportArgumentsLayout.setError(
                getString(R.string.local_game_import_invalid_arguments));
            arguments = new ArrayList<>();
            valid = false;
        }
        if (!valid) return;

        int generation = ++operationGeneration;
        setFormEnabled(false);
        binding.localGameImportProgress.show();
        binding.localGameImportStatus.setText(R.string.local_game_import_saving);
        List<String> launchArguments = arguments;
        String selectedContainerId = containerId;
        importExecutor.execute(() -> {
            String gameId = null;
            String error = null;
            try {
                gameId = existingGameId(treeUri.toString(), candidate.getRelativePath());
                if (gameId == null) gameId = UUID.randomUUID().toString();
                gameRepository.save(new Game(gameId, name, treeUri.toString(),
                    candidate.getRelativePath(), workingDirectory, launchArguments, "", 0));
                RuntimeProfile profile = RuntimeProfilePresets.create(gameId,
                    RuntimeProfilePreset.RECOMMENDED).withContainerId(selectedContainerId);
                profileRepository.save(profile);
            } catch (IOException | RuntimeException saveError) {
                error = safeMessage(saveError);
            }
            String importedId = gameId;
            String failure = error;
            runOnUiThread(() -> {
                if (destroyed || binding == null || generation != operationGeneration) return;
                binding.localGameImportProgress.hide();
                binding.localGameImportStatus.setText("");
                if (failure != null) {
                    setFormEnabled(true);
                    binding.localGameImportError.setText(getString(
                        R.string.local_game_import_save_failed, failure));
                    binding.localGameImportError.setVisibility(View.VISIBLE);
                    return;
                }
                setResult(Activity.RESULT_OK, new Intent().putExtra(
                    EXTRA_IMPORTED_GAME_ID, importedId));
                Toast.makeText(this, R.string.local_game_import_complete,
                    Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    @Nullable
    private String existingGameId(String rootUri, String executable) throws IOException {
        for (Game game : gameRepository.list()) {
            if (game.getRootUri().equals(rootUri) && game.getExecutable().equals(executable)) {
                return game.getId();
            }
        }
        return null;
    }

    private void renderPermissionLost() {
        ++operationGeneration;
        binding.localGameImportProgress.hide();
        binding.localGameImportStatus.setText("");
        binding.localGameImportForm.setVisibility(View.GONE);
        binding.localGameImportError.setText(R.string.local_game_import_permission_lost);
        binding.localGameImportError.setVisibility(View.VISIBLE);
        if (treeUri != null) {
            binding.localGameImportDirectory.setText(treeUri.toString());
            binding.localGameImportDirectory.setVisibility(View.VISIBLE);
        }
    }

    private boolean isPosixAccessible(Uri selected) {
        try {
            return appHost.resolveGameDirectory(selected.toString()).isResolved();
        } catch (RuntimeException error) {
            return false;
        }
    }

    private GameDocumentTree openDocumentTree(Uri root) throws IOException {
        if (isLocalGameDirectory(root)) {
            String path = root.getPath();
            if (path == null) throw new IOException("local_game_root_missing");
            return new FileGameDocumentTree(new File(path));
        }
        return new SafGameDocumentTree(getContentResolver(), root);
    }

    private static boolean isLocalGameDirectory(Uri uri) {
        return uri != null && "file".equals(uri.getScheme()) && uri.getPath() != null;
    }

    private void renderPosixAccessRequired() {
        ++operationGeneration;
        binding.localGameImportProgress.hide();
        binding.localGameImportStatus.setText("");
        binding.localGameImportForm.setVisibility(View.GONE);
        binding.localGameImportError.setText(R.string.local_game_import_posix_access_required);
        binding.localGameImportError.setVisibility(View.VISIBLE);
        if (treeUri != null) {
            binding.localGameImportDirectory.setText(treeUri.toString());
            binding.localGameImportDirectory.setVisibility(View.VISIBLE);
        }
    }

    private void renderScanFailure(String message) {
        binding.localGameImportProgress.hide();
        binding.localGameImportStatus.setText("");
        binding.localGameImportForm.setVisibility(View.GONE);
        binding.localGameImportError.setText(getString(R.string.local_game_import_failed, message));
        binding.localGameImportError.setVisibility(View.VISIBLE);
    }

    private CharSequence candidateLabel(ExecutableCandidate candidate) {
        String confidence = getString(candidate.isDiscouraged()
            ? R.string.local_game_import_candidate_review
            : R.string.local_game_import_candidate_recommended);
        String metadata = getString(R.string.local_game_import_candidate_metadata,
            Formatter.formatShortFileSize(this, Math.max(0, candidate.getSize())),
            candidate.getScore(), confidence);
        return candidate.getRelativePath() + "\n" + metadata + "\n" +
            getString(R.string.local_game_import_candidate_signals, signalText(candidate));
    }

    private String signalText(ExecutableCandidate candidate) {
        List<String> labels = new ArrayList<>();
        for (CandidateSignal signal : candidate.getSignals()) {
            switch (signal) {
                case ROOT_EXECUTABLE:
                    labels.add(getString(R.string.local_game_import_signal_root));
                    break;
                case ROOT_NAME_MATCH:
                    labels.add(getString(R.string.local_game_import_signal_name_match));
                    break;
                case LARGE_EXECUTABLE:
                    labels.add(getString(R.string.local_game_import_signal_large));
                    break;
                case LAUNCHER_NAME:
                    labels.add(getString(R.string.local_game_import_signal_launcher));
                    break;
                case DEEP_PATH:
                    labels.add(getString(R.string.local_game_import_signal_deep));
                    break;
                case HELPER_NAME:
                    labels.add(getString(R.string.local_game_import_signal_helper));
                    break;
                case SUPPORT_DIRECTORY:
                    labels.add(getString(R.string.local_game_import_signal_support));
                    break;
            }
        }
        return labels.isEmpty() ? getString(R.string.local_game_import_signal_none)
            : TextUtils.join(", ", labels);
    }

    @Nullable
    private ExecutableCandidate selectedExecutable() {
        if (scanResult == null || selectedCandidate < 0 ||
            selectedCandidate >= scanResult.getCandidates().size()) return null;
        return scanResult.getCandidates().get(selectedCandidate);
    }

    private void setFormEnabled(boolean enabled) {
        binding.localGameImportChooseDirectory.setEnabled(enabled);
        binding.localGameImportCandidateGroup.setEnabled(enabled);
        for (int index = 0; index < binding.localGameImportCandidateGroup.getChildCount(); index++) {
            binding.localGameImportCandidateGroup.getChildAt(index).setEnabled(enabled);
        }
        binding.localGameImportName.setEnabled(enabled);
        binding.localGameImportWorkingDirectory.setEnabled(enabled);
        binding.localGameImportArguments.setEnabled(enabled);
        binding.localGameImportContainer.setEnabled(enabled);
        binding.localGameImportConfirm.setEnabled(enabled);
    }

    private void clearInputErrors() {
        binding.localGameImportNameLayout.setError(null);
        binding.localGameImportWorkingDirectoryLayout.setError(null);
        binding.localGameImportArgumentsLayout.setError(null);
        binding.localGameImportContainerLayout.setError(null);
    }

    private void clearRestoredForm() {
        restoredExecutable = null;
        restoredName = null;
        restoredWorkingDirectory = null;
        restoredArguments = null;
    }

    private static String defaultGameName(String rootName, ExecutableCandidate candidate) {
        String name = rootName == null ? "" : rootName.trim();
        if (!name.isEmpty()) return name;
        String path = candidate.getRelativePath();
        int slash = path.lastIndexOf('/');
        String fileName = slash < 0 ? path : path.substring(slash + 1);
        return fileName.length() > 4 ? fileName.substring(0, fileName.length() - 4) : fileName;
    }

    @Nullable
    private String selectedContainerId() {
        return containerChoiceIds.get(textOf(binding.localGameImportContainer));
    }

    private static String textOf(android.widget.TextView view) {
        return view.getText() == null ? "" : view.getText().toString();
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ++operationGeneration;
        importExecutor.shutdownNow();
        binding = null;
        super.onDestroy();
    }
}
