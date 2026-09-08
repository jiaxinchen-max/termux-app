package com.termux.localgames.activity;

import androidx.annotation.NonNull;

import com.termux.localgames.domain.LaunchStage;

/** Pure mapping from persisted launch facts to stable user-facing phases/actions. */
public final class LaunchPresentation {

    public enum Phase {
        CHECK(1), PREPARE(2), START(3), RUNNING(3), STOPPING(3), COMPLETE(3);

        private final int step;

        Phase(int step) { this.step = step; }

        public int getStep() { return step; }
    }

    public enum RecoveryAction {
        NONE, REAUTHORIZE, COMPONENTS, RETRY
    }

    private LaunchPresentation() {
    }

    @NonNull
    public static Phase phaseFor(@NonNull LaunchStage stage) {
        switch (stage) {
            case QUEUED:
            case PRECHECK:
                return Phase.CHECK;
            case PREPARING_COMPONENTS:
            case PREPARING_PREFIX:
                return Phase.PREPARE;
            case STARTING_DISPLAY:
            case STARTING_AUDIO:
            case STARTING_GAME:
            case WAITING_FIRST_FRAME:
                return Phase.START;
            case RUNNING:
                return Phase.RUNNING;
            case STOPPING:
            case CLEANING:
                return Phase.STOPPING;
            default:
                return Phase.COMPLETE;
        }
    }

    @NonNull
    public static RecoveryAction recoveryFor(String errorCode, boolean recoverable) {
        String code = code(errorCode);
        if ("preflight_permission_lost".equals(code) ||
            "preflight_provider_unavailable".equals(code)) {
            return RecoveryAction.REAUTHORIZE;
        }
        if ("preflight_component_missing".equals(code) ||
            "preflight_component_version_mismatch".equals(code) ||
            "preflight_runtime_provision_required".equals(code)) {
            return RecoveryAction.COMPONENTS;
        }
        return recoverable ? RecoveryAction.RETRY : RecoveryAction.NONE;
    }

    @NonNull
    public static String code(String errorCode) {
        if (errorCode == null) return "";
        int split = errorCode.indexOf(':');
        return split < 0 ? errorCode : errorCode.substring(0, split);
    }

    @NonNull
    public static String subject(String errorCode) {
        if (errorCode == null) return "";
        int split = errorCode.indexOf(':');
        return split < 0 ? "" : errorCode.substring(split + 1);
    }
}
