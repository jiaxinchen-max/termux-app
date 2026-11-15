package com.termux.ui.desktop.archive;

import com.termux.ui.desktop.domain.DesktopDe;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class DesktopArchiveDistributionTest {
    @Test public void acceptsActualUbuntu2604PlasmaRelease() throws Exception {
        assertEquals("resolute", DesktopArchiveDistribution.codename(DesktopDe.KWIN_PLASMA,
            "PRETTY_NAME=\"Ubuntu 26.04.1 LTS\"\nVERSION_ID=\"26.04\"\nVERSION_CODENAME=resolute\nID=ubuntu\nID_LIKE=debian\n"));
    }
    @Test public void debianXfceStillWorksWithQuotedCrLfFields() throws Exception {
        assertEquals("trixie", DesktopArchiveDistribution.codename(DesktopDe.XFCE4,
            "ID='debian'\r\nVERSION_CODENAME=\"trixie\"\r\n"));
    }
    @Test public void rejectsWrongBaseReleaseMissingAndDuplicateFields() throws Exception {
        for (String release : new String[] {
            "ID=debian\nVERSION_CODENAME=resolute\n", "ID=ubuntu\nVERSION_CODENAME=noble\n",
            "ID=ubuntu\n", "VERSION_CODENAME=resolute\n",
            "ID=ubuntu\nID=ubuntu\nVERSION_CODENAME=resolute\n",
            "ID=ubuntu\nVERSION_CODENAME=resolute\nVERSION_CODENAME=resolute\n",
            "ID=u'buntu\nVERSION_CODENAME=resolute\n" }) {
            try { DesktopArchiveDistribution.codename(DesktopDe.KWIN_PLASMA, release); fail(release); }
            catch (IOException expected) { }
        }
    }
    @Test public void doesNotExpandGnomeArchiveSupport() throws Exception {
        assertFalse(DesktopArchiveDistribution.supports(DesktopDe.UBUNTU_GNOME, "noble"));
        try { DesktopArchiveDistribution.codename(DesktopDe.UBUNTU_GNOME, "ID=ubuntu\nVERSION_CODENAME=noble\n"); fail(); }
        catch (IOException expected) { }
    }
}
