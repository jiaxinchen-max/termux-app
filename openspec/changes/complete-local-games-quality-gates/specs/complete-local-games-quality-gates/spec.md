# Local Games app entry and quality specification

## ADDED Requirements

### Requirement: Visible host-app entry

The host app SHALL expose a visible Local Games entry that opens the module-owned Activity through its public Intent API without replacing the active terminal session.

#### Scenario: User opens Local Games from the drawer

- **WHEN** the user activates the Local Games drawer entry
- **THEN** the app closes the drawer and starts the explicit module Activity Intent
- **AND** TermuxActivity retains ownership of its terminal state only

### Requirement: Evidence-backed regression gates

The change SHALL record passing module, app, X11, JVM and available Android device checks, and SHALL distinguish unavailable physical compatibility tests from passing automated checks.

#### Scenario: Physical matrix is unavailable

- **WHEN** no physical controller or real game/GPU sample is connected
- **THEN** the result remains explicitly pending
- **AND** emulator or unit results are not reported as physical verification
