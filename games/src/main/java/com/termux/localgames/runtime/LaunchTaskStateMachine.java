package com.termux.localgames.runtime;

import com.termux.localgames.domain.LaunchEvent;
import com.termux.localgames.domain.LaunchStage;
import com.termux.localgames.domain.LaunchTask;
import com.termux.localgames.domain.LaunchTaskState;

/** Applies ordered script events without allowing task progress or stage regression. */
public final class LaunchTaskStateMachine {

    public LaunchTask apply(LaunchTask current, LaunchEvent event) {
        if (current == null || event == null) throw new IllegalArgumentException("task and event required");
        if (!current.getTaskId().equals(event.getTaskId())) {
            throw new IllegalArgumentException("launch_event_task_mismatch");
        }
        if (event.getSequence() <= current.getLastEventSequence()) return current;
        if (current.getState().isTerminal()) return current;
        if (event.getStage() == LaunchStage.RUNNING) {
            throw new IllegalArgumentException("first_frame_required_for_running");
        }
        if (event.getStage().ordinal() < current.getStage().ordinal()) {
            throw new IllegalArgumentException("launch_stage_must_not_decrease");
        }
        if (event.getProgress() < current.getProgress()) {
            throw new IllegalArgumentException("launch_progress_must_not_decrease");
        }
        validateTerminal(event);
        LaunchTask applied = current.applyEvent(event.getState(), event.getStage(), event.getProgress(),
            event.getPid(), event.getExitCode(), event.getErrorCode(), event.isRecoverable(),
            event.getLogRef(), event.getSequence(),
            Math.max(current.getUpdatedAt(), event.getTimestamp()));
        return applied.promoteToRunningFromFirstFrame();
    }

    public LaunchTask observeDisplayConnection(LaunchTask current, boolean connected,
                                               long timestamp) {
        if (current == null) throw new IllegalArgumentException("task required");
        if (current.getState().isTerminal() ||
            (current.isDisplayConnected() == connected && current.getDisplayUpdatedAt() > 0)) return current;
        return current.withDisplayConnection(connected, Math.max(current.getUpdatedAt(), timestamp));
    }

    public LaunchTask confirmFirstFrame(LaunchTask current, long timestamp) {
        if (current == null) throw new IllegalArgumentException("task required");
        return current.withFirstFrame(Math.max(current.getUpdatedAt(), timestamp));
    }

    private static void validateTerminal(LaunchEvent event) {
        LaunchTaskState state = event.getState();
        if (state == LaunchTaskState.QUEUED) {
            throw new IllegalArgumentException("script_cannot_emit_queued");
        }
        if (state.isTerminal() && event.getStage() != LaunchStage.COMPLETE) {
            throw new IllegalArgumentException("terminal_event_requires_complete_stage");
        }
        if (!state.isTerminal() && event.getStage() == LaunchStage.COMPLETE) {
            throw new IllegalArgumentException("complete_stage_requires_terminal_state");
        }
        if (state == LaunchTaskState.SUCCEEDED &&
            (event.getProgress() != 100 || event.getExitCode() == null || event.getExitCode() != 0)) {
            throw new IllegalArgumentException("invalid_success_event");
        }
        if (state == LaunchTaskState.FAILED && event.getErrorCode().isEmpty()) {
            throw new IllegalArgumentException("failed_event_requires_error_code");
        }
    }
}
