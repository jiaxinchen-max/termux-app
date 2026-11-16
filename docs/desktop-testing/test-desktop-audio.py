#!/usr/bin/env python3
"""Exercise runtime audio reuse/replacement and process ownership boundaries."""
import contextlib
import concurrent.futures
import signal
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ASSETS = Path(__file__).resolve().parents[2] / 'termux-ui/src/main/assets/local-games'
spec = importlib.util.spec_from_file_location('desktop_audio_service', ASSETS/'desktop_audio_service.py')
audio = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audio)

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
    elif s.get('no_output_after_start'): s.update(running=True,tcp=True,null_only=True)
    else: s.update(running=True,tcp=True,null_only=False)
elif 'load-module' in args:
    if s.get('module_error'): code=1
    else: s['tcp']=True
elif 'info' in args:
    code=0 if s.get('tcp') else 1
elif 'sinks' in args:
    if not s.get('tcp'): sys.exit(1)
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
            (root/'bin/python3').symlink_to(sys.executable)
            env=dict(os.environ,PREFIX=str(root),HOME=str(root),AUDIO_FIXTURE=str(root),
                DESKTOP_LOG_FILE=str(root/'audio.log'),DESKTOP_PID_FILE=str(root/'pid'),
                DESKTOP_STATUS_FILE=str(root/'status'),PULSE_SERVER='tcp:stale.invalid:9999')
            result=subprocess.run(['sh','-c',
                '. "$1"; start_desktop_audio "$2" "$3" || exit $?; printf "ENDPOINT=%s\n" "$PULSE_SERVER"',
                'audio-test',str(ASSETS/'desktop_common.sh'),mode,str(ASSETS/'desktop_audio_service.py')],env=env,text=True,
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

    def test_existing_local_daemon_is_replaced_before_new_service(self):
        result,calls=self.run_audio(running=True)
        self.assertEqual(0,result.returncode,result.stdout)
        self.assertTrue(any('--start' in c[1] for c in calls))
        self.assertFalse(any('load-module' in c[1] for c in calls))

    def test_package_replacement_cannot_duplicate_a_healthy_tcp_daemon(self):
        # dpkg unlinks the running executable; PulseAudio --check no longer recognizes it.
        result,calls=self.run_audio(running=False,tcp=True,start_error=True)
        self.assertEqual(0,result.returncode,result.stdout)
        self.assertIn('ENDPOINT=tcp:127.0.0.1:4713',result.stdout)
        self.assertFalse(any(c[0]=='pulseaudio' or 'load-module' in c[1] for c in calls))

    def test_failures_never_publish_successful_endpoint(self):
        for state in ({'start_error':True},{'no_output_after_start':True}):
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

class AudioLifecycle(unittest.TestCase):
    def service(self):
        return audio.AudioService('/test-prefix', '/test-home')

    def test_healthy_endpoint_never_enumerates_or_stops_processes(self):
        service=self.service()
        with patch.object(service,'locked',contextlib.nullcontext), patch.object(service,'ready',return_value=True), \
             patch.object(service,'stop_old') as stop, patch.object(service,'run') as run:
            service.ensure()
            stop.assert_not_called()
            run.assert_not_called()

    def test_unhealthy_is_stopped_before_exactly_one_start_and_checked(self):
        service=self.service(); events=[]
        def ready():
            events.append('ready'); return len(events)>1
        with patch.object(service,'locked',contextlib.nullcontext), patch.object(service,'ready',side_effect=ready), \
             patch.object(service,'stop_old',side_effect=lambda:events.append('stop')), \
             patch.object(service,'run',side_effect=lambda *a,**k:(events.append('start') or subprocess.CompletedProcess(a,0,''))):
            service.ensure()
        self.assertEqual(['ready','stop','start','ready'],events)

    def test_failed_stop_does_not_start_another_service(self):
        service=self.service()
        with patch.object(service,'locked',contextlib.nullcontext), patch.object(service,'ready',return_value=False), \
             patch.object(service,'stop_old',side_effect=PermissionError('blocked')), patch.object(service,'run') as run:
            with self.assertRaises(PermissionError):service.ensure()
            run.assert_not_called()

    def test_stop_checks_identity_and_waits_before_return(self):
        service=self.service()
        with patch.object(service,'processes',side_effect=[[(123,'start')],[],[]]), \
             patch.object(service,'identity',return_value=(123,'start')), patch.object(audio.os,'kill') as kill:
            service.stop_old()
        kill.assert_called_once_with(123,signal.SIGTERM)

    def test_recycled_pid_is_never_signalled(self):
        service=self.service()
        with patch.object(service,'processes',side_effect=[[(123,'old')],[],[]]), \
             patch.object(service,'identity',return_value=(123,'new')), patch.object(audio.os,'kill') as kill:
            service.stop_old()
        kill.assert_not_called()

    def test_term_timeout_escalates_then_stops(self):
        service=self.service()
        with patch.object(service,'processes',side_effect=[[(123,'start')],[(123,'start')],[(123,'start')],[],[]]), \
             patch.object(service,'identity',return_value=(123,'start')), \
             patch.object(audio.time,'monotonic',side_effect=[0,4,4]), patch.object(audio.os,'kill') as kill:
            service.stop_old()
        self.assertEqual([unittest.mock.call(123,signal.SIGTERM),unittest.mock.call(123,signal.SIGKILL)],kill.call_args_list)

    def test_unstoppable_daemon_fails_closed(self):
        service=self.service()
        with patch.object(service,'processes',return_value=[(123,'start')]), \
             patch.object(service,'identity',return_value=(123,'start')), \
             patch.object(audio.time,'monotonic',side_effect=[0,4,4,6]), patch.object(audio.os,'kill'):
            with self.assertRaises(RuntimeError):service.stop_old()

    def test_process_identity_accepts_current_executable_and_shell_argv(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary); proc=root/'123'; proc.mkdir()
            (proc/'comm').write_text('pulseaudio\n')
            (proc/'stat').write_text('123 (pulseaudio) S ' + ' '.join(['0']*18+['456']))
            service=self.service()
            with patch.object(audio,'PROC',root):
                for executable in (b'/test-prefix/bin/pulseaudio',b'pulseaudio'):
                    (proc/'cmdline').write_bytes(executable+b'\0--start\0')
                    self.assertEqual((123,'456'),service.identity(123))
                with patch.object(audio.os,'getuid',return_value=os.getuid()+1):
                    self.assertIsNone(service.identity(123))
                (proc/'cmdline').write_bytes(b'/other/pulseaudio\0')
                with self.assertRaises(RuntimeError):service.identity(123)
                (proc/'stat').write_text('123 (pulseaudio) Z ' + ' '.join(['0']*18+['456']))
                self.assertIsNone(service.identity(123))
                (proc/'comm').write_text('other-service\n')
                self.assertIsNone(service.identity(123))

    def test_android_hidden_other_uid_proc_entry_is_ignored(self):
        with patch.object(Path,'stat',side_effect=PermissionError('hidepid')):
            self.assertIsNone(self.service().identity(1))

    def test_same_uid_permission_failure_is_not_silently_ignored(self):
        with patch.object(Path,'stat',return_value=type('Stat',(),{'st_uid':os.getuid()})()), \
             patch.object(Path,'read_text',side_effect=PermissionError('comm')):
            with self.assertRaises(PermissionError):self.service().identity(123)

    def test_endpoint_timeout_marks_service_unusable(self):
        service=self.service()
        with patch.object(service,'run',side_effect=subprocess.TimeoutExpired('pactl',3)):
            self.assertFalse(service.ready())

    def test_serializes_real_lock_between_two_managers(self):
        with tempfile.TemporaryDirectory() as temporary:
            service=audio.AudioService('/test',temporary); state={'ready':False}; events=[]
            def start(*a,**kw):
                events.append('start'); state['ready']=True
                return subprocess.CompletedProcess(a,0,'')
            with patch.object(service,'ready',side_effect=lambda:state['ready']), \
                 patch.object(service,'stop_old'), patch.object(service,'run',side_effect=start):
                with concurrent.futures.ThreadPoolExecutor(2) as pool:
                    list(pool.map(lambda _:service.ensure(),range(2)))
            self.assertEqual(['start'],events)

if __name__=='__main__': unittest.main()
