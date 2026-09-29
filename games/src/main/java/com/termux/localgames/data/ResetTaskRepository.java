package com.termux.localgames.data;

import com.termux.localgames.domain.ResetTask;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface ResetTaskRepository {
    void save(ResetTask task) throws IOException;
    Optional<ResetTask> find(String taskId) throws IOException;
    List<ResetTask> list() throws IOException;
}
