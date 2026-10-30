/* SPDX-License-Identifier: MIT
 * Android is not booted with systemd and has neither NOTIFY_SOCKET nor journald.
 * Notification returns 0 (no socket); journal operations explicitly fail.
 */
#pragma once
#include <errno.h>
#include <syslog.h>
static inline int sd_booted(void) { return 0; }
static inline int sd_notify(int unset_environment, const char *state) {
    (void)unset_environment; (void)state; return 0;
}
static inline int sd_notifyf(int unset_environment, const char *format, ...) {
    (void)unset_environment; (void)format; return 0;
}
static inline int sd_journal_stream_fd(const char *identifier, int priority, int level_prefix) {
    (void)identifier; (void)priority; (void)level_prefix; return -ENOSYS;
}
static inline int sd_journal_send(const char *format, ...) {
    (void)format; return -ENOSYS;
}
