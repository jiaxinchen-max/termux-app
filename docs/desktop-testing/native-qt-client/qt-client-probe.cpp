/* SPDX-License-Identifier: MIT */
#include <QGuiApplication>
#include <QOffscreenSurface>
#include <QOpenGLContext>
#include <QOpenGLFunctions>
#include <cstdio>
int main(int argc, char **argv)
{
    const QByteArray mode = qgetenv("KWIN_ANDROID_GL_MODE");
    if (mode != "system" && mode != "zink" && mode != "llvmpipe") {
        std::fprintf(stderr, "explicit KWIN_ANDROID_GL_MODE=system|zink|llvmpipe required\n");
        return 2;
    }
    QGuiApplication app(argc, argv);
    QOffscreenSurface surface;
    surface.create();
    QOpenGLContext context;
    if (!context.create() || !context.makeCurrent(&surface)) {
        std::fprintf(stderr, "requested native Qt client OpenGL context failed\n");
        return 1;
    }
    const auto raw = context.functions()->glGetString(GL_RENDERER);
    const QByteArray renderer(raw ? reinterpret_cast<const char *>(raw) : "");
    const auto lower = renderer.toLower();
    const bool software = lower.contains("llvmpipe") || lower.contains("lavapipe") || lower.contains("softpipe");
    bool match = false;
    if (mode == "system") match = !renderer.isEmpty() && !software && !lower.contains("zink") && context.isOpenGLES();
    if (mode == "zink") match = lower.contains("zink") && !software;
    if (mode == "llvmpipe") match = lower.contains("llvmpipe") && !lower.contains("zink");
    std::printf("mode=%s native_qt_client_GL_RENDERER=%s isOpenGLES=%d matches=%d\n", mode.constData(), renderer.constData(), context.isOpenGLES(), match);
    context.doneCurrent();
    return match ? 0 : 1;
}
