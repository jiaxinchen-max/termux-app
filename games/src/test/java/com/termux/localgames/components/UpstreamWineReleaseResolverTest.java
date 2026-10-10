package com.termux.localgames.components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.termux.localgames.components.index.ComponentDescriptor;
import com.termux.localgames.components.index.ComponentType;
import com.termux.localgames.domain.GameRuntimeBackendType;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Queue;

public class UpstreamWineReleaseResolverTest {

    @Test
    public void resolvesHangoverSourceWithoutASha256() throws Exception {
        String body = "{\"tag_name\":\"hangover-11.16\",\"assets\":[" +
            asset("hangover_11.16_debian13_trixie_arm64.tar",
                "https://github.com/AndreRH/hangover/releases/download/hangover-11.16/hangover_11.16_debian13_trixie_arm64.tar",
                293099520L) +
            "," + asset("hangover_11.16_ubuntu2404_noble_arm64.tar",
                "https://github.com/AndreRH/hangover/releases/download/hangover-11.16/hangover_11.16_ubuntu2404_noble_arm64.tar",
                307138560L) +
            "]}";
        UpstreamWineReleaseResolver resolver = resolver(response(200, body));

        ComponentDescriptor resolved = resolver.resolveHangoverSource(hangoverTemplate());

        assertEquals("hangover-latest-debian13-source", resolved.getId());
        assertEquals(293099520L, resolved.getSize());
        assertEquals("", resolved.getSha256());
        assertTrue(resolved.getUrl().endsWith("hangover_11.16_debian13_trixie_arm64.tar"));
    }

    @Test
    public void resolvesBox64WineSha256FromReleaseBody() throws Exception {
        String body = "{\"tag_name\":\"11.19\",\"body\":\"" +
            "40068b3825887224d4b95364e3f49342d4bf7efac5486614b8bc4e7173bbef3e  wine-11.19-amd64.tar.xz\\n" +
            "a1fe858b45d12c9b16b91c7601d9b934f68430f7b6b95e240212ab6e2a198d8a  wine-11.19-amd64-wow64.tar.xz\\n" +
            "c89f69cf22f6664e3791210fa3b40c6e9e6d2e27a9aaa466f5a26d9c56664a09  wine-11.19-staging-amd64-wow64.tar.xz\\n" +
            "\",\"assets\":[" +
            asset("wine-11.19-amd64-wow64.tar.xz",
                "https://github.com/Kron4ek/Wine-Builds/releases/download/11.19/wine-11.19-amd64-wow64.tar.xz",
                99272636L) +
            "," + asset("wine-11.19-staging-amd64-wow64.tar.xz",
                "https://github.com/Kron4ek/Wine-Builds/releases/download/11.19/wine-11.19-staging-amd64-wow64.tar.xz",
                101336808L) +
            "]}";
        UpstreamWineReleaseResolver resolver = resolver(response(200, body));

        ComponentDescriptor resolved = resolver.resolveBox64Wine(box64WineTemplate());

        assertEquals("box64-wine-latest", resolved.getId());
        assertEquals(99272636L, resolved.getSize());
        assertEquals("a1fe858b45d12c9b16b91c7601d9b934f68430f7b6b95e240212ab6e2a198d8a",
            resolved.getSha256());
        assertTrue(resolved.getUrl().endsWith("wine-11.19-amd64-wow64.tar.xz"));
    }

    @Test
    public void resolvesProtonWineSkippingBetaAndExperimentalReleases() throws Exception {
        // The release LIST (newest first) mixes a plain numbered release, an experimental proton
        // tag and a beta proton tag before the first stable "proton-*" one -- only the last of
        // these should be picked.
        String numberedRelease = "{\"tag_name\":\"11.19\",\"assets\":[]}";
        String expRelease = "{\"tag_name\":\"proton-exp-11.0\",\"assets\":[" +
            asset("wine-proton-exp-11.0-amd64-wow64.tar.xz", "https://example.invalid/exp", 1L) +
            "]}";
        String betaRelease = "{\"tag_name\":\"proton-11.0-beta1\",\"assets\":[" +
            asset("wine-proton-11.0-beta1-amd64-wow64.tar.xz", "https://example.invalid/beta", 1L) +
            "]}";
        String stableRelease = "{\"tag_name\":\"proton-11.0-2\",\"body\":\"" +
            "51e1b54068c242c0a59c329674afe4b0ae03d313516a535be4acd1c5f3277e9b  wine-proton-11.0-2-amd64.tar.xz\\n" +
            "f166f7daa1a37b3c8e0ade213a6e7915e582091804071a58a4a32c6c672e1595  wine-proton-11.0-2-amd64-wow64.tar.xz\\n" +
            "\",\"assets\":[" +
            asset("wine-proton-11.0-2-amd64-wow64.tar.xz",
                "https://github.com/Kron4ek/Wine-Builds/releases/download/proton-11.0-2/wine-proton-11.0-2-amd64-wow64.tar.xz",
                80017220L) +
            "]}";
        String listBody = "[" + numberedRelease + "," + expRelease + "," + betaRelease + "," +
            stableRelease + "]";
        UpstreamWineReleaseResolver resolver = resolver(response(200, listBody));

        ComponentDescriptor resolved = resolver.resolveProtonWine(protonWineTemplate());

        assertEquals("box64-proton-latest", resolved.getId());
        assertEquals(80017220L, resolved.getSize());
        assertEquals("f166f7daa1a37b3c8e0ade213a6e7915e582091804071a58a4a32c6c672e1595",
            resolved.getSha256());
        assertTrue(resolved.getUrl().endsWith("wine-proton-11.0-2-amd64-wow64.tar.xz"));
    }

    @Test
    public void throwsWhenNoStableProtonReleaseExists() throws Exception {
        String listBody = "[{\"tag_name\":\"11.19\",\"assets\":[]}," +
            "{\"tag_name\":\"proton-exp-11.0\",\"assets\":[]}]";
        UpstreamWineReleaseResolver resolver = resolver(response(200, listBody));

        try {
            resolver.resolveProtonWine(protonWineTemplate());
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("no_proton_release_found"));
        }
    }

    @Test
    public void throwsWhenNoAssetMatchesTheExpectedName() throws Exception {
        String body = "{\"tag_name\":\"11.16\",\"assets\":[" +
            asset("hangover_11.16_ubuntu2404_noble_arm64.tar", "https://example.invalid/x", 1L) +
            "]}";
        UpstreamWineReleaseResolver resolver = resolver(response(200, body));

        try {
            resolver.resolveHangoverSource(hangoverTemplate());
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("no_matching_asset"));
        }
    }

    @Test
    public void throwsOnNonOkHttpStatus() throws Exception {
        UpstreamWineReleaseResolver resolver = resolver(response(403, "rate limited"));

        try {
            resolver.resolveHangoverSource(hangoverTemplate());
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("http_403"));
        }
    }

    private static String asset(String name, String url, long size) {
        return "{\"name\":\"" + name + "\",\"browser_download_url\":\"" + url +
            "\",\"size\":" + size + "}";
    }

    private static ComponentDescriptor hangoverTemplate() {
        return new ComponentDescriptor("hangover-latest-debian13-source", "runtime", 1,
            "https://github.com/AndreRH/hangover/releases/download/hangover-11.9/hangover_11.9_debian13_trixie_arm64.tar",
            273571840L, "896918679daa53d6d3a6a1c40132cd35a1d9edc7afdbc643f9bbb3e22b114348",
            ComponentType.RUNTIME_SUPPORT, "Debian 13 ImageFS Source", "Hangover (offline fallback)",
            "Verified Debian source archive.", "ROOTFS", false, false, "",
            Collections.singleton(GameRuntimeBackendType.ROOTFS_PROOT));
    }

    private static ComponentDescriptor box64WineTemplate() {
        return new ComponentDescriptor("box64-wine-latest", "runtime", 2,
            "https://github.com/Kron4ek/Wine-Builds/releases/download/10.0/wine-10.0-amd64-wow64.tar.xz",
            65074156L, "aeebbbf239e548f0136f1cd72a2e109dd9a572a8f703da3c09fa40d74d5c255f",
            ComponentType.TRANSLATOR, "Box64 + Wine (RootFS)", "Vanilla WoW64 (offline fallback)",
            "Portable vanilla Wine under the standalone Box64 translator.", "ROOTFS", false, false,
            "box64-wine-latest", Collections.singleton(GameRuntimeBackendType.ROOTFS_PROOT));
    }

    private static ComponentDescriptor protonWineTemplate() {
        return new ComponentDescriptor("box64-proton-latest", "runtime", 1,
            "https://github.com/Kron4ek/Wine-Builds/releases/download/proton-11.0-2/wine-proton-11.0-2-amd64-wow64.tar.xz",
            80017220L, "f166f7daa1a37b3c8e0ade213a6e7915e582091804071a58a4a32c6c672e1595",
            ComponentType.TRANSLATOR, "Box64 + Proton (RootFS)", "Proton WoW64 (offline fallback)",
            "Kron4ek's own Proton build under the standalone Box64 translator.", "ROOTFS", false,
            false, "box64-proton-latest", Collections.singleton(GameRuntimeBackendType.ROOTFS_PROOT));
    }

    private static UpstreamWineReleaseResolver resolver(FakeConnection response) {
        return new UpstreamWineReleaseResolver(new QueueFactory(response));
    }

    private static FakeConnection response(int status, String body) {
        return new FakeConnection(status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class QueueFactory implements HttpConnectionFactory {
        final Queue<FakeConnection> responses = new ArrayDeque<>();

        QueueFactory(FakeConnection... connections) {
            Collections.addAll(responses, connections);
        }

        @Override
        public HttpURLConnection open(URL url) throws IOException {
            FakeConnection response = responses.poll();
            if (response == null) throw new IOException("No fake response configured");
            return response;
        }
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final int status;
        private final byte[] body;

        FakeConnection(int status, byte[] body) {
            super(url());
            this.status = status;
            this.body = body;
        }

        private static URL url() {
            try {
                return new URL("https://api.github.invalid/releases/latest");
            } catch (java.net.MalformedURLException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public void setRequestProperty(String key, String value) { }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
