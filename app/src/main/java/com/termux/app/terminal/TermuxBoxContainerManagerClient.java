package com.termux.app.terminal;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Intent;
import android.view.Gravity;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;


import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.activities.TermuxBoxActivity;
import com.termux.app.activities.termuxbox.TermuxBoxContainerSpec;
import com.termux.app.activities.termuxbox.TermuxBoxHomeCardView;
import com.termux.app.activities.termuxbox.TermuxBoxRepository;

import java.util.List;

public class TermuxBoxContainerManagerClient {

    private static final String LOG_TAG = "TermuxBoxManager";
    private static final String START_WINE_SESSION_NAME = "termux-box-start-wine";
    private static final String BOOTSTRAP_SESSION_NAME = "termux-box-bootstrap";

    private final TermuxActivity mTermuxActivity;
    private final TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;
    private final TermuxBoxRepository mRepository;

    private LinearLayout mContent;
    /** Tracks containers for which we've already started a bootstrap session (avoids duplicates). */
    private final java.util.Set<String> mBootstrappedContainers = new java.util.HashSet<>();

    /** Number of active container start sessions. WinHandler only stops when this reaches 0. */
    private int mActiveContainerSessionCount = 0;

    public TermuxBoxContainerManagerClient(TermuxActivity activity, TermuxTerminalSessionActivityClient termuxTerminalSessionActivityClient) {
        mTermuxActivity = activity;
        mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient;
        mRepository = new TermuxBoxRepository();
        setContainerManagerView();
    }

    public void refresh() {
        if (mContent == null)
            return;

        mContent.removeAllViews();
        List<TermuxBoxContainerSpec> containers = mRepository.getContainers();
        if (containers.isEmpty()) {
            TextView empty = new TextView(mTermuxActivity);
            empty.setGravity(Gravity.CENTER);
            empty.setText(R.string.termux_box_container_manager_empty);
            empty.setTextColor(0xFF60707E);
            empty.setTextSize(12);
            empty.setPadding(dp(8), dp(16), dp(8), dp(16));
            mContent.addView(empty, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            return;
        }

        for (TermuxBoxContainerSpec spec : containers) {
            View card = TermuxBoxHomeCardView.create(mTermuxActivity, mRepository, spec, new TermuxBoxHomeCardView.Actions() {
                @Override
                public void onStartContainer(TermuxBoxContainerSpec spec) {
                    startContainer(spec);
                }

                @Override
                public void onEditContainer(TermuxBoxContainerSpec spec) {
                    openEditor(spec);
                }

                @Override
                public void onRemoveContainer(TermuxBoxContainerSpec spec) {
                    removeContainer(spec);
                }
            }, true);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            params.bottomMargin = dp(6);
            mContent.addView(card, params);
        }

        // After populating cards, check if the current container needs Wine prefix bootstrapping.
        // The bootstrap script is a pre-written asset (bootstrap_termux_box.sh) that is copied
        // to the container directory and executed via a terminal session so the user can watch.
        TermuxBoxContainerSpec currentSpec = mRepository.getCurrentContainer();
        if (currentSpec != null && !mRepository.isContainerBootstrapped(currentSpec)
                && !mBootstrappedContainers.contains(currentSpec.id)) {
            mBootstrappedContainers.add(currentSpec.id);
            Log.i(LOG_TAG, "Container needs bootstrapping: " + currentSpec.name);
            try {
                java.io.File scriptFile = mRepository.getBootstrapScriptFile(currentSpec);
                scriptFile.getParentFile().mkdirs();
                try (java.io.InputStream in = mTermuxActivity.getAssets().open("bootstrap_termux_box.sh");
                     java.io.OutputStream out = new java.io.FileOutputStream(scriptFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                scriptFile.setExecutable(true, false);

                java.io.File containerConf = new java.io.File(
                    mRepository.getContainerDir(currentSpec), "container.conf");
                String command = "sh " + scriptFile.getAbsolutePath()
                    + " " + containerConf.getAbsolutePath() + "\n";
                Log.i(LOG_TAG, "Starting bootstrap: " + command.trim());
                if (mRepository.getSessionAutoClose()) {
                    mTermuxTerminalSessionActivityClient.addNewAutoCloseSessionAndRunCommand(command, BOOTSTRAP_SESSION_NAME);
                } else {
                    mTermuxTerminalSessionActivityClient.addNewSessionAndRunCommand(command, BOOTSTRAP_SESSION_NAME);
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "Failed to deploy bootstrap script for: " + currentSpec.name, e);
                mBootstrappedContainers.remove(currentSpec.id);
            }
        }
    }

    private void setContainerManagerView() {
        View container = mTermuxActivity.findViewById(R.id.container_manager_container);
        if (!(container instanceof LinearLayout))
            return;

        LinearLayout containerLayout = (LinearLayout) container;
        containerLayout.removeAllViews();

        LinearLayout header = new LinearLayout(mTermuxActivity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(6), dp(4), dp(6), dp(2));
        containerLayout.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        // Left: Packages button (wrench icon)
        ImageView packagesButton = new ImageView(mTermuxActivity);
        packagesButton.setImageResource(R.drawable.ic_wrench);
        packagesButton.setBackgroundColor(0x00000000);
        packagesButton.setPadding(dp(4), dp(4), dp(4), dp(4));
        packagesButton.setClickable(true);
        packagesButton.setFocusable(true);
        packagesButton.setOnClickListener(v -> openPackagesEditor());
        int btnSize = dp(28);
        header.addView(packagesButton, new LinearLayout.LayoutParams(btnSize, btnSize));

        // Spacer to push "+" button to the right
        View spacer = new View(mTermuxActivity);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));

        ImageView createButton = new ImageView(mTermuxActivity);
        createButton.setImageResource(R.drawable.ic_add_simple);
        createButton.setBackgroundColor(0x00000000);
        createButton.setPadding(dp(4), dp(4), dp(4), dp(4));
        createButton.setClickable(true);
        createButton.setFocusable(true);
        createButton.setOnClickListener(v -> openCreateEditor());
        header.addView(createButton, new LinearLayout.LayoutParams(btnSize, btnSize));

        ScrollView scrollView = new ScrollView(mTermuxActivity);
        mContent = new LinearLayout(mTermuxActivity);
        mContent.setOrientation(LinearLayout.VERTICAL);
        mContent.setPadding(dp(4), dp(4), dp(4), dp(4));
        scrollView.addView(mContent, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        containerLayout.addView(scrollView, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        refresh();
    }

    private void startContainer(TermuxBoxContainerSpec spec) {
        try {
            if (mTermuxActivity.getTermuxService() == null) {
                Log.e(LOG_TAG, "Cannot start container: TermuxService is null");
                Toast.makeText(mTermuxActivity,
                    mTermuxActivity.getString(R.string.termux_box_container_manager_start_failed, "service unavailable"),
                    Toast.LENGTH_SHORT).show();
                return;
            }
            Log.i(LOG_TAG, "Starting container: " + spec.name + " (" + spec.id + ")");
            mRepository.ensureDirs();
            mRepository.setCurrentContainer(spec);

            // Deploy the start script from assets to the container directory
            java.io.File scriptFile = mRepository.getStartScriptFile(spec);
            scriptFile.getParentFile().mkdirs();
            try (java.io.InputStream in = mTermuxActivity.getAssets().open("start_termux_box.sh");
                 java.io.OutputStream out = new java.io.FileOutputStream(scriptFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            scriptFile.setExecutable(true, false);

            java.io.File containerConf = new java.io.File(
                mRepository.getContainerDir(spec), "container.conf");
            String command = "sh " + scriptFile.getAbsolutePath()
                + " " + containerConf.getAbsolutePath() + "\n";
            Log.i(LOG_TAG, "Starting container: " + command.trim());
            if (mRepository.getSessionAutoClose()) {
                mTermuxTerminalSessionActivityClient.addNewAutoCloseSessionAndRunCommand(command, START_WINE_SESSION_NAME);
            } else {
                mTermuxTerminalSessionActivityClient.addNewSessionAndRunCommand(command, START_WINE_SESSION_NAME);
            }

            // Keep WinHandler (gamepad control) alive for the lifetime of the container.
            // The WinHandler should persist through activity lifecycle changes and only
            // stop when ALL container sessions have ended.
            mActiveContainerSessionCount++;
            com.termux.x11.LorieViewRuntimeController lorieRuntime = mTermuxActivity.getLorieViewRuntime();
            if (lorieRuntime != null) {
                lorieRuntime.setKeepWinHandlerAlive(true);
                // Apply container's gamepad mapper type (0=Standard/DInput, 1=XInput)
                lorieRuntime.setGamepadMapperType(spec.dinputMapperType);
                Log.i(LOG_TAG, "WinHandler keep-alive enabled, mapper type: " + spec.dinputMapperType
                    + " (active sessions: " + mActiveContainerSessionCount + ")");
            }

            refresh();
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to start container: " + spec.name, e);
            Toast.makeText(mTermuxActivity,
                mTermuxActivity.getString(R.string.termux_box_container_manager_start_failed, e.getMessage()),
                Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Called when a wine container session finishes. Decrements the active session
     * count and only stops the WinHandler when no container sessions are left running.
     */
    public void onContainerSessionFinished() {
        mActiveContainerSessionCount = Math.max(0, mActiveContainerSessionCount - 1);
        Log.i(LOG_TAG, "Container session ended (active sessions: " + mActiveContainerSessionCount + ")");
        if (mActiveContainerSessionCount == 0) {
            com.termux.x11.LorieViewRuntimeController lorieRuntime = mTermuxActivity.getLorieViewRuntime();
            if (lorieRuntime != null) {
                lorieRuntime.stopWinHandler();
                Log.i(LOG_TAG, "WinHandler stopped (no active container sessions)");
            }
        }
    }

    private void openEditor(TermuxBoxContainerSpec spec) {
        try {
            mRepository.setCurrentContainer(spec);
        } catch (Exception e) {
            Toast.makeText(mTermuxActivity,
                mTermuxActivity.getString(R.string.termux_box_container_manager_start_failed, e.getMessage()),
                Toast.LENGTH_SHORT).show();
        }
        openEditor();
    }

    private void removeContainer(TermuxBoxContainerSpec spec) {
        try {
            mRepository.removeContainer(spec);
            refresh();
        } catch (Exception e) {
            Toast.makeText(mTermuxActivity, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void openEditor() {
        Intent intent = new Intent(mTermuxActivity, TermuxBoxActivity.class);
        intent.putExtra(TermuxBoxActivity.EXTRA_INITIAL_SECTION, TermuxBoxActivity.SECTION_CONTAINERS);
        mTermuxActivity.startActivity(intent);
    }

    private void openCreateEditor() {
        try {
            mRepository.clearCurrentContainer();
        } catch (Exception e) {
            Toast.makeText(mTermuxActivity, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
        openEditor();
    }

    private void openPackagesEditor() {
        Intent intent = new Intent(mTermuxActivity, TermuxBoxActivity.class);
        intent.putExtra(TermuxBoxActivity.EXTRA_INITIAL_SECTION, TermuxBoxActivity.SECTION_PACKAGES);
        mTermuxActivity.startActivity(intent);
    }

    private int dp(int value) {
        return Math.round(value * mTermuxActivity.getResources().getDisplayMetrics().density);
    }
}
