#!/usr/bin/env python3
"""Execute the actual joy.cpl enum callback with controlled DirectInput HRESULTs.

Usage: test-enumeration.py <original dinput.c> <patched dinput.c>
This validates the callback, not Android input delivery or a complete Windows game.
"""
import subprocess,sys,tempfile
from pathlib import Path
STUBS=r'''
#include <assert.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
typedef int BOOL;
typedef int32_t HRESULT;
typedef void IDirectInput8W;
typedef struct { int guidInstance; } DIDEVICEINSTANCEW;
typedef struct { unsigned dwSize,dwHeaderSize,dwObj,dwHow; } DIPROPHEADER;
typedef struct { DIPROPHEADER diph; unsigned dwData; } DIPROPDWORD;
struct list { int dummy; };
struct device { void *device; struct list entry; };
static struct list devices;
static int c_dfDIJoystick2,dialog_hwnd;
static int calls[4],added,released;
static HRESULT results[4];
static struct device *retained;
#define CALLBACK
#define SUCCEEDED(hr) ((hr)>=0)
#define DIPH_DEVICE 0
#define DIPROPAUTOCENTER_OFF 0
#define DIPROP_AUTOCENTER 9
#define GA_ROOT 2
#define DISCL_BACKGROUND 1
#define DISCL_EXCLUSIVE 2
#define DIENUM_STOP 0
#define DIENUM_CONTINUE 1
static HRESULT create(void **p) {calls[0]++; if(SUCCEEDED(results[0])) *p=(void*)1;return results[0];}
static HRESULT step(int i) {calls[i]++;return results[i];}
#define IDirectInput8_CreateDevice(a,b,p,n) ((void)(a),(void)(b),create(p))
#define IDirectInputDevice8_SetDataFormat(p,f) ((void)(f),step(1))
#define GetAncestor(h,f) (h)
#define IDirectInputDevice8_SetCooperativeLevel(p,h,f) ((void)(h),step(2))
#define IDirectInputDevice8_SetProperty(p,id,prop) ((void)(prop),step(3))
#define IDirectInputDevice8_Release(p) (released++)
static void list_add_tail(struct list *l,struct list *e) {
 (void)l;added++;retained=(struct device*)((char*)e-__builtin_offsetof(struct device,entry));
}
'''
MAIN=r'''
int main(void) {
 DIDEVICEINSTANCEW instance={0};
 /* All success; AUTOCENTER unsupported; each of the three required steps fails. */
 for(int scenario=0;scenario<5;scenario++) {
  added=released=0;retained=NULL;
  for(int i=0;i<4;i++){calls[i]=0;results[i]=0;}
  if(scenario==1) results[3]=(int32_t)0x80004001; /* DIERR_UNSUPPORTED / E_NOTIMPL */
  if(scenario>=2) results[scenario-2]=(int32_t)0x80004005;
  assert(enum_devices(&instance,NULL)==DIENUM_CONTINUE);
  int expected=scenario==0 || (scenario==1 && FIXED);
  assert(added==expected);
  for(int i=0;i<4;i++) assert(calls[i]==(scenario<2 || i<=scenario-2));
  assert(released==(!expected && scenario!=2));
  free(retained);
 }
 puts("PASS: five enumeration scenarios");
 return 0;
}
'''
assert len(sys.argv)==3, __doc__
for fixed,path in enumerate(sys.argv[1:]):
 source=Path(path).read_text()
 start=source.index('static BOOL CALLBACK enum_devices(')
 end=source.index('\nstatic void clear_devices(',start)
 with tempfile.TemporaryDirectory() as tmp:
  p=Path(tmp);(p/'test.c').write_text(STUBS+source[start:end]+MAIN)
  subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror',f'-DFIXED={fixed}','-fsanitize=address,undefined','-o',str(p/'test'),str(p/'test.c')],check=True)
  subprocess.run([str(p/'test')],check=True)
