#!/usr/bin/env python3
"""The legacy backup engine must refuse v11 without touching archives or runtime data."""
import json
from pathlib import Path
import subprocess
import tempfile

ASSETS = Path(__file__).resolve().parents[1] / "src/main/assets/local-games"
for mode, code in (("backup", "rootfs_external_backup_not_ready"),
                   ("restore", "rootfs_backup_format_unsupported")):
    with tempfile.TemporaryDirectory() as temporary:
        files = Path(temporary) / "files"
        runtime = files / "front/runtime"
        root = files / "usr/var/lib/proot-distro"
        manifest = root / "games-shared-rootfs/rootfs/etc/wine-runtime.properties"
        stage = files / "front/wine/templates/backup-staging/keep"
        archive = Path(temporary) / "existing.tar.gz"
        events = runtime / "backup/events/task.jsonl"
        spec = runtime / "backup/specs/task.backupspec"
        for path, data in ((manifest, "schemaVersion=3\nlayout=external-components-v1\n"),
                           (stage, "existing transaction"), (archive, "existing archive")):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(data)
        spec.parent.mkdir(parents=True)
        spec.write_text(f"schemaVersion=1\ntaskId=task\nmode={mode}\n"
                        f"backupArchivePath={archive}\neventsPath={events}\n"
                        f"logPath={runtime}/backup/logs/task.log\n")
        completed = subprocess.run(["sh", str(ASSETS / "backup_restore_rootfs.sh"), str(spec)],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
        assert completed.returncode != 0
        assert json.loads(events.read_text().splitlines()[-1])["errorCode"] == code
        assert archive.read_text() == "existing archive"
        assert stage.read_text() == "existing transaction"
        assert "external-components-v1" in manifest.read_text()
print("v11 legacy backup/restore guard passed (2 cases).")
