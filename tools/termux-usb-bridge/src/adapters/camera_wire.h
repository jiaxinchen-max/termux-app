/* SPDX-License-Identifier: MIT */
#ifndef TUSB_CAMERA_WIRE_H
#define TUSB_CAMERA_WIRE_H
#include <stdint.h>
#include <string.h>
#define TUSB_FRAME_HEADER 32
static inline void tusb_put32(uint8_t *p,uint32_t v){for(unsigned i=0;i<4;i++)p[i]=(uint8_t)(v>>(8*i));}
static inline void tusb_put64(uint8_t *p,uint64_t v){for(unsigned i=0;i<8;i++)p[i]=(uint8_t)(v>>(8*i));}
static inline uint32_t tusb_get32(const uint8_t *p){uint32_t v=0;for(unsigned i=0;i<4;i++)v|=(uint32_t)p[i]<<(8*i);return v;}
static inline uint64_t tusb_get64(const uint8_t *p){uint64_t v=0;for(unsigned i=0;i<8;i++)v|=(uint64_t)p[i]<<(8*i);return v;}
/* TCF1, RGB24 dimensions/length, original monotonic camera timestamp, sequence. */
static inline void tusb_frame_header(uint8_t *p,unsigned width,unsigned height,uint64_t stamp,uint64_t seq){
    memcpy(p,"TCF1",4);tusb_put32(p+4,width);tusb_put32(p+8,height);tusb_put32(p+12,width*height*3);
    tusb_put64(p+16,stamp);tusb_put64(p+24,seq);
}
#endif
