import ctypes as c,json,os,fcntl,struct
# Diagnostic only: never interpret an arbitrary native_handle fd as a dma-buf.
P=c.c_void_p; I=c.c_int; U=c.c_uint
ns=c.CDLL('libtermux-platform-ns.so');ns.platform_dlopen.argtypes=[c.c_char_p,I];ns.platform_dlopen.restype=P
h=ns.platform_dlopen(b'/data/data/com.termux/files/usr/lib/mutter-16/libvndksupport.so',2)
print('VNDK',h,flush=True)
h=ns.platform_dlopen(b'/system/lib64/libEGL.so',2)
e=c.CDLL('egl',handle=h)
def f(name,restype,args):
 x=getattr(e,name);x.restype=restype;x.argtypes=args;return x
get=f('eglGetDisplay',P,[P]);init=f('eglInitialize',U,[P,c.POINTER(I),c.POINTER(I)]);query=f('eglQueryString',c.c_char_p,[P,I]);error=f('eglGetError',I,[])
d=get(None);a=I();b=I();ok=init(d,c.byref(a),c.byref(b))
print('EGL',ok,hex(error()),a.value,b.value,flush=True)
result={'initialized':bool(ok),'vendor':query(d,0x3053).decode() if ok else None,'extensions':query(d,0x3055).decode().split() if ok else [],'client_extensions':(query(None,0x3055) or b'').decode().split()}
print(json.dumps(result,indent=2),flush=True)
# Inspect the allocator's exported handle without assuming every fd is a dma-buf.
android=c.CDLL('libandroid.so')
class Desc(c.Structure):_fields_=[('width',U),('height',U),('layers',U),('format',U),('usage',c.c_uint64),('stride',U),('rfu0',U),('rfu1',c.c_uint64)]
alloc=android.AHardwareBuffer_allocate;alloc.argtypes=[c.POINTER(Desc),c.POINTER(P)]
desc=Desc(64,64,1,1,(1<<8)|(1<<9),0,0,0);buf=P();ret=alloc(c.byref(desc),c.byref(buf));print('AHB_ALLOC',ret,flush=True)
if ret==0:
 describe=android.AHardwareBuffer_describe;describe.argtypes=[P,c.POINTER(Desc)];describe(buf,c.byref(desc));print('AHB_STRIDE',desc.stride,flush=True)
 handle=android.AHardwareBuffer_getNativeHandle;handle.argtypes=[P];handle.restype=c.POINTER(I)
 data=handle(buf);print('AHB_HANDLE',data[0],data[1],data[2],flush=True)
 for n in range(data[1]):
  fd=data[3+n]
  print('AHB_FD',fd,os.readlink('/proc/self/fd/'+str(fd)),flush=True)
  print(open('/proc/self/fdinfo/'+str(fd)).read(),flush=True)
  try:
   fcntl.ioctl(fd,0x40086200,struct.pack('Q',3))
   fcntl.ioctl(fd,0x40086200,struct.pack('Q',7))
   print('DMA_BUF_IOCTL_SYNC supported',flush=True)
  except OSError as error:print('DMA_BUF_IOCTL_SYNC',error.errno,error.strerror,flush=True)
 release=android.AHardwareBuffer_release;release.argtypes=[P];release(buf)
print('DEVICES',[{'path':p,'exists':os.path.exists(p),'read_write':os.access(p,os.R_OK|os.W_OK)} for p in ['/dev/dri/renderD128','/dev/kgsl-3d0','/dev/goldfish_pipe','/dev/dma_heap/system']],flush=True)
