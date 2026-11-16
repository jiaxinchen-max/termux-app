package com.termux.ui.desktop.archive;

import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.runtime.DesktopCreationCatalog;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** The export entry point and archive reader must use the same distribution policy. */
public final class DesktopArchiveDistribution {
    private DesktopArchiveDistribution() { }

    public static boolean supports(DesktopDe de, String codename) {
        return (de == DesktopDe.XFCE4 || de == DesktopDe.KWIN_PLASMA || de == DesktopDe.UBUNTU_GNOME)
            && DesktopCreationCatalog.containerCodename(de).equals(codename);
    }

    public static String codename(DesktopDe de, String release) throws IOException {
        Map<String, String> fields = new HashMap<>();
        for (String line : release.split("\n")) {
            if (!line.startsWith("ID=") && !line.startsWith("VERSION_CODENAME=")) continue;
            int eq = line.indexOf('=');
            String value = line.substring(eq + 1).trim();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))))
                value = value.substring(1, value.length() - 1);
            if (fields.put(line.substring(0, eq), value) != null)
                throw new IOException("desktop_archive_os_release_invalid");
        }
        String code = fields.get("VERSION_CODENAME");
        if (!supports(de, code) || !DesktopCreationCatalog.containerBase(de).equals(fields.get("ID")))
            throw new IOException("desktop_archive_distribution_unsupported");
        return code;
    }
}
