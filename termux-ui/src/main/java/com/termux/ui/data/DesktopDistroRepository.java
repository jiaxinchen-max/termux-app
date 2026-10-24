package com.termux.ui.data;

import com.termux.ui.domain.DesktopDistro;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Persistent inventory of managed KWin desktop distributions. */
public interface DesktopDistroRepository {
    List<DesktopDistro> list() throws IOException;
    Optional<DesktopDistro> find(String distroId) throws IOException;
    void save(DesktopDistro distro) throws IOException;
    void delete(String distroId) throws IOException;
}
