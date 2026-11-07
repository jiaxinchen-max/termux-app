package com.termux.ui.wine.service;

import org.junit.Test;
import java.io.IOException;

public class RootfsPrefixSourceIdentityTest {
    private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String METADATA = "{\"sourceComponentSha256\":\"" + SHA + "\"}";

    @Test public void matchingImmutableSourceIsAccepted() throws Exception {
        RootfsSetupAssetInstaller.requirePrefixSource(METADATA, new String[]{SHA});
    }
    @Test(expected = IOException.class) public void sameNameChangedBytesCannotReuseTemplate() throws Exception {
        RootfsSetupAssetInstaller.requirePrefixSource(METADATA, new String[]{SHA.replace('a', 'b')});
    }
    @Test(expected = IOException.class) public void missingSourceFails() throws Exception {
        RootfsSetupAssetInstaller.requirePrefixSource(METADATA, new String[0]);
    }
    @Test(expected = IOException.class) public void unrelatedSourceSetFails() throws Exception {
        RootfsSetupAssetInstaller.requirePrefixSource(METADATA, new String[]{SHA, SHA});
    }
    @Test(expected = IOException.class) public void malformedMetadataFails() throws Exception {
        RootfsSetupAssetInstaller.requirePrefixSource("{}", new String[]{SHA});
    }
}
