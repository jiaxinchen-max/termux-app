#include <QGuiApplication>
#include <QDBusConnection>
#include <QDBusInterface>
#include <QDBusReply>
#include <QElapsedTimer>
#include <QPointer>
#include <QSignalSpy>
#include <QTest>
#include <functional>
#include <DiscoverBackendsFactory.h>
#include <resources/AbstractResourcesBackend.h>

static bool until(const std::function<bool()> &condition, int timeout = 10000)
{
    QElapsedTimer elapsed; elapsed.start();
    while (!condition() && elapsed.elapsed() < timeout) QTest::qWait(20);
    return condition();
}
static void require(bool value, const char *message)
{
    if (!value) qFatal("FAIL %s", message);
}
int main(int argc, char **argv)
{
    QGuiApplication app(argc, argv);
    require(argc == 4, "usage: probe plugin-library-path fixed|baseline error|no-devices");
    const QString firstMode = QString::fromLocal8Bit(argv[3]);
    require(firstMode == "error" || firstMode == "no-devices", "explicit initial fixture mode required");
    const bool fixed = QByteArray(argv[2]) == "fixed";
    require(fixed || QByteArray(argv[2]) == "baseline", "explicit test expectation required");
    QCoreApplication::setLibraryPaths({QString::fromLocal8Bit(argv[1]), QStringLiteral("/usr/lib/aarch64-linux-gnu/qt6/plugins")});
    QDBusInterface fixture(QStringLiteral("org.freedesktop.fwupd"), QStringLiteral("/"), QStringLiteral("org.termux.FwupdFixture"), QDBusConnection::systemBus());
    if (!fixture.isValid()) qWarning() << fixture.lastError() << QDBusConnection::systemBus().lastError();
    require(fixture.isValid(), "independent fixture connection");
    auto control = [&](const QString &method, const QVariantList &args = {}) {
        QDBusMessage reply = fixture.callWithArgumentList(QDBus::Block, method, args);
        require(reply.type() != QDBusMessage::ErrorMessage, qPrintable(reply.errorMessage()));
        return reply;
    };
    control(QStringLiteral("Arm"), {firstMode});
    auto instances = DiscoverBackendsFactory().backend(QStringLiteral("fwupd-backend"));
    require(instances.size() == 1, "load actual fwupd plugin");
    auto backend = instances.first();
    QSignalSpy initialized(backend, SIGNAL(initialized()));
    QSignalSpy errors(backend, &AbstractResourcesBackend::passiveMessage);
    QSignalSpy progress(backend, &AbstractResourcesBackend::fetchingUpdatesProgressChanged);
    require(initialized.isValid(), "actual initialized signal");
    const QStringList modes = {firstMode, QStringLiteral("empty"), QStringLiteral("nonempty"), QStringLiteral("no-devices"), QStringLiteral("error")};
    for (int round = 0; round < modes.size(); ++round) {
        const auto mode = modes[round];
        if (round) {
            control(QStringLiteral("Arm"), {mode});
            backend->checkForUpdates();
        }
        require(until([&] {
            auto reply = control(QStringLiteral("Pending"));
            return !reply.arguments().isEmpty() && reply.arguments().first().toUInt() == 1;
        }), "GetDevices request reached independent service");
        require(backend->fetchingUpdatesProgress() == 42, "pending query reports fetching 42");
        auto pending = QPointer<ResultsStream>(backend->search({}));
        require(pending, "pending stream exists");
        QTest::qWait(40);
        require(pending, "pending stream waits before service reply");
        int errorCount = errors.count();
        int initCount = initialized.count();
        int progressCount = progress.count();
        control(QStringLiteral("Release"));
        if (!fixed) {
            QTest::qWait(500);
            require(backend->fetchingUpdatesProgress() == 42, "baseline remains fetching after GetDevices error");
            require(pending && initialized.count() == initCount, "baseline stream remains pending without initialized");
            if (mode == "error") require(errors.count() > errorCount, "baseline real error is retained");
            qInfo() << "BASELINE REPRODUCED:" << mode << "progress=42, stream pending, original passive error count=" << errors.count();
            return 0;
        }
        require(until([&] { return pending.isNull() && backend->fetchingUpdatesProgress() == 100; }), "settled query releases pending stream and progress");
        require(initialized.count() == initCount + 1, "exactly one query settled signal");
        require(progress.count() > progressCount, "settled query notifies updater progress");
        if (mode == "error") require(until([&] { return errors.count() > errorCount; }), "actual GetDevices error remains visible");
        if (mode == "empty" || mode == "nonempty") require(errors.count() == errorCount, "successful result does not create error");
        auto subsequent = QPointer<ResultsStream>(backend->search({}));
        require(until([&] { return subsequent.isNull(); }), "subsequent stream finishes after query completion");
        qInfo() << "PASS" << round << mode << "pending+subsequent streams finished; progress=100; errors=" << errors.count();
    }
    qInfo() << "PASS: actual plugin query lifecycle, 5 rounds including repeat refresh";
    return 0;
}
