# Games assets and recovery specification

## ADDED Requirements

### Requirement: Explainable private asset inventory

The system SHALL classify each game's private prefix, cache, logs, snapshots and configuration with byte/file counts, and SHALL identify external game/save content as protected and unmanaged.

#### Scenario: Inventory contains external game directory

- **WHEN** the game references a SAF tree
- **THEN** the inventory reports external content as protected
- **AND** no SAF document is traversed for cleanup sizing

### Requirement: Verified immutable snapshots

The system SHALL create immutable configuration/prefix snapshots in private storage and SHALL verify their deterministic SHA-256 before restore.

#### Scenario: Snapshot content is modified

- **WHEN** restore detects a path, size or content digest mismatch
- **THEN** restore fails before changing the active prefix or profile

### Requirement: Transactional restore

The system SHALL stage restored content, persist its transaction phase and retain the prior active prefix/configuration until publication succeeds.

#### Scenario: Restore publication fails

- **WHEN** any publication step fails
- **THEN** the previous prefix and configuration remain active

#### Scenario: Process stops during restore

- **WHEN** the next recovery operation observes a prepared restore without a committed marker
- **THEN** it restores the prior prefix/configuration from rollback paths
- **AND** a committed restore keeps the published content and only cleans temporary paths

### Requirement: Safe uninstall boundary

The system SHALL default to deleting only the private library record/artwork and SHALL require explicit selection for other private categories. External game directories and saves SHALL always be kept.

#### Scenario: User confirms default uninstall

- **WHEN** no optional private category is selected
- **THEN** only the library record and private artwork are removed
- **AND** prefix, cache, logs, snapshots, profiles and external SAF content remain

### Requirement: Active launch exclusion

The system SHALL reject snapshot creation, restore and uninstall while the game has a non-terminal LaunchTask, and SHALL serialize launch-task creation with those mutations.

#### Scenario: Game is running

- **WHEN** a recovery or uninstall command is requested
- **THEN** it fails with `game_asset_task_active` without modifying private assets
