# Vendored dependencies

- libuvc 0.0.7 (BSD-3-Clause): https://github.com/libuvc/libuvc/archive/refs/tags/v0.0.7.tar.gz
  SHA256: 7c6ba79723ad5d0ccdfbe6cadcfbd03f9f75b701d7ba96631eb1fd929a86ee72
- FatFs R0.15 (permissive license in fatfs/LICENSE): https://elm-chan.org/fsw/ff/arc/ff15.zip
  SHA256: e0d76654d877e6c74be5ea3c395808794d495169514e98cbf6046168b8f4f070

FatFs ffconf.h enables FAT/exFAT, UTF-8 long filenames, 64-bit LBA and 512-4096 byte sectors.
Public filesystem operations are serialized by the adapter mutex.
