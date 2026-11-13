#!/usr/bin/env python3
"""Fill the Randomize the Gathering app's art cache from Scryfall, following Scryfall's API rules.

How it stays within the rules:
  * Card -> image URLs come from ONE bulk-data request (Oracle Cards file), not from
    thousands of /cards/named calls (those are limited to 2 per second).
  * Images are downloaded straight from the scryfall.io file servers with a few
    parallel connections (default 4), and everything backs off on HTTP 429.
  * Every request sends an accurate User-Agent and an Accept header.
  * Images are stored unmodified (no resizing, no colour change).

Output: one file per card, named like the app's cache: sha256(card name) + ".img".
Double-faced cards whose back face has its own artwork (transform, modal) get a second
file for the back, named after sha256(card name + "|back").
Re-running resumes: files that already exist are skipped.

Usage:
  python fetch_art.py app/src/main/assets/creatures_min.json -o art --tar art_cache.tar
"""
import argparse
import concurrent.futures as cf
import gzip
import hashlib
import json
import re
import sys
import tarfile
import threading
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API_BASE = "https://api.scryfall.com"
CREDIT_NOTE = "Art (c) the respective artists; images (c) Wizards of the Coast, via Scryfall."


def cache_name(card_name: str, back: bool = False) -> str:
    """Must match ArtRepository.cacheFileName() in the app."""
    key = card_name + "|back" if back else card_name
    return hashlib.sha256(key.encode("utf-8")).hexdigest() + ".img"


def norm(name: str) -> str:
    s = unicodedata.normalize("NFKD", name).casefold()
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = s.replace("\u00e6", "ae")
    return re.sub(r"\s+", " ", s).strip()


def looks_like_image(data: bytes) -> bool:
    if len(data) < 500:
        return False
    return (
        data[:2] == b"\xff\xd8"
        or data[:4] == b"\x89PNG"
        or (data[:4] == b"RIFF" and data[8:12] == b"WEBP")
    )


class Client:
    def __init__(self, user_agent: str):
        self.user_agent = user_agent
        self.pause_until = 0.0
        self.lock = threading.Lock()

    def get(self, url: str, accept: str = "*/*", retries: int = 4) -> bytes:
        last = None
        for attempt in range(retries):
            delay = self.pause_until - time.time()
            if delay > 0:
                time.sleep(delay)
            req = urllib.request.Request(
                url, headers={"User-Agent": self.user_agent, "Accept": accept}
            )
            try:
                with urllib.request.urlopen(req, timeout=60) as resp:
                    return resp.read()
            except urllib.error.HTTPError as e:
                last = e
                if e.code == 429:
                    with self.lock:
                        self.pause_until = max(self.pause_until, time.time() + 60)
                    print("  HTTP 429: pausing all downloads for 60 s", file=sys.stderr)
                elif e.code in (500, 502, 503, 504):
                    time.sleep(2 * (attempt + 1))
                else:
                    raise
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                last = e
                time.sleep(2 * (attempt + 1))
        raise RuntimeError(f"GET {url} failed: {last}")


def load_names(path: Path, skip_funny: bool):
    data = json.loads(path.read_text(encoding="utf-8"))
    names = set()
    for cards in data.values():
        for c in cards:
            if skip_funny and c.get("f"):
                continue
            names.add(c["n"])
    return sorted(names)


def parse_cards(blob: bytes):
    if blob[:2] == b"\x1f\x8b":
        blob = gzip.decompress(blob)
    text = blob.decode("utf-8")
    if text.lstrip().startswith("["):
        yield from json.loads(text)
    else:
        for line in text.splitlines():
            line = line.strip()
            if line:
                yield json.loads(line)


def image_info(card: dict, version: str):
    """(front url, artist, back url or None), or None when there is no front image."""
    uris = card.get("image_uris")
    artist = card.get("artist", "")
    faces = card.get("card_faces") or []
    back_url = None
    if not uris and faces:
        face = faces[0]
        uris = face.get("image_uris")
        artist = face.get("artist") or artist
        if len(faces) > 1:
            back_uris = faces[1].get("image_uris")
            if back_uris and version in back_uris:
                back_url = back_uris[version]
    if not uris or version not in uris:
        return None
    return uris[version], artist, back_url


def build_index(client: Client, api_base: str, version: str):
    meta = json.loads(client.get(f"{api_base}/bulk-data", accept="application/json"))
    entry = next((e for e in meta["data"] if e.get("type") == "oracle_cards"), None)
    if entry is None:
        raise RuntimeError("No oracle_cards entry in /bulk-data")
    uri = entry.get("jsonl_download_uri") or entry["download_uri"]
    print(f"Downloading card list: {uri}")
    blob = client.get(uri)
    index = {}
    for card in parse_cards(blob):
        info = image_info(card, version)
        if info is None:
            continue
        name = card["name"]
        index.setdefault(norm(name), info)
        if " // " in name:
            index.setdefault(norm(name.split(" // ")[0]), info)
    print(f"Card list: {len(index)} entries with a '{version}' image")
    return index


def resolve_fallback(client: Client, api_base: str, name: str, version: str):
    """Slow path for names the bulk file did not match: /cards/named, max 2 per second."""
    time.sleep(0.6)
    url = f"{api_base}/cards/named?exact={urllib.parse.quote(name)}"
    try:
        card = json.loads(client.get(url, accept="application/json"))
    except urllib.error.HTTPError:
        return None
    return image_info(card, version)


def download(client: Client, url: str, dest: Path):
    data = client.get(url)
    if not looks_like_image(data):
        raise RuntimeError("response is not an image")
    tmp = dest.with_name(dest.name + ".tmp")
    tmp.write_bytes(data)
    tmp.replace(dest)
    return len(data)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("creatures_json", type=Path, help="creatures_min.json from the app")
    ap.add_argument("-o", "--out", type=Path, default=Path("art"))
    ap.add_argument("--version", default="art_crop",
                    help="Scryfall image version; must match what the app requests (default art_crop)")
    ap.add_argument("--workers", type=int, default=4, help="parallel downloads (default 4)")
    ap.add_argument("--skip-funny", action="store_true", help="skip Un-set / funny creatures")
    ap.add_argument("--fallback", action="store_true",
                    help="resolve names missing from the bulk file via /cards/named (2/s)")
    ap.add_argument("--contact", default="", help="optional contact (email/URL) for the User-Agent")
    ap.add_argument("--limit", type=int, default=0, help="only handle the first N names (testing)")
    ap.add_argument("--tar", type=Path, help="also write a tar for 'adb exec-in ... tar xf -'")
    ap.add_argument("--api-base", default=API_BASE, help=argparse.SUPPRESS)
    args = ap.parse_args(argv)

    ua = "RandomizeTheGatheringArtFetcher/1.0" + (f" ({args.contact})" if args.contact else "")
    client = Client(ua)
    names = load_names(args.creatures_json, args.skip_funny)
    if args.limit:
        names = names[: args.limit]
    print(f"{len(names)} creature names to cover")

    args.out.mkdir(parents=True, exist_ok=True)
    index = build_index(client, args.api_base, args.version)

    jobs, missing, credits = [], [], {}
    for name in names:
        info = index.get(norm(name))
        if info is None and args.fallback:
            info = resolve_fallback(client, args.api_base, name, args.version)
        if info is None:
            missing.append(name)
            continue
        url, artist, back_url = info
        credits[name] = artist
        jobs.append((name, url, args.out / cache_name(name)))
        if back_url:
            jobs.append((name + " (back)", back_url, args.out / cache_name(name, back=True)))

    todo = [j for j in jobs if not (j[2].exists() and j[2].stat().st_size > 0)]
    skipped = len(jobs) - len(todo)
    print(f"{len(jobs)} images matched, {skipped} already present, {len(todo)} to download, {len(missing)} names not found")

    done = failed = total_bytes = 0
    failures = []
    started = time.time()
    try:
        with cf.ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
            futs = {pool.submit(download, client, url, dest): name for name, url, dest in todo}
            for fut in cf.as_completed(futs):
                try:
                    total_bytes += fut.result()
                    done += 1
                except Exception as e:  # noqa: BLE001
                    failed += 1
                    failures.append(f"{futs[fut]}: {e}")
                if (done + failed) % 100 == 0:
                    rate = (done + failed) / max(1, time.time() - started)
                    print(f"  {done + failed}/{len(todo)} ({rate:.1f}/s, {total_bytes / 1e6:.0f} MB)")
    except KeyboardInterrupt:
        print("Interrupted. Run again to resume.", file=sys.stderr)

    credits_path = args.out.with_name(args.out.name + "_credits.json")
    credits_path.write_text(
        json.dumps({"note": CREDIT_NOTE, "artists": credits}, ensure_ascii=False, indent=1),
        encoding="utf-8",
    )
    problems = args.out.with_name(args.out.name + "_problems.txt")
    lines = [f"NOT FOUND: {n}" for n in missing] + [f"FAILED: {f}" for f in failures]
    problems.write_text("\n".join(lines), encoding="utf-8")

    if args.tar:
        with tarfile.open(args.tar, "w") as tf:
            for f in sorted(args.out.glob("*.img")):
                tf.add(f, arcname=f"art/{f.name}")
        print(f"Wrote {args.tar}")

    size = sum(f.stat().st_size for f in args.out.glob("*.img"))
    print(f"Done: {done} downloaded, {skipped} skipped, {failed} failed, {len(missing)} names not found.")
    print(f"Cache folder: {len(list(args.out.glob('*.img')))} files, {size / 1e6:.0f} MB. Details in {problems.name}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
