// C.19 libtorrent-backed persistent native playback session.
// The old bounded torrent_probe worker is NOT called by this module.
// The real engine is compiled only when libtorrent source is provided under
// cpp/third_party/libtorrent. Absent that dependency, the UI fails closed.
#include "streaming_torrent_session.h"
#include "torrent_media_type.h"
#include "torrent_discovery_hints.h"
#include "torrent_network_preflight.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cerrno>
#include <cctype>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#ifdef PM_WITH_LIBTORRENT
#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/session.hpp>
#include <libtorrent/torrent_handle.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/file_storage.hpp>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/alert_types.hpp>
#include <libtorrent/alert.hpp>
#include <libtorrent/operations.hpp>
#include <fcntl.h>
#include <arpa/inet.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <sys/stat.h>
#include <unistd.h>
#endif
namespace pm_torrent {
namespace {
struct Demand {
  uint64_t offset = 0;
  uint32_t bytes = 0;
  int urgency = 0;
  std::chrono::steady_clock::time_point at;
};
struct Engine {
  std::atomic<bool> closing{false};
  std::mutex lock;
  std::thread scheduler;
  std::string hash;
  int requestedFile = -1;
  int fileIndex = -1;
  uint64_t fileOffset = 0;
  uint64_t fileSize = 0;
  uint64_t playhead = 0;
  uint64_t urgentCount = 0;
  std::vector<Demand> requests;
  std::vector<int> scheduledPieceIds;
  std::string state = "Opening persistent torrent session";
  std::string directory;
  int trackerCount = 0;
  int trackerReplies = 0;
  int trackerErrors = 0;
  int dhtHints = 0;
  int dhtNodes = 0;
  bool dhtBootstrapped = false;
  bool privateTorrent = false;
  std::string lastDiscoveryEvent = "Waiting for network activity";
  std::string lastError;
  std::string nativeNetworkProbe;
  std::string lastListenFailure;
  int listenSuccesses = 0;
#ifdef PM_WITH_LIBTORRENT
  std::unique_ptr<libtorrent::session> session;
  libtorrent::torrent_handle torrent;
  std::string relativeName;
  std::string selectedPath;
#endif
};
std::mutex sessionLock;
std::shared_ptr<Engine> active;
// Advances even if an OpenStreamingTorrent call has not published its handle yet.
std::atomic<uint64_t> cancelEpoch{0};

bool ValidHash(const std::string& hash) {
  if(hash.size()!=40) return false;
  for(char ch:hash) if(!std::isxdigit(static_cast<unsigned char>(ch))) return false;
  return true;
}
#ifdef PM_WITH_LIBTORRENT
namespace lt=libtorrent;
// Select a concrete local IPv4 address for a single-interface, foreground
// playback session. This avoids libtorrent's wildcard-expansion dependency
// on the routing table, which can be inaccessible in restricted Android app sandboxes.
// Never expose the chosen address in user-visible diagnostics.
std::string PreferredListenIPv4() {
  ifaddrs* head=nullptr;
  if(::getifaddrs(&head)!=0) return {};
  std::string selected;
  for(auto* it=head;it;it=it->ifa_next) {
    if(!it->ifa_addr || it->ifa_addr->sa_family!=AF_INET)continue;
    if(!(it->ifa_flags & IFF_UP) || (it->ifa_flags & IFF_LOOPBACK))continue;
    const auto* addr=reinterpret_cast<const sockaddr_in*>(it->ifa_addr);
    const uint32_t ip=ntohl(addr->sin_addr.s_addr);
    // Reject unspecified, loopback, and IPv4 link-local candidates.
    if((ip>>24)==0 || (ip>>24)==127 || (ip>>16)==0xA9FE)continue;
    char buf[INET_ADDRSTRLEN]{};
    if(::inet_ntop(AF_INET,&addr->sin_addr,buf,sizeof(buf))) {
      selected=buf;
      break;
    }
  }
  ::freeifaddrs(head);
  return selected;
}
bool IsVideo(const std::string& name) {
  const auto dot=name.find_last_of('.');
  if(dot==std::string::npos) return false;
  std::string ext=name.substr(dot);
  std::transform(ext.begin(),ext.end(),ext.begin(),[](unsigned char c){return char(std::tolower(c));});
  return TorrentMediaTypeFromName(name).extension != "bin";
}
// Only names selected from SHA/authenticated metadata; libtorrent's storage is
// responsible for safe file-name handling. Do not use tracker-supplied paths.
void ResolveMetadata(const std::shared_ptr<Engine>& e) {
  if(!e->torrent.is_valid()) return;
  std::shared_ptr<const lt::torrent_info> info=e->torrent.torrent_file();
  if(!info) return;
  const auto& fs=info->layout();
  e->privateTorrent=info->priv();
  if(e->privateTorrent) {
    // This is a one-torrent session. Disable DHT after authenticated private
    // metadata arrives; libtorrent itself also forbids DHT/PEX for this torrent.
    lt::settings_pack privacy;
    privacy.set_bool(lt::settings_pack::enable_dht,false);
    e->session->apply_settings(privacy);
    e->lastDiscoveryEvent="Authenticated private torrent: DHT disabled";
  }
  int chosen=e->requestedFile;
  if(chosen<-1||chosen>=fs.num_files()) {e->state="Invalid selected file index";return;}
  if(chosen==-1) {
    uint64_t largest=0;
    for(int i=0;i<fs.num_files();++i) {
      const auto idx=lt::file_index_t(i);
      const uint64_t length=uint64_t(fs.file_size(idx));
      if(IsVideo(fs.file_path(idx))&&length>largest) {chosen=i;largest=length;}
    }
    if(chosen<0) for(int i=0;i<fs.num_files();++i) {
      const auto idx=lt::file_index_t(i);
      if(uint64_t(fs.file_size(idx))>largest){chosen=i;largest=uint64_t(fs.file_size(idx));}
    }
  }
  if(chosen<0 || fs.file_size(lt::file_index_t(chosen))<=0) {e->state="No playable file in torrent";return;}
  // Use a single file preference. Hash pieces overlapping neighbouring files
  // may still be fetched as required by libtorrent's piece verification.
  for(int i=0;i<fs.num_files();++i) {
    e->torrent.file_priority(lt::file_index_t(i), i==chosen ? lt::default_priority : lt::dont_download);
  }
  e->fileIndex=chosen;
  e->fileOffset=uint64_t(fs.file_offset(lt::file_index_t(chosen)));
  e->fileSize=uint64_t(fs.file_size(lt::file_index_t(chosen)));
  e->relativeName=fs.file_path(lt::file_index_t(chosen));
  e->selectedPath=e->directory+"/"+e->relativeName;
  e->state="Persistent torrent active; verified pieces scheduled";
}
void SetPieces(const std::shared_ptr<Engine>& e,uint64_t offset,uint64_t bytes,int deadline) {
  if(e->fileSize==0||offset>=e->fileSize||bytes==0) return;
  std::shared_ptr<const lt::torrent_info> ti=e->torrent.torrent_file();
  if(!ti)return;
  const int64_t pieceSize=ti->piece_length();
  if(pieceSize<=0)return;
  const uint64_t first=(e->fileOffset+offset)/uint64_t(pieceSize);
  const uint64_t last=(e->fileOffset+std::min(e->fileSize,offset+std::min(bytes,e->fileSize-offset))-1)/uint64_t(pieceSize);
  // Bound changes: no full-torrent O(n) deadline update on every Range read.
  for(uint64_t i=first;i<=last && i<first+512;++i) {
    const auto piece=lt::piece_index_t(int(i));
    if(e->torrent.have_piece(piece)) {
      const int id=int(i);
      e->scheduledPieceIds.erase(std::remove(e->scheduledPieceIds.begin(),
          e->scheduledPieceIds.end(),id), e->scheduledPieceIds.end());
    } else {
      // Do not reset deadlines every 350ms: that would prevent them from
      // ever advancing toward the true deadline.
      const int id=int(i);
      if(std::find(e->scheduledPieceIds.begin(),e->scheduledPieceIds.end(),id)==e->scheduledPieceIds.end()) {
        e->torrent.piece_priority(piece,lt::top_priority);
        e->torrent.set_piece_deadline(piece, deadline+int((i-first)*120));
        e->scheduledPieceIds.push_back(id);
      }
    }
  }
}
void Scheduler(const std::shared_ptr<Engine>& e) {
  auto nextStats=std::chrono::steady_clock::now();
  while(!e->closing.load()) {
    try {
      if(e->session) {
        const auto now=std::chrono::steady_clock::now();
        if(now>=nextStats) {
          e->session->post_dht_stats();
          nextStats=now+std::chrono::seconds(5);
        }
        std::vector<lt::alert*> alerts;
        e->session->pop_alerts(&alerts);
        if(!alerts.empty()) {
          std::lock_guard<std::mutex> guard(e->lock);
          for(const lt::alert* alert : alerts) {
            // Never log raw alert.message(): it may contain tracker tokens,
            // private announce URLs, or device network identifiers.
            if(const auto* a=lt::alert_cast<lt::tracker_error_alert>(alert)) {
              ++e->trackerErrors;
              e->lastError="Tracker error ("+std::to_string(a->error.value())+"): "+a->error.message();
              e->lastDiscoveryEvent="A tracker announce failed";
            } else if(const auto* a=lt::alert_cast<lt::tracker_reply_alert>(alert)) {
              ++e->trackerReplies;
              e->lastDiscoveryEvent="Tracker replied: "+std::to_string(a->num_peers)+" peer address(es)";
            } else if(lt::alert_cast<lt::dht_bootstrap_alert>(alert)) {
              e->dhtBootstrapped=true;
              e->lastDiscoveryEvent="DHT bootstrap completed";
            } else if(const auto* a=lt::alert_cast<lt::dht_stats_alert>(alert)) {
              int nodes=0;
              for(const auto& bucket:a->routing_table)nodes+=bucket.num_nodes;
              e->dhtNodes=std::max(0,nodes);
            } else if(const auto* a=lt::alert_cast<lt::dht_error_alert>(alert)) {
              e->lastError="DHT error: "+a->error.message();
            } else if(const auto* a=lt::alert_cast<lt::udp_error_alert>(alert)) {
              e->lastError="UDP network error: "+a->error.message();
            } else if(const auto* a=lt::alert_cast<lt::listen_failed_alert>(alert)) {
              // Unlike the heuristic session_error_alert message parser, this
              // has typed operation/socket fields supplied by libtorrent.
              // Never emit the listening IP or device name.
              e->lastListenFailure="socket="+std::to_string(int(a->socket_type))+
                " op="+std::string(lt::operation_name(a->op))+
                " errno="+std::to_string(a->error.value());
              e->lastError="Torrent listen failure: "+e->lastListenFailure+
                "; "+e->nativeNetworkProbe;
            } else if(const auto* a=lt::alert_cast<lt::listen_succeeded_alert>(alert)) {
              ++e->listenSuccesses;
              e->lastDiscoveryEvent="Torrent listener(s) opened: "+
                std::to_string(e->listenSuccesses);
            } else if(lt::alert_cast<lt::metadata_received_alert>(alert)) {
              e->lastDiscoveryEvent="Authenticated torrent metadata received";
            } else if(const auto* a=lt::alert_cast<lt::metadata_failed_alert>(alert)) {
              e->lastError="Torrent metadata rejected: "+a->error.message();
            } else if(const auto* a=lt::alert_cast<lt::torrent_error_alert>(alert)) {
              e->lastError="Torrent error: "+a->error.message();
            } else if(const auto* a=lt::alert_cast<lt::session_error_alert>(alert)) {
              e->state="Native network session failed";
              e->lastError="Network session error ("+std::to_string(a->error.value())+
                ", possible "+SafeSessionOperation(a->message())+"): "+a->error.message()+
                "; listeners opened="+std::to_string(e->listenSuccesses)+
                (e->lastListenFailure.empty() ? "" : "; last listen failure "+e->lastListenFailure)+
                "; "+e->nativeNetworkProbe;
            }
          }
          if(e->lastError.size()>360)e->lastError.resize(360);
        }
      }
      if(e->torrent.is_valid()) {
        {
          std::lock_guard<std::mutex> guard(e->lock);
          if(e->fileIndex<0)ResolveMetadata(e);
          if(e->fileSize>0) {
            // Opening and tail/index are independently prioritized. A tail
            // probe NEVER overwrites playhead priority.
            SetPieces(e,0,8*1024*1024,1200);
            SetPieces(e,e->fileSize>8*1024*1024 ? e->fileSize-8*1024*1024 : 0,
                      8*1024*1024,2800);
            SetPieces(e,e->playhead,32*1024*1024,3500);
            const auto now=std::chrono::steady_clock::now();
            e->requests.erase(std::remove_if(e->requests.begin(),e->requests.end(),
              [&](const Demand& d){return now-d.at>std::chrono::seconds(25);}),e->requests.end());
            for(const auto& d:e->requests)
              SetPieces(e,d.offset,d.bytes,d.urgency==0?300:1000);
          }
        }
      }
    }catch(const std::exception& ex) {
      std::lock_guard<std::mutex> guard(e->lock);
      e->state=std::string("Torrent scheduling error: ")+ex.what();
    }catch(...) {
      std::lock_guard<std::mutex> guard(e->lock);
      e->state="Torrent scheduling exception";
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(350));
  }
}
#endif
}

bool StreamingEngineAvailable() {
#ifdef PM_WITH_LIBTORRENT
  return true;
#else
  return false;
#endif
}
StreamingOpenResult OpenStreamingTorrent(const std::string& hash,int fileIndex,
    const std::string& trackers,const std::string& cacheDir) {
  const uint64_t openEpoch=cancelEpoch.load(std::memory_order_acquire);
  CloseStreamingTorrent();
  if(cancelEpoch.load(std::memory_order_acquire)!=openEpoch)
    return {false,"Torrent preparation cancelled"};
  if(!ValidHash(hash)||fileIndex < -1 || cacheDir.empty())
    return {false,"Invalid torrent identity or cache directory"};
#ifndef PM_WITH_LIBTORRENT
  (void)trackers;
  return {false,"C.19 libtorrent is not packaged. Add libtorrent source/NDK dependencies to build the native engine; legacy worker will not run."};
#else
  try {
    const DiscoveryHints discovery=ParseDiscoveryHints(trackers);
    auto e=std::make_shared<Engine>();
    e->trackerCount=int(discovery.trackers.size());
    e->dhtHints=int(discovery.dhtHints);
    e->lastDiscoveryEvent="Received "+std::to_string(e->trackerCount)+
      " tracker(s) (HTTP "+std::to_string(discovery.httpCount)+
      ", HTTPS "+std::to_string(discovery.httpsCount)+
      ", UDP "+std::to_string(discovery.udpCount)+")";
    e->hash=hash;e->requestedFile=fileIndex;
    e->nativeNetworkProbe=NativeNetworkPreflight();
    e->directory=cacheDir+"/pm-libtorrent";
    if(mkdir(e->directory.c_str(),0700)!=0 && errno!=EEXIST)
      return {false,"Cannot initialize the private torrent cache directory"};
    lt::settings_pack settings;
    settings.set_bool(lt::settings_pack::enable_dht,true);
    // Controlled compatibility test: avoid dual-stack listener enumeration and
    // port conflicts by using an OS-selected IPv4 port. This is not a
    // workaround for OS-denied getifaddrs()/socket() operations.
    const std::string listenIPv4=PreferredListenIPv4();
    // Bind by literal IP, never by interface name: the latter can activate
    // privileged SO_BINDTODEVICE. 0.0.0.0 remains a fallback when no
    // suitable non-loopback IPv4 address can be found.
    settings.set_str(lt::settings_pack::listen_interfaces,
      listenIPv4.empty() ? "0.0.0.0:0" : listenIPv4+":0");
    settings.set_bool(lt::settings_pack::validate_https_trackers,true);
    settings.set_int(lt::settings_pack::alert_mask,
      static_cast<int>(static_cast<std::uint32_t>(
        lt::alert_category::error | lt::alert_category::status |
        lt::alert_category::tracker | lt::alert_category::dht)));
    settings.set_bool(lt::settings_pack::enable_lsd,false);
    settings.set_bool(lt::settings_pack::enable_upnp,false);
    settings.set_bool(lt::settings_pack::enable_natpmp,false);
    e->session=std::make_unique<lt::session>(settings);
    auto atp=lt::parse_magnet_uri("magnet:?xt=urn:btih:"+hash);
    atp.save_path=e->directory;
    // Preserve add-on HTTP, HTTPS and UDP tracker hints, including the
    // Stremio `tracker:` prefix. No DHT ID is misinterpreted as a node URL.
    for(const auto& url : discovery.trackers)atp.trackers.push_back(url);
    e->torrent=e->session->add_torrent(std::move(atp));
    if(cancelEpoch.load(std::memory_order_acquire)!=openEpoch)
      return {false,"Torrent preparation cancelled"};
    e->scheduler=std::thread(Scheduler,e);
    {
      std::lock_guard<std::mutex> guard(sessionLock);
      if(cancelEpoch.load(std::memory_order_acquire)==openEpoch) active=e;
      else e->closing.store(true);
    }
    if(e->closing.load()) {
      if(e->scheduler.joinable())e->scheduler.join();
      return {false,"Torrent preparation cancelled"};
    }
    if(cancelEpoch.load(std::memory_order_acquire)!=openEpoch) {
      RequestStreamingTorrentCancel();
      CloseStreamingTorrent();
      return {false,"Torrent preparation cancelled"};
    }
    return {true,"Persistent libtorrent session created; waiting for authenticated metadata"};
  }catch(const std::exception& ex) {return {false,std::string("Cannot start libtorrent: ")+ex.what()};}
#endif
}
void RequestStreamingTorrentCancel() {
  cancelEpoch.fetch_add(1,std::memory_order_acq_rel);
  std::shared_ptr<Engine> e;
  {std::lock_guard<std::mutex> guard(sessionLock);e=active;}
  if(e)e->closing.store(true,std::memory_order_release);
}
namespace {
// One detached session owns all of its own libtorrent resources; its teardown
// cannot change the new active pointer or the cancellation epoch.
void ReapEngine(const std::shared_ptr<Engine>& old) {
  if (!old) return;
  old->closing.store(true, std::memory_order_release);
  if (old->scheduler.joinable()) old->scheduler.join();
#ifdef PM_WITH_LIBTORRENT
  // Other JNI readers may retain a shared_ptr<Engine>; serialize handle
  // destruction with those readers after the scheduling thread has exited.
  std::lock_guard<std::mutex> access(old->lock);
  old->torrent = {};
  old->session.reset(); // may await network shutdown; never run on UI caller
#endif
}
std::atomic<int> pendingReapers{0};
}
void CloseStreamingTorrent() {
  std::shared_ptr<Engine> old;
  { std::lock_guard<std::mutex> guard(sessionLock); old.swap(active); }
  ReapEngine(old);
}
void CloseStreamingTorrentAsync() {
  std::shared_ptr<Engine> old;
  { std::lock_guard<std::mutex> guard(sessionLock); old.swap(active); }
  if (!old) return;
  old->closing.store(true, std::memory_order_release);
  // Bound outstanding thread count. A torrent session cannot be freed before
  // its libtorrent workers exit, so never destroy it on the JNI caller thread
  // unless the reaper queue is already saturated.
  if (pendingReapers.fetch_add(1, std::memory_order_acq_rel) >= 3) {
    pendingReapers.fetch_sub(1, std::memory_order_acq_rel);
    ReapEngine(old);
    return;
  }
  try {
    std::thread([old] {
      ReapEngine(old);
      pendingReapers.fetch_sub(1, std::memory_order_acq_rel);
    }).detach();
  } catch (...) {
    pendingReapers.fetch_sub(1, std::memory_order_acq_rel);
    ReapEngine(old);
  }
}
StreamingInfo CurrentStreamingInfo() {
  std::shared_ptr<Engine> e;
  {std::lock_guard<std::mutex> guard(sessionLock);e=active;}
  StreamingInfo out;
  if(!e||e->closing.load()) {out.state="No torrent session";return out;}
  std::lock_guard<std::mutex> guard(e->lock);
  out.active=true;out.selectedFile=e->fileIndex;
  out.selectedSize=e->fileSize;out.metadata=e->fileIndex>=0;
  out.demands=e->urgentCount;out.state=e->state;
  out.trackerCount=e->trackerCount;
  out.trackerReplies=e->trackerReplies;
  out.trackerErrors=e->trackerErrors;
  out.dhtHints=e->dhtHints;
  out.dhtNodes=e->dhtNodes;
  out.dhtBootstrapped=e->dhtBootstrapped;
  out.privateTorrent=e->privateTorrent;
  out.lastDiscoveryEvent=e->lastDiscoveryEvent;
  out.lastError=e->lastError;
#ifdef PM_WITH_LIBTORRENT
  try {
    // M05d: the Play ARM64 build aborts with a stack-canary failure when
    // torrent_handle::status() returns its large torrent_status value here.
    // The 2026-11-25 and 2026-11-28 tombstones both point to this function,
    // from JNI torrentStatus() and torrentRangeUrl(). This is consistent with
    // a prebuilt libtorrent/client ABI or compile-configuration mismatch.
    // Until the dependency is rebuilt and verified with matching ABI flags,
    // do not request that by-value snapshot. Real authenticated metadata,
    // piece verification, and range serving continue to work independently.
    // Peer/seeder/rate counters intentionally remain unknown (zero) for M05d.
    const auto info=e->torrent.torrent_file();
    if(e->fileSize>0 && info && info->piece_length()>0 && e->playhead<e->fileSize) {
      const uint64_t step=uint64_t(info->piece_length());
      uint64_t pos=e->playhead;
      while(pos<e->fileSize && pos-e->playhead<128*1024*1024 &&
            e->torrent.have_piece(lt::piece_index_t(int((e->fileOffset+pos)/step)))) {
        pos=std::min(e->fileSize,((e->fileOffset+pos)/step+1)*step-e->fileOffset);
      }
      out.verifiedAhead=pos-e->playhead;
      // A playable MKV can require its Cues near the final byte, even before
      // frame 0. Report verified contiguous bytes backwards from true EOF.
      uint64_t cursor=e->fileSize;
      const uint64_t tailFloor=e->fileSize>2*1024*1024 ? e->fileSize-2*1024*1024 : 0;
      while(cursor>tailFloor && e->torrent.have_piece(lt::piece_index_t(int((e->fileOffset+cursor-1)/step)))) {
        const uint64_t boundary=((e->fileOffset+cursor-1)/step)*step;
        const uint64_t newCursor=boundary>e->fileOffset?boundary-e->fileOffset:0;
        if(newCursor>=cursor)break;
        cursor=newCursor;
      }
      out.verifiedTail=e->fileSize-cursor;
    }
  }catch(...) {out.state="Torrent status unavailable";}
#endif
  return out;
}
void DemandStreamingRange(uint64_t offset,uint32_t length,int urgency) {
  std::shared_ptr<Engine> e;
  {std::lock_guard<std::mutex> guard(sessionLock);e=active;}
  if(!e || e->closing.load())return;
  std::lock_guard<std::mutex> guard(e->lock);
  if(e->fileSize==0||offset>=e->fileSize)return;
  length=std::max<uint32_t>(64*1024,std::min<uint32_t>(length,8*1024*1024));
  const bool tail=offset>=e->fileSize-e->fileSize/10;
  // Only foreground reads update playhead. Tail probes stay secondary.
  if(!tail && offset>=8*1024*1024) {
    const uint64_t gap=offset>e->playhead?offset-e->playhead:e->playhead-offset;
    if(gap>48*1024*1024) {
#ifdef PM_WITH_LIBTORRENT
      // A large seek reprioritises all stale deadlines, not just one piece.
      e->torrent.clear_piece_deadlines();
#endif
      e->scheduledPieceIds.clear();
    }
    e->playhead=offset;
  }
  auto it=std::find_if(e->requests.begin(),e->requests.end(),[&](const Demand& d) {
    return d.offset/262144==offset/262144;
  });
  Demand demand{offset,length,urgency,std::chrono::steady_clock::now()};
  if(it!=e->requests.end())*it=demand;
  else {
    if(e->requests.size()>=32)e->requests.erase(e->requests.begin());
    e->requests.push_back(demand);
  }
  ++e->urgentCount;
}
uint64_t StreamingSelectedSize(const std::string& hash,int selectedFile) {
  std::shared_ptr<Engine> e;
  {std::lock_guard<std::mutex> guard(sessionLock);e=active;}
  if(!e||e->closing.load())return 0;
  std::lock_guard<std::mutex> guard(e->lock);
  return e->hash==hash&&e->fileIndex==selectedFile?e->fileSize:0;
}
std::string StreamingSelectedName(const std::string& hash,int selectedFile) {
  std::shared_ptr<Engine> e;
  {std::lock_guard<std::mutex> guard(sessionLock);e=active;}
  if(!e||e->closing.load())return {};
  std::lock_guard<std::mutex> guard(e->lock);
#ifdef PM_WITH_LIBTORRENT
  if(e->hash==hash && e->fileIndex==selectedFile && e->fileSize>0)
    return e->relativeName;
#else
  (void)hash;(void)selectedFile;
#endif
  return {};
}
bool ReadStreamingVerified(uint64_t offset,uint32_t length,std::vector<uint8_t>& output) {
  output.clear();
  if(length==0||length>1024*1024)return false;
  std::shared_ptr<Engine> e;
  {std::lock_guard<std::mutex> guard(sessionLock);e=active;}
  if(!e||e->closing.load())return false;
#ifndef PM_WITH_LIBTORRENT
  (void)offset;
  return false;
#else
  std::lock_guard<std::mutex> guard(e->lock);
  if(e->fileIndex<0||offset>e->fileSize||length>e->fileSize-offset)return false;
  const auto info=e->torrent.torrent_file();
  if(!info || info->piece_length()<=0)return false;
  const uint64_t pieceLen=uint64_t(info->piece_length());
  const uint64_t first=(e->fileOffset+offset)/pieceLen;
  const uint64_t last=(e->fileOffset+offset+length-1)/pieceLen;
  try {
    for(uint64_t i=first;i<=last;++i)
      if(!e->torrent.have_piece(lt::piece_index_t(int(i))))return false;
  }catch(...){return false;}
  const int fd=open(e->selectedPath.c_str(),O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
  if(fd<0)return false;
  output.resize(length);
  size_t received=0;
  while(received<length) {
    auto n=pread(fd,output.data()+received,length-received,off_t(offset+received));
    if(n<=0)break;
    received+=size_t(n);
  }
  close(fd);
  if(received!=length) {output.clear();return false;}
  return true;
#endif
}
}
