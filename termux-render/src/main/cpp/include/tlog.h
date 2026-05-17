#ifndef TLOG_H
#define TLOG_H

#define LOG_EMERG   0
#define LOG_ALERT   1
#define LOG_CRIT    2
#define LOG_ERR     3
#define LOG_WARNING 4
#define LOG_NOTICE  5
#define LOG_INFO    6
#define LOG_DEBUG   7

void tlog(int priority, const char *format, ...) ;

void tlog_set_level(int level);

#endif /* TLOG_H */
