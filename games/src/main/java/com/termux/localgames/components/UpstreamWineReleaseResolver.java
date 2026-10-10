package com.termux.localgames.components;

import com.termux.localgames.components.index.ComponentDescriptor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves "whatever upstream currently calls latest" for the three wine-family components this
 * app intentionally does NOT pin to a fixed version in index-v1.json (see
 * RootfsRuntimeComponentPreparer): Hangover's Debian source archive, the vanilla Kron4ek Wine
 * build used by the box64-wine translator path, and Kron4ek's own "Proton" build (Valve's Proton
 * wine patches, without Proton's python wrapper or bundled DXVK/vkd3d-proton -- same portable
 * box64-translated shape as the vanilla build) used by the box64-proton translator path. Hangover
 * never publishes a checksum for any release -- its returned descriptor's sha256 is left empty
 * (trust-on-first-download; see ComponentTask.withVerifiedSha256()). Kron4ek's release bodies
 * (both the vanilla and proton build families) are plain SHA256SUMS-style text blocks, which ARE
 * parsed and used as real, pre-verified digests.
 */
public final class UpstreamWineReleaseResolver {

    private static final String HANGOVER_RELEASES_URL =
        "https://api.github.com/repos/AndreRH/hangover/releases/latest";
    private static final String KRON4EK_RELEASES_URL =
        "https://api.github.com/repos/Kron4ek/Wine-Builds/releases/latest";
    // Kron4ek's Proton build is published under a separate "proton-*" tag lineage, not every
    // numbered release, so it cannot be found via /releases/latest -- the full list has to be
    // scanned for the newest tag matching that family (see fetchLatestStableProtonRelease()).
    private static final String KRON4EK_RELEASES_LIST_URL =
        "https://api.github.com/repos/Kron4ek/Wine-Builds/releases?per_page=100";
    // The RootFS recipe (setup_rootfs_runtime.sh) only ever provisions a Debian 13 "trixie"
    // guest -- the asset must match that exact distro variant, only the version number floats.
    private static final Pattern HANGOVER_ASSET =
        Pattern.compile("^hangover_[0-9.]+_debian13_trixie_arm64\\.tar$");
    // Vanilla (non-staging, non-tkg) WoW64 build -- the same variant the catalog previously
    // pinned by exact filename.
    private static final Pattern KRON4EK_VANILLA_WOW64_ASSET =
        Pattern.compile("^wine-[0-9.]+-amd64-wow64\\.tar\\.xz$");
    private static final Pattern KRON4EK_PROTON_WOW64_ASSET =
        Pattern.compile("^wine-proton-[0-9A-Za-z._-]+-amd64-wow64\\.tar\\.xz$");
    private static final Pattern SHA256SUMS_LINE = Pattern.compile("^([0-9a-f]{64})\\s+(\\S+)$");
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private final HttpConnectionFactory connectionFactory;

    public UpstreamWineReleaseResolver() {
        this(new DefaultHttpConnectionFactory());
    }

    public UpstreamWineReleaseResolver(HttpConnectionFactory connectionFactory) {
        if (connectionFactory == null) throw new IllegalArgumentException("connectionFactory required");
        this.connectionFactory = connectionFactory;
    }

    public ComponentDescriptor resolveHangoverSource(ComponentDescriptor template) throws IOException {
        JSONObject release = fetchJsonObject(HANGOVER_RELEASES_URL);
        Asset asset = findAsset(release, HANGOVER_ASSET);
        return ComponentDescriptor.withResolvedUpstream(template, versionFor(release), asset.url,
            asset.size, "");
    }

    public ComponentDescriptor resolveBox64Wine(ComponentDescriptor template) throws IOException {
        JSONObject release = fetchJsonObject(KRON4EK_RELEASES_URL);
        Asset asset = findAsset(release, KRON4EK_VANILLA_WOW64_ASSET);
        String sha256 = findSha256InBody(release.optString("body", ""), asset.name);
        if (sha256 == null) {
            throw new IOException("upstream_release_unavailable:box64_wine_sha256_not_found");
        }
        return ComponentDescriptor.withResolvedUpstream(template, versionFor(release), asset.url,
            asset.size, sha256);
    }

    public ComponentDescriptor resolveProtonWine(ComponentDescriptor template) throws IOException {
        JSONObject release = fetchLatestStableProtonRelease();
        Asset asset = findAsset(release, KRON4EK_PROTON_WOW64_ASSET);
        String sha256 = findSha256InBody(release.optString("body", ""), asset.name);
        if (sha256 == null) {
            throw new IOException("upstream_release_unavailable:proton_wine_sha256_not_found");
        }
        return ComponentDescriptor.withResolvedUpstream(template, versionFor(release), asset.url,
            asset.size, sha256);
    }

    /** Scans the release list (newest first) for the first "proton-*" tag that is not a beta or
     *  experimental build -- e.g. "proton-11.0-2" qualifies, "proton-11.0-beta1" and
     *  "proton-exp-11.0" do not. */
    private JSONObject fetchLatestStableProtonRelease() throws IOException {
        JSONArray releases = fetchJsonArray(KRON4EK_RELEASES_LIST_URL);
        for (int index = 0; index < releases.length(); index++) {
            JSONObject release = releases.optJSONObject(index);
            if (release == null) continue;
            String tag = release.optString("tag_name", "");
            if (isStableProtonTag(tag)) return release;
        }
        throw new IOException("upstream_release_unavailable:no_proton_release_found");
    }

    private static boolean isStableProtonTag(String tag) {
        return tag.startsWith("proton-") && !tag.contains("beta") && !tag.contains("exp");
    }

    private JSONObject fetchJsonObject(String apiUrl) throws IOException {
        try {
            return new JSONObject(fetchUrl(apiUrl));
        } catch (JSONException error) {
            throw new IOException("upstream_release_unavailable:invalid_json", error);
        }
    }

    private JSONArray fetchJsonArray(String apiUrl) throws IOException {
        try {
            return new JSONArray(fetchUrl(apiUrl));
        } catch (JSONException error) {
            throw new IOException("upstream_release_unavailable:invalid_json", error);
        }
    }

    private String fetchUrl(String apiUrl) throws IOException {
        HttpURLConnection connection = connectionFactory.open(new URL(apiUrl));
        try {
            connection.setRequestProperty("User-Agent", "termux-app-games-module");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("upstream_release_unavailable:http_" + status);
            }
            try (InputStream input = connection.getInputStream()) {
                return readAll(input);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) builder.append(buffer, 0, count);
        }
        return builder.toString();
    }

    private static Asset findAsset(JSONObject release, Pattern namePattern) throws IOException {
        JSONArray assets = release.optJSONArray("assets");
        if (assets != null) {
            for (int index = 0; index < assets.length(); index++) {
                JSONObject asset = assets.optJSONObject(index);
                if (asset == null) continue;
                String name = asset.optString("name", "");
                if (!namePattern.matcher(name).matches()) continue;
                String url = asset.optString("browser_download_url", "");
                long size = asset.optLong("size", -1);
                if (url.isEmpty() || size < 1) {
                    throw new IOException("upstream_release_unavailable:invalid_asset_metadata");
                }
                return new Asset(name, url, size);
            }
        }
        throw new IOException("upstream_release_unavailable:no_matching_asset");
    }

    private static String findSha256InBody(String body, String assetName) {
        for (String line : body.split("\\r?\\n")) {
            Matcher matcher = SHA256SUMS_LINE.matcher(line.trim());
            if (matcher.matches() && matcher.group(2).equals(assetName)) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private static int versionFor(JSONObject release) throws IOException {
        String tag = release.optString("tag_name", "");
        if (tag.isEmpty()) throw new IOException("upstream_release_unavailable:missing_tag");
        int hash = Math.abs(tag.hashCode());
        return hash == 0 ? 1 : hash;
    }

    private static final class Asset {
        final String name;
        final String url;
        final long size;

        Asset(String name, String url, long size) {
            this.name = name;
            this.url = url;
            this.size = size;
        }
    }
}
