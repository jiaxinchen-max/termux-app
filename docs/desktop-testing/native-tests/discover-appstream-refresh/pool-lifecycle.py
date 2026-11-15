"""Isolated real AppStream C pool; synthetic metadata, no production cache access."""
import ctypes as C
from pathlib import Path
import tempfile

lib = C.CDLL('libappstream.so.5')
obj = C.CDLL('libgobject-2.0.so.0')
def api(name, result, *args):
    fn = getattr(lib, name); fn.restype = result; fn.argtypes = args
    return fn
ptr, integer, string = C.c_void_p, C.c_int, C.c_char_p
new = api('as_pool_new', ptr)
std = api('as_pool_set_load_std_data_locations', None, ptr, integer)
extra = api('as_pool_add_extra_data_location', None, ptr, string, integer)
cache = api('as_pool_override_cache_locations', None, ptr, string, string)
flags = api('as_pool_add_flags', None, ptr, integer)
load = api('as_pool_load', integer, ptr, ptr, C.POINTER(ptr))
components = api('as_pool_get_components', ptr, ptr)
size = api('as_component_box_get_size', C.c_uint, ptr)
obj.g_object_unref.argtypes = [ptr]
def count(pool):
    box = components(pool)
    try: return size(box)
    finally: obj.g_object_unref(box)
with tempfile.TemporaryDirectory(prefix='isolated-appstream-') as tmp:
    source = Path(tmp) / 'xml'; source.mkdir()
    cachedir = Path(tmp) / 'cache'; cachedir.mkdir()
    pools = []
    def pool(force=False):
        p = new(); pools.append(p)
        std(p, 0)
        extra(p, str(source).encode(), 2)  # AS_FORMAT_STYLE_CATALOG
        cache(p, str(cachedir).encode(), str(cachedir).encode())
        if force: flags(p, 1 << 4)  # IGNORE_CACHE_AGE
        error = ptr()
        assert load(p, None, C.byref(error)), 'real AppStream load failed'
        return p
    old = pool()
    assert count(old) == 0
    (source / 'fixture.xml').write_text('''<?xml version="1.0"?><components version="1.0" origin="isolated-fixture"><component type="desktop-application"><id>org.kde.kcalc.desktop</id><name>KCalc</name><summary>Scientific Calculator</summary><metadata_license>CC0-1.0</metadata_license><pkgname>kcalc</pkgname><launchable type="desktop-id">org.kde.kcalc.desktop</launchable><categories><category>Utility</category></categories></component></components>''')
    producer = pool(force=True)
    assert count(producer) == 1
    assert count(old) == 0, 'old pool unexpectedly reloaded'
    first_after_preparation = pool()
    assert count(first_after_preparation) == 1
    print('PASS real AppStream library: initial=0; old-after-publication=0; first-after-preparation=1', flush=True)
    print('Synthetic catalog + private cache only; no real PackageKit transaction or GUI race replay.', flush=True)
    for p in reversed(pools): obj.g_object_unref(p)
