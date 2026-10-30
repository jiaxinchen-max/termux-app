#include <QGuiApplication>
#include <QDBusConnection>
#include <QDBusInterface>
#include <QDBusReply>
#include <QDir>
#include <QSaveFile>
#include <QFile>
#include <QSignalSpy>
#include <QTest>
#include <QElapsedTimer>
#include <QSemaphore>
#include <QtConcurrentRun>
#include <PackageKit/Offline>
#include "PackageKitResource.h"
#include <QPointer>
#include <QSet>
#include <QSharedPointer>
#include <QStringList>
#include <QThreadPool>
#include <QTimer>
#include <QVariantList>
#include "appstream/AppStreamConcurrentPool.h"
#include "resources/AbstractResourcesBackend.h"
#define private public
#include "PackageKitBackend.h"
#undef private
static void require(bool ok,const char *why){if(!ok)qFatal("FAIL: %s",why);}
static bool until(const std::function<bool()> &f,int timeout=12000){QElapsedTimer t;t.start();while(!f()&&t.elapsed()<timeout)QTest::qWait(10);return f();}
static void write(const QString &file,const QByteArray &contents){QSaveFile out(file);require(out.open(QIODevice::WriteOnly),"fixture output open");require(out.write(contents)==contents.size(),"fixture output write");require(out.commit(),"atomic fixture publish");}
static void publish(const QString &name,const QString &pkg,bool shared=false){
 QByteArray xml="<?xml version=\"1.0\"?><components version=\"1.0\" origin=\"debian-fixture\">";
 if(!name.isEmpty())xml+="<component type=\"desktop-application\"><id>org.kde.kcalc.desktop</id><name>"+name.toUtf8()+"</name><summary>Scientific Calculator</summary><metadata_license>CC0-1.0</metadata_license><pkgname>"+pkg.toUtf8()+"</pkgname><launchable type=\"desktop-id\">org.kde.kcalc.desktop</launchable><categories><category>Utility</category></categories></component>";
 if(shared)xml+="<component type=\"desktop-application\"><id>org.kde.kcalc.helper.desktop</id><name>KCalc Helper</name><summary>Calculator helper fixture</summary><metadata_license>CC0-1.0</metadata_license><pkgname>"+pkg.toUtf8()+"</pkgname><launchable type=\"desktop-id\">org.kde.kcalc.helper.desktop</launchable></component>";
 xml+="</components>";write("/var/lib/swcatalog/xml/debian-fixture.xml",xml);
 AppStream::Pool producer;producer.addFlags(AppStream::Pool::FlagIgnoreCacheAge);require(producer.load(),"real fixture AppStream cache compilation succeeds");require(producer.components().size()==(name.isEmpty()?0:shared?2:1),"fixture compiler component count");
}
struct Query{QPointer<ResultsStream> stream;QVector<AbstractResource*> rows;};
static void start(PackageKitBackend &backend,Query &q,bool search=false){AbstractResourcesBackend::Filters f;if(search)f.search="kcalc";q.stream=backend.search(f);require(q.stream,"actual result stream exists");QObject::connect(q.stream,&ResultsStream::resourcesFound,&backend,[&q](const QVector<StreamResult> &rows){for(const auto &row:rows)q.rows+=row.resource;});}
static QVector<AbstractResource*> query(PackageKitBackend &b,bool search=false){Query q;start(b,q,search);require(until([&]{return !q.stream;}),"actual stream completes");return q.rows;}
int main(int argc,char **argv){QGuiApplication app(argc,argv);QFile maps("/proc/self/maps");require(maps.open(QIODevice::ReadOnly),"read actual loaded ELF mappings");for(const auto &line:maps.readAll().split('\n')){if(line.contains("packagekit-backend.so")||line.contains("libDiscoverCommon.so"))qInfo().noquote()<<"LOADED-ELF"<<line.trimmed();}require(argc==2&&QByteArray(argv[1])=="--independent-packagekit-fixture","explicit test mode");
 QDBusInterface fixture("org.freedesktop.PackageKit","/org/freedesktop/PackageKit","org.termux.PackageKitFixture",QDBusConnection::systemBus());require(fixture.isValid(),"private PackageKit fixture bus");
 auto call=[&](const QString &method,const QVariantList &args={}){auto r=fixture.callWithArgumentList(QDBus::Block,method,args);require(r.type()!=QDBusMessage::ErrorMessage,qPrintable(r.errorMessage()));return r;};
 auto pending=[&]{auto r=call("Pending");return r.arguments().first().toUInt();};
 PackageKitBackend backend;QSignalSpy loads(backend.m_appdata.get(),&AppStream::ConcurrentPool::loadFinished);QSignalSpy errors(&backend,&AbstractResourcesBackend::passiveMessage);QSignalSpy contents(&backend,&AbstractResourcesBackend::contentsChanged);QSignalSpy removed(&backend,&AbstractResourcesBackend::resourceRemoved);
 require(until([&]{return backend.m_appstreamInitialized&&pending()==1;}),"initial empty pool and actual RefreshCache request ready");require(query(backend).isEmpty(),"initial AllApplications empty");require(backend.m_allPackagesLoaded,"initial load-all index was cached");publish("KCalc","kcalc");call("Release",{true});
#ifdef CANDIDATE
 auto settled=[&]{return backend.m_appstreamInitialized&&!backend.m_appstreamLoadInProgress&&backend.m_isFetching==0&&!backend.m_refresher;};
 require(until(settled),"successful refresh and real AppStream reload settle");require(loads.count()==2,"one initial load plus one refresh load");
#else
 require(until([&]{return backend.m_isFetching==0;}),"baseline refresh settles");require(loads.count()==1,"baseline refresh never reloaded AppStream");require(query(backend).isEmpty(),"baseline AllApplications permanently empty after metadata publish");require(backend.componentsById("org.kde.kcalc.desktop").isEmpty(),"baseline same pool misses actual new metadata");qInfo()<<"BASELINE REPRODUCED: actual PackageKitBackend received successful RefreshCache; stale pool and cached AllApplications both remain empty";return 0;
#endif
#ifdef CANDIDATE
 auto rows=query(backend);require(rows.size()==1,"AllApplications sees newly published metadata");QPointer<AbstractResource> first=rows.first();require(first->name()=="KCalc","real metadata name");require(backend.m_packages.packageToApp.value("kcalc").size()==1,"one package mapping");
 auto refresh=[&](bool ok){backend.checkForUpdates();require(until([&]{return pending()==1;}),"actual RefreshCache request held");call("Release",{ok});require(until(settled),"actual refresh settles");};
 for(int n=0;n<3;++n){refresh(true);rows=query(backend);require(rows.size()==1&&rows.first()==first,"unchanged metadata reuses exact resource object");require(backend.m_packages.packageToApp.value("kcalc").size()==1,"repeated refresh does not duplicate mappings");}
 qInfo()<<"PASS unchanged metadata: repeated successful refresh preserves resource identity and deduplicated mapping";
 publish("KCalc Shared","shared-package",true);refresh(true);const auto sharedRows=backend.resourcesByPackageNames<QVector<AbstractResource*>>(QStringList{"shared-package"});QSet<QString> sharedIds;for(auto resource:sharedRows)sharedIds.insert(resource->appstreamId());require(sharedRows.size()==2&&sharedIds.size()==2,"QVector same-package results contain each component exactly once");require(backend.m_packages.packageToApp.value("shared-package").size()==2,"same-package IDs are unique");backend.includePackagesToAdd();require(query(backend).size()==2,"same-package applications appear exactly once in AllApplications");backend.resolvePackages(QStringList{"generic-fixture"});require(until([&]{return backend.resourcesByPackageName("generic-fixture").size()==1;}),"actual Resolve event creates generic package resource");const auto genericRows=backend.resourcesByPackageNames<QVector<AbstractResource*>>(QStringList{"generic-fixture"});require(genericRows.size()==1&&!backend.m_packages.packageToApp.contains("generic-fixture"),"generic package without app mapping remains queryable");publish("KCalc","kcalc");refresh(true);require(query(backend).size()==1,"removing shared extra component restores one current application");qInfo()<<"PASS shared package: QVector unique IDs and generic unmapped package lookup preserved";
 publish("KCalc Renamed","kcalc-new");refresh(true);rows=query(backend);require(rows.size()==1&&rows.first()!=first,"changed metadata replaces active object");require(first&&first->name()=="KCalc"&&first->parent()==&backend,"held old detail object remains valid");require(rows.first()->name()=="KCalc Renamed"&&rows.first()->packageName()=="kcalc-new","renamed/repackaged metadata is current");require(!backend.m_packages.packageToApp.contains("kcalc")&&backend.m_packages.packageToApp.value("kcalc-new").size()==1,"old package mapping retired");QPointer<AbstractResource> second=rows.first();
 publish({},{});refresh(true);require(query(backend).isEmpty()&&query(backend,true).isEmpty(),"deleted metadata absent in AllApplications and search");require(backend.m_packages.packageToApp.isEmpty(),"deleted metadata absent from package mappings");require(first&&second&&removed.count()>=2,"retired resources remain valid for existing holders");
 qInfo()<<"PASS changed/deleted metadata: current indices updated; retired detail objects alive under backend";
 publish("KCalc Restored","kcalc");int before=loads.count(),beforeErrors=errors.count();refresh(false);require(loads.count()==before,"failed RefreshCache does not reload or retry");require(until([&]{return errors.count()>beforeErrors;}),"original RefreshCache failure remains visible");require(query(backend).isEmpty(),"failed refresh is not treated as new catalog success");QTest::qWait(80);require(pending()==0&&loads.count()==before,"failed refresh has no implicit retry");refresh(true);require(query(backend).size()==1,"subsequent explicitly requested successful refresh works");
 qInfo()<<"PASS failed refresh: original error, balanced fetching, no reload and no implicit retry";
 // A real worker barrier deterministically keeps loading queued while actual
 // D-Bus RefreshCache completion events arrive. No backend state is fabricated.
 backend.m_threadPool.setMaxThreadCount(1);QSemaphore started,release;auto blocker=QtConcurrent::run(&backend.m_threadPool,[&]{started.release();release.acquire();});require(started.tryAcquire(1,5000),"real worker barrier acquired");Query oldStream;start(backend,oldStream,true);QTest::qWait(30);backend.reloadPackageList();require(backend.m_appstreamLoadInProgress,"real reload is active");Query localFile;AbstractResourcesBackend::Filters localFilter;localFilter.resourceUrl=QUrl::fromLocalFile("/fixture/local-fixture.deb");localFile.stream=backend.search(localFilter);require(localFile.stream,"local package stream exists during catalog load");QObject::connect(localFile.stream,&ResultsStream::resourcesFound,&backend,[&](const QVector<StreamResult> &rows){for(const auto &row:rows)localFile.rows+=row.resource;});require(until([&]{return !localFile.stream;}),"local package stream completes independently of catalog load");require(backend.m_appstreamLoadInProgress&&!backend.m_appstreamInitialized,"catalog is still loading behind real worker barrier");require(localFile.rows.size()==1&&localFile.rows.first()->origin()=="/fixture/local-fixture.deb","valid local deb remains visible while catalog loads");qInfo()<<"PASS local package: real deb file discovery completes while AppStream load remains blocked";Query waiting;start(backend,waiting);QTest::qWait(20);require(waiting.stream&&waiting.rows.isEmpty(),"new stream waits during reload");before=loads.count();
 for(int n=0;n<2;++n){backend.checkForUpdates();require(until([&]{return pending()==1;}),"refresh requested while pool load is pending");call("Release",{true});require(until([&]{return !backend.m_refresher;}),"fixture update transaction completed");require(backend.m_appstreamReloadPending,"actual refresh completion queued exactly one reload");}
 require(waiting.stream&&waiting.rows.isEmpty()&&loads.count()==before,"intermediate generation has not been published");release.release();blocker.waitForFinished();require(until([&]{return settled()&&!waiting.stream&&!oldStream.stream;}),"queued reload and both real streams settle");require(loads.count()==before+2,"overlapping successful refreshes coalesce into one subsequent load");require(oldStream.rows.isEmpty(),"old in-flight query cannot publish stale generation");require(waiting.rows.size()==1&&waiting.rows.first()->name()=="KCalc Restored","waiting stream receives final generation");require(backend.m_isFetching==0,"fetching balanced after overlapping refreshes");
 qInfo()<<"PASS overlapping loads: worker barrier, actual D-Bus events, coalesced reload, old stream suppressed, waiting stream released";
 require(QDir().mkpath("/fixture/unwritable-cache"),"create explicit cache directory");require(QFile::setPermissions("/fixture/unwritable-cache",QFileDevice::ReadOwner|QFileDevice::ExeOwner),"make fixture cache read-only");backend.m_appdata->get()->overrideCacheLocations("/fixture/unwritable-cache","/fixture/unwritable-cache");beforeErrors=errors.count();before=loads.count();backend.reloadPackageList();require(until(settled),"actual pool load failure settles without retry");require(loads.count()==before+1&&!loads.last().at(0).toBool(),"real AppStream returned failure");require(until([&]{return errors.count()>beforeErrors;}),"original AppStream error remains visible");QTest::qWait(80);require(loads.count()==before+1&&backend.m_isFetching==0,"failed load did not retry or leak fetching");
 qInfo()<<"PASS failed pool load: original error visible, no implicit retry, fetching balanced";require(contents.count()>0,"real contents notifications delivered");qInfo()<<"PASS: actual PackageKitBackend refresh lifecycle and resource generation tests";return 0;
#endif
}
