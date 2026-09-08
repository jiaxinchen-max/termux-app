package com.termux.localgames.importer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic heuristics; ranking never replaces explicit user selection. */
public final class ExecutableCandidateRanker {

    private static final Set<String> HELPER_TERMS = new HashSet<>(Arrays.asList(
        "unins", "uninstall", "uninstaller", "setup", "installer", "crash",
        "report", "reporter", "unitycrashhandler", "redist", "vcredist",
        "vc_redist", "dxsetup", "easyanticheat", "eac", "prerequisite"));
    private static final Set<String> SUPPORT_DIRECTORIES = new HashSet<>(Arrays.asList(
        "redist", "_redist", "redistributable", "support", "installer", "installers",
        "prerequisites", "thirdparty", "crashreporter"));

    public ExecutableCandidate rank(GameDocument file, String relativePath, String rootName) {
        String baseName = withoutExe(file.getName()).toLowerCase(Locale.US);
        String normalizedRoot = normalizeName(rootName);
        String[] segments = relativePath.toLowerCase(Locale.US).split("/");
        int depth = Math.max(0, segments.length - 1);
        int score = 100 - depth * 8;
        boolean discouraged = false;
        List<CandidateSignal> signals = new ArrayList<>();

        if (depth == 0) {
            score += 40;
            signals.add(CandidateSignal.ROOT_EXECUTABLE);
        } else if (depth >= 3) {
            signals.add(CandidateSignal.DEEP_PATH);
        }
        if (!normalizedRoot.isEmpty() && normalizeName(baseName).equals(normalizedRoot)) {
            score += 80;
            signals.add(CandidateSignal.ROOT_NAME_MATCH);
        }
        if (file.getSize() >= 10L * 1024 * 1024) {
            score += 20;
            signals.add(CandidateSignal.LARGE_EXECUTABLE);
        }
        if (baseName.contains("launcher")) {
            score -= 10;
            signals.add(CandidateSignal.LAUNCHER_NAME);
        }
        if (containsTerm(baseName, HELPER_TERMS)) {
            score -= 220;
            discouraged = true;
            signals.add(CandidateSignal.HELPER_NAME);
        }
        for (int index = 0; index < segments.length - 1; index++) {
            if (containsTerm(normalizeName(segments[index]), SUPPORT_DIRECTORIES)) {
                score -= 140;
                discouraged = true;
                signals.add(CandidateSignal.SUPPORT_DIRECTORY);
                break;
            }
        }

        return new ExecutableCandidate(file.getUri(), relativePath,
            parentPath(relativePath), file.getSize(), score, discouraged, signals);
    }

    private static boolean containsTerm(String value, Set<String> terms) {
        for (String term : terms) {
            if (value.contains(term)) return true;
        }
        return false;
    }

    private static String parentPath(String relativePath) {
        int separator = relativePath.lastIndexOf('/');
        return separator < 0 ? "." : relativePath.substring(0, separator);
    }

    private static String withoutExe(String value) {
        return value.length() > 4 ? value.substring(0, value.length() - 4) : value;
    }

    private static String normalizeName(String value) {
        return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }
}
