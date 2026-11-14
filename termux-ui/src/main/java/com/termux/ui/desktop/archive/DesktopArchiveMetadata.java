package com.termux.ui.desktop.archive;

import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopGlMode;
import com.termux.ui.desktop.domain.DesktopGuestGlMode;
import com.termux.ui.desktop.domain.DesktopUsbStorageMode;
import com.termux.ui.desktop.runtime.DesktopRendererPolicy;
import com.termux.ui.desktop.domain.DesktopRunMode;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Current archives contain one complete proot container and explicit host/guest settings. */
public final class DesktopArchiveMetadata {
    private final String appPackage, architecture, codename, touchMode, orientation;
    private final DesktopDistro desktop;
    private final String archiveSha256;
    private final long regularBytes;
    private final int entryCount;

    private DesktopArchiveMetadata(String appPackage, String architecture, String codename,
                                    DesktopDistro desktop, String touchMode, String orientation,
                                    String archiveSha256, long regularBytes, int entryCount) {
        if (appPackage == null || !appPackage.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
            throw new IllegalArgumentException("invalid_archive_app_package");
        if (!"aarch64".equals(architecture)) throw new IllegalArgumentException("unsupported_archive_architecture");
        if (desktop == null || desktop.getRunMode() != DesktopRunMode.PROOT)
            throw new IllegalArgumentException("archive_requires_container");
        if (desktop.getUsbStorage() != com.termux.ui.desktop.domain.DesktopUsbStorageMode.OFF)
            throw new IllegalArgumentException("archive_requires_usb_storage_off");
        if (desktop.getHostGlMode() == DesktopGlMode.AUTO || desktop.getGuestGlMode() == DesktopGuestGlMode.UNRESOLVED)
            throw new IllegalArgumentException("archive_requires_explicit_renderer");
        if (!(desktop.getDe() == DesktopDe.XFCE4 && "trixie".equals(codename))
                && !(desktop.getDe() == DesktopDe.KWIN_PLASMA && "resolute".equals(codename)))
            throw new IllegalArgumentException("unsupported_archive_desktop_distribution");
        DesktopRendererPolicy.requireSupported(desktop);
        if (touchMode != null && !Arrays.asList("1", "2", "3").contains(touchMode))
            throw new IllegalArgumentException("invalid_archive_touch_mode");
        if (orientation != null && !Arrays.asList("auto", "vertical", "horizontal").contains(orientation))
            throw new IllegalArgumentException("invalid_archive_orientation");
        this.appPackage=appPackage; this.architecture=architecture; this.codename=codename;
        this.desktop=desktop; this.touchMode=touchMode; this.orientation=orientation;
        this.archiveSha256=archiveSha256; this.regularBytes=regularBytes; this.entryCount=entryCount;
    }
    public static DesktopArchiveMetadata create(String appPackage, String architecture,
            String codename, DesktopDistro desktop, String touchMode, String orientation) {
        return new DesktopArchiveMetadata(appPackage, architecture, codename, desktop,
            touchMode, orientation, null, -1, -1);
    }
    DesktopArchiveMetadata inspected(String sha, long bytes, int count) throws IOException {
        try { Math.addExact(bytes, Math.multiplyExact((long) count, 4096L)); }
        catch (ArithmeticException e) { throw new DesktopArchiveException("archive_size_overflow", e); }
        return new DesktopArchiveMetadata(appPackage, architecture, codename, desktop,
            touchMode, orientation, sha, bytes, count);
    }
    public String getAppPackage() { return appPackage; }
    public String getArchitecture() { return architecture; }
    public String getCodename() { return codename; }
    public DesktopDistro getDesktop() { return desktop; }
    public String getTouchMode() { return touchMode; }
    public String getOrientation() { return orientation; }
    public String getArchiveSha256() { return archiveSha256; }
    public int getEntryCount() { return entryCount; }
    public long getExpandedBytes() { return entryCount < 0 ? -1 : regularBytes + entryCount * 4096L; }

    byte[] encode() {
        String json="{\"format\":\"termux-desktop-container\",\"version\":2,\"compression\":\"gzip\""
            +",\"appPackage\":"+quote(appPackage)+",\"architecture\":"+quote(architecture)
            +",\"codename\":"+quote(codename)+",\"desktopSchema\":"+DesktopDistro.SCHEMA_VERSION
            +",\"id\":"+quote(desktop.getId())+",\"name\":"+quote(desktop.getName())
            +",\"runMode\":"+quote(desktop.getRunMode().getStorageValue())
            +",\"de\":"+quote(desktop.getDe().getStorageValue())
            +",\"prootAlias\":"+quote(desktop.getProotDistro())
            +",\"hostGlMode\":"+quote(desktop.getHostGlMode().getStorageValue())
            +",\"guestGlMode\":"+quote(desktop.getGuestGlMode().getStorageValue())
            +",\"resolution\":"+quote(desktop.getResolution())+",\"refreshRate\":"+desktop.getRefreshRate()
            +",\"touchMode\":"+quote(touchMode)+",\"orientation\":"+quote(orientation)
            +",\"payload\":\"container\",\"exclusions\":\"runtime-v1\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }
    static DesktopArchiveMetadata decode(byte[] data, String expectedPackage, String expectedArch)
            throws IOException {
        try {
            JSONObject j=new JSONObject(new String(data, StandardCharsets.UTF_8));
            int version = integer(j,"version");
            if (version != 2 || j.length() != 20 || !"termux-desktop-container".equals(string(j,"format"))
                    || !"gzip".equals(string(j,"compression"))
                    || integer(j,"desktopSchema") != DesktopDistro.SCHEMA_VERSION
                    || !"container".equals(string(j,"payload"))
                    || !"runtime-v1".equals(string(j,"exclusions")))
                throw new IllegalArgumentException("unsupported_archive_manifest");
            DesktopRunMode mode=DesktopRunMode.fromStorageValue(string(j,"runMode"));
            DesktopDe de=DesktopDe.fromStorageValue(string(j,"de"));
            DesktopGlMode host=DesktopGlMode.fromStorageValue(string(j,"hostGlMode"));
            DesktopGuestGlMode guest=DesktopGuestGlMode.fromStorageValue(string(j,"guestGlMode"));
            DesktopDistro d=new DesktopDistro(string(j,"id"),string(j,"name"),mode,string(j,"prootAlias"),
                de,host,guest,string(j,"resolution"),integer(j,"refreshRate"),DesktopUsbStorageMode.OFF);
            DesktopArchiveMetadata result=create(string(j,"appPackage"),string(j,"architecture"),
                string(j,"codename"),d,nullable(j,"touchMode"),nullable(j,"orientation"));
            if (!result.appPackage.equals(expectedPackage) || !result.architecture.equals(expectedArch))
                throw new IllegalArgumentException("archive_identity_mismatch");
            // Canonical JSON also rejects duplicate keys, coercions and ignored trailing fields.
            if (!Arrays.equals(data,result.encode())) throw new IllegalArgumentException("noncanonical_archive_manifest");
            return result;
        } catch (Exception e) { throw new DesktopArchiveException("invalid_archive_manifest",e); }
    }
    static String quote(String value) { return value==null ? "null" : JSONObject.quote(value); }
    private static String string(JSONObject j,String key) throws Exception {
        Object v=j.get(key); if (!(v instanceof String)) throw new IllegalArgumentException(key);
        return (String)v;
    }
    private static String nullable(JSONObject j,String key) throws Exception {
        Object v=j.get(key); return v==JSONObject.NULL ? null : string(j,key);
    }
    private static int integer(JSONObject j,String key) throws Exception {
        Object v=j.get(key);
        if (!(v instanceof Integer)) throw new IllegalArgumentException(key);
        return (Integer)v;
    }
}
