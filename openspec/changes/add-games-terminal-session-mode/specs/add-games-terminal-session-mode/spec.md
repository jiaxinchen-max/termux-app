# Optional TerminalSession launch mode specification

## ADDED Requirements

### Requirement: Per-game execution mode

The system SHALL persist an execution mode in each RuntimeProfile and SHALL default legacy or newly preset profiles to `APP_SHELL`.

#### Scenario: User enables terminal logs

- **WHEN** the user selects TerminalSession and saves the runtime profile
- **THEN** later launches for that game create a named TerminalSession

### Requirement: Frozen launch execution

The system SHALL freeze the selected execution mode in LaunchSpec before host submission.

#### Scenario: Task resumes after process restart

- **WHEN** an existing LaunchSpec is reconciled after Android process restart
- **THEN** host submission uses the execution mode stored in that LaunchSpec

### Requirement: Shared orchestration semantics

Both execution modes SHALL use the same launch script, JSONL event chain, PID reconciliation, cancellation markers and terminal state rules.

#### Scenario: TerminalSession launch exits

- **WHEN** a terminal-backed launch exits or is cancelled
- **THEN** LaunchTask converges through the same structured event and cleanup path as AppShell

### Requirement: No TermuxService modification

The app adapter SHALL select an existing TermuxService runner through `ACTION_SERVICE_EXECUTE` and SHALL NOT require a TermuxService code change.

#### Scenario: AppShell remains default

- **WHEN** the profile is absent or migrated from an older schema
- **THEN** the adapter submits an `APP_SHELL` background command
