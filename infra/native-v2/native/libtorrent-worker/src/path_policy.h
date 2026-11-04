#pragma once

#include <filesystem>
#include <string>

namespace worker {

// True when candidate is located under root (root itself counts as inside).
inline bool is_within_root(const std::filesystem::path& candidate,
                           const std::filesystem::path& root) {
    const std::filesystem::path relative = candidate.lexically_normal().lexically_relative(root);
    if (relative.empty() || relative.is_absolute()) {
        return false;
    }
    const auto first = relative.begin();
    return first != relative.end() && *first != std::filesystem::path("..");
}

inline bool resolve_following_symlinks(const std::filesystem::path& input,
                                       const std::filesystem::path& root,
                                       int& symlink_hops,
                                       std::filesystem::path& out,
                                       std::string& error) {
    constexpr int kMaxSymlinkHops = 40;
    std::filesystem::path current = input.root_path();
    bool entered_root = is_within_root(current, root);

    for (const auto& part : input.relative_path()) {
        if (part.empty() || part == ".") {
            continue;
        }
        if (part == "..") {
            current = current.parent_path();
            if (entered_root && !is_within_root(current, root)) {
                error = "storage_path must stay under the worker root";
                return false;
            }
            continue;
        }

        current /= part;

        std::error_code ec;
        const std::filesystem::file_status st = std::filesystem::symlink_status(current, ec);
        if (ec && ec != std::errc::no_such_file_or_directory) {
            error = "storage_path cannot be resolved";
            return false;
        }

        if (!ec && std::filesystem::is_symlink(st)) {
            if (++symlink_hops > kMaxSymlinkHops) {
                error = "storage_path symlink depth exceeded";
                return false;
            }
            const std::filesystem::path target = std::filesystem::read_symlink(current, ec);
            if (ec) {
                error = "storage_path symlink cannot be read";
                return false;
            }
            const std::filesystem::path combined =
                    target.is_absolute() ? target : (current.parent_path() / target);
            if (!resolve_following_symlinks(combined, root, symlink_hops, current, error)) {
                return false;
            }
        }

        if (is_within_root(current, root)) {
            entered_root = true;
        } else if (entered_root) {
            error = "storage_path must stay under the worker root";
            return false;
        }
    }

    out = current.lexically_normal();
    return true;
}

// Resolves a requested storage path against the configured root and rejects any
// escape (absolute outside root, .. traversal, or symlink leaving the root).
inline bool safe_storage_path(const std::filesystem::path& root,
                              const std::filesystem::path& requested,
                              std::filesystem::path& resolved,
                              std::string& error) {
    if (requested.empty()) {
        error = "storage_path is required";
        return false;
    }

    std::filesystem::path candidate = requested;
    if (!candidate.is_absolute()) {
        candidate = root / candidate;
    }

    int symlink_hops = 0;
    std::filesystem::path canonical_candidate;
    if (!resolve_following_symlinks(candidate, root, symlink_hops, canonical_candidate, error)) {
        return false;
    }

    if (!is_within_root(canonical_candidate, root)) {
        error = "storage_path must stay under the worker root";
        return false;
    }

    resolved = canonical_candidate;
    return true;
}

inline bool safe_storage_path(const std::filesystem::path& root,
                              const std::filesystem::path& requested) {
    std::filesystem::path resolved;
    std::string error;
    return safe_storage_path(root, requested, resolved, error);
}

// Every data write must land exactly on <root>/resources/<resource_uuid>.
inline bool allowed_resource_path(const std::filesystem::path& root,
                                  const std::string& resource_id,
                                  const std::filesystem::path& resolved) {
    const std::filesystem::path expected = (root / "resources" / resource_id).lexically_normal();
    return resolved == expected;
}

}  // namespace worker
