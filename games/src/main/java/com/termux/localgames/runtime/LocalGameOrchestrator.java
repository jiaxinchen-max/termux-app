package com.termux.localgames.runtime;

import com.termux.localgames.domain.LaunchTask;

import java.util.Optional;

/** Stable command/query boundary between feature UI and the task owner. */
public interface LocalGameOrchestrator {

    String launch(String gameId) throws OrchestrationException;

    void cancel(String taskId, boolean force) throws OrchestrationException;

    void reportDisplayConnection(String taskId, boolean connected) throws OrchestrationException;

    void reportFirstFrame(String taskId) throws OrchestrationException;

    Optional<LaunchTask> findTask(String taskId) throws OrchestrationException;

    Subscription observe(String taskId, TaskObserver<LaunchTask> observer)
        throws OrchestrationException;
}
