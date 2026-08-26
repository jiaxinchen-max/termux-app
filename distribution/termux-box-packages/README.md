# Termux Box Package Repository

This directory contains the source catalog and migration tooling for the
standalone `jiaxinchen-max/termux-box-packages` repository.

Package archives are GitHub Release assets and are not committed to Git.
The generated `repository/index-v1.json` file is committed to the package
repository and consumed by Termux Box.

## Publish

Requirements: `curl`, `git`, `gh`, `jq`, `sha256sum`, and `tar`.

```sh
gh auth login -h github.com
./distribution/termux-box-packages/migrate-mobox-packages.sh
```

The script is idempotent. Existing releases are downloaded and verified
instead of being overwritten.

The repository also includes a manually dispatched GitHub Actions workflow.
It performs the migration on a GitHub runner and avoids routing all package
data through the developer workstation.
