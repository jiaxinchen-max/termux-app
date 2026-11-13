#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Android m3ww-inspired shell; original configuration, no upstream install scripts."""
import argparse
import configparser
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import shlex
import shutil
import subprocess
import time
import unicodedata

PREFIX = Path(os.environ.get('PREFIX', '/data/data/com.termux/files/usr'))
HOME = Path(os.environ.get('HOME', '/data/data/com.termux/files/home'))
VERSION = 'm3ww-v1'

def default(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open('x') as f:
            f.write(text)
    except FileExistsError:
        pass

def selected(work):
    path = work / 'config/desktop-theme'
    if path.exists():
        value = path.read_text().strip()
        if value not in ('r7', VERSION):
            raise ValueError('Unknown Hyprland desktop theme: ' + value)
        return value
    # Only migrate the uncustomized app default. Custom user configurations keep r7.
    config = work / 'config/hyprland.lua'
    if not config.exists() or hashlib.sha256(config.read_bytes()).hexdigest() == 'e0de98acf8ad690a0bcfb0e0814b58630d5e6919ab9f1c59d11068262cf1bbd8':
        return VERSION
    return 'r7'

def command(work, *args):
    return shlex.join([str(work / 'bin/termux-hyprland-theme'), *map(str, args)])

def dialog_command(work, action):
    # EWW times out long-running click commands; dialogs outlive the short handler.
    # The inherited session token keeps them within supervisor cleanup.
    return command(work, action) + ' >> ' + shlex.quote(str(work / 'session.log')) + ' 2>&1 &'

def prepare(work, run_mode):
    components = runpy.run_path(str(Path(__file__).with_name('desktop_hyprland_components.py')))
    components['resources'].__globals__['PREFIX'] = PREFIX
    resources = components['resources'](VERSION)
    theme = work / 'config/themes' / VERSION
    theme.mkdir(parents=True, exist_ok=True)
    helper = Path(__file__).resolve()
    launcher = work / 'bin/termux-hyprland-theme'
    launcher.write_text('#!' + str(PREFIX / 'bin/sh') + '\nexec ' + shlex.join([str(PREFIX / 'bin/python3'), '-I', '-B', str(helper), '--work', str(work)]) + ' "$@"\n')
    launcher.chmod(0o700)
    default(theme / 'wallpaper.svg', (resources / 'wallpaper.svg').read_text())
    default(theme / 'eww.scss', (resources / 'eww.scss').read_text())
    cmd = command(work)
    initial = json.dumps(dict(workspace=1, tasks='(box)', battery='—', clock='—', date='', count=0))
    workspaces = ' '.join('(button :class {desktop.workspace == '+str(i)+' ? "workspace current" : "workspace"} :timeout "3s" :onclick '+json.dumps(command(work,'workspace',i))+' "'+str(i)+'")' for i in range(1,5))
    yuck = '(deflisten desktop :initial '+json.dumps(initial)+' '+json.dumps(cmd+' watch')+')\n'
    yuck += '''(defwidget desktop_bar []
 (centerbox :class "bar"
  (box :space-evenly false :spacing 8
   (button :class "launch" :timeout "3s" :onclick @LAUNCH@ "󰣇  Apps")
   (literal :content {desktop.tasks}))
  (box :class "pill" :space-evenly false @WORKSPACES@)
  (box :space-evenly false :halign "end" :spacing 4
   (button :class "clock" :timeout "3s" :tooltip {desktop.date} :onclick @DASHBOARD@ {desktop.clock})
   (label :class "battery" :text {desktop.battery})
   (button :timeout "3s" :tooltip "Desktop appearance and window controls" :onclick @SETTINGS@ "󰒓"))))
(defwindow bar :monitor 0 :namespace "m3ww-bar" :stacking "fg" :exclusive true
 :geometry (geometry :x "10px" :y "8px" :width "98%" :anchor "top center") (desktop_bar))
'''.replace('@LAUNCH@',json.dumps(dialog_command(work,'launcher'))).replace('@DASHBOARD@',json.dumps(dialog_command(work,'dashboard'))).replace('@SETTINGS@',json.dumps(dialog_command(work,'settings'))).replace('@WORKSPACES@',workspaces)
    default(theme / 'eww.yuck', yuck)
    default(theme / 'foot.ini', (resources / 'foot.ini').read_text())
    default(theme / 'starship.toml', (resources / 'starship.toml').read_text())
    default(theme / 'fish/config.fish', 'set -gx STARSHIP_CONFIG ' + shlex.quote(str(theme / 'starship.toml')) + '\n'+str(PREFIX / 'bin/starship')+' init fish | source\n')
    # Scope Fish configuration to this terminal, leave HOME and user shell files alone.
    terminal = work / 'bin/termux-hyprland-terminal-themed'
    terminal.write_text('#!'+str(PREFIX/'bin/sh')+'\nexec '+shlex.join([str(PREFIX/'bin/env'),'-u','LD_LIBRARY_PATH','-u','LD_PRELOAD',str(PREFIX/'bin/foot'),'--working-directory='+str(HOME),'--config='+str(theme/'foot.ini'),str(PREFIX/'bin/fish'),'--no-config','--init-command=source '+shlex.quote(str(theme/'fish/config.fish'))])+' "$@"\n')
    terminal.chmod(0o700)
    if run_mode == 'native':
        (work/'bin/termux-hyprland-terminal').write_text('#!'+str(PREFIX/'bin/sh')+'\nexec '+shlex.quote(str(terminal))+' "$@"\n')
    fonts = work / 'data/fonts'
    fonts.mkdir(parents=True,exist_ok=True)
    source = resources / 'font.ttf'
    if source.exists() and not (fonts/source.name).exists():
        shutil.copyfile(source,fonts/source.name)
        if (PREFIX/'bin/fc-cache').is_file():
            subprocess.run([str(PREFIX/'bin/fc-cache'),str(fonts)],capture_output=True,timeout=20)
    # Per-session GTK, including notification popups and application launch dialogs.
    default(work/'config/gtk-3.0/settings.ini', (resources/'settings.ini').read_text())
    default(work/'config/gtk-3.0/gtk.css', (resources/'gtk.css').read_text())
    effects = theme/'effects'
    default(effects,'balanced\n')
    blur = 'true' if effects.read_text().strip()=='blur' else 'false'
    # A launch overlay loads the untouched user file before the chosen theme settings.
    overlay = theme/'session.lua'
    overlay.write_text('dofile('+json.dumps(str(work/'config/hyprland.lua'))+')\n'+'''hl.config({ general = { gaps_in = 5, gaps_out = 12, border_size = 2,
 col = { active_border = { colors = {"rgba(a6c9ffff)", "rgba(c8b6ffff)"}, angle = 45 }, inactive_border = "rgba(514f7388)" } },
 decoration = { rounding = 15, blur = { enabled = '''+blur+''', size = 3, passes = 1 }, shadow = { enabled = true, range = 12, render_power = 3, color = "rgba(05091888)" } },
 animations = { enabled = true },
 -- Android uses the app session supervisor and the GTK dialogs provided here.
 misc = { disable_watchdog_warning = true, disable_hyprland_guiutils_check = true } })
hl.curve("m3ww", { type = "bezier", points = { {0.05, 0.7}, {0.1, 1} } })
hl.animation({ leaf = "windows", enabled = true, speed = 3, bezier = "m3ww", style = "popin 90%" })
hl.animation({ leaf = "workspaces", enabled = true, speed = 3, bezier = "m3ww", style = "slide" })
hl.animation({ leaf = "fade", enabled = true, speed = 2, bezier = "m3ww" })
hl.bind("SUPER + Space", hl.dsp.exec_cmd("termux-hyprland-theme launcher"))
''')
    (work/'theme-session-config').write_text(str(overlay))
    (theme/'run-mode').write_text(run_mode)
    return [[str(PREFIX/'bin/swaybg'),'-i',str(theme/'wallpaper.svg'),'-m','fill'],
            [str(PREFIX/'lib/xfce4/notifyd/xfce4-notifyd')],
            [str(launcher),'serve']]

def session(work):
    state=json.loads((work/'session.json').read_text())
    if not (Path('/proc')/str(state['supervisor'])).exists():
        raise RuntimeError('Desktop is not running')
    return state

def ipc(work,*args):
    state=session(work)
    executable=next((Path(p)/'hyprctl' for p in state['env']['PATH'].split(':') if (Path(p)/'hyprctl').is_file()),None)
    if executable is None:raise RuntimeError('Hyprland IPC missing')
    return subprocess.check_output([str(executable),*args],env=state['env'],text=True,timeout=4)

def battery(path,now=None):
    try:
        data=json.loads(path.read_text());age=(time.time() if now is None else now)-data['updated']/1000
        if not 0<=age<=120 or type(data['percent']) is not int or not 0<=data['percent']<=100:raise ValueError()
        return ('󰂄 ' if data.get('charging') else '󰁹 ')+str(data['percent'])+'%'
    except (OSError,ValueError,KeyError,TypeError):return 'Battery —'

def task_markup(work,clients,active):
    buttons=[]
    width = int(os.environ.get('AQ_ANDROID_WIDTH', '1280'))
    limit = 2 if width >= 1100 else (1 if width >= 900 else 0)
    for c in clients:
        address=c.get('address','')
        if not c.get('mapped') or not re.fullmatch(r'0x[0-9a-fA-F]+',address):continue
        # Limit width; all remaining windows remain reachable in the launcher task list.
        if len(buttons)>=limit:break
        title=' '.join(str(c.get('title') or c.get('class') or 'Window').split())
        title=''.join(ch for ch in title if ch.isprintable())
        clipped=''; columns=0
        for ch in title:
            columns += 2 if unicodedata.east_asian_width(ch) in ('W','F') else 1
            if columns > 13:
                clipped += '…'
                break
            clipped += ch
        title=clipped
        buttons.append('(button :class '+json.dumps('task active' if address==active else 'task')+' :timeout "3s" :onclick '+json.dumps(command(work,'focus',address))+' '+json.dumps(title,ensure_ascii=False)+')')
    return '(box :space-evenly false :spacing 4 '+' '.join(buttons)+')'

def apps(work,run_mode):
    result=[dict(name='Terminal',description='Foot · Fish · Starship' if run_mode=='native' else 'Container terminal',icon='utilities-terminal',command=[str(work/'bin/termux-hyprland-terminal')]),dict(name='Files',description='File manager',icon='system-file-manager',command=[str(work/'bin/termux-hyprland-files')])]
    if run_mode=='native':
        seen=set()
        for directory in [HOME/'.local/share/applications',PREFIX/'share/applications']:
            for path in sorted(directory.glob('*.desktop')):
                cfg=configparser.ConfigParser(interpolation=None,strict=False)
                try:
                    cfg.read(path);item=cfg['Desktop Entry']
                    if item.get('Type')!='Application' or any(item.getboolean(k,fallback=False) for k in ('Hidden','NoDisplay')):continue
                    name=item.get('Name','').strip()
                    if not name or name in seen:continue
                    seen.add(name)
                    result.append(dict(name=name,description=item.get('Comment','Application')[:90],icon=item.get('Icon','application-x-executable'),command=[str(PREFIX/'bin/gio'),'launch',str(path)]))
                except (OSError,ValueError,configparser.Error,KeyError):continue
    return result

# Search input is handled by GTK events, without shell substitution.
def dialog(work, kind):
    session(work)
    dialog_lock = (work / 'dialog.lock').open('w')
    try:
        fcntl.flock(dialog_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        dialog_lock.close()
        return
    focused = json.loads(ipc(work, '-j', 'activewindow')).get('address') if kind == 'settings' else None
    import gi
    gi.require_version('Gtk', '3.0')
    gi.require_version('GtkLayerShell', '0.1')
    from gi.repository import Gtk, Gdk, GtkLayerShell
    Gtk.init([])
    window = Gtk.Window(title='Hyprland desktop')
    window.set_default_size(600, 520)
    # Layer-shell ignores the normal toplevel default size; request the dialog size.
    window.set_size_request(600, 520)
    window.set_app_paintable(True)
    visual = window.get_screen().get_rgba_visual()
    if visual:
        window.set_visual(visual)
    GtkLayerShell.init_for_window(window)
    GtkLayerShell.set_namespace(window, 'm3ww-dialog')
    GtkLayerShell.set_layer(window, GtkLayerShell.Layer.OVERLAY)
    GtkLayerShell.set_keyboard_mode(window, GtkLayerShell.KeyboardMode.EXCLUSIVE)
    provider = Gtk.CssProvider()
    provider.load_from_data(b'''window { background: transparent; } #surface { background: rgba(20,24,43,.98); border-radius: 22px; border: 1px solid #9b8dca; padding: 22px; color: #eee5ff; } * { font-family: Sans; font-size: 14px; color: #eee5ff; } #heading { font-size: 24px; font-weight: bold; color: #d0bdff; } #muted { color: #a5abc9; font-size: 12px; } entry { background: #2a2e49; border-radius: 14px; padding: 14px; border: 1px solid #5a577e; } button { background: transparent; border-radius: 12px; padding: 10px; min-height: 24px; } button:hover { background: #353954; } calendar { padding: 20px; }''')
    Gtk.StyleContext.add_provider_for_screen(window.get_screen(), provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
    window.connect('destroy', lambda *_: Gtk.main_quit())
    def key(_, event):
        if event.keyval == Gdk.KEY_Escape:
            window.destroy()
            return True
        return False
    window.connect('key-press-event', key)
    root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
    root.set_name('surface')
    window.add(root)
    def label(text, style=None, parent=root):
        widget = Gtk.Label(label=text, xalign=0)
        widget.set_line_wrap(True)
        if style:
            widget.set_name(style)
        parent.pack_start(widget, False, False, 0)
        return widget
    def button(text, action, parent=root):
        widget = Gtk.Button(label=text)
        widget.connect('clicked', lambda *_: action())
        parent.pack_start(widget, False, False, 0)
        return widget
    header = Gtk.Box(spacing=8)
    root.pack_start(header, False, False, 0)
    label('Applications' if kind == 'launcher' else ('Your desktop' if kind == 'settings' else datetime.datetime.now().strftime('%A, %d %B')), 'heading', header)
    button('×', window.destroy, header)
    theme = work / 'config/themes' / VERSION
    if kind == 'launcher':
        label('Search your apps · switch windows · make it yours', 'muted')
        entry = Gtk.SearchEntry(placeholder_text='Search applications…')
        root.pack_start(entry, False, False, 0)
        scroll = Gtk.ScrolledWindow()
        scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        scroll.set_min_content_height(300)
        root.pack_start(scroll, True, True, 0)
        listing = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        scroll.add(listing)
        items = apps(work, (theme / 'run-mode').read_text())
        rows = []
        def launch(item):
            subprocess.Popen(item['command'], cwd=str(HOME), close_fds=True)
            window.destroy()
        for item in items:
            row = Gtk.Button()
            rowbox = Gtk.Box(spacing=14)
            rowbox.pack_start(Gtk.Image.new_from_icon_name(item['icon'], Gtk.IconSize.DIALOG), False, False, 0)
            text = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=3)
            label(item['name'], parent=text)
            label(item['description'], 'muted', text)
            rowbox.pack_start(text, True, True, 0)
            row.add(rowbox)
            row.connect('clicked', lambda _, item=item: launch(item))
            listing.pack_start(row, False, False, 0)
            rows.append((row, item))
        def search(*_):
            query = entry.get_text().casefold()
            for row, item in rows:
                row.set_visible(query in (item['name'] + ' ' + item['description']).casefold())
        entry.connect('search-changed', search)
        def accept(*_):
            query = entry.get_text().casefold()
            for row, item in rows:
                if query in (item['name'] + ' ' + item['description']).casefold():
                    launch(item)
                    break
        entry.connect('activate', accept)
        clients = json.loads(ipc(work, '-j', 'clients'))
        for client in clients:
            address = client.get('address', '')
            if client.get('mapped') and re.fullmatch(r'0x[0-9a-fA-F]+', address):
                title = (client.get('title') or client.get('class', 'Window'))[:65]
                row = button('↗ ' + title, lambda a=address: (dispatch(work, 'focus', a), window.destroy()), listing)
                rows.append((row, dict(name=title, description='Window',
                                       command=[str(work / 'bin/termux-hyprland-theme'), 'focus', address])))
        window.show_all()
        entry.grab_focus()
    elif kind == 'dashboard':
        label(datetime.datetime.now().strftime('%H:%M'), 'heading')
        label(battery(HOME.parent / 'front/desktop/state/android-battery.json'), 'muted')
        root.pack_start(Gtk.Calendar(), True, True, 0)
        window.show_all()
    else:
        label('m3ww · Midnight sky', 'muted')
        def pair(left, right):
            row = Gtk.Box(spacing=10, homogeneous=True)
            root.pack_start(row, False, False, 0)
            button(*left, parent=row)
            button(*right, parent=row)
        pair(('Open terminal', lambda: (subprocess.Popen([str(work / 'bin/termux-hyprland-terminal')], cwd=str(HOME)), window.destroy())),
             ('Open file manager', lambda: (subprocess.Popen([str(work / 'bin/termux-hyprland-files')], cwd=str(HOME)), window.destroy())))
        pair(('Toggle floating window', lambda: (dispatch(work, 'float', focused), window.destroy())),
             ('Close focused window', lambda: (dispatch(work, 'close', focused), window.destroy())))
        def restart_notice(title):
            subprocess.Popen([str(PREFIX / 'bin/notify-send'), title, 'Stop and reopen this desktop to apply.'])
            window.destroy()
        def choose(value):
            subprocess.Popen([str(PREFIX / 'bin/notify-send'), 'Desktop appearance',
                              'Stop the desktop and choose its theme in App settings.'])
            restart_notice('Desktop appearance')
        def effect(value):
            (theme / 'effects').write_text(value + '\n')
            restart_notice('Desktop effects')
        pair(('Balanced effects', lambda: effect('balanced')),
             ('Try soft blur', lambda: effect('blur')))
        label('8-bit output · blur depends on your device', 'muted')
        button('Restore r7 classic desktop', lambda: choose('r7'))
        label('Theme changes apply after stopping and reopening this desktop.', 'muted')
        window.show_all()
    Gtk.main()
    dialog_lock.close()

def dispatch(work,action,value=None):
    if action=='workspace':
        number=int(value)
        if number not in range(1,5):raise ValueError('Invalid workspace')
        expr='hl.dsp.focus({ workspace = '+str(number)+' })'
    elif action=='focus':
        if not re.fullmatch(r'0x[0-9a-fA-F]+',value or ''):raise ValueError('Invalid window address')
        expr='hl.dsp.focus({ window = '+json.dumps('address:'+value)+' })'
    elif action in ('float', 'close'):
        clients = json.loads(ipc(work, '-j', 'clients'))
        if value is None:
            value = json.loads(ipc(work, '-j', 'activewindow')).get('address')
        if not re.fullmatch(r'0x[0-9a-fA-F]+', value or ''):
            return 'No focused window'
        client = next((c for c in clients if c['address'] == value and c.get('mapped')), None)
        if client is None:
            return 'Window already closed'
        selector = json.dumps('address:' + value)
        if action == 'close':
            return ipc(work, 'dispatch', 'hl.dsp.window.close({ window = ' + selector + ' })')
        result = ipc(work, 'dispatch', 'hl.dsp.window.float({ action = "toggle", window = ' + selector + ' })')
        if not client.get('floating'):
            # Foot's preferred size can exceed the screen when first floated.
            monitors = json.loads(ipc(work, '-j', 'monitors'))
            monitor = next(m for m in monitors if m['id'] == client['monitor'])
            width, height = int(monitor['width'] * .72), int(monitor['height'] * .65)
            ipc(work, 'dispatch', 'hl.dsp.window.resize({ x = '+str(width)+', y = '+str(height)+', window = '+selector+' })')
            ipc(work, 'dispatch', 'hl.dsp.window.center({ window = '+selector+' })')
        return result
    else:raise ValueError('Unknown desktop action')
    return ipc(work,'dispatch',expr)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--work',type=Path,required=True);parser.add_argument('action',choices=['serve','watch','launcher','dashboard','settings','workspace','focus','float','close']);parser.add_argument('value',nargs='?');a=parser.parse_args();work=a.work
    if work.parent!=HOME/'.termux' or not re.fullmatch(r'hyprland-desktop-[A-Za-z0-9._-]+',work.name):parser.error('Invalid desktop path')
    theme=work/'config/themes'/VERSION
    if a.action=='serve':
        cmd=[str(PREFIX/'bin/eww'),'--config',str(theme)]
        # Foreground daemon stays under the session supervisor; no stale detached server.
        process=subprocess.Popen([*cmd,'daemon','--force-wayland','--no-daemonize'])
        try:
            for _ in range(60):
                if process.poll() is not None:raise RuntimeError('EWW daemon exited')
                result=subprocess.run([*cmd,'open','bar'],capture_output=True,text=True)
                if result.returncode==0:break
                time.sleep(.1)
            else:raise RuntimeError('EWW bar did not open')
            raise SystemExit(process.wait())
        finally:
            process.terminate()
            try:process.wait(timeout=3)
            except subprocess.TimeoutExpired:process.kill();process.wait()
    elif a.action=='watch':
        while True:
            try:
                clients=json.loads(ipc(work,'-j','clients'));active=json.loads(ipc(work,'-j','activewindow'));workspace=json.loads(ipc(work,'-j','activeworkspace'))
                value=dict(workspace=workspace['id'],tasks=task_markup(work,clients,active.get('address')),battery=battery(HOME.parent/'front/desktop/state/android-battery.json'),clock=datetime.datetime.now().strftime('%H:%M'),date=datetime.datetime.now().strftime('%A · %d %B %Y'),count=len(clients))
                print(json.dumps(value,ensure_ascii=False),flush=True)
            except (OSError,ValueError,KeyError,RuntimeError,subprocess.SubprocessError):
                if not (work/'session.json').exists():return
            time.sleep(1)
    elif a.action in ('launcher','dashboard','settings'):dialog(work,a.action)
    else:print(dispatch(work,a.action,a.value))

if __name__=='__main__':
    try:main()
    except (OSError,ValueError,KeyError,RuntimeError,subprocess.SubprocessError) as error:raise SystemExit('Hyprland theme: '+str(error))
