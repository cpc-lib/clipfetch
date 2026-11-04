#pragma once

#include <fcntl.h>
#include <sys/file.h>
#include <unistd.h>

#include <chrono>
#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <map>
#include <iostream>
#include <mutex>
#include <string>
#include <thread>

// Same-host fencing: retain each flock until process exit. Never unlink lock files.
// The watchdog exits the whole process before the kernel releases any lock, so
// asynchronous libtorrent disk jobs cannot outlive physical write ownership.
class ResourceFences {
    struct Fence {
        int fd;
        std::int64_t epoch;
        std::int64_t expires;
    };
    std::filesystem::path root_;
    std::mutex mutex_;
    std::map<std::string, Fence> fences_;

    static std::int64_t now_ms() {
        return std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::system_clock::now().time_since_epoch()).count();
    }

public:
    explicit ResourceFences(std::filesystem::path root) : root_(std::move(root)) {
        std::filesystem::create_directories(root_);
    }

    void release_drained(const std::string& rid) {
        std::lock_guard<std::mutex> guard(mutex_);
        auto it = fences_.find(rid);
        if (it != fences_.end()) {
            close(it->second.fd);
            fences_.erase(it);
        }
    }

    grpc::Status validate(const cc::ivera::magnet::engine::v1::CommandEnvelope& env,
                          bool adding, bool needs_data_lease) {
        const auto now = now_ms();
        const auto expiry = env.lease_until().seconds() * 1000 + env.lease_until().nanos() / 1000000;
        const auto deadline = env.deadline().seconds() * 1000 + env.deadline().nanos() / 1000000;
        if (deadline <= now || expiry <= now) {
            return {grpc::StatusCode::DEADLINE_EXCEEDED, "command or lease expired"};
        }
        const auto& rid = env.resource_id();
        if (rid.size() != 36 || rid.find_first_not_of("0123456789abcdefABCDEF-") != std::string::npos
                || env.owner_epoch() < 0 || (needs_data_lease && env.owner_epoch() == 0)) {
            return {grpc::StatusCode::FAILED_PRECONDITION, "invalid resource or data lease"};
        }
        std::lock_guard<std::mutex> guard(mutex_);
        auto it = fences_.find(rid);
        if (it == fences_.end()) {
            if (!adding) {
                return {grpc::StatusCode::FAILED_PRECONDITION, "resource is not attached"};
            }
            const int fd = open((root_ / rid).c_str(), O_CREAT | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0600);
            if (fd < 0) {
                return {grpc::StatusCode::FAILED_PRECONDITION, "cannot open resource fence"};
            }
            if (flock(fd, LOCK_EX | LOCK_NB) != 0) {
                close(fd);
                return {grpc::StatusCode::FAILED_PRECONDITION, "resource is locked by another Worker"};
            }
            char buffer[32] = {};
            const auto count = pread(fd, buffer, sizeof(buffer) - 1, 0);
            char* end = nullptr;
            const auto previous = count > 0 ? std::strtoll(buffer, &end, 10) : 0;
            if (count < 0 || (count > 0 && (end == buffer || *end != '\0'))
                    || env.owner_epoch() < previous) {
                close(fd);
                return {grpc::StatusCode::FAILED_PRECONDITION, "stale epoch or invalid fence watermark"};
            }
            const auto text = std::to_string(env.owner_epoch());
            if (pwrite(fd, text.data(), text.size(), 0) != static_cast<ssize_t>(text.size())
                    || ftruncate(fd, text.size()) != 0 || fsync(fd) != 0) {
                close(fd);
                return {grpc::StatusCode::FAILED_PRECONDITION, "cannot persist fence watermark"};
            }
            it = fences_.emplace(rid, Fence{fd, env.owner_epoch(), expiry}).first;
        } else {
            auto& fence = it->second;
            if (fence.expires <= now || env.owner_epoch() < fence.epoch
                    || (env.owner_epoch() > fence.epoch && (!adding || fence.epoch != 0))) {
                return {grpc::StatusCode::FAILED_PRECONDITION, "stale ownership; process restart required"};
            }
            if (env.owner_epoch() > fence.epoch) {
                const auto text = std::to_string(env.owner_epoch());
                if (pwrite(fence.fd, text.data(), text.size(), 0) != static_cast<ssize_t>(text.size())
                        || ftruncate(fence.fd, text.size()) != 0 || fsync(fence.fd) != 0) {
                    return {grpc::StatusCode::FAILED_PRECONDITION, "cannot persist new epoch"};
                }
                fence.epoch = env.owner_epoch();
                fence.expires = expiry;
            } else {
                fence.expires = std::max(fence.expires, expiry);
            }
        }
        return grpc::Status::OK;
    }

    void watchdog() {
        for (;;) {
            {
                std::lock_guard<std::mutex> guard(mutex_);
                for (const auto& [rid, fence] : fences_) {
                    if (fence.expires <= now_ms()) {
                        std::cerr << "[fence] lease expired; exiting Worker resource=" << rid << std::endl;
                        std::_Exit(75);
                    }
                }
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
    }
};
