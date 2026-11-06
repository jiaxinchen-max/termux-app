package com.termux.ui.wine.data;

import com.termux.ui.wine.domain.RuntimeSetupTask;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface RuntimeSetupTaskRepository {
    void save(RuntimeSetupTask task) throws IOException;
    Optional<RuntimeSetupTask> find(String taskId) throws IOException;
    List<RuntimeSetupTask> list() throws IOException;
}
