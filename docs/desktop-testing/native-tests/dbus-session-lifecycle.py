import os, pathlib, shutil, socket, stat, subprocess, sys, time
BASE=pathlib.Path('/tmp/dbus-proot-lifecycle-20261030')
PROOT='/tmp/xserver-proot-diagnosis-20261030/proot-5.1.107.101/src/proot'
if len(sys.argv)>1 and sys.argv[1]=='inside':
    mode=sys.argv[2]
    address='unix:path=/tmp/dbus-default.sock' if mode=='long' else 'unix:path=/run/wl/container-dbus/session.sock'
    daemon=subprocess.Popen(['/usr/bin/dbus-daemon','--session','--nofork','--nopidfile','--address='+address,'--print-address=1'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    try:
        actual=daemon.stdout.readline().strip()
        assert actual.startswith(address+',guid='),(actual,daemon.poll(),daemon.stderr.read())
        cmd=['/usr/bin/dbus-send','--bus='+actual,'--print-reply','--reply-timeout=3000','--dest=org.freedesktop.DBus','/org/freedesktop/DBus','org.freedesktop.DBus.Peer.Ping']
        before=subprocess.run(cmd,text=True,capture_output=True,timeout=5)
        assert before.returncode==0,(before.returncode,before.stderr)
        tmp=pathlib.Path('/control/'+mode+'/proot-tmp')
        entries=list(tmp.iterdir())
        socket_entries=[str(p) for p in entries if stat.S_ISSOCK(p.lstat().st_mode)]
        print('CASE',mode,'ADDRESS',actual,'BEFORE_PING',before.returncode,'PROOT_TMP_SOCKETS',socket_entries,flush=True)
        assert bool(socket_entries)==(mode=='long'),socket_entries
        # Match clearing the test's own PRoot temporary directory; its loader lives outside it.
        for p in entries:
            if p.is_dir() and not p.is_symlink():shutil.rmtree(p)
            else:p.unlink()
        after=subprocess.run(cmd,text=True,capture_output=True,timeout=5)
        alive=daemon.poll() is None
        print('CASE',mode,'AFTER_TMP_CLEAR_PING',after.returncode,'DAEMON_ALIVE',alive,'STDERR',after.stderr.strip(),flush=True)
        assert alive
        assert (after.returncode!=0 if mode=='long' else after.returncode==0),(after.returncode,after.stderr)
    finally:
        daemon.terminate()
        try:daemon.wait(timeout=5)
        except subprocess.TimeoutExpired:daemon.kill();daemon.wait()
    raise SystemExit(0)
BASE.mkdir(exist_ok=True)
for mode in ['long','short']:
    case=BASE/mode
    if case.exists():shutil.rmtree(case)
    longtmp=case/('rootfs-'+'x'*80)/'tmp';longtmp.mkdir(parents=True)
    runtime=case/'runtime';(runtime/'container-dbus').mkdir(parents=True)
    ptmp=case/'proot-tmp';ptmp.mkdir()
    env=dict(os.environ,PROOT_NO_SECCOMP='1',PROOT_TMP_DIR=str(ptmp),LD_LIBRARY_PATH='/tmp/xserver-proot-diagnosis-20261030/deps/usr/lib/x86_64-linux-gnu')
    default_physical=longtmp/'dbus-default.sock';fixed_physical=runtime/'container-dbus/session.sock'
    print('PHYSICAL_PATH_BYTES',mode,'old',len(str(default_physical)),'fixed',len(str(fixed_physical)),flush=True)
    assert len(str(default_physical))>108 and len(str(fixed_physical))<108
    cmd=[PROOT,'-r','/','-b',str(longtmp)+':/tmp','-b',str(runtime)+':/run/wl','-b',str(BASE)+':/control','-w','/','/usr/bin/python3','/control/lifecycle.py','inside',mode]
    r=subprocess.run(cmd,env=env,text=True,capture_output=True,timeout=30)
    print(r.stdout,end='');print(r.stderr,end='')
    assert r.returncode==0,r.returncode
print('PASS: actual PRoot 5.1.107.101 + dbus-daemon: long path fails after TMP clear; explicit short runtime path survives')
