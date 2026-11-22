#!/usr/bin/env python3
"""Build Pinch's portable plugin ZIP, with separate development/release checks."""
import argparse
import json
from pathlib import Path
import re
from urllib.parse import urlsplit
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "plugins" / "pinch"


def https_url(value):
    p = urlsplit(value)
    if p.scheme != "https" or not p.hostname or p.username or p.password or p.hostname in {"localhost", "127.0.0.1", "example.com", "example.org"} or p.hostname.endswith((".test", ".invalid", ".example")):
        raise argparse.ArgumentTypeError("Provide a real public HTTPS URL without credentials.")
    return value


def build(output, settings=None):
    manifest = json.loads((SOURCE / "plugin.json").read_text())
    mcp = json.loads((SOURCE / "mcp.json").read_text())
    if settings:
        interface = manifest["extensions"]["com.openai"]["interface"]
        for field, option in {"websiteURL": "website", "supportURL": "support", "privacyPolicyURL": "privacy", "termsOfServiceURL": "terms"}.items():
            interface[field] = settings[option]
        manifest["author"]["email"] = settings["support_email"]
        manifest["homepage"] = settings["website"]
        manifest["extensions"]["com.openai"]["review"]["demo_recording_url"] = settings["demo"]
        mcp["mcpServers"]["pinch"]["url"] = settings["server"].rstrip("/") + "/mcp"
    files = {"plugin.json": (json.dumps(manifest, indent=2, ensure_ascii=False)+"\n").encode(),
             "mcp.json": (json.dumps(mcp, indent=2)+"\n").encode()}
    for folder in ["skills", "assets"]:
        for file in sorted((SOURCE / folder).rglob("*")):
            if file.is_file() and not file.name.startswith("."):
                if file.is_symlink():
                    raise ValueError("Plugin assets may not be symlinks.")
                files[file.relative_to(SOURCE).as_posix()] = file.read_bytes()
    interface = manifest["extensions"]["com.openai"]["interface"]
    for field in ["composerIcon", "logo"]:
        assert interface[field].removeprefix("./") in files, f"Missing {field}"
    assert "skills/recipes/SKILL.md" in files
    assert len(interface["shortDescription"]) <= 30
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in files.items():
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", action="store_true", help="Require real deployment and review metadata")
    parser.add_argument("--output", type=Path)
    for name in ["server", "website", "support", "privacy", "terms", "demo"]:
        parser.add_argument("--"+name, type=https_url)
    parser.add_argument("--support-email")
    args = parser.parse_args()
    settings = vars(args)
    if args.release:
        missing = [name for name in ["server", "website", "support", "privacy", "terms", "demo", "support_email"] if not settings[name]]
        if missing:
            parser.error("Release needs " + ", ".join("--"+n.replace("_", "-") for n in missing))
        if urlsplit(args.server).path not in {"", "/"} or urlsplit(args.server).query or urlsplit(args.server).fragment:
            parser.error("--server must be the public origin, without /mcp or other paths.")
        if not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", args.support_email):
            parser.error("Provide a valid public support email.")
    elif any(settings[name] for name in ["server", "website", "support", "privacy", "terms", "demo", "support_email"]):
        parser.error("Publication settings require --release.")
    suffix = "" if args.release else "-dev"
    result = build(args.output or ROOT / "dist" / f"pinch-1.0.0{suffix}.zip", settings if args.release else None)
    print(result)
    if not args.release:
        print("Development package: localhost endpoint; not a public submission.")


if __name__ == "__main__":
    main()
