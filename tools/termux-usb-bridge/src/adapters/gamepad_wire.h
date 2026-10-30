/* SPDX-License-Identifier: MIT */
#include "termux_usb_gamepad.h"
#define TUSB_GAMEPAD_WIRE_SIZE 48
static void gp_u32(unsigned char *p,uint32_t value) {for(unsigned i=0;i<4;i++)p[i]=(unsigned char)(value>>(24-8*i));}
static uint32_t gp_read_u32(const unsigned char *p) {return (uint32_t)p[0]<<24|(uint32_t)p[1]<<16|(uint32_t)p[2]<<8|p[3];}
static void gp_encode(unsigned char *p,const struct tusb_gamepad_state *s) {
    memset(p,0,48);memcpy(p,"TGP1",4);gp_u32(p+4,s->sequence);gp_u32(p+8,s->connected);gp_u32(p+12,s->buttons);
    for(unsigned i=0;i<8;i++){p[16+2*i]=(unsigned char)((uint16_t)s->axes[i]>>8);p[17+2*i]=(unsigned char)s->axes[i];}gp_u32(p+32,s->hat);
}
static int gp_decode(const unsigned char *p,struct tusb_gamepad_state *s) {
    if(memcmp(p,"TGP1",4))return -1;s->sequence=gp_read_u32(p+4);s->connected=gp_read_u32(p+8);s->buttons=gp_read_u32(p+12);
    for(unsigned i=0;i<8;i++)s->axes[i]=(int16_t)((unsigned)p[16+2*i]<<8|p[17+2*i]);s->hat=gp_read_u32(p+32);return 0;
}
