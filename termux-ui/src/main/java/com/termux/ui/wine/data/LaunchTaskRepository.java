package com.termux.ui.wine.data;

import com.termux.ui.wine.domain.LaunchTask;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface LaunchTaskRepository {
    List<LaunchTask> list() throws IOException;
    Optional<LaunchTask> find(String taskId) throws IOException;
    void save(LaunchTask task) throws IOException;
}
