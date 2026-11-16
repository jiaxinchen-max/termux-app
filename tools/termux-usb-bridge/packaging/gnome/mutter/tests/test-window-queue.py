#!/usr/bin/env python3
"""Compile actual Mutter visibility/queue functions with reentrant callbacks.

--source must point at Mutter 50.1, optionally patched. Requires C11 and host
GLib headers. Display/window callbacks are explicit test doubles; this does not
exercise a GPU. Original 50.1 and the incomplete caller-only fix must fail.
"""
import argparse
from pathlib import Path
import re
import shlex
import subprocess
import tempfile


def function(source, name):
    match = re.search(r'(?:static )?void\n' + name + r' \([^\{]+\n\{', source)
    if not match:
        raise ValueError('Missing upstream function: ' + name)
    return source[match.start():source.index('\n}\n', match.end()) + 3]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    window = (args.source / 'src/core/window.c').read_text()
    display = (args.source / 'src/core/display.c').read_text()
    functions = '\n'.join(function(window, name) for name in (
        'meta_window_clear_queued', 'meta_window_unqueue',
        'meta_window_flush_calc_showing', 'meta_window_queue'))
    functions += function(display, 'update_window_visibilities')
    functions += function(display, 'meta_display_flush_queued_window')
    fixture = r'''
#include <assert.h>
#include <glib.h>
#include <stdio.h>
typedef unsigned MetaQueueType;
enum { META_QUEUE_CALC_SHOWING = 1, META_QUEUE_MOVE_RESIZE = 2, META_N_QUEUE_TYPES = 2 };
typedef struct { unsigned queued_types; } MetaWindowPrivate;
typedef struct { GList *pending; unsigned flushes;
                 gboolean requeue_signal, reenter_shown; void *stack; } MetaDisplay;
typedef struct { MetaWindowPrivate priv; MetaDisplay *display;
                 gboolean override_redirect, unmanaging, placed; } MetaWindow;
#define COGL_TRACE_BEGIN_SCOPED(...)
#define COGL_TRACE_BEGIN(...)
#define COGL_TRACE_END(...)
#define meta_topic(...)
#define g_signal_emit(d,...) test_visibility_updated(d)
static MetaWindow *test_window;
static MetaWindowPrivate *meta_window_get_instance_private(MetaWindow *w)
{ return &w->priv; }
static void warn_on_incorrectly_unmanaged_window(MetaWindow *w)
{ assert(!w->unmanaging); }
static gboolean meta_window_should_be_showing(MetaWindow *w) { return TRUE; }
static int meta_window_stack_position_compare(const void *a, const void *b) { return 0; }
static void meta_stack_freeze(void *s) { }
static void meta_stack_thaw(void *s) { }
static void meta_display_queue_window(MetaDisplay *d, MetaWindow *w, unsigned types)
{
  assert(types == META_QUEUE_CALC_SHOWING);
  assert(!g_list_find(d->pending, w));
  d->pending = g_list_append(d->pending, w);
}
static void meta_display_unqueue_window(MetaDisplay *d, MetaWindow *w, unsigned types)
{ assert(types == META_QUEUE_CALC_SHOWING); d->pending = g_list_remove(d->pending, w); }
static void meta_display_flush_queued_window(MetaDisplay *, MetaWindow *, unsigned);
static void update_window_visibilities(MetaDisplay *, GList *);
static void meta_window_update_visibility(MetaWindow *);
static void test_visibility_updated(MetaDisplay *);
typedef void (*WindowQueueFunc)(MetaDisplay *, GList *);
static const WindowQueueFunc window_queue_func[META_N_QUEUE_TYPES] = {
  update_window_visibilities, NULL
};
'''
    fixture += functions + r'''
static void test_visibility_updated(MetaDisplay *display)
{
  if (display->requeue_signal) {
    display->requeue_signal = FALSE;
    meta_window_queue(test_window, META_QUEUE_CALC_SHOWING);
  }
}
static void meta_window_update_visibility(MetaWindow *window)
{
  MetaDisplay *display = window->display;
  display->flushes++;
  if (display->reenter_shown) {
    display->reenter_shown = FALSE;
    meta_window_flush_calc_showing(window);
    meta_window_queue(window, META_QUEUE_CALC_SHOWING);
  }
}
static void destroy_and_check(MetaWindow *window)
{
  window->unmanaging = TRUE;
  meta_window_unqueue(window, META_QUEUE_CALC_SHOWING | META_QUEUE_MOVE_RESIZE);
  assert(!window->display->pending && "destroyed window remained queued after reentrant update");
  assert(window->priv.queued_types == 0);
  window->unmanaging = FALSE;
}
int main(void)
{
  MetaDisplay display = {0};
  MetaWindow window = {.display = &display};
  test_window = &window;
  meta_window_flush_calc_showing(&window);
  assert(display.flushes == 0);
  meta_window_queue(&window, META_QUEUE_CALC_SHOWING);
  meta_window_flush_calc_showing(&window);
  assert(display.flushes == 1 && !display.pending && !window.priv.queued_types);
  /* A signal emitted after visibility update queues a new task. */
  display.requeue_signal = TRUE;
  meta_window_queue(&window, META_QUEUE_CALC_SHOWING);
  meta_window_flush_calc_showing(&window);
  assert(display.pending);
  destroy_and_check(&window);
  /* A shown callback reenters synchronous flush before the outer update ends.
   * Removing only the caller's redundant clear does not fix this case. */
  display.reenter_shown = TRUE;
  meta_window_queue(&window, META_QUEUE_CALC_SHOWING);
  GList *batch = g_steal_pointer(&display.pending);
  update_window_visibilities(&display, batch);
  g_list_free(batch);
  assert(display.pending);
  destroy_and_check(&window);
  puts("PASS: idle/consumed work, signal requeue, nested shown flush, destruction cleanup");
}
'''
    with tempfile.TemporaryDirectory(prefix='mutter-queue-test-') as directory:
        root = Path(directory)
        (root / 'test.c').write_text(fixture)
        flags = shlex.split(subprocess.check_output(
            ['pkg-config', '--cflags', '--libs', 'glib-2.0'], text=True))
        subprocess.run(['cc', '-std=c11', '-Wall', '-Werror', str(root / 'test.c'),
                        '-o', str(root / 'test')] + flags, check=True)
        subprocess.run([str(root / 'test')], check=True)


if __name__ == '__main__':
    main()
