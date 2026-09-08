package com.termux.localgames.data;

import com.termux.localgames.domain.RuntimeProfile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface RuntimeProfileRepository {
    List<RuntimeProfile> list() throws IOException;
    Optional<RuntimeProfile> find(String profileId) throws IOException;
    Optional<RuntimeProfile> findLastSuccessful(String profileId) throws IOException;
    void save(RuntimeProfile profile) throws IOException;
    void saveLastSuccessful(RuntimeProfile profile) throws IOException;
    void delete(String profileId) throws IOException;
}
