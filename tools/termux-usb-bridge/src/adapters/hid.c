/* SPDX-License-Identifier: MIT */
#include "termux_usb_gamepad.h"
#include <errno.h>
#include <limits.h>
#include <string.h>

struct global { uint32_t page, size, count, report; int32_t minimum, maximum; };
static int bad(void) {errno=EINVAL;return -1;}
int tusb_hid_parse(const void *descriptor,size_t length,struct tusb_hid_descriptor *parsed)
{
    if(!descriptor || !parsed || !length || length>65536) return bad();
    memset(parsed,0,sizeof(*parsed));
    const uint8_t *bytes=descriptor;
    struct global g={0},stack[8];unsigned stack_count=0;
    uint32_t offsets[256]={0},usages[64],usage_min=0,usage_max=0;unsigned usage_count=0;
    int collections[32]={0};unsigned depth=0;
    for(size_t pos=0;pos<length;) {
        uint8_t prefix=bytes[pos++];
        if(prefix==0xfe) {if(pos+2>length || pos+2+bytes[pos]>length) return bad();pos+=2+bytes[pos];continue;}
        unsigned n=prefix&3;if(n==3) n=4;
        if(pos+n>length) return bad();
        uint32_t value=0;for(unsigned i=0;i<n;i++) value|=(uint32_t)bytes[pos+i]<<(8*i);
        int32_t signed_value=(int32_t)value;
        if(n && n<4 && (value&(1u<<(n*8-1)))) signed_value=(int32_t)(value|(~0u<<(n*8)));
        pos+=n;unsigned type=(prefix>>2)&3,tag=prefix>>4;
        if(type==1) {
            switch(tag) {
            case 0:g.page=value;break;case 1:g.minimum=signed_value;break;
            case 2:g.maximum=g.minimum<0?signed_value:(value>INT32_MAX?INT32_MAX:(int32_t)value);break;
            case 7:if(value>32) return bad();g.size=value;break;
            case 8:if(!value || value>255) return bad();g.report=value;parsed->report_ids=1;break;
            case 9:if(value>4096) return bad();g.count=value;break;
            case 10:if(stack_count==8) return bad();stack[stack_count++]=g;break;
            case 11:if(!stack_count) return bad();g=stack[--stack_count];break;
            default:break;
            }
        } else if(type==2) {
            uint32_t usage=n==4?value:(g.page<<16)|value;
            if(tag==0) {if(usage_count==64) return bad();usages[usage_count++]=usage;}
            else if(tag==1) usage_min=usage;
            else if(tag==2) usage_max=usage;
        } else if(type==0) {
            if(tag==10) {
                if(depth==31) return bad();
                uint32_t usage=usage_count?usages[0]:usage_min;
                collections[depth+1]=collections[depth] || (value==1 && ((usage>>16)==1) && ((usage&65535)==4 || (usage&65535)==5));depth++;
            } else if(tag==12) {if(!depth) return bad();depth--;}
            else if(tag==8) {
                if(g.size*g.count>32768 || offsets[g.report]>32768-g.size*g.count) return bad();
                for(uint32_t i=0;i<g.count;i++) {
                    uint32_t usage=i<usage_count?usages[i]:(usage_min && usage_min+i<=usage_max?usage_min+i:0);
                    uint16_t kind=0,index=0;unsigned page=usage>>16,u=usage&65535;
                    if(collections[depth] && !(value&1) && (value&2) && !(value&4) && g.size) {
                        if(page==9 && u>=1 && u<=32) {kind=1;index=(uint16_t)(u-1);}
                        else if(page==1 && u>=0x30 && u<=0x37 && g.maximum>g.minimum) {kind=2;index=(uint16_t)(u-0x30);}
                        else if(page==1 && u==0x39) kind=3;
                    }
                    if(kind) {
                        if(parsed->count==TUSB_HID_FIELDS) return bad();
                        parsed->fields[parsed->count++]=(struct tusb_hid_field){offsets[g.report]+i*g.size,g.minimum,g.maximum,(uint16_t)g.report,(uint16_t)g.size,kind,index};
                    }
                }
                offsets[g.report]+=g.size*g.count;
            }
            usage_count=0;usage_min=usage_max=0;
        }
    }
    if(depth || stack_count || !parsed->count) {errno=ENOTSUP;return -1;}
    return 0;
}
int tusb_hid_report(const struct tusb_hid_descriptor *parsed,const void *report,size_t length,struct tusb_gamepad_state *state)
{
    if(!parsed || !report || !state || !length) return bad();
    const uint8_t *data=report;unsigned id=0;
    if(parsed->report_ids) {id=*data++;length--;}
    int found=0;
    /* Validate the full report before changing any state. */
    for(size_t i=0;i<parsed->count;i++) {
        const struct tusb_hid_field *f=&parsed->fields[i];
        if(f->report==id && (uint64_t)f->offset+f->bits>(uint64_t)length*8) return bad();
    }
    for(size_t i=0;i<parsed->count;i++) {
        const struct tusb_hid_field *f=&parsed->fields[i];if(f->report!=id) continue;found=1;
        uint32_t raw=0;
        for(unsigned bit=0;bit<f->bits;bit++) if(data[(f->offset+bit)/8]&(1u<<((f->offset+bit)%8))) raw|=1u<<bit;
        int64_t value=raw;
        if(f->minimum<0 && f->bits<32 && (raw&(1u<<(f->bits-1)))) value=(int32_t)(raw|(~0u<<f->bits));
        else if(f->minimum<0 && f->bits==32) value=(int32_t)raw;
        if(f->kind==1) {
            uint32_t mask=UINT32_C(1)<<f->index;if(value) state->buttons|=mask;else state->buttons&=~mask;
        } else if(f->kind==2) {
            if(value<f->minimum) value=f->minimum;if(value>f->maximum) value=f->maximum;
            state->axes[f->index]=(int16_t)(((value-f->minimum)*65535)/((int64_t)f->maximum-f->minimum)-32768);
        } else if(f->kind==3) {
            static const uint8_t hats[8]={1,3,2,6,4,12,8,9};
            int64_t position=value-f->minimum;
            if(f->maximum-f->minimum==3) position*=2;
            state->hat=position>=0 && position<8?hats[position]:0;
        }
    }
    if(!found) {errno=ENODATA;return -1;}
    state->connected=1;state->sequence++;return 0;
}
