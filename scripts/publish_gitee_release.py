from __future__ import annotations

import argparse
import http.client
import json
import mimetypes
import os
import secrets
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


API_ROOT = "https://gitee.com/api/v5"
ASSET_NAMES = (
    "ccrelay-full.zip",
    "ccrelay-full.zip.sha256",
    "ccrelay-bootstrap.zip",
    "ccrelay-bootstrap.zip.sha256",
    "release-manifest.json",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Publish CC Relay release assets to Gitee.")
    parser.add_argument("--owner", required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--release-dir", required=True)
    parser.add_argument("--target-commitish", required=True)
    parser.add_argument("--tag", default="latest")
    parser.add_argument("--name", default="Latest CC Relay build")
    return parser.parse_args()


class GiteeClient:
    def __init__(self, owner: str, repo: str, token: str) -> None:
        self.owner = urllib.parse.quote(owner, safe="")
        self.repo = urllib.parse.quote(repo, safe="")
        self.token = token

    def request(self, method: str, path: str, fields: dict[str, Any] | None = None) -> Any:
        values = {"access_token": self.token, **(fields or {})}
        data = urllib.parse.urlencode(values).encode("utf-8")
        url = f"{API_ROOT}{path}"
        if method == "GET":
            url += "?" + data.decode("ascii")
            data = None
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("Accept", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                body = response.read()
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"Gitee API {method} {path} failed with HTTP {exc.code}: {detail}") from exc
        return json.loads(body.decode("utf-8")) if body else None

    def upload(self, release_id: int, file_path: Path) -> dict[str, Any]:
        boundary = "----ccrelay-" + secrets.token_hex(16)
        content_type = mimetypes.guess_type(file_path.name)[0] or "application/octet-stream"
        prefix = (
            f"--{boundary}\r\n"
            'Content-Disposition: form-data; name="access_token"\r\n\r\n'
            f"{self.token}\r\n"
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="{file_path.name}"\r\n'
            f"Content-Type: {content_type}\r\n\r\n"
        ).encode("utf-8")
        suffix = f"\r\n--{boundary}--\r\n".encode("ascii")
        length = len(prefix) + file_path.stat().st_size + len(suffix)
        connection = http.client.HTTPSConnection("gitee.com", timeout=1800)
        path = f"/api/v5/repos/{self.owner}/{self.repo}/releases/{release_id}/attach_files"
        connection.putrequest("POST", path)
        connection.putheader("Accept", "application/json")
        connection.putheader("Content-Type", f"multipart/form-data; boundary={boundary}")
        connection.putheader("Content-Length", str(length))
        connection.endheaders()
        try:
            connection.send(prefix)
            with file_path.open("rb") as source:
                while chunk := source.read(1024 * 1024):
                    connection.send(chunk)
            connection.send(suffix)
            response = connection.getresponse()
            body = response.read()
            if response.status < 200 or response.status >= 300:
                detail = body.decode("utf-8", errors="replace")
                raise RuntimeError(
                    f"Unable to upload {file_path.name} to Gitee: HTTP {response.status}: {detail}"
                )
            result = json.loads(body.decode("utf-8"))
        finally:
            connection.close()
        return result

    def release_by_tag(self, tag: str) -> dict[str, Any] | None:
        path = f"/repos/{self.owner}/{self.repo}/releases/tags/{urllib.parse.quote(tag, safe='')}"
        try:
            return self.request("GET", path)
        except RuntimeError as exc:
            if "HTTP 404" in str(exc):
                return None
            raise


def publish(args: argparse.Namespace) -> dict[str, Any]:
    token = os.environ.get("GITEE_TOKEN", "").strip()
    if not token:
        raise RuntimeError("GITEE_TOKEN is required")
    release_dir = Path(args.release_dir).resolve()
    assets = [release_dir / name for name in ASSET_NAMES]
    missing = [str(path) for path in assets if not path.is_file()]
    if missing:
        raise RuntimeError("Release assets are missing: " + ", ".join(missing))

    client = GiteeClient(args.owner, args.repo, token)
    release = client.release_by_tag(args.tag)
    fields = {
        "tag_name": args.tag,
        "name": args.name,
        "body": "Automatically mirrored from the latest successful GitHub Actions release build.",
        "prerelease": "false",
        "target_commitish": args.target_commitish,
    }
    if release:
        release = client.request(
            "PATCH",
            f"/repos/{client.owner}/{client.repo}/releases/{release['id']}",
            fields,
        )
    else:
        release = client.request("POST", f"/repos/{client.owner}/{client.repo}/releases", fields)

    release_id = int(release["id"])
    existing = client.request(
        "GET", f"/repos/{client.owner}/{client.repo}/releases/{release_id}/attach_files"
    )
    names = set(ASSET_NAMES)
    for attachment in existing:
        if attachment.get("name") in names:
            client.request(
                "DELETE",
                f"/repos/{client.owner}/{client.repo}/releases/{release_id}/attach_files/{attachment['id']}",
            )

    uploaded = []
    for asset in assets:
        result = client.upload(release_id, asset)
        if result.get("size") is not None and int(result["size"]) != asset.stat().st_size:
            raise RuntimeError(f"Gitee attachment size mismatch for {asset.name}")
        uploaded.append({"name": asset.name, "size": asset.stat().st_size})
    published = client.request(
        "GET", f"/repos/{client.owner}/{client.repo}/releases/{release_id}/attach_files"
    )
    published_names = {attachment.get("name") for attachment in published}
    if not set(ASSET_NAMES).issubset(published_names):
        raise RuntimeError("Gitee release is missing one or more published assets")
    return {"releaseId": release_id, "tag": args.tag, "assets": uploaded}


def main() -> int:
    try:
        print(json.dumps(publish(parse_args()), ensure_ascii=False, indent=2))
        return 0
    except Exception as exc:
        print(f"[ERROR] {exc}", file=os.sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
