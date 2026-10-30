# Embedded USB module

`:termux-usb` is an Android library in the main App. It implements only USB host
enumeration, Android authorization and FD transfer. It declares no camera,
contacts, storage or location permission, and never contacts an external API APK.

`TermuxApplication` initializes it only in the main process. Each client command
opens a new connection, so subsequent commands reconnect after an App process
restart. An interrupted command reports failure; it does not retry or select
another backend.

The App installs its client atomically at `$PREFIX/libexec/termux-usb-app`, after
`$PREFIX/bin/sh` exists. App resume and the USB dependency check both install or
refresh it; initialization does not create a partial bootstrap prefix. Python 3
is supplied by the desktop USB support packages.

The coordinator must explicitly select this command with `--termux-usb`:

```sh
"$PREFIX/libexec/termux-usb-app" -l
"$PREFIX/libexec/termux-usb-app" -r /dev/bus/usb/001/002
"$PREFIX/libexec/termux-usb-app" -e 'program argument' /dev/bus/usb/001/002
```

`-e` receives the FD as its final argument, or in `TERMUX_USB_FD` when combined
with `-E`. Callback commands are parsed with `shlex.split`; shell expressions
are not evaluated. This supports the existing bridge's `shlex.join` handoff.
List and open never request permission without `-r`. New permission requests
require a resumed, visible App Activity and use Android's USB permission dialog.
Enumeration returns all Android USB devices; the read-only storage coordinator
still owns device classification and the desktop volume policy.

The abstract Unix socket is `com.termux.usb.v1.<kernel UID>`. Both peers verify
kernel UID credentials; the client derives its UID from a socketpair so PRoot's
UID emulation cannot select a different endpoint. A request is one UTF-8 JSON
line (maximum 4096 bytes):

```json
{"version":1,"action":"list","device":null,"request":false}
```

Other actions are `permission` and `open`, with a valid `/dev/bus/usb/BBB/DDD`
path. `request` is an explicit boolean. A response is one JSON line (maximum
65536 bytes), with `version:1`, numeric POSIX `errno`, and `result` on success or
`error` on failure. List results are arrays of device paths; other successful
results are `true`. Only a successful open carries exactly one `SCM_RIGHTS` FD.
The Java side closes its USB connection and temporary duplicated FD after
sending. The client initially marks its received FD non-inheritable, then
enables inheritance only immediately before executing the requested callback.
Every error closes any received FDs. Unsolicited incoming FDs are rejected.

Protocol tests run with `python3 termux-usb/tests/test_client.py`. They exercise
real socketpair FD transfer, callback execution, malformed responses and error
FD cleanup. They do not establish Android USB or App UI acceptance.
