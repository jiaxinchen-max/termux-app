/* Read-only XI2 device declaration probe; does not select events or grab. */
#include <X11/Xlib.h>
#include <X11/extensions/XInput2.h>
#include <stdio.h>
#include <stdlib.h>
int main(void) {
    if(!getenv("DISPLAY")){fprintf(stderr,"DISPLAY must be explicit\n");return 2;}
    Display *d=XOpenDisplay(NULL);if(!d){fprintf(stderr,"cannot open DISPLAY\n");return 3;}
    int major=2,minor=4,n=0;
    if(XIQueryVersion(d,&major,&minor)!=Success){fprintf(stderr,"XI2 unavailable\n");return 3;}
    printf("DISPLAY=%s vendor=%s release=%d XI=%d.%d\n",DisplayString(d),ServerVendor(d),VendorRelease(d),major,minor);
    XIDeviceInfo *devices=XIQueryDevice(d,XIAllDevices,&n);
    for(int i=0;i<n;i++) {
        XIDeviceInfo *v=&devices[i];printf("device=%d name=%s use=%d attachment=%d enabled=%d\n",v->deviceid,v->name,v->use,v->attachment,v->enabled);
        for(int j=0;j<v->num_classes;j++) {
            XIAnyClassInfo *c=v->classes[j];
            if(c->type==XITouchClass){XITouchClassInfo *t=(XITouchClassInfo*)c;printf(" touch sourceid=%d mode=%d (%s) num_touches=%d\n",t->sourceid,t->mode,t->mode==XIDirectTouch?"direct":"dependent",t->num_touches);}
            if(c->type==XIValuatorClass){XIValuatorClassInfo *a=(XIValuatorClassInfo*)c;char *label=a->label?XGetAtomName(d,a->label):NULL;printf(" axis=%d label=%s mode=%d min=%g max=%g resolution=%d\n",a->number,label?label:"<none>",a->mode,a->min,a->max,a->resolution);if(label)XFree(label);}
        }
    }
    XIFreeDeviceInfo(devices);XCloseDisplay(d);return 0;
}
