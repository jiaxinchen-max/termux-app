#!/usr/bin/env python3
"""Run setup against a reusable image and a fresh image, checking payload lifetimes."""
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import sys

ASSETS = Path(__file__).resolve().parents[1] / "src/main/assets/local-games"


def write(path, data, executable=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(data)
    if executable:
        path.chmod(0o700)


def ready(root):
    for name in ("usr/bin/env", "usr/bin/wine", "usr/bin/wineboot"):
        write(root / name, "#!/bin/sh\nexit 0\n", True)
    write(root / "usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", "font")
    write(root / "etc/games-runtime.properties", "schemaVersion=3\nlayout=external-components-v1\nrecipeSha256=" + "a" * 64 + "\n")
    for name in ("mnt/games/game", "mnt/games/prefix"):
        (root / name).mkdir(parents=True)


for scenario in ("reuse", "fresh", "explicit", "damaged", "missing", "interrupted", "changed", "low-space", "normalize-failure", "locale-failure", "dependency-repair", "dependency-broken", "publish-failure"):
    reuse = scenario in ("reuse", "changed")
    rejected = scenario in ("damaged", "missing", "interrupted", "changed", "low-space", "normalize-failure", "locale-failure", "dependency-broken", "publish-failure")
    with tempfile.TemporaryDirectory(prefix="rootfs-staging-") as tmp:
        files = Path(tmp) / "termux files"
        front = files / "front"
        runtime = front / "runtime"
        prefix = files / "usr"
        context = runtime / "setup/staging/task"
        shared = prefix / "var/lib/proot-distro/games-shared-rootfs/rootfs"
        scratch = prefix / "var/lib/proot-distro/containers/tmpl-build/rootfs"
        recipe = runtime / "recipe"
        digest = "a" * 64
        write(recipe / "setup-container.sh", "fixture")
        shutil.copyfile(ASSETS / "relocate_rootfs_links.py", recipe / "relocate_rootfs_links.py")
        write(recipe / "games-runtime.properties", "runtimePackages=rootfs_hangover_11.16\n")
        write(front / "components/component/payload", "payload")
        receipt = prefix / "var/lib/proot-distro/games-template-cache/games-rootfs-base.recipe"
        if scenario not in ("fresh", "interrupted", "low-space", "normalize-failure", "dependency-repair", "dependency-broken"):
            write(receipt, "b" * 64 if scenario == "changed" else digest)
        if reuse or scenario in ("locale-failure", "publish-failure"):
            ready(shared)
            if scenario in ("locale-failure", "publish-failure"):
                write(shared / "keep-old-environment", "old")
        elif scenario == "damaged":
            write(shared / "keep-user-data", "untouched")
        if scenario in ("fresh", "explicit", "interrupted", "normalize-failure", "locale-failure", "dependency-repair", "dependency-broken", "publish-failure"):
            # Fake distro install/login verifies staged sources before publishing this tree.
            seed = files / "fixture-seed/rootfs"
            ready(seed)
            (seed / "etc/games-runtime.properties").unlink()
            write(seed.parent / "manifest.json", "{}")
            write(seed / ".l2s/locale.0001", "locale data")
            (seed / ".l2s/locale").symlink_to(scratch / ".l2s/locale.0001")
            (seed / "usr/lib/locale").mkdir(parents=True)
            (seed / "usr/lib/locale/locale-archive").symlink_to(scratch / ".l2s/locale")
            if scenario == "normalize-failure":
                (seed / ".l2s/locale.0001").unlink()
            if scenario == "interrupted":
                shutil.copytree(seed.parent, scratch.parent, symlinks=True)
        write(prefix / "bin/df", "#!/bin/sh\nprintf 'Filesystem 1024-blocks Used Available Capacity Mounted\\nfixture 10000000 0 " + ("1" if scenario == "low-space" else "16000000") + " 1%% /\\n'\n", True)
        for name in ("pulseaudio", "termux-x11", "dbus-daemon"):
            write(prefix / "bin" / name, "#!/bin/sh\nexit 0\n", True)
        (prefix / "bin/python").symlink_to(sys.executable)
        if not scenario.startswith("dependency-"):
            write(prefix / "lib/libexpat.so.1", "versioned library")
        write(prefix / "bin/pkg", """#!/bin/sh
printf '%s\\n' "$*" > "$FIXTURE_INSTALL"
case " $* " in *" dbus libexpat "*) ;; *) exit 90 ;; esac
[ "$FIXTURE_SCENARIO" != dependency-broken ] || exit 0
mkdir -p "$PREFIX/lib"
printf library > "$PREFIX/lib/libexpat.so.1"
""", True)
        write(prefix / "bin/proot", """#!/bin/sh
while [ "$#" -gt 0 ]; do
    if [ "$1" = -r ]; then shift; root=$1; fi
    shift
done
[ "$FIXTURE_SCENARIO" != locale-failure ] || exit 1
test -f "$root/usr/lib/locale/locale-archive"
""", True)
        write(prefix / "bin/proot-distro", """#!/bin/sh
case "$*" in
  *--help*) echo '--name --isolated --bind'; exit 0 ;;
esac
if [ "$1" = install ]; then
    mkdir -p "$(dirname "$FIXTURE_ROOT")"
    cp -R "$FIXTURE_SEED/." "$(dirname "$FIXTURE_ROOT")/"
    exit 0
fi
test "$1" = login || exit 65
case "$*" in *"$FIXTURE_SOURCE:/run/games-setup/components/component"*) ;; *) exit 66 ;; esac
test -f "$FIXTURE_SOURCE/payload" || exit 67
test ! -e "$FIXTURE_CONTEXT/components/component/payload" || exit 68
printf guest > "$FIXTURE_GUEST"
printf 'schemaVersion=3\nlayout=external-components-v1\n' > "$FIXTURE_ROOT/etc/games-runtime.properties"
""", True)
        write(prefix / "bin/mv", """#!/bin/sh
if [ "$FIXTURE_SCENARIO" = publish-failure ] && [ "$1" = "${FIXTURE_ROOT%/rootfs}" ]; then exit 1; fi
exec /bin/mv "$@"
""", True)
        shutil.copyfile(ASSETS / "rootfs_script_common.sh", runtime / "rootfs_script_common.sh")
        shutil.copyfile(ASSETS / "proot_component_bindings.sh", runtime / "proot_component_bindings.sh")
        write(runtime / "warmup.sh", """
resolve_rootfs_translator() { RUNTIME_TRANSLATOR=hangover; }
prepare_external_prefix_template() { printf selected > "$FIXTURE_TEMPLATE"; }
require_external_prepared_prefix() { :; }
warmup_rootfs_prefix() { test ! -e "$BUILD_CONTEXT" || exit 80; printf warm > "$FIXTURE_WARM"; }
build_prefix_templates() { test ! -e "$BUILD_CONTEXT" || exit 81; printf template > "$FIXTURE_TEMPLATE"; }
""")
        values = dict(taskId="task", packageName="debian", version=11,
                      recipeSha256=digest, containerId="container", containerName="container",
                      buildContext=context, recipeDirectory=recipe,
                      component=f"component|{front}/components/component",
                      eventsPath=runtime / "events/task.jsonl", logPath=runtime / "logs/task.log",
                      baseOnly="true" if scenario in ("explicit", "locale-failure", "publish-failure") else "false", winePrefixDirectory=front / "prefix",
                      homeDirectory=front / "home", winePackage="rootfs_hangover_11.16",
                      prefixWarmupScript=runtime / "warmup.sh")
        spec = runtime / "setup/specs/task.setupspec"
        component = values.pop('component')
        spec_text = 'schemaVersion=4\n' + ''.join(f"{key}=" + base64.b64encode(str(value).encode()).decode() + '\n' for key, value in values.items())
        spec_text += 'componentCount=1\n' + ''.join(f"component.0.{key}=" + base64.b64encode(value.encode()).decode() + '\n' for key,value in zip(('id','directory'),component.split('|',1))) + 'bindingCount=0\n'
        write(spec, spec_text)
        env = dict(os.environ, PREFIX=str(prefix), PATH=f"{prefix}/bin:{os.environ['PATH']}",
                   FIXTURE_CONTEXT=str(context), FIXTURE_ROOT=str(scratch),
                   FIXTURE_SEED=str(files / "fixture-seed"),
                   FIXTURE_SOURCE=str(front / "components/component"),
                   FIXTURE_GUEST=str(files / "guest"), FIXTURE_WARM=str(files / "warm"),
                   FIXTURE_TEMPLATE=str(files / "template"), FIXTURE_INSTALL=str(files / "install"), FIXTURE_SCENARIO=scenario)
        result = subprocess.run(["sh", str(ASSETS / "setup_rootfs_runtime.sh"), str(spec)],
                                env=env, capture_output=True, text=True, timeout=20)
        if rejected:
            assert result.returncode == (69 if scenario == "dependency-broken" else 70), result.stderr + result.stdout
            error = {"low-space": "rootfs_insufficient_storage", "normalize-failure": "rootfs_link_validation_failed", "locale-failure": "rootfs_shared_image_invalid", "dependency-broken": "termux_runtime_dependencies_invalid", "publish-failure": "rootfs_shared_image_publish_failed"}.get(scenario, "rootfs_rebuild_required")
            assert error in Path(values["logPath"]).read_text()
            if scenario in ("normalize-failure", "locale-failure", "publish-failure"):
                assert (receipt.read_text() == digest) if scenario in ("locale-failure", "publish-failure") else not receipt.exists()
                assert scratch.exists()
                assert not (shared.parent.with_name(shared.parent.name + ".previous")).exists()
                if scenario in ("locale-failure", "publish-failure"):
                    assert (shared / "keep-old-environment").read_text() == "old"
                else:
                    assert not shared.exists()
            assert (files / "guest").exists() == (scenario in ("normalize-failure", "locale-failure", "publish-failure"))
            assert not (files / "warm").exists()
            if scenario == "damaged":
                assert (shared / "keep-user-data").read_text() == "untouched"
            if scenario == "interrupted":
                assert (scratch.parent / "manifest.json").exists()
            if scenario in ("missing", "changed"):
                assert receipt.read_text() == ("b" * 64 if scenario == "changed" else digest)
            continue
        assert result.returncode == 0, result.stderr + result.stdout
        assert (files / "warm").exists() == (scenario != "explicit")
        assert (files / "guest").exists() == (not reuse)
        assert (files / "template").exists() == (scenario != "explicit")
        events = [json.loads(line) for line in Path(values["eventsPath"]).read_text().splitlines()]
        assert events[-1]["state"] == "SUCCEEDED"
        assert not context.exists()
        if not reuse:
            assert (shared / "usr/lib/locale/locale-archive").read_text() == "locale data"
        if scenario == "dependency-repair":
            assert "dbus libexpat" in (files / "install").read_text()
            assert (prefix / "lib/libexpat.so.1").is_file()
print("v11 staging, no bulk templates, source binds and explicit recovery passed (13 cases).")
