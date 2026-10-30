/* SPDX-License-Identifier: MIT
 * Android has no logind sessions. Explicit unsupported results, never invented
 * seats/sessions or successful authentication. Private compile compatibility.
 */
#pragma once
#include <errno.h>
#include <stddef.h>
#include <sys/types.h>
typedef struct sd_login_monitor sd_login_monitor;
#define TUSB_LOGIND_STRING(name, type) \
static inline int name(type value, char **result) { \
    (void)value; if (result) *result = NULL; return -ENOSYS; }
TUSB_LOGIND_STRING(sd_pid_get_user_unit, pid_t)
TUSB_LOGIND_STRING(sd_pid_get_session, pid_t)
TUSB_LOGIND_STRING(sd_uid_get_display, uid_t)
TUSB_LOGIND_STRING(sd_session_get_class, const char *)
TUSB_LOGIND_STRING(sd_session_get_display, const char *)
TUSB_LOGIND_STRING(sd_session_get_seat, const char *)
TUSB_LOGIND_STRING(sd_session_get_state, const char *)
TUSB_LOGIND_STRING(sd_session_get_type, const char *)
#undef TUSB_LOGIND_STRING
static inline int sd_session_get_uid(const char *session, uid_t *uid) {
    (void)session; if (uid) *uid = (uid_t)-1; return -ENOSYS;
}
static inline int sd_seat_can_multi_session(const char *seat) {
    (void)seat; return -ENOSYS;
}
static inline int sd_get_sessions(char ***sessions) {
    if (sessions) *sessions = NULL; return -ENOSYS;
}
static inline int sd_uid_get_sessions(uid_t uid, int active, char ***sessions) {
    (void)uid; (void)active; return sd_get_sessions(sessions);
}
static inline int sd_login_monitor_new(const char *category, sd_login_monitor **monitor) {
    (void)category; if (monitor) *monitor = NULL; return -ENOSYS;
}
static inline int sd_login_monitor_get_fd(sd_login_monitor *monitor) {
    (void)monitor; return -ENOSYS;
}
static inline int sd_login_monitor_flush(sd_login_monitor *monitor) {
    (void)monitor; return -ENOSYS;
}
static inline sd_login_monitor *sd_login_monitor_unref(sd_login_monitor *monitor) {
    (void)monitor; return NULL;
}
