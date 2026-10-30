/* Read-only X11 extension probe; run against an already App-started session. */
#include <X11/Xlib.h>
#include <stdio.h>
#include <string.h>

int main(int argc, char **argv)
{
    Display *display;
    int opcode = 0, first_event = 0, first_error = 0;
    int present, expected;

    if (argc != 3 || (strcmp(argv[2], "present") && strcmp(argv[2], "absent"))) {
        fprintf(stderr, "usage: %s EXTENSION present|absent (uses DISPLAY)\n", argv[0]);
        return 2;
    }
    expected = !strcmp(argv[2], "present");
    display = XOpenDisplay(NULL);
    if (!display) {
        fprintf(stderr, "cannot open DISPLAY=%s\n", XDisplayName(NULL));
        return 3;
    }
    present = XQueryExtension(display, argv[1], &opcode, &first_event, &first_error);
    printf("DISPLAY=%s extension=%s present=%d opcode=%d first_event=%d first_error=%d\n",
           DisplayString(display), argv[1], present != 0, opcode, first_event, first_error);
    XCloseDisplay(display);
    return (present != 0) == expected ? 0 : 1;
}
