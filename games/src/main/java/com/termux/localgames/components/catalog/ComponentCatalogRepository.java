package com.termux.localgames.components.catalog;

import com.termux.localgames.components.index.ComponentDescriptor;
import com.termux.localgames.components.index.ComponentIndex;
import com.termux.localgames.components.install.ComponentInstallationReader;
import com.termux.localgames.components.install.ComponentInstallationSnapshot;
import com.termux.localgames.components.install.InstalledComponent;
import com.termux.localgames.data.ComponentTaskRepository;
import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.ComponentTaskState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Combines immutable index metadata with durable task and install state for UI rendering. */
public final class ComponentCatalogRepository {

    private final ComponentIndex index;
    private final ComponentTaskRepository tasks;
    private final ComponentInstallationReader installations;

    public ComponentCatalogRepository(ComponentIndex index, ComponentTaskRepository tasks,
                                      ComponentInstallationReader installations) {
        if (index == null || tasks == null || installations == null) {
            throw new IllegalArgumentException("catalog dependencies must not be null");
        }
        this.index = index;
        this.tasks = tasks;
        this.installations = installations;
    }

    public List<ComponentCatalogItem> load() throws IOException {
        List<ComponentTask> allTasks = tasks.list();
        List<ComponentCatalogItem> result = new ArrayList<>(index.getComponents().size());
        for (ComponentDescriptor descriptor : index.getComponents()) {
            ComponentInstallationSnapshot installation = installations.read(descriptor.getId());
            InstalledComponent active = installation.getActive().orElse(null);
            InstalledComponent previous = installation.getPrevious().orElse(null);
            ComponentTask task = selectTask(descriptor, allTasks);
            result.add(new ComponentCatalogItem(descriptor, task, active, previous,
                resolveState(descriptor, task, active)));
        }
        return result;
    }

    private static ComponentTask selectTask(ComponentDescriptor descriptor,
                                            List<ComponentTask> tasks) {
        ComponentTask selected = null;
        int selectedRank = Integer.MIN_VALUE;
        for (ComponentTask candidate : tasks) {
            if (!descriptor.getId().equals(candidate.getPackageName()) ||
                descriptor.getVersion() != candidate.getVersion() ||
                !descriptor.getSha256().equals(candidate.getSha256())) {
                continue;
            }
            int rank = rank(candidate.getState());
            if (rank > selectedRank || (rank == selectedRank && selected != null &&
                candidate.getTaskId().compareTo(selected.getTaskId()) > 0)) {
                selected = candidate;
                selectedRank = rank;
            }
        }
        return selected;
    }

    private static int rank(ComponentTaskState state) {
        if (state.shouldRecoverAutomatically()) return 50;
        if (state == ComponentTaskState.PAUSED) return 40;
        if (state == ComponentTaskState.FAILED) return 30;
        if (state == ComponentTaskState.INSTALLED) return 20;
        return 10;
    }

    private static ComponentCatalogState resolveState(ComponentDescriptor descriptor,
                                                      ComponentTask task,
                                                      InstalledComponent active) {
        if (task != null && task.getState().shouldRecoverAutomatically()) {
            switch (task.getState()) {
                case QUEUED: return ComponentCatalogState.QUEUED;
                case DOWNLOADING: return ComponentCatalogState.DOWNLOADING;
                case VERIFYING: return ComponentCatalogState.VERIFYING;
                case VERIFIED: return ComponentCatalogState.VERIFIED;
                case INSTALLING: return ComponentCatalogState.INSTALLING;
                default: break;
            }
        }
        if (active != null && active.getVersion() == descriptor.getVersion() &&
            active.getSha256().equals(descriptor.getSha256())) {
            return ComponentCatalogState.INSTALLED;
        }
        if (task != null) {
            if (task.getState() == ComponentTaskState.PAUSED) {
                return ComponentCatalogState.PAUSED;
            }
            if (task.getState() == ComponentTaskState.FAILED) {
                return ComponentCatalogState.FAILED;
            }
        }
        if (active == null) return ComponentCatalogState.NOT_INSTALLED;
        return descriptor.getVersion() > active.getVersion()
            ? ComponentCatalogState.UPDATE_AVAILABLE : ComponentCatalogState.INSTALLED;
    }
}
