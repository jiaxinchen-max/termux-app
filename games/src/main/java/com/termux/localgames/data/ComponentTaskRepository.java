package com.termux.localgames.data;

import com.termux.localgames.domain.ComponentTask;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface ComponentTaskRepository {
    List<ComponentTask> list() throws IOException;
    Optional<ComponentTask> find(String taskId) throws IOException;
    void save(ComponentTask task) throws IOException;
}
