// SPDX-License-Identifier: MIT
#if defined(TEST_COMPARE_NAMES)
#  define _LIBCPP_TYPEINFO_COMPARISON_IMPLEMENTATION 2
#endif
#include <any>
struct xdg_popup;
extern "C" __attribute__((visibility("default"))) void make_role(std::any *out)
{
    *out = static_cast<xdg_popup *>(nullptr);
}
