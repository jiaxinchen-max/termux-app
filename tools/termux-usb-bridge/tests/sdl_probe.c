/* SPDX-License-Identifier: MIT */
#ifdef TUSB_SDL3
#include <SDL3/SDL.h>
#else
#include <SDL.h>
#endif
#include <stdio.h>
int main(void)
{
    /* Run twice to exercise detach/re-init, not just first attachment. */
    for(int iteration=0;iteration<2;iteration++) {
#ifdef TUSB_SDL3
        if(!SDL_Init(SDL_INIT_GAMEPAD)){fprintf(stderr,"SDL_Init: %s\n",SDL_GetError());return 1;}
        int count=0;SDL_JoystickID *ids=SDL_GetJoysticks(&count);
        SDL_Joystick *joystick=count?SDL_OpenJoystick(ids[0]):NULL;SDL_free(ids);
#else
        if(SDL_Init(SDL_INIT_GAMECONTROLLER)<0){fprintf(stderr,"SDL_Init: %s\n",SDL_GetError());return 1;}
        SDL_Joystick *joystick=SDL_NumJoysticks()?SDL_JoystickOpen(0):NULL;
#endif
        if(!joystick){fprintf(stderr,"virtual gamepad not found: %s\n",SDL_GetError());return 1;}
        SDL_PumpEvents();
#ifdef TUSB_SDL3
        SDL_UpdateJoysticks();
        int a=SDL_GetJoystickAxis(joystick,0),b=SDL_GetJoystickAxis(joystick,1);
        int buttons=SDL_GetJoystickButton(joystick,0)|(SDL_GetJoystickButton(joystick,1)<<1);
        int hat=SDL_GetJoystickHat(joystick,0);
        SDL_CloseJoystick(joystick);
#else
        SDL_JoystickUpdate();
        int a=SDL_JoystickGetAxis(joystick,0),b=SDL_JoystickGetAxis(joystick,1);
        int buttons=SDL_JoystickGetButton(joystick,0)|(SDL_JoystickGetButton(joystick,1)<<1);
        int hat=SDL_JoystickGetHat(joystick,0);
        SDL_JoystickClose(joystick);
#endif
        printf("iteration=%d axes=%d,%d buttons=%d hat=%d\n",iteration,a,b,buttons,hat);
        if(a!=-32768 || b!=32767 || buttons!=3 || hat!=2){fprintf(stderr,"incorrect bridged state\n");return 1;}
        SDL_Quit();
    }
    return 0;
}
