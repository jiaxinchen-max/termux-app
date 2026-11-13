#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Wayland desktop defaults and panel actions for one private Hyprland session."""
import argparse
import html
import json
import os
from pathlib import Path
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

PREFIX = Path(os.environ.get('PREFIX', '/data/data/com.termux/files/usr'))
HOME = Path(os.environ.get('HOME', '/data/data/com.termux/files/home'))


def create_default(path, content):
    """Only populate absent defaults; updates never overwrite user customization."""
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open('x') as output:
            output.write(content)
    except FileExistsError:
        pass


def property_of(parent, name, kind, value=None):
    attrs = {'name': name, 'type': kind}
    if value is not None:
        attrs['value'] = str(value).lower() if isinstance(value, bool) else str(value)
    return ET.SubElement(parent, 'property', attrs)


def panel_config(menu, launcher):
    root = ET.Element('channel', name='xfce4-panel', version='1.0')
    property_of(root, 'configver', 'int', 2)
    panels = property_of(root, 'panels', 'array')
    ET.SubElement(panels, 'value', type='int', value='1')
    property_of(panels, 'dark-mode', 'bool', True)
    panel = property_of(panels, 'panel-1', 'empty')
    for name, kind, value in [('position', 'string', 'p=6;x=0;y=0'),
                             ('length', 'uint', 100), ('position-locked', 'bool', True),
                             ('size', 'uint', 42), ('icon-size', 'uint', 24),
                             ('autohide-behavior', 'uint', 0)]:
        property_of(panel, name, kind, value)
    ids = property_of(panel, 'plugin-ids', 'array')
    for i in range(1, 8):
        ET.SubElement(ids, 'value', type='int', value=str(i))
    plugins = property_of(root, 'plugins', 'empty')
    menu_plugin = property_of(plugins, 'plugin-1', 'string', 'applicationsmenu')
    property_of(menu_plugin, 'custom-menu', 'bool', True)
    property_of(menu_plugin, 'custom-menu-file', 'string', menu)
    property_of(menu_plugin, 'button-title', 'string', 'Applications')
    property_of(menu_plugin, 'show-button-title', 'bool', True)
    property_of(menu_plugin, 'show-menu-icons', 'bool', True)
    workspace = property_of(plugins, 'plugin-2', 'string', 'genmon')
    property_of(workspace, 'command', 'string', shlex.join([launcher, 'workspace-status']))
    property_of(workspace, 'use-label', 'bool', False)
    property_of(workspace, 'update-period', 'int', 2000)
    tasklist = property_of(plugins, 'plugin-3', 'string', 'tasklist')
    property_of(tasklist, 'grouping', 'uint', 0)
    property_of(tasklist, 'include-all-workspaces', 'bool', True)
    property_of(tasklist, 'show-labels', 'bool', True)
    separator = property_of(plugins, 'plugin-4', 'string', 'separator')
    property_of(separator, 'expand', 'bool', True)
    property_of(separator, 'style', 'uint', 0)
    property_of(plugins, 'plugin-5', 'string', 'systray')
    battery = property_of(plugins, 'plugin-6', 'string', 'genmon')
    property_of(battery, 'command', 'string', shlex.join([launcher, 'battery-status']))
    property_of(battery, 'use-label', 'bool', False)
    property_of(battery, 'update-period', 'int', 30000)
    clock = property_of(plugins, 'plugin-7', 'string', 'clock')
    property_of(clock, 'mode', 'uint', 2)
    property_of(clock, 'digital-format', 'string', '%m-%d  %H:%M')
    return ET.tostring(root, encoding='unicode') + '\n'


def prepare(work, run_mode, theme_id):
    """Return host shell commands; applications route through session-local wrappers."""
    import runpy
    components = runpy.run_path(str(Path(__file__).with_name('desktop_hyprland_components.py')))
    components['resources'].__globals__['PREFIX'] = PREFIX
    resources = components['resources'](theme_id)
    config, data = work / 'config', work / 'data'
    helper = Path(__file__).resolve()
    launcher = work / 'bin/termux-hyprland-shell'
    launcher.write_text('#!' + str(PREFIX / 'bin/sh') + '\nexec ' + shlex.join([
        str(PREFIX / 'bin/python3'), '-I', '-B', str(helper), '--work', str(work)]) + ' "$@"\n')
    launcher.chmod(0o700)
    entries = [
        ('terminal', 'Terminal', '终端', 'utilities-terminal', str(work / 'bin/termux-hyprland-terminal'), 'Applications'),
        ('files', 'Files', '文件管理器', 'system-file-manager', str(work / 'bin/termux-hyprland-files'), 'Applications'),
        ('float', 'Toggle floating window', '切换浮动窗口', 'view-restore', str(launcher) + ' float', 'Windows'),
        ('close', 'Close active window', '关闭当前窗口', 'window-close', str(launcher) + ' close', 'Windows'),
        ('theme-m3ww', 'Use m3ww theme (restart)', '启用 m3ww 主题（重启生效）', 'preferences-desktop-theme', str(launcher) + ' theme-m3ww', 'Desktop'),
        ('theme-r7', 'Restore r7 theme (restart)', '恢复 r7 外观（重启生效）', 'preferences-desktop-theme', str(launcher) + ' theme-r7', 'Desktop'),
        ('display', 'Display information', '显示能力说明', 'video-display', str(launcher) + ' display', 'Desktop'),
    ]
    for index in range(1, 5):
        entries.append(('workspace-' + str(index), 'Workspace ' + str(index), '工作区 ' + str(index),
                        'preferences-desktop-workspaces', str(launcher) + ' workspace ' + str(index), 'Workspaces'))
    for key, name, chinese, icon, command, category in entries:
        # Managed entries are regenerated so an old APK path never persists after updates.
        p = data / 'applications' / ('termux-hyprland-' + key + '.desktop')
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text('[Desktop Entry]\nType=Application\nName=' + name + '\nName[zh_CN]=' + chinese
                     + '\nIcon=' + icon + '\nExec=' + command + '\nTerminal=false\nCategories=Termux'
                     + category + ';\n')
    sections = []
    for name, chinese in [('Applications', '应用程序'), ('Windows', '窗口'), ('Workspaces', '工作区'), ('Desktop', '桌面')]:
        directory = data / 'desktop-directories' / ('termux-hyprland-' + name + '.directory')
        create_default(directory, '[Desktop Entry]\nType=Directory\nName=' + name + '\nName[zh_CN]=' + chinese + '\nIcon=applications-other\n')
        include = '<Category>Termux' + name + '</Category>'
        if name == 'Applications' and run_mode == 'native':
            include += '<All/>'
        # Keep controls in their own submenus even when listing installed native applications.
        exclude = ''
        if name == 'Applications' and run_mode == 'native':
            exclude = '<Exclude>' + ''.join('<Category>Termux' + c + '</Category>' for c in ['Windows', 'Workspaces', 'Desktop']) + '</Exclude>'
        sections.append('<Menu><Name>' + name + '</Name><Directory>' + directory.name
                        + '</Directory><Include>' + include + '</Include>' + exclude + '</Menu>')
    menu = config / 'menus/hyprland-applications.menu'
    # Separate files avoid persisting native application entries in a container desktop.
    create_default(menu, '<Menu><Name>Hyprland</Name><DefaultAppDirs/><DefaultDirectoryDirs/>'
                   + ''.join(sections) + '</Menu>\n')
    panel = config / 'xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml'
    create_default(panel, panel_config(str(menu), str(launcher)))
    wallpaper = config / 'hyprland-wallpaper.svg'
    create_default(wallpaper, (resources / 'wallpaper.svg').read_text())
    info = config / 'hyprland-display.txt'
    info.write_text('Android display capabilities\n\n'
                    'The current Android output backend supports 8-bit ARGB8888 buffers.\n'
                    'FP16 rendering additionally requires EGL half-float color-buffer support\n'
                    'and a renderable ABGR16161616F format. Output support alone does not\n'
                    'establish these capabilities, nor does this warning prove that the physical\n'
                    'GPU lacks FP16. Normal terminal and file windows use 8-bit output.\n'
                    'Some advanced effects and color-management settings are unavailable.\n'
                    'Blur, shadows and animations are disabled in the conservative default.\n\n'
                    '窗口操作：任务栏可切换窗口，右键任务可关闭窗口；应用菜单的 Windows\n'
                    '子菜单可切换浮动/平铺。Workspaces 子菜单可选择工作区。\n')
    notifyd = PREFIX / 'lib/xfce4/notifyd/xfce4-notifyd'
    import runpy
    theme = runpy.run_path(str(Path(__file__).with_name('desktop_hyprland_theme.py')))
    if theme_id == theme['VERSION']:
        return theme['prepare'](work, run_mode)
    return [[str(PREFIX / 'bin/swaybg'), '-i', str(wallpaper), '-m', 'fill'],
            [str(notifyd)], [str(PREFIX / 'bin/xfce4-panel'), '--disable-wm-check']]


def call_ipc(state, *args):
    root = state['env']['PATH'].split(':')
    executable = next((Path(p) / 'hyprctl' for p in root if (Path(p) / 'hyprctl').is_file()), None)
    if executable is None:
        raise RuntimeError('Hyprland IPC client missing')
    return subprocess.check_output([str(executable), *args], env=state['env'], text=True, timeout=5)


def battery_status(path, now=None):
    try:
        status = json.loads(path.read_text())
        age = (time.time() if now is None else now) - status['updated'] / 1000
        percent = status['percent']
        if not 0 <= age <= 120 or type(percent) is not int or not 0 <= percent <= 100:
            raise ValueError('Stale or invalid battery status')
        label = ('⚡ ' if status.get('charging') else '') + str(percent) + '%'
        tip = 'Android battery'
    except (OSError, ValueError, KeyError, TypeError):
        label, tip = 'Battery —', 'Android battery status unavailable'
    return '<txt>' + label + '</txt><tool>' + tip + '</tool>'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('action', choices=['workspace-status', 'workspace', 'workspace-next', 'battery-status', 'float', 'close', 'display', 'theme-m3ww', 'theme-r7'])
    parser.add_argument('number', nargs='?', type=int)
    args = parser.parse_args()
    if args.work.parent != HOME / '.termux' or not args.work.name.startswith('hyprland-desktop-'):
        parser.error('Invalid private desktop path')
    if args.action == 'battery-status':
        print(battery_status(HOME.parent / 'front/desktop/state/android-battery.json'))
        return
    if args.action.startswith('theme-'):
        subprocess.Popen([str(PREFIX / 'bin/notify-send'), 'Desktop appearance', 'Stop the desktop and choose its theme in App settings.'])
        return
    state = json.loads((args.work / 'session.json').read_text())
    if not (Path('/proc') / str(state['supervisor'])).exists():
        raise RuntimeError('Hyprland supervisor is no longer running')
    if args.action.startswith('workspace'):
        active = json.loads(call_ipc(state, '-j', 'activeworkspace'))['id']
        if args.action == 'workspace-status':
            command = shlex.join([str(args.work / 'bin/termux-hyprland-shell'), 'workspace-next'])
            print('<txt>Workspace ' + str(active) + ' ▸</txt><tool>Tap to switch workspace; select 1–4 from Applications → Workspaces</tool><txtclick>' + html.escape(command) + '</txtclick>')
        else:
            target = args.number if args.action == 'workspace' else active % 4 + 1
            if target not in range(1, 5):
                parser.error('Workspace must be 1–4')
            print(call_ipc(state, 'dispatch', 'hl.dsp.focus({ workspace = ' + str(target) + ' })'))
    elif args.action == 'display':
        # A normal persistent terminal window avoids requiring another dialog toolkit.
        subprocess.Popen([str(PREFIX / 'bin/xfce4-terminal'), '--disable-server', '--hold',
                          '--title=Display information', '-x', str(PREFIX / 'bin/cat'),
                          str(args.work / 'config/hyprland-display.txt')], close_fds=True)
    else:
        action = 'hl.dsp.window.close()' if args.action == 'close' else 'hl.dsp.window.float({ action = "toggle" })'
        print(call_ipc(state, 'dispatch', action))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        # A transient IPC failure must not turn the panel into a Python traceback.
        import sys
        if 'workspace-status' in sys.argv:
            print('<txt>Workspace —</txt><tool>Workspace service unavailable</tool>')
        else:
            raise SystemExit('Hyprland desktop action failed: ' + str(error))
