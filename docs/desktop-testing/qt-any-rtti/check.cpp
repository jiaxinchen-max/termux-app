// SPDX-License-Identifier: MIT
#include <any>
#include <cstdio>
#include <dlfcn.h>
struct xdg_popup;

int main(int argc, char **argv)
{
    if (argc != 3) {
        std::fprintf(stderr, "usage: %s old-producer.so fixed-producer.so\n", argv[0]);
        return 2;
    }
    for (int i = 1; i <= 2; ++i) {
        void *library = dlopen(argv[i], RTLD_NOW | RTLD_LOCAL);
        if (!library) { std::fprintf(stderr, "%s\n", dlerror()); return 2; }
        auto make = reinterpret_cast<void (*)(std::any *)>(dlsym(library, "make_role"));
        if (!make) { std::fprintf(stderr, "%s\n", dlerror()); return 2; }
        bool matches;
        {
            std::any role;
            make(&role);
            matches = std::any_cast<xdg_popup *>(&role) != nullptr;
        }
        dlclose(library);
        std::printf("%s any_cast=%s\n", i == 1 ? "old" : "fixed", matches ? "match" : "mismatch");
        if (matches != (i == 2)) return 1;
    }
    return 0;
}
