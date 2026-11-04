// libtorrent gRPC Worker for ClipFetch native engine v2 (design §7-§8).
//
// The Worker owns a single libtorrent session plus one torrent handle per
// resource_id. All settings come from environment keys; nothing is hard-coded
// except protocol invariants. Every mutating RPC is fenced by command_id,
// resource_id, owner_epoch, deadline and lease_until. A lease that expires
// terminates the process before the kernel releases the flock, so asynchronous
// disk writes cannot outlive the control plane's ownership.

#include "download_engine_v2.grpc.pb.h"
#include "download_engine_v2.pb.h"

#include "ip_filter_policy.h"
#include "path_policy.h"
#include "resource_fences.h"

#include <grpcpp/grpcpp.h>

#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/alert_types.hpp>
#include <libtorrent/bdecode.hpp>
#include <libtorrent/bencode.hpp>
#include <libtorrent/create_torrent.hpp>
#include <libtorrent/info_hash.hpp>
#include <libtorrent/ip_filter.hpp>
#include <libtorrent/read_resume_data.hpp>
#include <libtorrent/session.hpp>
#include <libtorrent/torrent_handle.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/torrent_status.hpp>
#include <libtorrent/version.hpp>
#include <libtorrent/write_resume_data.hpp>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

namespace proto = cc::ivera::magnet::v2::proto;
using proto::DownloadEngineV2;

struct Settings {
    std::string worker_id = "local-worker-1";
    std::string listen_address = "0.0.0.0:50051";
    std::string download_root = "/data";
    std::string lock_root = "/locks";
    bool dht_enabled = true;
    bool block_private_networks = true;
    bool tls_enabled = false;
    bool allow_plaintext = false;
    std::string ca_cert;
    std::string server_cert;
    std::string server_key;
    int max_connections = 500;
    int max_upload_slots = 20;
    int download_rate_limit = 0;
    int upload_rate_limit = 0;
    int alert_queue_size = 1000;
    int disk_write_buffer_bytes = 32 * 1024 * 1024;
};

std::string env_string(const char* key, const std::string& fallback) {
    const char* value = std::getenv(key);
    return value == nullptr ? fallback : std::string(value);
}

int env_int(const char* key, int fallback) {
    const char* value = std::getenv(key);
    if (value == nullptr || *value == '\0') {
        return fallback;
    }
    char* end = nullptr;
    const long parsed = std::strtol(value, &end, 10);
    if (end == value || *end != '\0') {
        std::cerr << "[worker] 配置 " << key << " 不是合法整数，使用默认值" << std::endl;
        return fallback;
    }
    return static_cast<int>(parsed);
}

bool env_bool(const char* key, bool fallback) {
    const char* value = std::getenv(key);
    if (value == nullptr) {
        return fallback;
    }
    std::string text(value);
    if (text == "1" || text == "true" || text == "TRUE" || text == "yes") return true;
    if (text == "0" || text == "false" || text == "FALSE" || text == "no") return false;
    return fallback;
}

std::optional<std::string> read_text_file(const std::string& path) {
    std::ifstream stream(path, std::ios::binary);
    if (!stream) {
        return std::nullopt;
    }
    std::ostringstream buffer;
    buffer << stream.rdbuf();
    if (!stream.good() && !stream.eof()) {
        return std::nullopt;
    }
    return buffer.str();
}

// ---- Shared worker state ----

struct SessionEntry {
    lt::torrent_handle handle;
    std::mutex operation_mutex;
    bool ready = false;
    bool removed = false;
    bool removing = false;
};

std::once_flag g_session_init;
std::unique_ptr<lt::session> g_session;
std::mutex g_handles_mutex;
std::map<std::string, std::shared_ptr<SessionEntry>> g_handles;
std::mutex g_resume_mutex;
std::map<std::string, std::int64_t> g_resume_versions;
std::mutex g_alert_mutex;
std::filesystem::path g_download_root;
std::unique_ptr<worker::ResourceFences> g_fences;
std::mutex g_command_mutex;
Settings g_settings;

// Bounded replay cache for mutating commands whose success carries no payload.
struct CachedOutcome {
    proto::EngineErrorCode code;
    std::string error;
};
std::mutex g_cache_mutex;
std::map<std::string, CachedOutcome> g_outcomes;
std::deque<std::string> g_outcome_order;
constexpr std::size_t kOutcomeCacheLimit = 256;

std::shared_ptr<SessionEntry> find_session_entry(const std::string& rid) {
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    auto it = g_handles.find(rid);
    return it == g_handles.end() ? nullptr : it->second;
}

void erase_session_entry_if_current(const std::string& rid,
                                    const std::shared_ptr<SessionEntry>& entry) {
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    auto it = g_handles.find(rid);
    if (it != g_handles.end() && it->second == entry) {
        g_handles.erase(it);
    }
}

std::int64_t next_resume_version(const std::string& rid) {
    std::lock_guard<std::mutex> lock(g_resume_mutex);
    return ++g_resume_versions[rid];
}

bool replay_cached(const std::string& command_id, CachedOutcome& outcome) {
    if (command_id.empty()) {
        return false;
    }
    std::lock_guard<std::mutex> lock(g_cache_mutex);
    auto it = g_outcomes.find(command_id);
    if (it == g_outcomes.end()) {
        return false;
    }
    outcome = it->second;
    return true;
}

void cache_outcome(const std::string& command_id,
                   proto::EngineErrorCode code,
                   const std::string& error) {
    if (command_id.empty()) {
        return;
    }
    std::lock_guard<std::mutex> lock(g_cache_mutex);
    if (g_outcomes.find(command_id) != g_outcomes.end()) {
        return;
    }
    g_outcomes.emplace(command_id, CachedOutcome{code, error});
    g_outcome_order.push_back(command_id);
    while (g_outcome_order.size() > kOutcomeCacheLimit) {
        g_outcomes.erase(g_outcome_order.front());
        g_outcome_order.pop_front();
    }
}

template <typename Response>
void reject(Response* resp, proto::EngineErrorCode code, const std::string& message) {
    resp->set_accepted(false);
    resp->set_error_code(code);
    resp->set_error(message);
}

template <typename Response>
bool replay_into(const std::string& command_id, Response* resp) {
    CachedOutcome outcome{};
    if (!replay_cached(command_id, outcome)) {
        return true;
    }
    const bool success = outcome.code == proto::ENGINE_ERROR_CODE_NONE;
    resp->set_accepted(success);
    resp->set_error_code(outcome.code);
    resp->set_error(outcome.error);
    return false;
}

lt::ip_filter build_ip_filter() {
    lt::ip_filter filter;
    const auto block = lt::ip_filter::blocked;
    auto add4 = [&filter, block](const char* lo, const char* hi) {
        filter.add_rule(lt::make_address(lo), lt::make_address(hi), block);
    };
    add4("0.0.0.0", "0.255.255.255");
    add4("10.0.0.0", "10.255.255.255");
    add4("100.64.0.0", "100.127.255.255");
    add4("127.0.0.0", "127.255.255.255");
    add4("169.254.0.0", "169.254.255.255");
    add4("172.16.0.0", "172.31.255.255");
    add4("192.0.0.0", "192.0.2.255");
    add4("192.168.0.0", "192.168.255.255");
    add4("198.18.0.0", "198.19.255.255");
    add4("198.51.100.0", "198.51.100.255");
    add4("203.0.113.0", "203.0.113.255");
    add4("224.0.0.0", "255.255.255.255");
    filter.add_rule(lt::make_address("::1"), lt::make_address("::1"), block);
    filter.add_rule(lt::make_address("fc00::"), lt::make_address("fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"), block);
    filter.add_rule(lt::make_address("fe80::"), lt::make_address("febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff"), block);
    filter.add_rule(lt::make_address("ff00::"), lt::make_address("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"), block);
    return filter;
}

void init_session() {
    std::call_once(g_session_init, []() {
        const int listen_port = env_int("MAGNET_V2_WORKER_DOWNLOAD_PORT", 6881);
        lt::session_params params;
        params.settings.set_bool(lt::settings_pack::enable_dht, g_settings.dht_enabled);
        params.settings.set_bool(lt::settings_pack::enable_lsd, true);
        params.settings.set_int(lt::settings_pack::alert_mask, static_cast<int>(
                lt::alert_category::error | lt::alert_category::storage | lt::alert_category::status));
        params.settings.set_str(lt::settings_pack::listen_interfaces,
                                "0.0.0.0:" + std::to_string(listen_port));
        params.settings.set_int(lt::settings_pack::connections_limit, g_settings.max_connections);
        params.settings.set_int(lt::settings_pack::unchoke_slots_limit, g_settings.max_upload_slots);
        params.settings.set_int(lt::settings_pack::download_rate_limit, g_settings.download_rate_limit);
        params.settings.set_int(lt::settings_pack::upload_rate_limit, g_settings.upload_rate_limit);
        // libtorrent 默认对局域网 Peer 豁免限速；显式配置的速率上限必须对所有 Peer 生效。
        params.settings.set_bool(lt::settings_pack::ignore_limits_on_local_network, false);
        // 只用 TCP：uTP 连接在任务完成后不会及时关闭，种子端会把暂停后重连的新连接当重复连接丢弃，
        // 导致“暂停→恢复”永远无法重新拿到 Peer（已在 2.0.8 上复现）。
        params.settings.set_bool(lt::settings_pack::enable_outgoing_utp, false);
        params.settings.set_bool(lt::settings_pack::enable_incoming_utp, false);
        params.settings.set_int(lt::settings_pack::alert_queue_size, g_settings.alert_queue_size);
        params.settings.set_int(lt::settings_pack::max_queued_disk_bytes, g_settings.disk_write_buffer_bytes);
        g_session = std::make_unique<lt::session>(std::move(params));
        if (g_settings.block_private_networks) {
            g_session->set_ip_filter(build_ip_filter());
            std::cout << "[worker] private/reserved peer ranges blocked" << std::endl;
        }
        std::cout << "[worker] libtorrent session ready " << lt::version() << std::endl;
    });
}

bool await_removal(const lt::torrent_handle& handle, bool deleting) {
    std::lock_guard<std::mutex> alerts_guard(g_alert_mutex);
    const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(3);
    while (std::chrono::steady_clock::now() < until) {
        g_session->wait_for_alert(lt::milliseconds(50));
        std::vector<lt::alert*> alerts;
        g_session->pop_alerts(&alerts);
        for (const auto* alert : alerts) {
            if (deleting) {
                if (const auto* done = lt::alert_cast<lt::torrent_deleted_alert>(alert);
                        done != nullptr && done->handle == handle) return true;
            } else {
                if (const auto* done = lt::alert_cast<lt::torrent_removed_alert>(alert);
                        done != nullptr && done->handle == handle) return true;
            }
        }
    }
    return false;
}

bool hash_matches(const lt::torrent_info& info, const std::string& btih, const std::string& btmh) {
    const lt::info_hash_t hashes = info.info_hashes();
    if (!btih.empty()) {
        if (btih.size() != 20 || !hashes.has_v1()) {
            return false;
        }
        if (std::memcmp(btih.data(), hashes.v1.data(), 20) != 0) {
            return false;
        }
    }
    if (!btmh.empty()) {
        if (btmh.size() != 32 || !hashes.has_v2()) {
            return false;
        }
        if (std::memcmp(btmh.data(), hashes.v2.data(), 32) != 0) {
            return false;
        }
    }
    return true;
}

bool hash_lengths_valid(const std::string& btih, const std::string& btmh) {
    return (btih.empty() || btih.size() == 20) && (btmh.empty() || btmh.size() == 32);
}

class DownloadEngineV2Impl final : public DownloadEngineV2::Service {
public:
    grpc::Status Health(grpc::ServerContext*, const proto::HealthRequest*, proto::HealthResponse* resp) override {
        resp->set_ok(true);
        resp->set_worker_id(g_settings.worker_id);
        resp->set_libtorrent_version(lt::version());
        resp->set_message("");
        return grpc::Status::OK;
    }

    grpc::Status ResolveMetadata(grpc::ServerContext*, const proto::ResolveMetadataRequest* req,
                                 proto::ResolveMetadataResponse* resp) override {
        std::lock_guard<std::mutex> command_lock(g_command_mutex);
        const auto fenced = g_fences->validate(req->envelope(), false, false);
        if (!fenced.ok()) return fenced;
        const std::string& rid = req->envelope().resource_id();
        const auto entry = find_session_entry(rid);
        if (!entry) {
            reject(resp, proto::ENGINE_ERROR_CODE_RESOURCE_BUSY, "session not attached");
            return grpc::Status::OK;
        }
        std::shared_ptr<const lt::torrent_info> info;
        {
            std::lock_guard<std::mutex> lock(entry->operation_mutex);
            if (entry->removed || !entry->ready || !entry->handle.is_valid()) {
                reject(resp, proto::ENGINE_ERROR_CODE_RESOURCE_BUSY, "session not attached");
                return grpc::Status::OK;
            }
            if (!entry->handle.status().has_metadata) {
                resp->set_accepted(true);
                resp->set_error_code(proto::ENGINE_ERROR_CODE_NONE);
                resp->set_error("PENDING");
                return grpc::Status::OK;
            }
            info = entry->handle.torrent_file();
        }
        if (!info) {
            resp->set_accepted(true);
            resp->set_error_code(proto::ENGINE_ERROR_CODE_NONE);
            resp->set_error("PENDING");
            return grpc::Status::OK;
        }
        lt::create_torrent creator(*info);
        for (const auto& tracker : info->trackers()) {
            creator.add_tracker(tracker.url, tracker.tier);
        }
        std::vector<char> buffer;
        lt::bencode(std::back_inserter(buffer), creator.generate());
        resp->set_accepted(true);
        resp->set_error_code(proto::ENGINE_ERROR_CODE_NONE);
        resp->set_torrent_file(buffer.data(), buffer.size());
        std::cout << "[worker] metadata resolved resource=" << rid
                  << " bytes=" << buffer.size() << " files=" << info->num_files() << std::endl;
        return grpc::Status::OK;
    }

    grpc::Status AddTorrent(grpc::ServerContext*, const proto::AddTorrentRequest* req,
                            proto::AddTorrentResponse* resp) override {
        if (!replay_into(req->envelope().command_id(), resp)) {
            return grpc::Status::OK;
        }
        const std::string& rid = req->envelope().resource_id();
        if (!hash_lengths_valid(req->btih(), req->btmh())) {
            reject(resp, proto::ENGINE_ERROR_CODE_INVALID_ARGUMENT, "invalid info hash length");
            cache_outcome(req->envelope().command_id(), proto::ENGINE_ERROR_CODE_INVALID_ARGUMENT,
                          "invalid info hash length");
            return grpc::Status::OK;
        }

        std::filesystem::path storage_path;
        std::string storage_error;
        if (!worker::safe_storage_path(g_download_root, req->storage_path(), storage_path, storage_error)) {
            reject(resp, proto::ENGINE_ERROR_CODE_UNSAFE_PATH, storage_error);
            cache_outcome(req->envelope().command_id(), proto::ENGINE_ERROR_CODE_UNSAFE_PATH, storage_error);
            return grpc::Status::OK;
        }
        if (!worker::allowed_resource_path(g_download_root, rid, storage_path)) {
            reject(resp, proto::ENGINE_ERROR_CODE_UNSAFE_PATH, "storage_path must match resources/resource_id");
            cache_outcome(req->envelope().command_id(), proto::ENGINE_ERROR_CODE_UNSAFE_PATH,
                          "storage_path must match resources/resource_id");
            return grpc::Status::OK;
        }

        std::lock_guard<std::mutex> command_lock(g_command_mutex);
        const auto fenced = g_fences->validate(req->envelope(), true, !req->metadata_only());
        if (!fenced.ok()) return fenced;
        init_session();

        lt::add_torrent_params params;
        if (!req->resume_data().empty()) {
            lt::error_code ec;
            params = lt::read_resume_data(
                    lt::span<const char>(req->resume_data().data(), req->resume_data().size()), ec);
            if (ec) {
                reject(resp, proto::ENGINE_ERROR_CODE_INVALID_ARGUMENT, "resume_data cannot be parsed");
                cache_outcome(req->envelope().command_id(), proto::ENGINE_ERROR_CODE_INVALID_ARGUMENT,
                              "resume_data cannot be parsed");
                return grpc::Status::OK;
            }
        } else if (!req->torrent_file().empty()) {
            lt::error_code ec;
            lt::bdecode_node root = lt::bdecode(
                    lt::span<const char>(req->torrent_file().data(), req->torrent_file().size()), ec);
            if (ec) {
                reject(resp, proto::ENGINE_ERROR_CODE_INVALID_ARGUMENT, "torrent_file cannot be parsed");
                cache_outcome(req->envelope().command_id(), proto::ENGINE_ERROR_CODE_INVALID_ARGUMENT,
                              "torrent_file cannot be parsed");
                return grpc::Status::OK;
            }
            lt::torrent_info info(root, ec);
            if (ec) {
                reject(resp, proto::ENGINE_ERROR_CODE_INVALID_ARGUMENT, "torrent_info cannot be built");
                cache_outcome(req->envelope().command_id(), proto::ENGINE_ERROR_CODE_INVALID_ARGUMENT,
                              "torrent_info cannot be built");
                return grpc::Status::OK;
            }
            if (!hash_matches(info, req->btih(), req->btmh())) {
                reject(resp, proto::ENGINE_ERROR_CODE_HASH_MISMATCH, "torrent_file hash does not match request");
                cache_outcome(req->envelope().command_id(), proto::ENGINE_ERROR_CODE_HASH_MISMATCH,
                              "torrent_file hash does not match request");
                return grpc::Status::OK;
            }
            params.ti = std::make_shared<lt::torrent_info>(std::move(info));
        } else if (!req->btih().empty() || !req->btmh().empty()) {
            if (req->btih().size() == 20) {
                lt::sha1_hash v1;
                std::memcpy(v1.data(), req->btih().data(), 20);
                params.info_hashes.v1 = v1;
            }
            if (req->btmh().size() == 32) {
                lt::sha256_hash v2;
                std::memcpy(v2.data(), req->btmh().data(), 32);
                params.info_hashes.v2 = v2;
            }
            for (const auto& tracker : req->trackers()) {
                params.trackers.push_back(tracker);
            }
            if (params.trackers.empty()) {
                for (const char* tracker : {
                        "udp://tracker.openbittorrent.com:6969/announce",
                        "udp://tracker.opentrackr.org:1337/announce",
                        "udp://open.stealth.si:80/announce",
                        "udp://exodus.desync.com:6969/announce"}) {
                    params.trackers.emplace_back(tracker);
                }
            }
        } else {
            reject(resp, proto::ENGINE_ERROR_CODE_INVALID_ARGUMENT, "no torrent_file and no info hash");
            cache_outcome(req->envelope().command_id(), proto::ENGINE_ERROR_CODE_INVALID_ARGUMENT,
                          "no torrent_file and no info hash");
            return grpc::Status::OK;
        }

        params.save_path = storage_path.string();
        params.flags &= ~lt::torrent_flags::seed_mode;
        params.have_pieces.clear();
        params.verified_pieces.clear();
        params.unfinished_pieces.clear();
        if (req->metadata_only()) {
            params.flags |= lt::torrent_flags::default_dont_download;
        }

        std::shared_ptr<SessionEntry> entry;
        std::unique_lock<std::mutex> entry_lock;
        for (;;) {
            auto candidate = std::make_shared<SessionEntry>();
            std::unique_lock<std::mutex> candidate_lock(candidate->operation_mutex);
            bool inserted = false;
            {
                std::lock_guard<std::mutex> lock(g_handles_mutex);
                auto it = g_handles.find(rid);
                if (it == g_handles.end()) {
                    entry = candidate;
                    g_handles.emplace(rid, entry);
                    inserted = true;
                } else {
                    entry = it->second;
                }
            }
            if (inserted) {
                entry_lock = std::move(candidate_lock);
                break;
            }
            candidate_lock.unlock();
            std::unique_lock<std::mutex> existing_lock(entry->operation_mutex);
            if (entry->removing) {
                reject(resp, proto::ENGINE_ERROR_CODE_RESOURCE_BUSY, "previous removal has not drained");
                cache_outcome(req->envelope().command_id(), proto::ENGINE_ERROR_CODE_RESOURCE_BUSY,
                              "previous removal has not drained");
                return grpc::Status::OK;
            }
            if (!entry->removed && entry->ready && entry->handle.is_valid()) {
                const bool upgrading_with_metadata =
                        !entry->handle.status().has_metadata &&
                        (!req->torrent_file().empty() || !req->resume_data().empty());
                if (!upgrading_with_metadata) {
                    if (!req->metadata_only()) {
                        entry->handle.resume();
                    }
                    resp->set_accepted(true);
                    resp->set_error_code(proto::ENGINE_ERROR_CODE_NONE);
                    return grpc::Status::OK;
                }
                g_session->remove_torrent(entry->handle);
                if (!await_removal(entry->handle, false)) {
                    entry->removing = true;
                    std::_Exit(75);
                }
                entry->ready = false;
                entry->handle = lt::torrent_handle();
                entry_lock = std::move(existing_lock);
                break;
            }
            erase_session_entry_if_current(rid, entry);
        }

        lt::error_code ec;
        lt::torrent_handle handle = g_session->add_torrent(std::move(params), ec);
        if (ec) {
            entry->removed = true;
            erase_session_entry_if_current(rid, entry);
            reject(resp, proto::ENGINE_ERROR_CODE_STORAGE_UNAVAILABLE, "add_torrent failed");
            cache_outcome(req->envelope().command_id(), proto::ENGINE_ERROR_CODE_STORAGE_UNAVAILABLE,
                          "add_torrent failed");
            return grpc::Status::OK;
        }
        entry->handle = handle;
        if (!req->metadata_only()) {
            handle.force_recheck();
        }
        entry->ready = true;
        resp->set_accepted(true);
        resp->set_error_code(proto::ENGINE_ERROR_CODE_NONE);
        cache_outcome(req->envelope().command_id(), proto::ENGINE_ERROR_CODE_NONE, "");
        std::cout << "[worker] AddTorrent resource=" << rid
                  << " metadata_only=" << req->metadata_only() << std::endl;
        return grpc::Status::OK;
    }

    grpc::Status UpdateFilePriorities(grpc::ServerContext*, const proto::UpdateFilePrioritiesRequest* req,
                                      proto::UpdateFilePrioritiesResponse* resp) override {
        if (!replay_into(req->envelope().command_id(), resp)) {
            return grpc::Status::OK;
        }
        std::lock_guard<std::mutex> command_lock(g_command_mutex);
        const auto fenced = g_fences->validate(req->envelope(), false, true);
        if (!fenced.ok()) return fenced;
        const std::string& rid = req->envelope().resource_id();
        const auto entry = find_session_entry(rid);
        if (!entry) {
            reject(resp, proto::ENGINE_ERROR_CODE_RESOURCE_BUSY, "resource_id handle not found");
            cache_outcome(req->envelope().command_id(), proto::ENGINE_ERROR_CODE_RESOURCE_BUSY,
                          "resource_id handle not found");
            return grpc::Status::OK;
        }
        std::lock_guard<std::mutex> lock(entry->operation_mutex);
        if (entry->removed || !entry->ready || !entry->handle.is_valid()) {
            reject(resp, proto::ENGINE_ERROR_CODE_RESOURCE_BUSY, "resource_id handle not found");
            cache_outcome(req->envelope().command_id(), proto::ENGINE_ERROR_CODE_RESOURCE_BUSY,
                          "resource_id handle not found");
            return grpc::Status::OK;
        }
        const auto info = entry->handle.torrent_file();
        // 控制端的文件清单不包含 BEP 47 padding 文件，优先级按下标只覆盖非 padding 文件。
        int selectable_files = 0;
        if (info) {
            for (int i = 0; i < info->num_files(); ++i) {
                if (!info->files().pad_file_at(i)) ++selectable_files;
            }
        }
        if (!info || req->file_priorities_size() != selectable_files) {
            reject(resp, proto::ENGINE_ERROR_CODE_INVALID_ARGUMENT, "file priorities do not match file count");
            cache_outcome(req->envelope().command_id(), proto::ENGINE_ERROR_CODE_INVALID_ARGUMENT,
                          "file priorities do not match file count");
            return grpc::Status::OK;
        }
        std::vector<int> priorities(static_cast<size_t>(info->num_files()), 0);
        int next_priority = 0;
        for (int i = 0; i < info->num_files(); ++i) {
            if (!info->files().pad_file_at(i)) {
                priorities[static_cast<size_t>(i)] = req->file_priorities(next_priority++);
            }
        }
        entry->handle.prioritize_files(priorities);
        resp->set_accepted(true);
        resp->set_error_code(proto::ENGINE_ERROR_CODE_NONE);
        cache_outcome(req->envelope().command_id(), proto::ENGINE_ERROR_CODE_NONE, "");
        return grpc::Status::OK;
    }

    grpc::Status Pause(grpc::ServerContext*, const proto::CommandRequest* req, proto::CommandResponse* resp) override {
        if (!replay_into(req->envelope().command_id(), resp)) {
            return grpc::Status::OK;
        }
        std::lock_guard<std::mutex> command_lock(g_command_mutex);
        const auto fenced = g_fences->validate(req->envelope(), false, false);
        if (!fenced.ok()) return fenced;
        const std::string& rid = req->envelope().resource_id();
        if (const auto entry = find_session_entry(rid)) {
            std::lock_guard<std::mutex> lock(entry->operation_mutex);
            if (!entry->removed && entry->ready && entry->handle.is_valid()) {
                entry->handle.pause();
            }
        }
        resp->set_accepted(true);
        resp->set_error_code(proto::ENGINE_ERROR_CODE_NONE);
        cache_outcome(req->envelope().command_id(), proto::ENGINE_ERROR_CODE_NONE, "");
        return grpc::Status::OK;
    }

    grpc::Status Resume(grpc::ServerContext*, const proto::CommandRequest* req, proto::CommandResponse* resp) override {
        if (!replay_into(req->envelope().command_id(), resp)) {
            return grpc::Status::OK;
        }
        std::lock_guard<std::mutex> command_lock(g_command_mutex);
        const auto fenced = g_fences->validate(req->envelope(), false, true);
        if (!fenced.ok()) return fenced;
        const std::string& rid = req->envelope().resource_id();
        if (const auto entry = find_session_entry(rid)) {
            std::lock_guard<std::mutex> lock(entry->operation_mutex);
            if (!entry->removed && entry->ready && entry->handle.is_valid()) {
                entry->handle.resume();
                // 暂停期间旧连接已断开，立刻重新 announce 拿回 Peer，而不是等 tracker interval。
                entry->handle.force_reannounce();
            }
        }
        resp->set_accepted(true);
        resp->set_error_code(proto::ENGINE_ERROR_CODE_NONE);
        cache_outcome(req->envelope().command_id(), proto::ENGINE_ERROR_CODE_NONE, "");
        return grpc::Status::OK;
    }

    grpc::Status GetSnapshot(grpc::ServerContext*, const proto::GetSnapshotRequest* req,
                             proto::GetSnapshotResponse* resp) override {
        std::lock_guard<std::mutex> command_lock(g_command_mutex);
        const auto fenced = g_fences->validate(req->envelope(), false, false);
        if (!fenced.ok()) return fenced;
        const std::string& rid = req->envelope().resource_id();
        const auto entry = find_session_entry(rid);
        if (!entry) {
            resp->set_accepted(true);
            resp->set_error_code(proto::ENGINE_ERROR_CODE_NONE);
            resp->set_state("DISCOVERING");
            resp->set_metadata_ready(false);
            return grpc::Status::OK;
        }
        std::lock_guard<std::mutex> lock(entry->operation_mutex);
        if (entry->removed || !entry->ready || !entry->handle.is_valid()) {
            resp->set_accepted(true);
            resp->set_error_code(proto::ENGINE_ERROR_CODE_NONE);
            resp->set_state("DISCOVERING");
            resp->set_metadata_ready(false);
            return grpc::Status::OK;
        }
        lt::torrent_status status = entry->handle.status();
        const char* state = "IDLE";
        switch (status.state) {
            case lt::torrent_status::checking_files:
            case lt::torrent_status::checking_resume_data:
                state = "RECOVERING";
                break;
            case lt::torrent_status::downloading_metadata:
                state = "DISCOVERING";
                break;
            case lt::torrent_status::downloading:
            case lt::torrent_status::finished:
            case lt::torrent_status::seeding:
                state = status.has_metadata ? "ACTIVE" : "METADATA_READY";
                break;
            default:
                state = "IDLE";
                break;
        }
        resp->set_accepted(true);
        resp->set_error_code(proto::ENGINE_ERROR_CODE_NONE);
        resp->set_state(state);
        resp->set_metadata_ready(status.has_metadata);
        resp->set_verified_bytes(status.total_done);
        resp->set_download_rate_bytes_per_sec(status.download_rate);
        resp->set_upload_rate_bytes_per_sec(status.upload_rate);
        resp->set_peer_count(status.num_peers);
        const auto info = entry->handle.torrent_file();
        const std::vector<std::int64_t> progress =
                entry->handle.file_progress(lt::torrent_handle::piece_granularity);
        // 与 UpdateFilePriorities 一致：只上报可选择的非 padding 文件，避免 BEP 47 padding 位移文件下标。
        for (int i = 0; info && i < static_cast<int>(progress.size()); ++i) {
            if (!info->files().pad_file_at(i)) {
                resp->add_file_verified_bytes(progress[static_cast<size_t>(i)]);
            }
        }
        return grpc::Status::OK;
    }

    grpc::Status SaveResumeData(grpc::ServerContext*, const proto::SaveResumeDataRequest* req,
                                proto::SaveResumeDataResponse* resp) override {
        std::lock_guard<std::mutex> command_lock(g_command_mutex);
        const auto fenced = g_fences->validate(req->envelope(), false, true);
        if (!fenced.ok()) return fenced;
        const std::string& rid = req->envelope().resource_id();
        const auto entry = find_session_entry(rid);
        if (!entry) {
            reject(resp, proto::ENGINE_ERROR_CODE_RESOURCE_BUSY, "resource_id handle not found");
            return grpc::Status::OK;
        }

        lt::torrent_handle handle;
        {
            std::lock_guard<std::mutex> lock(entry->operation_mutex);
            if (entry->removed || !entry->ready || !entry->handle.is_valid()) {
                reject(resp, proto::ENGINE_ERROR_CODE_RESOURCE_BUSY, "resource_id handle not found");
                return grpc::Status::OK;
            }
            handle = entry->handle;
        }

        std::lock_guard<std::mutex> alert_lock(g_alert_mutex);
        {
            std::lock_guard<std::mutex> lock(entry->operation_mutex);
            if (entry->removed || !entry->ready || !entry->handle.is_valid()) {
                reject(resp, proto::ENGINE_ERROR_CODE_RESOURCE_BUSY, "resource_id handle not found");
                return grpc::Status::OK;
            }
            entry->handle.save_resume_data(lt::torrent_handle::save_info_dict);
        }

        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
        while (std::chrono::steady_clock::now() < deadline) {
            g_session->wait_for_alert(lt::milliseconds(100));
            std::vector<lt::alert*> alerts;
            g_session->pop_alerts(&alerts);
            for (lt::alert* alert : alerts) {
                if (const auto* failed = lt::alert_cast<lt::save_resume_data_failed_alert>(alert);
                        failed != nullptr && failed->handle == handle) {
                    reject(resp, proto::ENGINE_ERROR_CODE_TEMPORARY_UNAVAILABLE,
                           "failed to save resume data");
                    return grpc::Status::OK;
                }
                if (const auto* saved = lt::alert_cast<lt::save_resume_data_alert>(alert);
                        saved != nullptr && saved->handle == handle) {
                    const std::vector<char> bytes = lt::write_resume_data_buf(saved->params);
                    if (bytes.empty()) {
                        reject(resp, proto::ENGINE_ERROR_CODE_INTERNAL, "resume data is empty");
                        return grpc::Status::OK;
                    }
                    resp->set_resume_data(bytes.data(), bytes.size());
                    resp->set_resume_version(next_resume_version(rid));
                    resp->set_accepted(true);
                    resp->set_error_code(proto::ENGINE_ERROR_CODE_NONE);
                    return grpc::Status::OK;
                }
            }
        }

        reject(resp, proto::ENGINE_ERROR_CODE_TEMPORARY_UNAVAILABLE, "saving resume data timed out");
        return grpc::Status::OK;
    }

    grpc::Status RemoveTorrent(grpc::ServerContext*, const proto::RemoveTorrentRequest* req,
                               proto::CommandResponse* resp) override {
        if (!replay_into(req->envelope().command_id(), resp)) {
            return grpc::Status::OK;
        }
        std::lock_guard<std::mutex> command_lock(g_command_mutex);
        const auto fenced = g_fences->validate(req->envelope(), false, req->remove_data());
        if (!fenced.ok()) return fenced;
        const std::string& rid = req->envelope().resource_id();
        if (const auto entry = find_session_entry(rid)) {
            std::lock_guard<std::mutex> lock(entry->operation_mutex);
            if (!entry->removing && entry->ready && entry->handle.is_valid()) {
                entry->removing = true;
                g_session->remove_torrent(entry->handle, req->remove_data()
                        ? lt::session_handle::delete_files : lt::remove_flags_t{});
            }
            if (entry->removing && !await_removal(entry->handle, req->remove_data())) {
                std::_Exit(75);
            }
            if (req->remove_data()) {
                std::error_code cleanup_error;
                std::filesystem::remove_all(g_download_root / "resources" / rid, cleanup_error);
                if (cleanup_error) {
                    std::_Exit(75);
                }
            }
            entry->removed = true;
            entry->ready = false;
            erase_session_entry_if_current(rid, entry);
            g_fences->release_drained(rid);
        }
        resp->set_accepted(true);
        resp->set_error_code(proto::ENGINE_ERROR_CODE_NONE);
        cache_outcome(req->envelope().command_id(), proto::ENGINE_ERROR_CODE_NONE, "");
        return grpc::Status::OK;
    }

    grpc::Status StreamAlerts(grpc::ServerContext* context, const proto::AlertStreamRequest*,
                              grpc::ServerWriter<proto::Alert>*) override {
        context->TryCancel();
        return grpc::Status(grpc::StatusCode::UNIMPLEMENTED, "StreamAlerts is not a state authority in phase one");
    }
};

bool plaintext_allowed(const std::string& listen_address) {
    if (g_settings.allow_plaintext) {
        return true;
    }
    const std::string host = listen_address.substr(0, listen_address.find(':'));
    return host == "127.0.0.1" || host == "::1" || host == "localhost" || host.rfind("127.", 0) == 0;
}

std::shared_ptr<grpc::ServerCredentials> build_credentials() {
    if (!g_settings.tls_enabled) {
        return grpc::InsecureServerCredentials();
    }
    auto ca = read_text_file(g_settings.ca_cert);
    auto cert = read_text_file(g_settings.server_cert);
    auto key = read_text_file(g_settings.server_key);
    if (!ca || !cert || !key) {
        std::cerr << "[worker] TLS 已启用但证书/私钥/CA 无法读取" << std::endl;
        return nullptr;
    }
    grpc::SslServerCredentialsOptions options(GRPC_SSL_REQUEST_AND_REQUIRE_CLIENT_CERTIFICATE_AND_VERIFY);
    options.pem_root_certs = *ca;
    options.pem_key_cert_pairs.push_back({*key, *cert});
    return grpc::SslServerCredentials(options);
}

int run_health_check(const std::string& target) {
    auto channel = grpc::CreateChannel(target, grpc::InsecureChannelCredentials());
    auto stub = DownloadEngineV2::NewStub(channel);
    proto::HealthRequest request;
    proto::HealthResponse response;
    grpc::ClientContext context;
    context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(3));
    const grpc::Status status = stub->Health(&context, request, &response);
    if (!status.ok() || !response.ok()) {
        std::cerr << "[worker] health check failed: " << status.error_message() << std::endl;
        return 1;
    }
    return 0;
}

}  // namespace

int main(int argc, char* argv[]) {
    std::string listen_address = env_string("MAGNET_V2_WORKER_LISTEN_ADDRESS", "0.0.0.0:50051");
    std::string download_root = env_string("MAGNET_V2_STORAGE_ROOT", "/data");
    std::string health_target;
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        if (arg.rfind("--port=", 0) == 0) {
            listen_address = "0.0.0.0:" + arg.substr(7);
        } else if (arg.rfind("--listen=", 0) == 0) {
            listen_address = arg.substr(std::string("--listen=").size());
        } else if (arg.rfind("--download-root=", 0) == 0) {
            download_root = arg.substr(std::string("--download-root=").size());
        } else if (arg == "--download-root" && i + 1 < argc) {
            download_root = argv[++i];
        } else if (arg.rfind("--health-check", 0) == 0) {
            health_target = arg.find('=') == std::string::npos
                    ? "127.0.0.1:50051"
                    : arg.substr(std::string("--health-check=").size());
        }
    }

    std::error_code path_ec;
    g_settings.download_root = std::filesystem::weakly_canonical(
            std::filesystem::absolute(download_root, path_ec), path_ec);
    if (path_ec) {
        std::cerr << "[worker] cannot resolve --download-root" << std::endl;
        return 1;
    }
    g_download_root = g_settings.download_root;

    if (!health_target.empty()) {
        return run_health_check(health_target);
    }

    g_settings.listen_address = listen_address;
    g_settings.worker_id = env_string("MAGNET_V2_WORKER_ID", "local-worker-1");
    g_settings.lock_root = env_string("MAGNET_V2_WORKER_LOCK_ROOT", "/locks");
    g_settings.dht_enabled = env_bool("MAGNET_V2_WORKER_DHT_ENABLED", true);
    g_settings.block_private_networks = env_bool("MAGNET_V2_BLOCK_PRIVATE_NETWORKS", true);
    g_settings.tls_enabled = env_bool("MAGNET_V2_WORKER_TLS_ENABLED", false);
    g_settings.allow_plaintext = env_bool("MAGNET_V2_WORKER_ALLOW_PLAINTEXT", false);
    g_settings.ca_cert = env_string("MAGNET_V2_WORKER_CA_CERT", "");
    g_settings.server_cert = env_string("MAGNET_V2_WORKER_SERVER_CERT", "");
    g_settings.server_key = env_string("MAGNET_V2_WORKER_SERVER_KEY", "");
    g_settings.max_connections = env_int("MAGNET_V2_WORKER_MAX_CONNECTIONS", 500);
    g_settings.max_upload_slots = env_int("MAGNET_V2_WORKER_MAX_UPLOAD_SLOTS", 20);
    g_settings.download_rate_limit = env_int("MAGNET_V2_WORKER_DOWNLOAD_RATE_LIMIT_BPS", 0);
    g_settings.upload_rate_limit = env_int("MAGNET_V2_WORKER_UPLOAD_RATE_LIMIT_BPS", 0);
    g_settings.alert_queue_size = env_int("MAGNET_V2_WORKER_ALERT_QUEUE_SIZE", 1000);
    g_settings.disk_write_buffer_bytes =
            env_int("MAGNET_V2_WORKER_DISK_WRITE_BUFFER_BYTES", 32 * 1024 * 1024);

    if (!g_settings.tls_enabled && !plaintext_allowed(listen_address)) {
        std::cerr << "[worker] 非回环地址拒绝明文 gRPC；请启用 TLS 或显式设置 "
                     "MAGNET_V2_WORKER_ALLOW_PLAINTEXT=true" << std::endl;
        return 1;
    }

    std::cout << "[worker] starting gRPC on " << listen_address
              << " root=" << g_download_root << " tls=" << g_settings.tls_enabled << std::endl;

    g_fences = std::make_unique<worker::ResourceFences>(g_settings.lock_root);
    std::thread([] { g_fences->watchdog(); }).detach();

    DownloadEngineV2Impl service;
    grpc::ServerBuilder builder;
    auto credentials = build_credentials();
    if (credentials == nullptr) {
        return 1;
    }
    builder.AddListeningPort(listen_address, credentials);
    builder.RegisterService(&service);
    std::unique_ptr<grpc::Server> server(builder.BuildAndStart());
    if (!server) {
        std::cerr << "[worker] gRPC server failed to start" << std::endl;
        return 1;
    }
    std::cout << "[worker] gRPC service ready" << std::endl;
    server->Wait();
    return 0;
}
