#!/usr/bin/env python3
"""Exercise the production audio initializer with isolated PulseAudio commands."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ASSETS = Path(__file__).resolve().parents[2] / 'termux-ui/src/main/assets/local-games'
MOCK = r"""
import json, os, pathlib, sys
root=pathlib.Path(os.environ['AUDIO_FIXTURE'])
s=json.loads((root/'state.json').read_text())
cmd=pathlib.Path(sys.argv[0]).name
args=sys.argv[1:]
with (root/'calls.jsonl').open('a') as out:
    out.write(json.dumps([cmd,args,os.environ.get('PULSE_SERVER')])+'\n')
code=0
if cmd=='pulseaudio':
    if '--check' in args: code=0 if s.get('running') else 1
    elif s.get('start_error'): code=1
    else: s.update(running=True,tcp=True)
elif 'load-module' in args:
    if s.get('module_error'): code=1
    else: s['tcp']=True
elif 'info' in args:
    code=0 if s.get('tcp') else 1
elif 'sinks' in args:
    print('0 auto_null module-null-sink.c s16le 2ch 44100Hz IDLE' if s.get('null_only')
          else '1 OpenSL_ES_sink module-sles-sink.c s16le 2ch 44100Hz IDLE')
(root/'state.json').write_text(json.dumps(s))
sys.exit(code)
"""

class DesktopAudio(unittest.TestCase):
    def run_audio(self, mode='host-pulseaudio', missing=None, **state):
        with tempfile.TemporaryDirectory(prefix='desktop-audio-',dir='/tmp') as temporary:
            root=Path(temporary)
            (root/'bin').mkdir()
            (root/'state.json').write_text(json.dumps(state))
            for command in ('pulseaudio','pactl'):
                if command==missing: continue
                executable=root/'bin'/command
                executable.write_text('#!'+sys.executable+'\n'+MOCK)
                executable.chmod(0o700)
            env=dict(os.environ,PREFIX=str(root),HOME=str(root),AUDIO_FIXTURE=str(root),
                DESKTOP_LOG_FILE=str(root/'audio.log'),DESKTOP_PID_FILE=str(root/'pid'),
                DESKTOP_STATUS_FILE=str(root/'status'),PULSE_SERVER='tcp:stale.invalid:9999')
            result=subprocess.run(['sh','-c',
                '. "$1"; start_desktop_audio "$2" || exit $?; printf "ENDPOINT=%s\n" "$PULSE_SERVER"',
                'audio-test',str(ASSETS/'desktop_common.sh'),mode],env=env,text=True,
                stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=10)
            calls=[json.loads(x) for x in (root/'calls.jsonl').read_text().splitlines()] if (root/'calls.jsonl').exists() else []
            return result,calls

    def test_cold_start_uses_loopback_and_real_android_sink(self):
        result,calls=self.run_audio()
        self.assertEqual(0,result.returncode,result.stdout)
        self.assertIn('ENDPOINT=tcp:127.0.0.1:4713',result.stdout)
        start=next(c for c in calls if '--start' in c[1])
        self.assertIsNone(start[2])
        self.assertIn('--load=module-native-protocol-tcp listen=127.0.0.1 port=4713 auth-anonymous=1',start[1])
        self.assertTrue(any('set-default-sink' in c[1] and 'OpenSL_ES_sink' in c[1] for c in calls))

    def test_reuses_existing_server_without_restarting_or_killing(self):
        result,calls=self.run_audio(running=True,tcp=True)
        self.assertEqual(0,result.returncode,result.stdout)
        self.assertFalse(any('--start' in c[1] or '--kill' in c[1] or 'load-module' in c[1] for c in calls))

    def test_existing_local_daemon_gets_container_protocol(self):
        result,calls=self.run_audio(running=True)
        self.assertEqual(0,result.returncode,result.stdout)
        load=next(c for c in calls if 'load-module' in c[1])
        self.assertIsNone(load[2])
        self.assertIn('listen=127.0.0.1',load[1])

    def test_package_replacement_cannot_duplicate_a_healthy_tcp_daemon(self):
        # dpkg unlinks the running executable; PulseAudio --check no longer recognizes it.
        result,calls=self.run_audio(running=False,tcp=True,start_error=True)
        self.assertEqual(0,result.returncode,result.stdout)
        self.assertIn('ENDPOINT=tcp:127.0.0.1:4713',result.stdout)
        self.assertFalse(any(c[0]=='pulseaudio' or 'load-module' in c[1] for c in calls))

    def test_failures_never_publish_successful_endpoint(self):
        for state in ({'start_error':True},{'running':True,'module_error':True},
                      {'running':True,'tcp':True,'null_only':True}):
            with self.subTest(state=state):
                result,calls=self.run_audio(**state)
                self.assertEqual(70,result.returncode,result.stdout)
                self.assertNotIn('ENDPOINT=',result.stdout)

    def test_missing_support_fails_when_service_cannot_start_and_implicit_modes_fail(self):
        for missing in ('pulseaudio','pactl'):
            result,calls=self.run_audio(missing=missing)
            self.assertNotEqual(0,result.returncode,result.stdout)
            self.assertNotIn('ENDPOINT=',result.stdout)
        for mode in ('','auto','container-pulseaudio'):
            result,calls=self.run_audio(mode)
            self.assertEqual(64,result.returncode,result.stdout)
            self.assertFalse(calls)

if __name__=='__main__': unittest.main()
