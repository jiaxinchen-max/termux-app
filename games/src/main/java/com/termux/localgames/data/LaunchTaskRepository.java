package com.termux.localgames.data;

import com.termux.localgames.domain.LaunchTask;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface LaunchTaskRepository {
    List<LaunchTask> list() throws IOException;
    Optional<LaunchTask> find(String taskId) throws IOException;
    void save(LaunchTask task) throws IOException;
}
