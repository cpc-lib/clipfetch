#!/usr/bin/env python3
"""Generate deterministic payloads, V1/V2/Hybrid torrents and a runtime manifest."""
import hashlib
import json
import os
import shutil
import sys
import time
from urllib.parse import quote

import libtorrent as lt

SPEC_PATH = "/opt/seeder/manifest.json"
OUT_ROOT = "/fixtures"
DATA_ROOT = os.path.join(OUT_ROOT, "data")
TORRENT_ROOT = os.path.join(OUT_ROOT, "torrents")
RUNTIME_MANIFEST = os.path.join(OUT_ROOT, "manifest.runtime.json")


def deterministic_bytes(seed, rel, size):
    digest = hashlib.sha256(f"{seed}:{rel}".encode()).digest()
    out = bytearray()
    counter = 0
    while len(out) < size:
        out.extend(hashlib.sha256(digest + counter.to_bytes(8, "big")).digest())
        counter += 1
    return bytes(out[:size])


def materialize(fixture, seed):
    root = os.path.join(DATA_ROOT, fixture["name"])
    entries = []
    for item in fixture["files"]:
        rel = item["path"]
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        payload = deterministic_bytes(seed, f"{fixture['name']}/{rel}", item["bytes"])
        with open(path, "wb") as handle:
            handle.write(payload)
        entries.append({
            "path": rel,
            "bytes": item["bytes"],
            "sha256": hashlib.sha256(payload).hexdigest(),
        })
    return entries


def build_torrent(fixture, entries, spec):
    flags = {
        "v1": lt.create_torrent.v1_only,
        "v2": lt.create_torrent.v2_only,
        "hybrid": 0,
    }[fixture["kind"]]
    storage = lt.file_storage()
    for item in fixture["files"]:
        storage.add_file(f"{fixture['name']}/{item['path']}", item["bytes"])
    creator = lt.create_torrent(storage, spec["pieceLength"], flags)
    creator.add_tracker(spec["tracker"])
    lt.set_piece_hashes(creator, DATA_ROOT)
    raw = lt.bencode(creator.generate())
    torrent_path = os.path.join(TORRENT_ROOT, f"{fixture['kind']}.torrent")
    with open(torrent_path, "wb") as handle:
        handle.write(raw)

    info = lt.torrent_info(lt.bdecode(raw))
    hashes = info.info_hashes()
    btih = str(hashes.v1) if hashes.has_v1() else None
    btmh = str(hashes.v2) if hashes.has_v2() else None
    xhs = []
    if btih:
        xhs.append(f"urn:btih:{btih}")
    if btmh:
        xhs.append(f"urn:btmh:1220{btmh}")
    magnet = "magnet:?xt=" + "&xt=".join(xhs)
    magnet += "&dn=" + quote(fixture["name"])
    magnet += "&tr=" + quote(spec["tracker"], safe="")
    return raw, torrent_path, btih, btmh, magnet


def main():
    spec = json.load(open(SPEC_PATH, encoding="utf-8"))
    for path in (DATA_ROOT, TORRENT_ROOT):
        if os.path.isdir(path):
            shutil.rmtree(path)
        os.makedirs(path, exist_ok=True)

    runtime = {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "pieceLength": spec["pieceLength"],
        "tracker": spec["tracker"],
        "fixtures": {},
    }
    for label, fixture in spec["fixtures"].items():
        entries = materialize(fixture, spec["seedSalt"])
        _, torrent_path, btih, btmh, magnet = build_torrent(fixture, entries, spec)
        runtime["fixtures"][label] = {
            "kind": fixture["kind"],
            "name": fixture["name"],
            "torrent": os.path.relpath(torrent_path, OUT_ROOT),
            "magnet": magnet,
            "infoHashV1": btih,
            "infoHashV2": btmh,
            "totalBytes": sum(entry["bytes"] for entry in entries),
            "files": entries,
        }
    with open(RUNTIME_MANIFEST, "w", encoding="utf-8") as handle:
        json.dump(runtime, handle, indent=2, sort_keys=True)
    print(json.dumps({label: value["infoHashV2"] for label, value in runtime["fixtures"].items()}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
