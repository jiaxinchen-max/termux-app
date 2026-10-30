import ctypes,sys
from ctypes import c_void_p as P,c_char_p as S,c_uint as U,c_int as I
x=ctypes.CDLL('libxmlb.so.2');gio=ctypes.CDLL('libgio-2.0.so.0');g=ctypes.CDLL('libglib-2.0.so.0')
class Error(ctypes.Structure):_fields_=[('domain',U),('code',I),('message',S)]
class Array(ctypes.Structure):_fields_=[('pdata',ctypes.POINTER(P)),('len',U)]
def setup(lib,name,args,result):
 f=getattr(lib,name);f.argtypes=args;f.restype=result;return f
new=setup(x,'xb_silo_new',[],P);file=setup(gio,'g_file_new_for_path',[S],P)
load=setup(x,'xb_silo_load_from_file',[P,P,U,P,ctypes.POINTER(P)],I)
query=setup(x,'xb_silo_query',[P,S,U,ctypes.POINTER(P)],ctypes.POINTER(Array))
text=setup(x,'xb_node_get_text',[P],S)
export=setup(x,'xb_node_export',[P,U,ctypes.POINTER(P)],S)
s=new();e=P();ok=load(s,file(sys.argv[1].encode()),0,None,ctypes.byref(e));print('loaded',ok)
if not ok:
 print(ctypes.cast(e,ctypes.POINTER(Error)).contents.message);sys.exit(1)
for expr in ['components/component/id',"components/component/id[text()='org.kde.kcalc.desktop']/..","components/component/id[text()='org.kde.kate.desktop']/.."]:
 e=P();a=query(s,expr.encode(),0,ctypes.byref(e))
 if not a:
  print(expr,'error',ctypes.cast(e,ctypes.POINTER(Error)).contents.message if e else None);continue
 print(expr,'count',a.contents.len)
 if expr.endswith('/id'):
  ids=[text(a.contents.pdata[i]) for i in range(a.contents.len)]
  print('KCalc ID present',b'org.kde.kcalc.desktop' in ids,'Kate ID present',b'org.kde.kate.desktop' in ids)
 else:
  for i in range(min(a.contents.len,1)):print(export(a.contents.pdata[i],0,None).decode()[:1000])
