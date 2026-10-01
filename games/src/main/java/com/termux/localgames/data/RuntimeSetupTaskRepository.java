package com.termux.localgames.data;

import com.termux.localgames.domain.RuntimeProvisionTask;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface RuntimeProvisionTaskRepository {
    void save(RuntimeProvisionTask task) throws IOException;
    Optional<RuntimeProvisionTask> find(String taskId) throws IOException;
    List<RuntimeProvisionTask> list() throws IOException;
}
