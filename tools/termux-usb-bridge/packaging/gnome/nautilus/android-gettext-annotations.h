/* SPDX-License-Identifier: MIT
 * The NDK's inline gettext stubs return their input, but omit format_arg.
 * Add the declarations used by GNU gettext so Clang can check translated
 * format literals without weakening Nautilus's format-security warnings.
 */
#pragma once
static __inline__ char *gettext(const char *) __attribute__((format_arg(1)));
static __inline__ char *dgettext(const char *, const char *) __attribute__((format_arg(2)));
static __inline__ char *dcgettext(const char *, const char *, int) __attribute__((format_arg(2)));
static __inline__ char *ngettext(const char *, const char *, unsigned long) __attribute__((format_arg(1), format_arg(2)));
static __inline__ char *dngettext(const char *, const char *, const char *, unsigned long) __attribute__((format_arg(2), format_arg(3)));
static __inline__ char *dcngettext(const char *, const char *, const char *, unsigned long, int) __attribute__((format_arg(2), format_arg(3)));
#include <libintl.h>
