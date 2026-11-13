/* GPL-2.0-or-later. Async Android battery query; no blocking work on the D-Bus main loop. */
#include <gio/gio.h>
#include <signal.h>
#include <unistd.h>

typedef void (*TermuxBatteryResult) (const gchar *json, gpointer user_data);
typedef struct {
    GSubprocess *process;
    guint deadline;
    TermuxBatteryResult result;
    gpointer user_data;
    GDestroyNotify destroy;
} TermuxBatteryQuery;

static void termux_battery_child_setup (gpointer unused) { setsid (); }

static gboolean termux_battery_deadline (gpointer user_data)
{
    TermuxBatteryQuery *query = user_data;
    query->deadline = 0;
    /* Kill the script, native API client and am helper as one private group. */
    const gchar *identifier = g_subprocess_get_identifier (query->process);
    if (identifier) {
        pid_t pid = (pid_t) g_ascii_strtoll (identifier, NULL, 10);
        if (pid > 1) kill (-pid, SIGKILL);
    }
    g_subprocess_force_exit (query->process);
    return G_SOURCE_REMOVE;
}

static void termux_battery_finished (GObject *object, GAsyncResult *result, gpointer user_data)
{
    TermuxBatteryQuery *query = user_data;
    g_autofree gchar *output = NULL;
    g_autoptr(GError) error = NULL;
    gboolean success = g_subprocess_communicate_utf8_finish (query->process, result, &output, NULL, &error);
    if (query->deadline) g_source_remove (query->deadline);
    if (success && g_subprocess_get_successful (query->process) && output && strlen (output) <= 16384)
        query->result (output, query->user_data);
    else
        query->result (NULL, query->user_data);
    if (query->destroy) query->destroy (query->user_data);
    g_object_unref (query->process);
    g_free (query);
}

static gboolean termux_battery_query (const gchar *command, guint timeout_ms,
                                     TermuxBatteryResult result, gpointer user_data, GDestroyNotify destroy)
{
    g_autoptr(GSubprocessLauncher) launcher = g_subprocess_launcher_new (
        G_SUBPROCESS_FLAGS_STDOUT_PIPE | G_SUBPROCESS_FLAGS_STDERR_SILENCE);
    g_subprocess_launcher_set_child_setup (launcher, termux_battery_child_setup, NULL, NULL);
    g_autoptr(GError) error = NULL;
    GSubprocess *process = g_subprocess_launcher_spawn (launcher, &error, command, NULL);
    if (!process) {
        result (NULL, user_data);
        if (destroy) destroy (user_data);
        return FALSE;
    }
    TermuxBatteryQuery *query = g_new0 (TermuxBatteryQuery, 1);
    query->process = process;
    query->result = result;
    query->user_data = user_data;
    query->destroy = destroy;
    query->deadline = g_timeout_add (timeout_ms, termux_battery_deadline, query);
    g_subprocess_communicate_utf8_async (process, NULL, NULL, termux_battery_finished, query);
    return TRUE;
}
