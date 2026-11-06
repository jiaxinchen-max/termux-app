#!/usr/bin/env python3
"""Exercise real cache identity/publication/reuse/corruption paths without running Wine."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
ASSET=Path(__file__).resolve().parents[1]/'src/main/assets/local-games/rootfs_prefix_warmup.sh'

class ExternalTemplates(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='v11 templates $ = ')
        self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        (self.root/'rootfs/etc').mkdir(parents=True)
        (self.root/'rootfs/etc/games-runtime.properties').write_text('recipeSha256='+'a'*64+'\n')
        (self.root/'game').mkdir()
    def run_shell(self,tail):
        code='''set -eu
. "$ASSET"
warmup_fail() { echo "$1" >&2; exit 70; }
progress() { :; }
run_logged() { "$@"; }
ROOTFS_CANONICAL="$ROOT/rootfs"
TEMPLATE_CACHE_DIR="$ROOT/cache"
PREFIX_PATH="$ROOT/game"
HOME_PATH="$ROOT/home"
RUNTIME_ROOT_PATH="$ROOT/rootfs"
WINE_PACKAGE=rootfs_hangover_11.16
RUNTIME_TRANSLATOR=hangover
TASK_ID=owned-task
LOG_PATH="$ROOT/log"
# Fake only Wine's initialization. All cache identity, tar, hashing and publication run unchanged.
warmup_rootfs_prefix() {
  printf boot >> "$ROOT/boots"
  mkdir -p "$PREFIX_PATH/drive_c/windows" "$PREFIX_PATH/.games-runtime"
  printf registry > "$PREFIX_PATH/system.reg"
  printf registry > "$PREFIX_PATH/user.reg"
  printf '%s' "$GAMES_TEMPLATE_KEY" > "$PREFIX_PATH/.games-runtime/prepared-identity"
  printf '%s' "$RUNTIME_ROOT_PATH|$WINE_PACKAGE" > "$PREFIX_PATH/.games-runtime/runtime"
}
'''+tail
        return subprocess.run(['sh','-c',code],env=dict(os.environ,ROOT=str(self.root),ASSET=str(ASSET)),capture_output=True,text=True,timeout=20)
    def test_one_selected_template_reused_and_corrupt_archive_rebuilt(self):
        p=self.run_shell('''prepare_external_prefix_template
first=$EXTERNAL_TEMPLATE_DIR
prepare_external_prefix_template
[ "$(cat "$ROOT/boots")" = boot ]
printf broken > "$EXTERNAL_TEMPLATE_DIR/prefix.tar.gz"
prepare_external_prefix_template
[ "$(cat "$ROOT/boots")" = bootboot ]
[ "$first" = "$EXTERNAL_TEMPLATE_DIR" ]
[ "$PREFIX_PATH" = "$ROOT/game" ]
[ ! -e "$TEMPLATE_CACHE_DIR/v11/.stage-owned-task" ]
''')
        self.assertEqual(0,p.returncode,p.stderr)
        self.assertEqual(1,len(list((self.root/'cache/v11').iterdir())))
    def test_wine_translator_digest_and_recipe_change_key(self):
        p=self.run_shell('''resolve_external_template_identity
old=$GAMES_TEMPLATE_KEY
PROOT_BINDING_IDENTITY='WINE|rootfs_wine_10.0|1|new-digest'
resolve_external_template_identity
[ "$old" != "$GAMES_TEMPLATE_KEY" ]
new=$GAMES_TEMPLATE_KEY
RUNTIME_TRANSLATOR=box64
resolve_external_template_identity
[ "$new" != "$GAMES_TEMPLATE_KEY" ]
''')
        self.assertEqual(0,p.returncode,p.stderr)
    def test_missing_prepared_identity_fails_without_wineboot(self):
        p=self.run_shell('''resolve_external_template_identity
require_external_prepared_prefix
''')
        self.assertEqual(70,p.returncode,p.stderr)
        self.assertIn('rootfs_configuration_prepare_required',p.stderr)
        self.assertFalse((self.root/'boots').exists())
    def test_interrupted_template_never_published(self):
        p=self.run_shell('''warmup_rootfs_prefix() { warmup_fail injected_boot_failure; }
prepare_external_prefix_template
''')
        self.assertEqual(70,p.returncode,p.stderr)
        self.assertEqual([],list((self.root/'cache').rglob('manifest')))
        p=self.run_shell('prepare_external_prefix_template\n')
        self.assertEqual(0,p.returncode,p.stderr)
        self.assertEqual(1,len(list((self.root/'cache').rglob('manifest'))))
if __name__=='__main__':unittest.main()
