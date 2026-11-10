package com.termux.ui.service;

import com.termux.ui.api.LocalGamesHost;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.data.FileRuntimeProfileRepository;
import com.termux.ui.wine.domain.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.util.*;
import static org.junit.Assert.*;

public class ComponentMutationStopTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void mutatingDefaultBox64StopsGamesWithoutAnExplicitSelection() throws Exception {
        File files = temp.newFolder();
        GameStoragePaths paths = new GameStoragePaths(files);
        RuntimeProfile base = RuntimeProfilePresets.create("wine-default", RuntimeProfilePreset.RECOMMENDED);
        Map<String, String> environment = new LinkedHashMap<>(base.getEnvironment());
        environment.remove("GAMES_CUSTOM_BOX64_ID");
        RuntimeProfile profile = new RuntimeProfile(base.getId(), base.getWinePackage(), base.getGraphicsDriver(),
            base.getDxWrapper(), base.getAudioDriver(), base.getResolution(), base.getBox64Preset(), environment,
            base.getInputProfileId(), base.getComponentVersions());
        new FileRuntimeProfileRepository(paths.getProfilesDirectory()).save(profile);
        List<String> stopped = new ArrayList<>();
        LocalGamesHost host = new LocalGamesHost() {
            public boolean isRuntimeAvailable() { return true; }
            public void forceStopRuntimeProcesses(List<String> scopes) { stopped.addAll(scopes); }
        };
        ComponentMutationStop.stop(files, host, "glibc_box64_0.2.8");
        assertTrue(stopped.contains(paths.getContainerPrefixDirectory(profile.getContainerId(),
            profile.getRuntimeBackendType(), profile.getWinePackage()).getAbsolutePath()));
    }
}
