/* SPDX-License-Identifier: MIT */
#define _GNU_SOURCE
#ifdef TUSB_SDL3
#include <SDL3/SDL.h>
#define INIT_RETURN bool
#define INIT_FLAGS SDL_InitFlags
#define REAL_UPDATE SDL_UpdateJoysticks
#define CLOSE SDL_CloseJoystick
#define DETACH SDL_DetachVirtualJoystick
#define SET_AXIS SDL_SetJoystickVirtualAxis
#define SET_BUTTON SDL_SetJoystickVirtualButton
#define SET_HAT SDL_SetJoystickVirtualHat
#else
#include <SDL.h>
#define INIT_RETURN int
#define INIT_FLAGS Uint32
#define REAL_UPDATE SDL_JoystickUpdate
#define CLOSE SDL_JoystickClose
#define DETACH SDL_JoystickDetachVirtual
#define SET_AXIS SDL_JoystickSetVirtualAxis
#define SET_BUTTON SDL_JoystickSetVirtualButton
#define SET_HAT SDL_JoystickSetVirtualHat
#endif
#include "termux_usb_gamepad.h"
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

static pthread_mutex_t mutex=PTHREAD_MUTEX_INITIALIZER;
static _Thread_local int inside;
static SDL_Joystick *joystick;
static uint64_t next_poll;
#ifdef TUSB_SDL3
static SDL_JoystickID attached;
#else
static int attached=-1;
#endif
static void reset(void)
{
    if(joystick && SDL_WasInit(SDL_INIT_JOYSTICK)) {CLOSE(joystick);DETACH(attached);}
    joystick=NULL;
}

/* Called on the application's SDL thread, never on a background thread. */
void tusb_sdl_poll(void)
{
    const char *address=getenv("TERMUX_USB_GAMEPAD_SOCKET");
    if(inside || !address || !*address || !SDL_WasInit(SDL_INIT_JOYSTICK))return;
    struct timespec now;clock_gettime(CLOCK_MONOTONIC,&now);
    uint64_t tick=(uint64_t)now.tv_sec*1000+(uint64_t)now.tv_nsec/1000000;
    inside=1;pthread_mutex_lock(&mutex);
    if(tick<next_poll)goto out;next_poll=tick+16;
    struct tusb_gamepad_state state;
    if(tusb_gamepad_snapshot(address,&state) || !state.connected) {reset();next_poll=tick+500;goto out;}
    if(!joystick) {
        SDL_VirtualJoystickDesc descriptor;
#ifdef TUSB_SDL3
        SDL_INIT_INTERFACE(&descriptor);
        descriptor.type=SDL_JOYSTICK_TYPE_GAMEPAD;
#else
        SDL_zero(descriptor);descriptor.version=SDL_VIRTUAL_JOYSTICK_DESC_VERSION;
        descriptor.type=SDL_JOYSTICK_TYPE_GAMECONTROLLER;
#endif
        descriptor.naxes=8;descriptor.nbuttons=32;descriptor.nhats=1;
        descriptor.name="Termux USB gamepad";descriptor.vendor_id=0x1209;descriptor.product_id=0x5455;
        descriptor.button_mask=0xffff;descriptor.axis_mask=0x3f;
#ifdef TUSB_SDL3
        attached=SDL_AttachVirtualJoystick(&descriptor);if(!attached)goto out;
        joystick=SDL_OpenJoystick(attached);
#else
        attached=SDL_JoystickAttachVirtualEx(&descriptor);if(attached<0)goto out;
        joystick=SDL_JoystickOpen(attached);
#endif
        if(!joystick) {DETACH(attached);goto out;}
        char guid[33],mapping[1024];
#ifdef TUSB_SDL3
        SDL_GUIDToString(SDL_GetJoystickGUID(joystick),guid,sizeof(guid));
#else
        SDL_JoystickGetGUIDString(SDL_JoystickGetGUID(joystick),guid,sizeof(guid));
#endif
        snprintf(mapping,sizeof(mapping),"%s,Termux USB gamepad,a:b0,b:b1,x:b2,y:b3,back:b6,start:b7,guide:b8,leftstick:b9,rightstick:b10,leftshoulder:b4,rightshoulder:b5,leftx:a0,lefty:a1,rightx:a3,righty:a4,lefttrigger:a2,righttrigger:a5,dpup:h0.1,dpright:h0.2,dpdown:h0.4,dpleft:h0.8,",guid);
#ifdef TUSB_SDL3
        SDL_AddGamepadMapping(mapping);
#else
        SDL_GameControllerAddMapping(mapping);
#endif
    }
    for(unsigned i=0;i<8;i++)SET_AXIS(joystick,(int)i,state.axes[i]);
    for(unsigned i=0;i<32;i++)SET_BUTTON(joystick,(int)i,(state.buttons>>i)&1);
    SET_HAT(joystick,0,(Uint8)state.hat);
out:pthread_mutex_unlock(&mutex);inside=0;
}

INIT_RETURN SDL_Init(INIT_FLAGS flags)
{
    INIT_RETURN (*function)(INIT_FLAGS)=dlsym(RTLD_NEXT,"SDL_Init");
    INIT_RETURN result=function(flags);tusb_sdl_poll();return result;
}
INIT_RETURN SDL_InitSubSystem(INIT_FLAGS flags)
{
    INIT_RETURN (*function)(INIT_FLAGS)=dlsym(RTLD_NEXT,"SDL_InitSubSystem");
    INIT_RETURN result=function(flags);tusb_sdl_poll();return result;
}
void SDL_PumpEvents(void)
{
    void (*function)(void)=dlsym(RTLD_NEXT,"SDL_PumpEvents");tusb_sdl_poll();function();
}
void REAL_UPDATE(void)
{
#ifdef TUSB_SDL3
    void (*function)(void)=dlsym(RTLD_NEXT,"SDL_UpdateJoysticks");
#else
    void (*function)(void)=dlsym(RTLD_NEXT,"SDL_JoystickUpdate");
#endif
    tusb_sdl_poll();function();
}
void SDL_QuitSubSystem(INIT_FLAGS flags)
{
    void (*function)(INIT_FLAGS)=dlsym(RTLD_NEXT,"SDL_QuitSubSystem");
    if(!inside && (flags&SDL_INIT_JOYSTICK)) {inside=1;pthread_mutex_lock(&mutex);reset();next_poll=0;pthread_mutex_unlock(&mutex);inside=0;}
    function(flags);
}
void SDL_Quit(void)
{
    void (*function)(void)=dlsym(RTLD_NEXT,"SDL_Quit");
    if(!inside) {inside=1;pthread_mutex_lock(&mutex);reset();next_poll=0;pthread_mutex_unlock(&mutex);inside=0;}
    function();
}
