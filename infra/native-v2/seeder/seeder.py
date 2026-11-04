#!/usr/bin/env python3
"""Seed the deterministic fixtures at a throttled upload rate for acceptance."""
import json
import os
import sys
import time

import libtorrent as lt

FIXTURE_ROOT = "/fixtures"
DATA_ROOT = os.path.join(FIXTURE_ROOT, "data")
READY_FILE = os.path.join(FIXTURE_ROOT, "ready")
UPLOAD_RATE_BPS = 16384


def main():
    manifest = json.load(open(os.path.join(FIXTURE_ROOT, "manifest.runtime.json"), encoding="utf-8"))
    settings = {
        "listen_interfaces": "0.0.0.0:6882",
        "enable_dht": False,
        "enable_lsd": False,
        "enable_upnp": False,
        "enable_natpmp": False,
        "upload_rate_limit": UPLOAD_RATE_BPS,
        "alert_mask": int(lt.alert.category_t.error_notification | lt.alert.category_t.status_notification),
    }
    session = lt.session(settings)
    handles = []
    for label, fixture in manifest["fixtures"].items():
        info = lt.torrent_info(os.path.join(FIXTURE_ROOT, fixture["torrent"]))
        params = lt.add_torrent_params()
        params.ti = info
        params.save_path = DATA_ROOT
        params.flags |= lt.torrent_flags.seed_mode
        handle = session.add_torrent(params)
        handle.force_reannounce()
        handles.append(handle)
        print(f"[seeder] seeding {label} name={fixture['name']} pieces={info.num_pieces()}", flush=True)

    time.sleep(1)
    with open(READY_FILE, "w", encoding="utf-8") as ready:
        ready.write(f"{len(handles)}\n")
    while True:
        time.sleep(5)
        for label, handle in zip(manifest["fixtures"].keys(), handles):
            status = handle.status()
            if status.is_seeding and status.num_peers > 0:
                print(f"[seeder] {label} peers={status.num_peers} uploaded={status.total_upload}", flush=True)


if __name__ == "__main__":
    sys.exit(main())
