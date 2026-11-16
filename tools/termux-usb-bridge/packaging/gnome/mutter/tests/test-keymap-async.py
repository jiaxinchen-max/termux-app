#!/usr/bin/env python3
"""Exercise the actual Android keymap request code with GLib's main loop.

XKB/seat operations are explicit doubles. This checks signal ordering, request
ownership, cancellation and failed compilation, not keyboard layout contents.
The former synchronous implementation must fail the ordering assertion.
"""
import argparse
from pathlib import Path
import shlex
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=Path(__file__).resolve().parents[1]
                        / 'android/meta-backend-android.c')
    args = parser.parse_args()
    source = args.source.read_text()
    start = source.find('typedef struct\n{\n  MetaKeymapDescription *description;')
    if start < 0:
        start = source.index('static void backend_set_keymap_async(')
    source = source[start:source.index('static void backend_pointer_constraint', start)]
    fixture = r'''
#include <gio/gio.h>
#include <assert.h>
typedef GObject MetaBackend;
typedef unsigned xkb_layout_index_t;
typedef struct { unsigned refs; gboolean invalid; } MetaKeymapDescription;
struct xkb_keymap { int unused; };
struct xkb_state { unsigned index; };
typedef struct {
  struct xkb_keymap *xkb_keymap;
  struct xkb_state *xkb_state;
  MetaKeymapDescription *description;
  void *keymap;
} AndroidSeat;
static AndroidSeat seat;
static gboolean caller_active;
static unsigned signals, completed, freed_descriptions;
static AndroidSeat *backend_get_seat(MetaBackend *backend) { return &seat; }
static MetaKeymapDescription *meta_keymap_description_ref(MetaKeymapDescription *d)
{ d->refs++; return d; }
static void meta_keymap_description_unref(MetaKeymapDescription *d)
{ assert(d->refs); if (!--d->refs) { freed_descriptions++; g_free(d); } }
static struct xkb_keymap *meta_keymap_description_create_xkb_keymap(
  MetaKeymapDescription *d, void *a, void *b, GError **error)
{
  assert(d->refs);
  if (d->invalid) { g_set_error_literal(error, G_IO_ERROR, G_IO_ERROR_FAILED,
                                      "invalid test keymap"); return NULL; }
  return g_new0(struct xkb_keymap, 1);
}
static struct xkb_state *xkb_state_new(struct xkb_keymap *map)
{ return g_new0(struct xkb_state, 1); }
static void xkb_state_update_mask(struct xkb_state *s, int a, int b, int c,
                                  int d, int e, unsigned index) { s->index = index; }
static void xkb_keymap_unref(struct xkb_keymap *map) { g_free(map); }
static void xkb_state_unref(struct xkb_state *state) { g_free(state); }
static void keymap_signal(void)
{ assert(!caller_active && "keymap signal reentered input-source selection"); signals++; }
#define g_signal_emit_by_name(...) keymap_signal()
static void meta_backend_notify_keymap_changed(MetaBackend *b) { keymap_signal(); }
static void meta_backend_notify_keymap_layout_group_changed(MetaBackend *b, unsigned i)
{ assert(seat.xkb_state->index == i); keymap_signal(); }
'''
    fixture += source + r'''
static void done(GObject *source, GAsyncResult *result, gpointer data)
{
  GError *error = NULL;
  gboolean success = g_task_propagate_boolean(G_TASK(result), &error);
  int expected = GPOINTER_TO_INT(data);
  assert(!caller_active);
  if (expected == -1) { assert(success && !error); }
  else { assert(!success); assert(g_error_matches(error, G_IO_ERROR, expected)); }
  g_clear_error(&error);
  completed++;
}
static void submit(MetaBackend *backend, gboolean invalid, gboolean cancel)
{
  MetaKeymapDescription *description = g_new0(MetaKeymapDescription, 1);
  description->refs = 1; description->invalid = invalid;
  GCancellable *cancellable = g_cancellable_new();
  int expected = cancel ? G_IO_ERROR_CANCELLED : invalid ? G_IO_ERROR_FAILED : -1;
  GTask *task = g_task_new(backend, cancellable, done, GINT_TO_POINTER(expected));
  if (cancel) g_cancellable_cancel(cancellable);
  caller_active = TRUE;
  backend_set_keymap_async(backend, description, 2, task);
  meta_keymap_description_unref(description);
  caller_active = FALSE;
  g_object_unref(cancellable);
}
int main(void)
{
  MetaBackend *backend = g_object_new(G_TYPE_OBJECT, NULL);
  gpointer weak_backend = backend;
  g_object_add_weak_pointer(backend, &weak_backend);
  submit(backend, FALSE, FALSE);
  submit(backend, FALSE, TRUE);
  submit(backend, TRUE, FALSE);
  assert(signals == 0 && completed == 0 && freed_descriptions == 0);
  g_object_unref(backend);
  assert(weak_backend && "pending task lost its backend");
  while (completed < 3) g_main_context_iteration(NULL, TRUE);
  while (g_main_context_iteration(NULL, FALSE)) { }
  assert(signals == 3 && seat.xkb_state->index == 2);
  assert(freed_descriptions == 2 && !weak_backend);
  meta_keymap_description_unref(seat.description);
  xkb_state_unref(seat.xkb_state);
  xkb_keymap_unref(seat.xkb_keymap);
  assert(freed_descriptions == 3);
  g_print("Android keymap ordering, ownership, cancellation and error: PASS\n");
}
'''
    with tempfile.TemporaryDirectory(prefix='mutter-keymap-') as directory:
        path = Path(directory)
        (path / 'test.c').write_text(fixture)
        flags = shlex.split(subprocess.check_output(
            ['pkg-config', '--cflags', '--libs', 'gio-2.0'], text=True))
        subprocess.run(['cc', '-std=c11', '-Werror=implicit-function-declaration',
                        str(path / 'test.c'), '-o', str(path / 'test'), *flags], check=True)
        subprocess.run([str(path / 'test')], check=True)


if __name__ == '__main__':
    main()
