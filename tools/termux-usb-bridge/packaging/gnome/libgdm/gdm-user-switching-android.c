/* SPDX-License-Identifier: GPL-2.0-or-later */
#include <gio/gio.h>
#include "gdm-user-switching.h"
gboolean gdm_goto_login_session_sync(GCancellable *cancellable, GError **error)
{
    (void)cancellable;
    g_set_error_literal(error, G_IO_ERROR, G_IO_ERROR_NOT_SUPPORTED,
                        "Android does not provide a GDM login session");
    return FALSE;
}
