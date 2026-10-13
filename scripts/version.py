#!/usr/bin/env python3
"""
Semantic version tracker and automatic Git commit versioner for ODialer Video Ringtone.
Tracks version in VERSION file (0.1.x) and automatically bumps patch version on git commit.
"""

import sys
import os
import re
import subprocess
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
VERSION_FILE = REPO_ROOT / "VERSION"
GIT_DIR = REPO_ROOT / ".git"
PENDING_VER_FILE = GIT_DIR / "CURRENT_COMMIT_VERSION"


def parse_semver(ver_str: str):
    m = re.match(r"^(\d+)\.(\d+)\.(\d+)(.*)$", ver_str.strip())
    if not m:
        return (0, 1, 0, "")
    return (int(m.group(1)), int(m.group(2)), int(m.group(3)), m.group(4))


def format_semver(major: int, minor: int, patch: int, suffix: str = "") -> str:
    return f"{major}.{minor}.{patch}{suffix}"


def get_current_version() -> str:
    if VERSION_FILE.exists():
        content = VERSION_FILE.read_text(encoding="utf-8").strip()
        if content:
            return content
    return "0.1.0"


def set_version(ver_str: str):
    VERSION_FILE.write_text(ver_str.strip() + "\n", encoding="utf-8")


def bump(part: str = "patch") -> str:
    curr = get_current_version()
    major, minor, patch, suffix = parse_semver(curr)
    if part == "major":
        major += 1
        minor = 0
        patch = 0
    elif part == "minor":
        minor += 1
        patch = 0
    else:  # patch
        patch += 1
    new_ver = format_semver(major, minor, patch, suffix)
    set_version(new_ver)
    return new_ver


def pre_commit():
    if os.environ.get("SKIP_VERSION_BUMP") == "1":
        return 0

    # Don't bump during rebase or merge commits
    if (GIT_DIR / "MERGE_HEAD").exists() or (GIT_DIR / "rebase-merge").exists() or (GIT_DIR / "rebase-apply").exists():
        return 0

    new_ver = bump("patch")
    if PENDING_VER_FILE.parent.exists():
        PENDING_VER_FILE.write_text(new_ver, encoding="utf-8")

    # Stage the updated VERSION file
    subprocess.run(["git", "add", str(VERSION_FILE)], cwd=str(REPO_ROOT), check=True)
    print(f"[Version Hook] Auto-bumped to v{new_ver}")
    return 0


def commit_msg(msg_file_path: str):
    if not os.path.exists(msg_file_path):
        return 0

    version = None
    if PENDING_VER_FILE.exists():
        try:
            version = PENDING_VER_FILE.read_text(encoding="utf-8").strip()
            PENDING_VER_FILE.unlink(missing_ok=True)
        except Exception:
            pass

    if not version:
        version = get_current_version()

    prefix = f"[v{version}]"

    with open(msg_file_path, "r", encoding="utf-8", errors="replace") as f:
        content = f.read()

    lines = content.splitlines()
    if not lines:
        return 0

    first_line = lines[0].strip()

    # Skip if merge commit, tag, or already prefixed
    if (
        first_line.startswith("Merge ")
        or first_line.startswith(prefix)
        or re.match(r"^\[v?\d+\.\d+\.\d+\]", first_line)
        or re.match(r"^v?\d+\.\d+\.\d+:", first_line)
    ):
        return 0

    lines[0] = f"{prefix} {first_line}"
    new_content = "\n".join(lines)
    if content.endswith("\n"):
        new_content += "\n"

    with open(msg_file_path, "w", encoding="utf-8") as f:
        f.write(new_content)

    return 0


def main():
    args = sys.argv[1:]
    if not args or args[0] == "get":
        print(get_current_version())
    elif args[0] == "set" and len(args) > 1:
        set_version(args[1])
        print(f"Version set to {args[1]}")
    elif args[0] == "bump":
        part = args[1] if len(args) > 1 else "patch"
        new_ver = bump(part)
        print(f"Bumped to {new_ver}")
    elif args[0] == "pre-commit":
        sys.exit(pre_commit())
    elif args[0] == "commit-msg" and len(args) > 1:
        sys.exit(commit_msg(args[1]))
    else:
        print("Usage: version.py [get | set <ver> | bump [patch|minor|major] | pre-commit | commit-msg <file>]")


if __name__ == "__main__":
    main()
