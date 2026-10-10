package com.termux.localgames.service;

import android.content.Context;
import android.util.Log;

import com.termux.localgames.api.ComponentTasks;
import com.termux.localgames.components.ComponentStoragePaths;
import com.termux.localgames.components.UpstreamWineReleaseResolver;
import com.termux.localgames.components.index.ComponentDescriptor;
import com.termux.localgames.components.index.ComponentIndex;
import com.termux.localgames.components.index.ComponentIndexParser;
import com.termux.localgames.components.install.ComponentInstallationReader;
import com.termux.localgames.components.install.InstalledComponent;
import com.termux.localgames.data.FileComponentTaskRepository;
import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.ComponentTaskState;
import com.termux.localgames.runtime.RootfsSetupRecipe;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;

/**
 * Ensures the RootFS base components are downloaded and installed into the games-private component
 * tree before a shared-image build starts.
 *
 * <p>Mirrors {@link GlibcRuntimeComponentPreparer}, but RootFS components are consumed by the
 * in-container build rather than published into the host GLIBC prefix, so "ready" means the
 * component's private active pointer matches the catalog descriptor -- there is no host activation
 * step. This closes the gap where a rebuild threw {@code rootfs_source_component_missing} (and
 * created no task, leaving the UI stuck) when a required component had never been downloaded.</p>
 *
 * <p>Three of the base components -- the Hangover source archive, the box64-wine translator
 * build and the box64-proton translator build -- are intentionally NOT pinned to a fixed version
 * in the bundled catalog: every rebuild re-resolves "whatever upstream currently calls latest"
 * via {@link UpstreamWineReleaseResolver} instead. The catalog's static entry for each survives
 * only as a last-resort fallback for a first-ever install with no network available; once any
 * version has installed successfully, a later resolve failure (offline, GitHub API hiccup) is
 * never allowed to fall back onto that static entry, since it could be older than what is already
 * installed -- see {@link #resolveDescriptor}.</p>
 */
final class RootfsRuntimeComponentPreparer {
    private static final String TAG = "GamesRootfsComponentPrep";
    private static final String INDEX_ASSET = "termux-box-packages/index-v1.json";
    private static final long COMPONENT_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final long POLL_INTERVAL_MS = 250L;

    private final Context context;
    private final ComponentIndex index;
    private final FileComponentTaskRepository tasks;
    private final ComponentInstallationReader installations;
    private final UpstreamWineReleaseResolver upstreamResolver;

    RootfsRuntimeComponentPreparer(Context context) throws IOException {
        this.context = context.getApplicationContext();
        ComponentStoragePaths paths = new ComponentStoragePaths(context.getFilesDir());
        tasks = new FileComponentTaskRepository(paths.getTasksDirectory());
        installations = new ComponentInstallationReader(paths.getInstallDirectory());
        upstreamResolver = new UpstreamWineReleaseResolver();
        try (InputStream input = context.getAssets().open(INDEX_ASSET)) {
            index = new ComponentIndexParser().parse(input);
        }
    }

    /** Downloads and installs every component id that is not already installed at the catalog
     *  version; returns once all are present, or throws if any cannot be prepared in time. */
    void ensure(Collection<String> componentIds) throws IOException {
        for (String componentId : componentIds) {
            ensure(resolveDescriptor(componentId));
        }
    }

    /** For the three upstream-tracked wine components, prefers a freshly resolved "latest"
     *  release over the bundled catalog's static entry. On resolve failure (offline, GitHub API
     *  rate limit, etc.) it degrades without ever re-downloading or downgrading: if a version is
     *  already installed it is kept as-is; otherwise the bundled static entry is used so a
     *  first-ever install can still succeed with no network. Every other component id is
     *  unaffected and reads the catalog as before. */
    private ComponentDescriptor resolveDescriptor(String componentId) throws IOException {
        ComponentDescriptor template = index.find(componentId).orElseThrow(() ->
            new IOException("component_unknown:" + componentId));
        if (!isUpstreamTracked(componentId)) return template;
        try {
            return resolveUpstream(componentId, template);
        } catch (IOException resolveError) {
            InstalledComponent active = installations.read(componentId).getActive().orElse(null);
            if (active != null) {
                // Already have a version -- keep exactly it (do not re-download or downgrade).
                // Describe it at the installed version/digest so isInstalled() reports ready.
                Log.w(TAG, "Upstream release resolve failed for " + componentId +
                    ", keeping the installed version", resolveError);
                return ComponentDescriptor.withResolvedUpstream(template, active.getVersion(),
                    template.getUrl(), template.getSize(), active.getSha256());
            }
            // Never installed and cannot resolve latest -- fall back to the bundled static entry
            // so a first-ever RootFS build can still complete offline.
            Log.w(TAG, "Upstream release resolve failed for " + componentId +
                " with nothing installed; using the bundled fallback", resolveError);
            return template;
        }
    }

    private static boolean isUpstreamTracked(String componentId) {
        return RootfsSetupRecipe.DEFAULT_SOURCE.equals(componentId) ||
            RootfsSetupRecipe.BOX64_WINE_COMPONENT.equals(componentId) ||
            RootfsSetupRecipe.BOX64_PROTON_COMPONENT.equals(componentId);
    }

    private ComponentDescriptor resolveUpstream(String componentId, ComponentDescriptor template)
        throws IOException {
        if (RootfsSetupRecipe.DEFAULT_SOURCE.equals(componentId)) {
            return upstreamResolver.resolveHangoverSource(template);
        }
        if (RootfsSetupRecipe.BOX64_WINE_COMPONENT.equals(componentId)) {
            return upstreamResolver.resolveBox64Wine(template);
        }
        return upstreamResolver.resolveProtonWine(template);
    }

    private void ensure(ComponentDescriptor descriptor) throws IOException {
        if (isInstalled(descriptor)) return;

        ComponentTask reusable = findReusableTask(descriptor, tasks.list());
        String taskId;
        if (reusable == null) {
            taskId = ComponentTasks.enqueue(context, descriptor.getId());
        } else {
            taskId = reusable.getTaskId();
            if (reusable.getState() == ComponentTaskState.FAILED) {
                ComponentTasks.retry(context, taskId);
            } else if (reusable.getState() == ComponentTaskState.PAUSED) {
                ComponentTasks.resume(context, taskId);
            } else {
                ComponentTasks.reconcile(context);
            }
        }
        waitForTask(descriptor, taskId);
    }

    private ComponentTask findReusableTask(ComponentDescriptor descriptor,
                                           List<ComponentTask> candidates) {
        ComponentTask best = null;
        for (ComponentTask task : candidates) {
            if (!task.getPackageName().equals(descriptor.getId()) ||
                task.getVersion() != descriptor.getVersion() ||
                !task.getSha256().equals(descriptor.getSha256()) ||
                task.getState() == ComponentTaskState.CANCELLED ||
                task.getState() == ComponentTaskState.INSTALLED) {
                continue;
            }
            if (best == null || task.getDownloadedBytes() > best.getDownloadedBytes()) best = task;
        }
        return best;
    }

    private void waitForTask(ComponentDescriptor descriptor, String taskId) throws IOException {
        long deadline = System.currentTimeMillis() + COMPONENT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            ComponentTask task = tasks.find(taskId).orElse(null);
            if (task != null) {
                if (task.getState() == ComponentTaskState.INSTALLED && isInstalled(descriptor)) {
                    return;
                }
                if (task.getState() == ComponentTaskState.FAILED) {
                    throw new IOException("component_prepare_failed:" + descriptor.getId());
                }
                if (task.getState() == ComponentTaskState.CANCELLED) {
                    throw new IOException("component_prepare_cancelled:" + descriptor.getId());
                }
                if (task.getState() == ComponentTaskState.PAUSED) {
                    throw new IOException("component_prepare_paused:" + descriptor.getId());
                }
            }
            sleep();
        }
        throw new IOException("component_prepare_timeout:" + descriptor.getId());
    }

    /** RootFS components are "ready" once installed into the private tree at the catalog version;
     *  unlike GLIBC components they are not published to the host prefix. An empty descriptor
     *  sha256 means it was resolved without a pre-known digest (Hangover never publishes one) --
     *  identity then falls back to the version alone, since the installed receipt always holds
     *  the real computed hash and could never equal an empty string. */
    private boolean isInstalled(ComponentDescriptor descriptor) throws IOException {
        InstalledComponent active = installations.read(descriptor.getId()).getActive().orElse(null);
        if (active == null || active.getVersion() != descriptor.getVersion()) return false;
        return descriptor.getSha256().isEmpty() || active.getSha256().equals(descriptor.getSha256());
    }

    private static void sleep() throws IOException {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("component_prepare_interrupted", error);
        }
    }
}
