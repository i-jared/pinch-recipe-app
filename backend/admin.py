"""Local operator tools; run on the server or with docker compose exec pinch."""
import argparse
import getpass
import os
from pathlib import Path
import sqlite3
from server import password_hash


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("backup", help="Consistent backup, including photos, while the server is running").add_argument("destination", type=Path)
    commands.add_parser("reset-password", help="Reset a forgotten password and revoke all sessions").add_argument("username")
    args = parser.parse_args()
    path = Path(os.getenv("PINCH_DATA_DIR", "./data")) / "pinch.sqlite3"
    if not path.is_file():
        parser.error("Server database not found. Start the server first or set PINCH_DATA_DIR.")
    with sqlite3.connect(path) as db:
        db.execute("PRAGMA foreign_keys=ON")
        if args.command == "backup":
            # Never overwrite an existing backup, and restrict its permissions immediately.
            descriptor = os.open(args.destination, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            os.close(descriptor)
            with sqlite3.connect(args.destination) as target:
                db.backup(target)
                assert target.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
            print(f"Backup saved to {args.destination}")
        else:
            user = db.execute("SELECT id FROM users WHERE username=?", (args.username.lower(),)).fetchone()
            if not user:
                parser.error("Username not found.")
            password = getpass.getpass("New password (10–256 characters): ")
            if not 10 <= len(password) <= 256 or password != getpass.getpass("Repeat password: "):
                parser.error("Passwords must match and contain 10–256 characters.")
            db.execute("UPDATE users SET password=? WHERE id=?", (password_hash(password), user[0]))
            db.execute("DELETE FROM sessions WHERE user_id=?", (user[0],))
            for table in ("oauth_codes", "oauth_grants"):
                if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone():
                    db.execute(f"DELETE FROM {table} WHERE user_id=?", (user[0],))
            print("Password reset. Sign in again on each device.")


if __name__ == "__main__":
    main()
