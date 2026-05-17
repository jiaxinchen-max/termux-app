#include "include/tlog.h"
#include <stdio.h>
#include <stdarg.h>
#include <time.h>
#include <string.h>
#include <errno.h>
static const char* level_names[] = {
        "EMERG", "ALERT", "CRIT", "ERROR", "WARN", "NOTICE", "INFO", "DEBUG"
};
static int current_level = LOG_DEBUG;

void tlog(int priority, const char *format, ...) {
    if (priority > current_level)
        return;

    time_t t = time(NULL);
    struct tm tm_info;
    localtime_r(&t, &tm_info);
    char ts[20];
    strftime(ts, sizeof(ts), "%Y-%m-%d %H:%M:%S", &tm_info);

    fprintf(stderr, "[%s] %-6s: ", ts, level_names[priority]);

    va_list args;
    va_start(args, format);
    vfprintf(stderr, format, args);
    va_end(args);

    fprintf(stderr, "\n");
}

void tlog_set_level(int level) {
    current_level = level;
}
