#!/usr/bin/python3
"""Independent PackageKit event fixture; no host/production PackageKit connection."""
import gi
from gi.repository import Gio,GLib
DAEMON='org.freedesktop.PackageKit';TX=DAEMON+'.Transaction';CONTROL='org.termux.PackageKitFixture'
xml='''<node><interface name="org.freedesktop.PackageKit">
<method name="CreateTransaction"><arg type="o" direction="out"/></method>
<method name="GetTimeSinceAction"><arg type="u" direction="in"/><arg type="u" direction="out"/></method>
<property name="VersionMajor" type="u" access="read"/><property name="VersionMinor" type="u" access="read"/><property name="VersionMicro" type="u" access="read"/>
<property name="BackendName" type="s" access="read"/><property name="BackendDescription" type="s" access="read"/><property name="BackendAuthor" type="s" access="read"/>
<property name="Roles" type="t" access="read"/><property name="Groups" type="t" access="read"/><property name="Filters" type="t" access="read"/><property name="MimeTypes" type="as" access="read"/>
<property name="Locked" type="b" access="read"/><property name="NetworkState" type="u" access="read"/><property name="DistroId" type="s" access="read"/>
</interface><interface name="org.termux.PackageKitFixture"><method name="Pending"><arg type="u" direction="out"/></method><method name="Release"><arg type="b" direction="in"/></method></interface></node>'''
txxml='''<node><interface name="org.freedesktop.PackageKit.Transaction">
<method name="SetHints"><arg type="as" direction="in"/></method><method name="RefreshCache"><arg type="b" direction="in"/></method><method name="GetUpdates"><arg type="t" direction="in"/></method><method name="GetRepoList"><arg type="t" direction="in"/></method><method name="Resolve"><arg type="t" direction="in"/><arg type="as" direction="in"/></method>
<signal name="Finished"><arg type="u"/><arg type="u"/></signal><signal name="ErrorCode"><arg type="u"/><arg type="s"/></signal><signal name="Package"><arg type="u"/><arg type="s"/><arg type="s"/></signal>
<property name="Role" type="u" access="read"/><property name="Status" type="u" access="read"/><property name="Percentage" type="u" access="read"/><property name="AllowCancel" type="b" access="read"/><property name="CallerActive" type="b" access="read"/><property name="ElapsedTime" type="u" access="read"/><property name="RemainingTime" type="u" access="read"/><property name="Speed" type="u" access="read"/><property name="DownloadSizeRemaining" type="t" access="read"/><property name="TransactionFlags" type="t" access="read"/><property name="LastPackage" type="s" access="read"/>
</interface></node>'''
node=Gio.DBusNodeInfo.new_for_xml(xml);txnode=Gio.DBusNodeInfo.new_for_xml(txxml)
connection=Gio.bus_get_sync(Gio.BusType.SESSION,None);state={'next':0,'pending':[]};objects=[]
def emit(path,name,signature,values):connection.emit_signal(None,path,TX,name,GLib.Variant(signature,values))
def finish(path,success=True):
 if not success:emit(path,'ErrorCode','(us)',(1,'fixture refresh failure retained'))
 emit(path,'Finished','(uu)',(1 if success else 2,1));return False

def prop(c,sender,path,interface,name):
 if interface==DAEMON:
  values={'VersionMajor':('u',1),'VersionMinor':('u',4),'VersionMicro':('u',0),'BackendName':('s','apt'),'BackendDescription':('s','Independent fixture'),'BackendAuthor':('s','Test fixture'),'Roles':('t',8371765246),'Groups':('t',0),'Filters':('t',83890516),'MimeTypes':('as',[]),'Locked':('b',False),'NetworkState':('u',0),'DistroId':('s','')}
  sig,v=values[name];return GLib.Variant(sig,v)
 sig='b' if name in ('AllowCancel','CallerActive') else 't' if name in ('DownloadSizeRemaining','TransactionFlags') else 's' if name=='LastPackage' else 'u'
 return GLib.Variant(sig,False if sig=='b' else '' if sig=='s' else 0)

def call(c,sender,path,interface,method,parameters,inv):
 values=parameters.unpack();
 if method!='Pending':print(path,method,values,flush=True)
 if interface==CONTROL:
  if method=='Pending':inv.return_value(GLib.Variant('(u)',(len(state['pending']),)))
  elif method=='Release':
   pending=state['pending'];state['pending']=[]
   for p in pending:finish(p,values[0])
   inv.return_value(GLib.Variant('()',()))
  return
 if method=='CreateTransaction':
  state['next']+=1;p='/org/freedesktop/PackageKit/transactions/t'+str(state['next']);objects.append(connection.register_object(p,txnode.interfaces[0],call,prop,None));inv.return_value(GLib.Variant('(o)',(p,)));return
 if method=='GetTimeSinceAction':inv.return_value(GLib.Variant('(u)',(7200,)));return
 inv.return_value(GLib.Variant('()',()))
 if method=='RefreshCache':state['pending'].append(path)
 elif method=='Resolve':
  for pkg in values[1]:emit(path,'Package','(uss)',(2,pkg+';1;arm64;fixture','Fixture package'))
  GLib.timeout_add(20,finish,path)
 elif method in ('GetUpdates','GetRepoList'):GLib.timeout_add(20,finish,path)
for interface in node.interfaces:objects.append(connection.register_object('/org/freedesktop/PackageKit',interface,call,prop,None))
reply=connection.call_sync('org.freedesktop.DBus','/org/freedesktop/DBus','org.freedesktop.DBus','RequestName',GLib.Variant('(su)',(DAEMON,4)),GLib.VariantType.new('(u)'),Gio.DBusCallFlags.NONE,-1,None);assert reply.unpack()[0]==1
print('READY',flush=True);GLib.MainLoop().run()
