#!/usr/bin/env python3
"""Save-time preparation and runtime rendezvous for PRoot DEB application stores."""
import os
from pathlib import Path
import shutil
import time
from xml.sax.saxutils import escape

GUEST_HOME = '/usr/local/lib/termux-desktop'
MANAGER = GUEST_HOME + '/desktop_container_software_services.py'


def prepare(root, runtime):
    """Called only while installing, saving or restoring, never while launching."""
    statuses = (root / 'var/lib/dpkg/status').read_text()
    installed = set()
    for record in statuses.split('\n\n'):
        fields = dict(line.split(': ', 1) for line in record.splitlines()
                      if line and not line[0].isspace() and ': ' in line)
        if fields.get('Status') in ('install ok installed', 'hold ok installed'):
            installed.add(fields.get('Package'))
    for package in ('gnome-software', 'gnome-software-plugin-deb', 'packagekit',
                    'appstream', 'apt-config-icons', 'polkitd', 'python3-gi',
                    'gir1.2-polkit-1.0', 'zenity'):
        if package not in installed:
            raise RuntimeError('Unconfigured software center package: ' + package + '; reinstall desktop')
    for name in ('gnome-software', 'zenity', 'python3', 'dbus-daemon', 'appstreamcli'):
        if not os.access(root / 'usr/bin' / name, os.X_OK):
            raise RuntimeError('Missing software center dependency: ' + name + '; reinstall desktop')
    if not os.access(root / 'usr/lib/polkit-1/polkitd', os.X_OK):
        raise RuntimeError('Missing container PolicyKit; reinstall desktop')
    if not any(os.access(root / name, os.X_OK) for name in
               ('usr/libexec/packagekitd', 'usr/lib/packagekit/packagekitd')):
        raise RuntimeError('Missing container PackageKit; reinstall desktop')
    directory = root / GUEST_HOME.lstrip('/')
    directory.mkdir(parents=True, exist_ok=True)
    for name in ('desktop_container_software_services.py', 'desktop_container_polkit_agent.py'):
        shutil.copyfile(Path(__file__).with_name(name), directory / name)
        (directory / name).chmod(0o755)
    # No activation directories: unsupported hardware daemons cannot be spawned.
    (directory / 'systembus.conf').write_text('<busconfig><type>system</type><listen>unix:path=' +
        escape(runtime + '/system-bus') + '</listen><auth>EXTERNAL</auth>'
        '<policy context="default"><allow user="*"/><allow own="*"/>'
        '<allow send_destination="*"/><allow receive_sender="*"/></policy></busconfig>')
    endpoint = root / 'run/dbus/system_bus_socket'
    endpoint.parent.mkdir(parents=True, exist_ok=True)
    if endpoint.is_symlink():
        endpoint.unlink()
    elif endpoint.exists():
        raise RuntimeError('Foreign container system bus; stop it before saving')
    # PAM clears the environment, so its packaged helper needs the standard address.
    endpoint.symlink_to(runtime + '/system-bus')
    autostart = root / 'home/termux/.config/autostart/org.gnome.Software.desktop'
    autostart.parent.mkdir(parents=True, exist_ok=True)
    autostart.write_text('[Desktop Entry]\nType=Application\nName=Software\nHidden=true\n')


def wait_ready(process, runtime, timeout=30):
    """Bounded rendezvous with newly started services; no probes or repair."""
    deadline = time.monotonic() + timeout
    marker = runtime / 'software-center.ready'
    while not marker.is_file():
        if process.poll() is not None or time.monotonic() >= deadline:
            raise RuntimeError('Container package services failed to start; inspect software-center log')
        time.sleep(.05)
    if process.poll() is not None:
        raise RuntimeError('Container package services exited during startup')
