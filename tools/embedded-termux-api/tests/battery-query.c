#include "../upower/termux-battery-query.h"
static GMainLoop *loop;
static int ticks;
static gboolean tick(gpointer unused) { ticks++; return G_SOURCE_CONTINUE; }
static void complete(const gchar *json, gpointer data) {
    gboolean expect_success = GPOINTER_TO_INT(data);
    g_assert_cmpint(json != NULL, ==, expect_success);
    if (json) g_assert_nonnull(strstr(json, "percentage"));
    else g_assert_cmpint(ticks, >, 5);
    g_main_loop_quit(loop);
}
int main(int argc, char **argv) {
    g_assert_cmpint(argc, ==, 3);
    loop = g_main_loop_new(NULL, FALSE);
    guint timer = g_timeout_add(10, tick, NULL);
    termux_battery_query(argv[1], 200, complete, GINT_TO_POINTER(atoi(argv[2])), NULL);
    g_main_loop_run(loop);
    g_source_remove(timer); g_main_loop_unref(loop);
    return 0;
}
