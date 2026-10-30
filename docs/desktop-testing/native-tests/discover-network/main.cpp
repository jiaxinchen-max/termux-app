#include <QGuiApplication>
#include <QQmlEngine>
#include <QQmlComponent>
#include <QFile>
#include <QTest>
#include <QDebug>
#include <QQuickWindow>
#include <memory>

class NetworkFixture : public QObject {
    Q_OBJECT
    Q_PROPERTY(Reachability reachability READ reachability NOTIFY reachabilityChanged)
public:
    enum class Reachability { Unknown, Disconnected, Local, Site, Online };
    Q_ENUM(Reachability)
    Reachability reachability() const { return m_value; }
    void set(Reachability value) { if(m_value!=value) {m_value=value;emit reachabilityChanged();} }
signals:
    void reachabilityChanged();
private:
    Reachability m_value=Reachability::Disconnected;
};

static bool visible(QObject *popup) {return popup->property("visible").toBool();}
static void require(bool condition,const char *message) {
    if(!condition)qFatal("FAIL: %s",message);
}

int main(int argc,char **argv) {
    QGuiApplication application(argc,argv);
    require(argc==2,"test expects generated QML fixture path");
    NetworkFixture network;
    qmlRegisterSingletonInstance("TestNetwork",1,0,"NetworkInformation",&network);
    QQmlEngine engine;
    QQmlComponent component(&engine,QUrl::fromLocalFile(QString::fromLocal8Bit(argv[1])));
    std::unique_ptr<QObject> window(component.create());
    if(!window){qCritical()<<component.errors();return 1;}
    QObject *popup=window->findChild<QObject*>(QStringLiteral("connectionDialog"));
    require(popup,"popup object exists");
    using R=NetworkFixture::Reachability;
    require(!visible(popup),"initial transient does not warn synchronously");
    QTest::qWait(200);
    network.set(R::Online);
    QTest::qWait(1100);
    require(!visible(popup),"initial transient becoming Online cancels warning");

    network.set(R::Disconnected);
    QTest::qWait(350);
    network.set(R::Local);
    QTest::qWait(350);
    network.set(R::Site);
    QTest::qWait(450);
    require(visible(popup),"continuous real non-Online states warn after original fixed deadline");
    require(network.reachability()==R::Site,"warning never overwrites actual reachability");

    network.set(R::Online);
    QTest::qWait(400);
    require(!visible(popup),"real Online closes stale warning");

    network.set(R::Disconnected);
    QTest::qWait(1100);
    require(visible(popup),"sustained real offline remains warned");
    network.set(R::Online);
    QTest::qWait(40);
    const bool visibleDuringExit=visible(popup);
    require(visibleDuringExit,"close race is exercised while Popup exit animation is visible");
    network.set(R::Disconnected);
    QTest::qWait(1100);
    require(visible(popup),"real offline during close is warned again");
    require(network.reachability()==R::Disconnected,"close race never changes real network state");
    network.set(R::Online);
    QTest::qWait(400);
    require(!visible(popup),"final Online restores closed warning");
    qInfo()<<"PASS: Qt"<<qVersion()<<"real QML Timer/Connections/Popup; initial transient, fixed offline deadline, Online close, offline during close; visibleDuringExit="<<visibleDuringExit;
    return 0;
}
#include "main.moc"
