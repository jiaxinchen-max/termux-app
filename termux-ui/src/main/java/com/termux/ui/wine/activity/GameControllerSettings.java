package com.termux.ui.wine.activity;

import com.termux.ui.wine.data.RuntimeProfileRepository;
import com.termux.ui.wine.domain.RuntimeProfile;

import java.io.IOException;
import java.util.function.UnaryOperator;

/** Per-game controller edits. Call on the session's serial IO queue; each edit reads fresh data. */
final class GameControllerSettings {
    private final RuntimeProfileRepository repository;
    private final String gameId;

    GameControllerSettings(RuntimeProfileRepository repository, String gameId) {
        this.repository = repository;
        this.gameId = gameId;
    }

    RuntimeProfile read() throws IOException {
        return repository.find(gameId).orElseThrow(() -> new IOException("runtime_profile_missing"));
    }

    RuntimeProfile setMapper(String mapper) throws IOException {
        return update(profile -> profile.withInputProfileId(
            GameInputProfileChoices.withMapper(profile.getInputProfileId(), mapper)));
    }

    RuntimeProfile setInputApi(boolean xinputApi, boolean enabled) throws IOException {
        return update(profile -> profile.withInputApis(
            xinputApi ? enabled : profile.isXinputEnabled(),
            xinputApi ? profile.isDinputEnabled() : enabled));
    }

    RuntimeProfile setTouchProfile(int profileId) throws IOException {
        return update(profile -> profile.withInputProfileId(
            GameInputProfileChoices.compose(profile.getInputProfileId(), profileId)));
    }

    private RuntimeProfile update(UnaryOperator<RuntimeProfile> change) throws IOException {
        RuntimeProfile updated = change.apply(read());
        repository.save(updated);
        return updated;
    }
}
