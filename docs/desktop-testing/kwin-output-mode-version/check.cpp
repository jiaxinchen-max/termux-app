// SPDX-License-Identifier: MIT
// Exercises KWin's actual OutputDeviceV2Interface -> generated Wayland send layer.
#include "core/backendoutput.h"
#include "wayland/display.h"
#include "wayland/outputdevice_v2.h"
#include <QCoreApplication>
#include <wayland-server-core.h>
#include <sys/socket.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <vector>

class TestOutput : public KWin::BackendOutput {
public:
    TestOutput() {
        Information info;
        info.name = QStringLiteral("protocol-version-test");
        info.manufacturer = QStringLiteral("test");
        info.model = QStringLiteral("virtual");
        setInformation(info);
        State state;
        state.modes = {std::make_shared<KWin::OutputMode>(QSize(800, 600), 60000,
                         KWin::OutputMode::Flags(KWin::OutputMode::Flag::Preferred) | KWin::OutputMode::Flag::Custom)};
        state.currentMode = state.modes.front();
        state.enabled = true;
        setState(state);
    }
    KWin::RenderLoop *renderLoop() const override { return nullptr; }
    bool testPresentation(const std::shared_ptr<KWin::OutputFrame> &) override { return false; }
    bool present(const QList<KWin::OutputLayer *> &, const std::shared_ptr<KWin::OutputFrame> &) override { return false; }
};
struct Observed { uint32_t global = 0; int sizeEvents = 0; int flagsEvents = 0; uint32_t version = 0; uint32_t flags = 0; };
static void logProtocol(void *data, wl_protocol_logger_type direction, const wl_protocol_logger_message *message) {
    if (direction != WL_PROTOCOL_LOGGER_EVENT) return;
    auto &state = *static_cast<Observed *>(data);
    const char *interface = wl_resource_get_class(message->resource);
    if (!std::strcmp(interface, "wl_registry") && !std::strcmp(message->message->name, "global") &&
        !std::strcmp(message->arguments[1].s, "kde_output_device_v2")) {
        state.global = message->arguments[0].u;
    }
    if (!std::strcmp(interface, "kde_output_device_mode_v2")) {
        state.version = wl_resource_get_version(message->resource);
        if (message->message_opcode == 0) ++state.sizeEvents;
        if (message->message_opcode == 4) { ++state.flagsEvents; state.flags = message->arguments[0].u; }
    }
}
static bool sendWords(int fd, const std::vector<uint32_t> &words) {
    return send(fd, words.data(), words.size() * sizeof(uint32_t), MSG_NOSIGNAL) == ssize_t(words.size() * sizeof(uint32_t));
}
int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    bool passed = true;
    for (uint32_t version : {18u, 19u, 20u}) {
        KWin::Display display;
        TestOutput output;
        KWin::OutputDeviceV2Interface device(&display, &output);
        Observed observed;
        auto logger = wl_display_add_protocol_logger(display, logProtocol, &observed);
        int sockets[2];
        if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sockets) || !display.createClient(sockets[0])) return 2;
        // wl_display.get_registry(new_id=2)
        if (!sendWords(sockets[1], {1, (12u << 16) | 1u, 2})) return 2;
        display.dispatchEvents(); display.flush();
        if (!observed.global) { std::fprintf(stderr, "output global missing\n"); return 2; }
        // wl_registry.bind(name, interface, version, new_id=3)
        const char interface[] = "kde_output_device_v2";
        std::vector<uint32_t> bind{2, 0, observed.global, sizeof(interface)};
        size_t offset = bind.size();
        bind.resize(offset + (sizeof(interface) + 3) / 4, 0);
        std::memcpy(bind.data() + offset, interface, sizeof(interface));
        bind.push_back(version); bind.push_back(3);
        bind[1] = uint32_t(bind.size() * sizeof(uint32_t)) << 16;
        if (!sendWords(sockets[1], bind)) return 2;
        display.dispatchEvents(); display.flush();
        const int expectedFlags = version >= 19 ? 1 : 0;
        const bool okay = observed.version == version && observed.sizeEvents == 1 &&
                          observed.flagsEvents == expectedFlags &&
                          (expectedFlags == 0 || observed.flags == 1);
        std::printf("v%u mode_version=%u size_events=%d flags_opcode4=%d flags=%u %s\n",
                    version, observed.version, observed.sizeEvents, observed.flagsEvents, observed.flags,
                    okay ? "PASS" : "FAIL");
        passed &= okay;
        close(sockets[1]);
        wl_protocol_logger_destroy(logger);
    }
    return passed ? 0 : 1;
}
