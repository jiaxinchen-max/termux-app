package com.termux.localgames.domain;

import java.util.List;

/** Immutable reference to a user-owned local Windows game. */
public final class Game {

    public static final int SCHEMA_VERSION = 1;

    private final String id;
    private final String name;
    private final String rootUri;
    private final String executable;
    private final String workingDirectory;
    private final List<String> arguments;
    private final String artworkUri;
    private final long lastPlayedAt;

    public Game(String id, String name, String rootUri, String executable,
                String workingDirectory, List<String> arguments, String artworkUri,
                long lastPlayedAt) {
        this.id = DomainValidation.requireText(id, "id");
        this.name = DomainValidation.requireText(name, "name");
        this.rootUri = DomainValidation.requireText(rootUri, "rootUri");
        this.executable = DomainValidation.requireText(executable, "executable");
        this.workingDirectory = DomainValidation.requireText(workingDirectory, "workingDirectory");
        this.arguments = DomainValidation.immutableTextList(arguments, "arguments");
        this.artworkUri = DomainValidation.optionalText(artworkUri);
        if (lastPlayedAt < 0) {
            throw new IllegalArgumentException("lastPlayedAt must not be negative");
        }
        this.lastPlayedAt = lastPlayedAt;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getRootUri() { return rootUri; }
    public String getExecutable() { return executable; }
    public String getWorkingDirectory() { return workingDirectory; }
    public List<String> getArguments() { return arguments; }
    public String getArtworkUri() { return artworkUri; }
    public long getLastPlayedAt() { return lastPlayedAt; }

    public Game withLastPlayedAt(long playedAt) {
        return new Game(id, name, rootUri, executable, workingDirectory,
            arguments, artworkUri, playedAt);
    }
}
