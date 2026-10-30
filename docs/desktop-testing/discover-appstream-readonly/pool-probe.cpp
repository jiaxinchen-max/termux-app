#include <QCoreApplication>
#include <QDebug>
#include <QElapsedTimer>
#include <AppStreamQt/pool.h>
#include <AppStreamQt/component-box.h>
int main(int argc, char **argv) {
 QCoreApplication app(argc, argv);
 if(argc!=3) return 64;
 AppStream::Pool pool;
 if(QByteArray(argv[1])!="--defaults") pool.overrideCacheLocations(QString::fromLocal8Bit(argv[1]),QString::fromLocal8Bit(argv[2]));
 QElapsedTimer timer;timer.start();
 const bool loaded=pool.load();
 qInfo()<<"pool_loaded"<<loaded<<"error"<<pool.lastError()<<"elapsed_ms"<<timer.elapsed()<<"locale"<<pool.locale()<<"components"<<pool.components().size()<<"flags"<<pool.flags();
 for(const auto &term : {QStringLiteral("kcalc"),QStringLiteral("kate")}) {
  timer.restart();const auto result=pool.search(term);
  qInfo()<<"search"<<term<<"count"<<result.size()<<"elapsed_ms"<<timer.elapsed();
  for(const auto &c : result)qInfo()<<"result"<<c.id()<<c.name()<<c.packageNames();
 }
 for(const auto &id : {QStringLiteral("org.kde.kcalc.desktop"),QStringLiteral("org.kde.kate.desktop")})qInfo()<<"by_id"<<id<<pool.componentsById(id).size();
 return loaded?0:1;
}
