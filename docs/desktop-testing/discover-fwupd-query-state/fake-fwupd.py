#!/usr/bin/python3
"""Independent GDBus fwupd fixture. It never connects to the host system bus."""
import gi
from gi.repository import Gio, GLib

xml = '''<node>
<interface name="org.freedesktop.fwupd">
 <method name="GetDevices"><arg type="aa{sv}" direction="out"/></method>
 <method name="GetRemotes"><arg type="aa{sv}" direction="out"/></method>
 <property name="DaemonVersion" type="s" access="read"/>
 <property name="Status" type="u" access="read"/>
 <property name="Percentage" type="u" access="read"/>
 <property name="Tainted" type="b" access="read"/>
</interface>
<interface name="org.termux.FwupdFixture">
 <method name="Arm"><arg type="s" direction="in"/></method>
 <method name="Pending"><arg type="u" direction="out"/></method>
 <method name="Release"/>
</interface>
</node>'''
connection = Gio.bus_get_sync(Gio.BusType.SESSION, None)
state = dict(mode='error', hold=True, pending=[])

def respond(invocation):
    mode = state['mode']
    if mode == 'error':
        invocation.return_dbus_error('org.freedesktop.fwupd.NotFound', 'fixture GetDevices failure retained')
    elif mode == 'no-devices':
        invocation.return_dbus_error('org.freedesktop.fwupd.NothingToDo', 'No detected devices')
    elif mode == 'empty':
        invocation.return_value(GLib.Variant('(aa{sv})', ([],)))
    elif mode == 'nonempty':
        device = {'DeviceId': GLib.Variant('s', '0123456789012345678901234567890123456789'),
                  'Name': GLib.Variant('s', 'Fixture Unsupported Device'),
                  'Flags': GLib.Variant('t', 0), 'Version': GLib.Variant('s', '1')}
        invocation.return_value(GLib.Variant('(aa{sv})', ([device],)))
    else:
        raise AssertionError(mode)

def call(connection, sender, path, interface, method, parameters, invocation):
    print(interface, method, state['mode'], flush=True)
    if interface == 'org.termux.FwupdFixture':
        if method == 'Arm':
            assert not state['pending']
            mode = parameters.unpack()[0]
            assert mode in ['error', 'no-devices', 'empty', 'nonempty']
            state.update(mode=mode, hold=True)
            invocation.return_value(GLib.Variant('()', ()))
        elif method == 'Pending':
            invocation.return_value(GLib.Variant('(u)', (len(state['pending']),)))
        elif method == 'Release':
            pending = state['pending'];state['pending'] = []
            for held in pending: respond(held)
            invocation.return_value(GLib.Variant('()', ()))
    elif method == 'GetRemotes':
        invocation.return_value(GLib.Variant('(aa{sv})', ([],)))
    elif method == 'GetDevices':
        if state['hold']:
            state['hold'] = False;state['pending'].append(invocation)
        else:
            respond(invocation)
    else:
        invocation.return_dbus_error('org.freedesktop.DBus.Error.UnknownMethod', method)

def prop(connection, sender, path, interface, name):
    return {'DaemonVersion': GLib.Variant('s', '2.1.8'), 'Status': GLib.Variant('u', 0),
            'Percentage': GLib.Variant('u', 0), 'Tainted': GLib.Variant('b', False)}[name]

node = Gio.DBusNodeInfo.new_for_xml(xml)
for interface in node.interfaces:
    connection.register_object('/', interface, call, prop, None)
reply = connection.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus',
                             'RequestName', GLib.Variant('(su)', ('org.freedesktop.fwupd', 4)),
                             GLib.VariantType.new('(u)'), Gio.DBusCallFlags.NONE, -1, None)
assert reply.unpack()[0] == 1, reply
print('READY', flush=True)
GLib.MainLoop().run()
