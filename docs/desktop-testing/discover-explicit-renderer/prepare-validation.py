from pathlib import Path
b=Path('/home/builder/desktop-fixes-20261030/discover')
s=b/'rootfs/root/build/fix2-source/discover-6.7.4'
t=b/'rootfs/root/build/discover-explicit-renderer-test'
t.mkdir(exist_ok=True)
q=(s/'discover/qml/DiscoverWindow.qml').read_text()
start=q.index('            function onWebflowStarted(url) {')
pos=q.index('{',start);depth=1;i=pos+1
while depth:
    if q[i]=='{':depth+=1
    elif q[i]=='}':depth-=1
    i+=1
handler=q[start:i]
fixture='''import QtQml
QtObject {
 id: window
 property bool webViewAvailable: false
 property int cancellationCount: transaction.cancellations
 property int openingCount: transaction.openings
 property int messageCount: messagesSheet.count
 property string message: messagesSheet.lastMessage
 property QtObject fakeTransaction: QtObject {
   id: transaction
   property int cancellations: 0
   property int openings: 0
   function cancel() { cancellations++; }
 }
 property QtObject messages: QtObject {
   id: messagesSheet
   property int count: 0
   property string lastMessage: ""
   function addMessage(message) { count++; lastMessage = message; }
 }
 function i18n(message, detail) { return detail === undefined ? message : message.replace("%1", detail); }
'''+handler+'\n}\n'
for name in ('ready','missing','creation-error'):
    d=t/name;d.mkdir(exist_ok=True);(d/'fixture.qml').write_text(fixture)
(t/'ready/WebflowDialog.qml').write_text('''import QtQml
QtObject {
 property QtObject transaction
 property url url
 function open() { transaction.openings++; }
}
''')
(t/'creation-error/WebflowDialog.qml').write_text('''import QtQml
QtObject {
 property QtObject transaction
 property url url
 required property string unavailableRequiredValue
 function open() { transaction.openings++; }
}
''')
(t/'CMakeLists.txt').write_text('''cmake_minimum_required(VERSION 3.16)
project(discover_explicit_renderer_test LANGUAGES CXX)
find_package(Qt6 REQUIRED COMPONENTS Core Qml)
add_executable(discover-explicit-renderer-test main.cpp)
target_link_libraries(discover-explicit-renderer-test PRIVATE Qt6::Core Qt6::Qml)
''')
(t/'main.cpp').write_text(r'''#include <QCoreApplication>
#include <QQmlEngine>
#include <QQmlComponent>
#include <QDebug>
#include <QVariant>
#include <memory>
static void require(bool ok, const char *text) { if (!ok) qFatal("FAIL: %s", text); }
int main(int argc, char **argv) {
 QCoreApplication app(argc, argv); require(argc==2,"test directory required");
 for (const auto &name : {"ready", "missing", "creation-error"}) {
  QQmlEngine engine;
  QQmlComponent component(&engine, QUrl::fromLocalFile(QString::fromLocal8Bit(argv[1])+"/"+name+"/fixture.qml"));
  std::unique_ptr<QObject> fixture(component.create());
  if (!fixture) { qCritical()<<component.errors(); return 1; }
  require(QMetaObject::invokeMethod(fixture.get(), "onWebflowStarted", Q_ARG(QVariant, QVariant(QStringLiteral("https://example.invalid/auth")))), "invoke unavailable flow");
  require(fixture->property("cancellationCount").toInt()==1,"unavailable cancels exactly once");
  require(fixture->property("openingCount").toInt()==0,"unavailable never opens WebView");
  require(fixture->property("messageCount").toInt()==1,"unavailable shows one message");
  require(fixture->property("message").toString().contains("requires an explicitly selected OpenGL"),"unavailable explains explicit mode requirement");
  fixture->setProperty("webViewAvailable",true);
  require(QMetaObject::invokeMethod(fixture.get(), "onWebflowStarted", Q_ARG(QVariant, QVariant(QStringLiteral("https://example.invalid/auth")))), "invoke available flow");
  if (QByteArray(name)=="ready") {
   require(fixture->property("cancellationCount").toInt()==1,"ready flow not cancelled");
   require(fixture->property("openingCount").toInt()==1,"ready flow opens once");
   require(fixture->property("messageCount").toInt()==1,"ready flow has no added error");
  } else {
   require(fixture->property("cancellationCount").toInt()==2,"failed flow cancels exactly once");
   require(fixture->property("openingCount").toInt()==0,"failed flow never opens");
   require(fixture->property("messageCount").toInt()==2,"failed flow shows explicit error");
   require(fixture->property("message").toString().contains("Cannot"),"failed flow explains failure");
  }
  qInfo()<<"PASS:"<<name<<"unavailable gating, explicit error cancellation, no browser or renderer switch";
 }
 return 0;
}
''')
print(t)
