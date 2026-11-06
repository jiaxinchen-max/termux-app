package com.termux.app.localgames;

import com.termux.ui.api.RuntimeComponentVariant;
import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.wine.domain.GameRuntimeBackendType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Exact launcher archive inventory; never guesses a replacement version or exposes paths. */
final class TermuxInstalledRuntimeVariants {
    private final File glibcDirectory;

    TermuxInstalledRuntimeVariants(File filesDirectory) {
        if (filesDirectory == null) throw new IllegalArgumentException("files_directory_required");
        glibcDirectory = new File(filesDirectory, "usr/glibc");
    }

    List<RuntimeComponentVariant> list(CustomRuntimeComponent.Kind kind,
                                       GameRuntimeBackendType backend) {
        if (kind == null || backend == null)
            throw new IllegalArgumentException("runtime_component_target_required");
        if (backend != GameRuntimeBackendType.GLIBC_TERMUX_BOX) return Collections.emptyList();
        String relativeDirectory;
        String[] basenames;
        switch (kind) {
            case DXVK:
                relativeDirectory = "opt/libs/d3d";
                basenames = new String[]{"dxvk-0.96", "dxvk-1.6.1", "dxvk-async-1.10.3",
                    "dxvk-async-2.0", "dxvk-dev", "dxvk-gplasync-2.2", "dxvk-gplasync-2.3"};
                break;
            case VKD3D:
                relativeDirectory = "opt/libs/d3d";
                basenames = new String[]{"vkd3d"};
                break;
            case TURNIP:
                relativeDirectory = "opt/libs/mesa";
                basenames = new String[]{"turnip-v2", "turnip-v3.5", "turnip-v4", "turnip-v5",
                    "turnip-v6.5"};
                break;
            default:
                return Collections.emptyList();
        }
        List<RuntimeComponentVariant> variants = new ArrayList<>();
        File directory = new File(glibcDirectory, relativeDirectory);
        for (String basename : basenames) {
            File archive = new File(directory, basename + ".7z");
            if (archive.isFile() && archive.length() > 0) {
                variants.add(new RuntimeComponentVariant("native-" + basename, basename,
                    kind, backend));
            }
        }
        return Collections.unmodifiableList(variants);
    }
}
