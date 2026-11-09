#!/usr/bin/env python3
"""Run the production guest wrapper to preserve its normal Wine server shutdown contract."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games/start_rootfs_game.sh'


class NaturalExitTests(unittest.TestCase):
    def test_natural_exit_requests_registry_flush_and_preserves_game_exit_code(self):
        script = SOURCE.read_text().split("GAME_SESSION_SCRIPT=$(cat <<'EOS'\n", 1)[1].split('\nEOS\n', 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            server = base / 'wineserver'
            server.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$TEST_WINESERVER_CALLS"\n')
            server.chmod(0o700)
            calls = base / 'server-calls'
            env = dict(os.environ, GAMES_WINESERVER=str(server), TEST_WINESERVER_CALLS=str(calls))
            for code in [0, 7]:
                with self.subTest(game_exit=code):
                    calls.unlink(missing_ok=True)
                    result = subprocess.run(['sh', '-c', script, 'game-session', 'sh', '-c', 'exit ' + str(code)],
                                            env=env, capture_output=True, text=True, timeout=3)
                    self.assertEqual(result.returncode, code, result.stderr)
                    self.assertEqual(calls.read_text().splitlines(), ['-k', '-w'])


if __name__ == '__main__':
    unittest.main()
