#include <QGuiApplication>
#include <QFutureWatcher>
#include <QThreadPool>
#include <QTimer>
#include <QDebug>
#include <QElapsedTimer>
#include "AppStreamConcurrentPool.h"
int main(int argc,char **argv) {
 QGuiApplication app(argc,argv);if(argc!=3)return 64;
 QThreadPool threads;AppStream::ConcurrentPool pool;
 auto raw=new AppStream::Pool;
 if(QByteArray(argv[1])!="--defaults") raw->overrideCacheLocations(QString::fromLocal8Bit(argv[1]),QString::fromLocal8Bit(argv[2]));
 pool.reset(raw,&threads);
 QElapsedTimer timer;timer.start();
 QObject::connect(&pool,&AppStream::ConcurrentPool::loadFinished,&app,[&](bool success){
  qInfo()<<"async_load_finished"<<success<<pool.lastError()<<"elapsed_ms"<<timer.elapsed()<<"locale"<<raw->locale();
  QMetaObject::invokeMethod(&app,[&]{
   qInfo()<<"async_components"<<pool.components().result().size()<<"distro"<<pool.componentsById(QStringLiteral("org.debian.debian")).result().size();
   auto future=pool.search(QStringLiteral("kcalc"));
   auto watcher=new QFutureWatcher<AppStream::ComponentBox>(&app);
   watcher->setFuture(future);
   QObject::connect(watcher,&QFutureWatcher<AppStream::ComponentBox>::finished,&app,[&,future]{
    const auto results=future.result();qInfo()<<"async_search_finished"<<results.size()<<"elapsed_ms"<<timer.elapsed();
    for(const auto &c:results)qInfo()<<"result"<<c.id()<<c.name()<<c.packageNames();
    app.exit(results.size()>0?0:2);
   });
  },Qt::QueuedConnection);
 });
 QTimer::singleShot(15000,&app,[&]{qCritical()<<"probe timeout";app.exit(3);});
 pool.loadAsync();return app.exec();
}
