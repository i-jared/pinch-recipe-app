"""Pinch's single-node server. SQLite holds recipes, images and auth atomically."""

from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timezone
import asyncio
import ipaddress
import math
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import time
from typing import Annotated
import uuid

from anyio import to_thread

from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, HTMLResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from plugin_auth import SCHEMA as PLUGIN_SCHEMA
import cookbooks
import managed

ID = r"[a-zA-Z0-9_-]{1,80}"
PHOTO = rf"{ID}\.jpg"
MAX_PHOTO = 12 * 1024 * 1024
MAX_JSON = 1024 * 1024
SCHEMA = """
CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL, password TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, expires REAL NOT NULL);
CREATE TABLE IF NOT EXISTS recipes(user_id TEXT REFERENCES users(id) ON DELETE CASCADE, id TEXT, body TEXT NOT NULL, updated REAL NOT NULL, PRIMARY KEY(user_id,id));
CREATE TABLE IF NOT EXISTS photos(user_id TEXT REFERENCES users(id) ON DELETE CASCADE, name TEXT, body BLOB NOT NULL, PRIMARY KEY(user_id,name));
CREATE TABLE IF NOT EXISTS groups(id TEXT PRIMARY KEY, name TEXT NOT NULL, owner TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, invite TEXT UNIQUE NOT NULL);
CREATE TABLE IF NOT EXISTS members(group_id TEXT REFERENCES groups(id) ON DELETE CASCADE, user_id TEXT REFERENCES users(id) ON DELETE CASCADE, PRIMARY KEY(group_id,user_id));
CREATE TABLE IF NOT EXISTS shares(id TEXT PRIMARY KEY, group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, recipe_id TEXT NOT NULL, body TEXT NOT NULL, shared_at REAL NOT NULL DEFAULT 0, UNIQUE(group_id,user_id,recipe_id));
CREATE TABLE IF NOT EXISTS share_photos(share_id TEXT REFERENCES shares(id) ON DELETE CASCADE, name TEXT, body BLOB NOT NULL, PRIMARY KEY(share_id,name));
CREATE TABLE IF NOT EXISTS attempts(bucket TEXT PRIMARY KEY, count INTEGER NOT NULL, expires REAL NOT NULL);
CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);
CREATE INDEX IF NOT EXISTS shares_group ON shares(group_id);
CREATE TABLE IF NOT EXISTS group_invites(code TEXT PRIMARY KEY, group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE);
CREATE TABLE IF NOT EXISTS saved_shares(user_id TEXT REFERENCES users(id) ON DELETE CASCADE, share_id TEXT NOT NULL, recipe_id TEXT NOT NULL, PRIMARY KEY(user_id,share_id));
"""


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def password_hash(password: str, salt: str | None = None) -> str:
    salt = salt or secrets.token_hex(16)
    key = hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt), n=2**15, r=8, p=1, maxmem=64*1024*1024)
    return salt + ":" + key.hex()


def timestamp(value: str) -> float:
    try:
        date = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if date.tzinfo is None:
            raise ValueError()
        return date.timestamp()
    except (ValueError, OverflowError):
        raise HTTPException(422, "Recipe dates must be ISO-8601 with a timezone.")


class Credentials(BaseModel):
    username: str = Field(min_length=3, max_length=64, pattern=r"^[a-zA-Z0-9_.-]+$")
    password: str = Field(min_length=10, max_length=256)
    registrationKey: str = Field(default="", max_length=256)

    @field_validator("username")
    @classmethod
    def normalize(cls, value: str) -> str:
        return value.lower()


class Item(BaseModel):
    id: str = Field(min_length=1, max_length=80)
    text: str = Field(default="", max_length=20000)
    isHeader: bool = False


class RecipeOrigin(BaseModel):
    groupId: str = Field(pattern=f"^{ID}$")
    shareId: str = Field(pattern=f"^{ID}$")
    groupName: str = Field(max_length=80)
    sharedBy: str = Field(max_length=64)


class Recipe(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)
    schema_version: int = Field(default=1, alias="schema", ge=1, le=1)
    id: str = Field(pattern=f"^{ID}$")
    title: str = Field(default="", max_length=1000)
    summary: str = Field(default="", max_length=20000)
    source: str = Field(default="", max_length=20000)
    sourceUrl: str = Field(default="", max_length=10000)
    servings: str = Field(default="", max_length=1000)
    prepTime: str = Field(default="", max_length=1000)
    cookTime: str = Field(default="", max_length=1000)
    totalTime: str = Field(default="", max_length=1000)
    ingredients: list[Item] = Field(default_factory=list, max_length=1000)
    steps: list[Item] = Field(default_factory=list, max_length=1000)
    notes: str = Field(default="", max_length=200000)
    tags: list[Annotated[str, Field(max_length=200)]] = Field(default_factory=list, max_length=100)
    emoji: str = Field(default="🍽️", max_length=100)
    palette: str = Field(default="jade", max_length=30)
    pattern: str = Field(default="gingham", max_length=30)
    photos: list[Annotated[str, Field(pattern=f"^{PHOTO}$")]] = Field(default_factory=list, max_length=100)
    recipePhotos: list[Annotated[str, Field(pattern=f"^{PHOTO}$")]] = Field(default_factory=list, max_length=100)
    bannerPhoto: str | None = Field(default=None, pattern=f"^{PHOTO}$")
    isFavorite: bool = False
    createdAt: str = Field(max_length=40)
    updatedAt: str = Field(max_length=40)
    lastCookedAt: str | None = Field(default=None, max_length=40)
    rawText: str = Field(default="", max_length=200000)
    deletedAt: str | None = Field(default=None, max_length=40)
    origin: RecipeOrigin | None = None

    @field_validator("id")
    @classmethod
    def lower_id(cls, value: str) -> str:
        return value.lower()

    @model_validator(mode="after")
    def validate_banner(self):
        if self.bannerPhoto is not None and self.bannerPhoto not in self.recipePhotos:
            raise ValueError("The banner must be one of the recipe photos.")
        return self


class ConfirmPassword(BaseModel):
    password: str = Field(min_length=10, max_length=256)


class GroupName(BaseModel):
    name: str = Field(min_length=1, max_length=80)

    @field_validator("name")
    @classmethod
    def trim(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Choose a group name.")
        return value.strip()


class Invite(BaseModel):
    code: str = Field(min_length=1, max_length=128)


class InviteCredentials(Credentials):
    code: str = Field(min_length=1, max_length=128)


class TransferOwner(BaseModel):
    userId: str = Field(pattern=f"^{ID}$")


class Share(BaseModel):
    recipeId: str = Field(pattern=f"^{ID}$")


class BodyLimit:
    """Authenticate large requests before reading and bound their memory and time."""
    def __init__(self, app, authenticate, upload_limit=2, idle_timeout=15, total_timeout=180, managed_enabled=False):
        self.app = app
        self.authenticate = authenticate
        self.upload_limit = upload_limit
        self.idle_timeout = idle_timeout
        self.total_timeout = total_timeout
        self.active_uploads = 0
        self.managed_enabled = managed_enabled

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        path = scope["path"]
        root = scope.get("root_path", "").rstrip("/")
        if root and path.startswith(root + "/"):
            path = path[len(root):]
        photo = scope["method"] == "PUT" and path.startswith("/v1/photos/")
        ai = self.managed_enabled and scope["method"] == "POST" and path == managed.AI_PATH
        large = photo or ai
        acquired = False
        if large:
            try:
                auth = await HTTPBearer(auto_error=False)(Request(scope))
                user = await to_thread.run_sync(self.authenticate, auth.credentials if auth else "")
            except HTTPException as error:
                return await JSONResponse({"detail": error.detail}, error.status_code,
                                          headers=error.headers)(scope, receive, send)
            # This check/increment has no await: one worker admits at most the configured count.
            if self.active_uploads >= self.upload_limit:
                return await JSONResponse({"detail": "Pinch is busy. Try again shortly." if ai else "Uploads are busy. Try backing up again shortly."},
                                          503, headers={"Retry-After": "2"})(scope, receive, send)
            self.active_uploads += 1
            acquired = True
            scope.setdefault("state", {})["pinch_user"] = user
        try:
            limit = MAX_PHOTO if large else MAX_JSON
            headers = dict(scope["headers"])
            if b"content-length" in headers:
                try:
                    declared = int(headers[b"content-length"])
                    if declared < 0:
                        raise ValueError()
                except ValueError:
                    return await JSONResponse({"detail": "Invalid content length."}, 400)(scope, receive, send)
                if declared > limit:
                    return await JSONResponse({"detail": "Upload is too large."}, 413)(scope, receive, send)
            # A bytearray avoids holding a list of chunks plus a joined body. We check before
            # extending it, and retain only the immutable final body before calling the app.
            buffer = bytearray()
            deadline = time.monotonic() + self.total_timeout
            while True:
                try:
                    if large:
                        remaining = deadline - time.monotonic()
                        if remaining <= 0:
                            raise TimeoutError()
                        message = await asyncio.wait_for(receive(), min(self.idle_timeout, remaining))
                    else:
                        message = await receive()
                except TimeoutError:
                    return await JSONResponse({"detail": "Upload timed out. Try backing up again."},
                                              408)(scope, receive, send)
                if message["type"] == "http.disconnect":
                    return
                chunk = message.get("body", b"")
                if len(buffer) + len(chunk) > limit:
                    return await JSONResponse({"detail": "Upload is too large."}, 413)(scope, receive, send)
                buffer.extend(chunk)
                if not message.get("more_body", False):
                    break
            body = bytes(buffer)
            del buffer, chunk, message
            if photo:
                scope["state"]["pinch_photo"] = body
            delivered = False

            async def bounded_receive():
                nonlocal delivered
                if delivered:
                    return await receive()
                delivered = True
                return {"type": "http.request", "body": body, "more_body": False}

            await self.app(scope, bounded_receive, send)
        finally:
            if acquired:
                self.active_uploads -= 1


def create_app(data_dir: str | Path | None = None, registration_key: str | None = None,
               public_url: str | None = None) -> FastAPI:
    directory = Path(data_dir or os.getenv("PINCH_DATA_DIR", "./data"))
    signup_key = registration_key if registration_key is not None else os.getenv("PINCH_REGISTRATION_KEY", "")
    quota = int(os.getenv("PINCH_USER_QUOTA_MB", "1024")) * 1024 * 1024
    plugin_origin = public_url if public_url is not None else os.getenv("PINCH_PUBLIC_URL", "")
    managed_config = managed.Config.from_env()
    plugin = None
    # Never accept a wildcard or a whole Docker/LAN subnet as a trusted proxy.
    for value in filter(None, os.getenv("PINCH_TRUSTED_PROXY_IPS", "").split(",")):
        try:
            ipaddress.ip_address(value.strip())
        except ValueError:
            raise ValueError("PINCH_TRUSTED_PROXY_IPS must contain exact proxy IP addresses.") from None
    upload_limit = int(os.getenv("PINCH_MAX_UPLOADS", "2"))
    idle_timeout = float(os.getenv("PINCH_UPLOAD_IDLE_SECONDS", "15"))
    total_timeout = float(os.getenv("PINCH_UPLOAD_TOTAL_SECONDS", "180"))
    if upload_limit < 1 or not all(math.isfinite(v) and v > 0 for v in [idle_timeout, total_timeout]):
        raise ValueError("Upload limits and timeouts must be positive and finite.")
    root_path = os.getenv("PINCH_ROOT_PATH", "").rstrip("/")
    if not re.fullmatch(r"(?:/[A-Za-z0-9_-]+)*", root_path):
        raise ValueError("PINCH_ROOT_PATH must be a URL path such as /recipes.")
    if root_path and plugin_origin:
        raise ValueError("The optional OAuth plugin requires its own origin; use an empty PINCH_ROOT_PATH for it.")

    @contextmanager
    def database(write=False):
        db = sqlite3.connect(directory / "pinch.sqlite3", timeout=30)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            if write:
                db.execute("BEGIN IMMEDIATE")
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    @asynccontextmanager
    async def lifespan(app):
        directory.mkdir(parents=True, exist_ok=True)
        with database() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript(SCHEMA)
            db.executescript(PLUGIN_SCHEMA)
            if managed_config:
                db.executescript(managed.SCHEMA)
            if "shared_at" not in {row[1] for row in db.execute("PRAGMA table_info(shares)")}:
                db.execute("ALTER TABLE shares ADD COLUMN shared_at REAL NOT NULL DEFAULT 0")
            cookbooks.migrate(db)
        if plugin:
            async with plugin.session_manager.run():
                yield
        else:
            yield

    app = FastAPI(title="Pinch Server", version="1.0.0", lifespan=lifespan, root_path=root_path)
    bearer = HTTPBearer(auto_error=False)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, error):
        # Do not reflect passwords, registration keys or recipe text in errors/logs.
        return JSONResponse({"detail": "Invalid input. Check the fields and try again."}, 422)

    def rate_limit(bucket: str, limit=20):
        now = time.time()
        with database(True) as db:
            db.execute("DELETE FROM attempts WHERE expires < ?", (now,))
            db.execute("INSERT INTO attempts VALUES (?,1,?) ON CONFLICT(bucket) DO UPDATE SET count=count+1", (digest(bucket), now+900))
            count = db.execute("SELECT count FROM attempts WHERE bucket=?", (digest(bucket),)).fetchone()[0]
        if count > limit:
            raise HTTPException(429, "Too many attempts. Try again in 15 minutes.", headers={"Retry-After": "900"})

    def resolve_user(token: str):
        if token and len(token) <= 256:
            with database() as db:
                user = db.execute("SELECT u.* FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token=? AND s.expires>?", (digest(token), time.time())).fetchone()
                if user:
                    return dict(user)
        raise HTTPException(401, "Sign in to your server again.", headers={"WWW-Authenticate": "Bearer"})

    def current_user(request: Request, auth: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)]):
        cached = getattr(request.state, "pinch_user", None)
        return cached if cached is not None else resolve_user(auth.credentials if auth else "")

    app.add_middleware(BodyLimit, authenticate=resolve_user, upload_limit=upload_limit,
                       idle_timeout=idle_timeout, total_timeout=total_timeout, managed_enabled=managed_config is not None)

    def check_failed_login(bucket: str):
        with database() as db:
            row = db.execute("SELECT count,expires FROM attempts WHERE bucket=?", (digest(bucket),)).fetchone()
        if row and row[1] > time.time() and row[0] >= 10:
            raise HTTPException(429, "Too many attempts. Try again later.",
                                headers={"Retry-After": str(max(1, math.ceil(row[1] - time.time())))})

    User = Annotated[dict, Depends(current_user)]
    # Annotations defined in this closure must be resolved at route registration time.

    def session(db, user):
        token = secrets.token_urlsafe(32)
        db.execute("DELETE FROM sessions WHERE expires<?", (time.time(),))
        db.execute("INSERT INTO sessions VALUES (?,?,?)", (digest(token), user["id"], time.time()+90*86400))
        return {"token": token, "username": user["username"]}

    def membership(db, gid, uid):
        group = db.execute("SELECT g.* FROM groups g JOIN members m ON m.group_id=g.id WHERE g.id=? AND m.user_id=?", (gid, uid)).fetchone()
        if not group:
            raise HTTPException(404, "Group not found. You may no longer be a member.")
        require_legacy_group(db, gid)
        return group

    def require_legacy_group(db, gid):
        # A migrated group must not bypass cookbook roles or revoked invitations.
        if db.execute("SELECT 1 FROM cookbooks WHERE id=?", (gid,)).fetchone():
            raise HTTPException(410, "This group is now a cookbook. Update Pinch to open it.")

    def owner_group(db, gid, uid):
        group = membership(db, gid, uid)
        if group["owner"] != uid:
            raise HTTPException(403, "Only the group owner can do that.")
        return group

    def invited_group(db, code):
        group = db.execute("SELECT * FROM groups WHERE invite=? OR id IN (SELECT group_id FROM group_invites WHERE code=?)", (digest(code.strip()), digest(code.strip()))).fetchone()
        if not group:
            raise HTTPException(404, "This invitation has expired or was replaced. Ask for a new one.")
        require_legacy_group(db, group["id"])
        return group

    def group_summary(db, group, uid):
        require_legacy_group(db, group["id"])
        shares = db.execute("SELECT * FROM shares WHERE group_id=? ORDER BY shared_at DESC,id", (group["id"],)).fetchall()
        names = [r[0] for r in db.execute("SELECT u.username FROM members m JOIN users u ON u.id=m.user_id WHERE m.group_id=? ORDER BY u.username", (group["id"],))]
        return {"id": group["id"], "name": group["name"], "isOwner": group["owner"] == uid,
                "memberCount": len(names), "memberNames": names, "recipeCount": len(shares),
                "latestActivity": max((s["shared_at"] for s in shares), default=0),
                "previews": [{k: v for k, v in json.loads(s["body"]).items() if k in {"id", "title", "emoji", "palette", "pattern", "createdAt", "updatedAt", "totalTime", "prepTime", "cookTime"}} for s in shares[:3]],
                "mySharedRecipeIds": [s["recipe_id"] for s in shares if s["user_id"] == uid]}

    def shared_item(db, row, group, uid):
        saved = db.execute("SELECT r.id FROM saved_shares s JOIN recipes r ON r.user_id=s.user_id AND r.id=s.recipe_id WHERE s.user_id=? AND s.share_id=? AND json_extract(r.body,'$.deletedAt') IS NULL", (uid, row["id"])).fetchone()
        return {"id": row["id"], "sharedBy": row["username"], "canRemove": row["user_id"] == uid or group["owner"] == uid,
                "sharedAt": row["shared_at"], "savedRecipeId": saved[0] if saved else None, "recipe": json.loads(row["body"])}

    def check_quota(db, uid, additional):
        used = db.execute("SELECT COALESCE(SUM(length(body)),0) FROM photos WHERE user_id=?", (uid,)).fetchone()[0]
        used += db.execute("SELECT COALESCE(SUM(length(body)),0) FROM recipes WHERE user_id=?", (uid,)).fetchone()[0]
        used += db.execute("SELECT COALESCE(SUM(length(p.body)),0) FROM share_photos p JOIN shares s ON s.id=p.share_id WHERE s.user_id=?", (uid,)).fetchone()[0]
        used += db.execute("SELECT COALESCE(SUM(length(body)),0) FROM shares WHERE user_id=?", (uid,)).fetchone()[0]
        if used + additional > quota:
            raise HTTPException(413, "Your server storage quota is full. Ask the host to increase it.")

    @app.get("/health")
    def health():
        with database() as db:
            db.execute("SELECT 1 FROM users LIMIT 1")
        return {"status": "ok", "service": "pinch", "apiVersion": 1}

    def create_user(body):
        hashed = password_hash(body.password)
        user = {"id": str(uuid.uuid4()), "username": body.username}
        try:
            with database(True) as db:
                db.execute("INSERT INTO users VALUES (?,?,?)", (user["id"], body.username, hashed))
                return session(db, user)
        except sqlite3.IntegrityError:
            raise HTTPException(409, "That username is already taken.")

    @app.post("/v1/auth/register", status_code=201)
    def register(body: Credentials, request: Request):
        rate_limit("auth:" + (request.client.host if request.client else "unknown"))
        if not signup_key or not hmac.compare_digest(body.registrationKey.encode(), signup_key.encode()):
            raise HTTPException(403, "Ask your server host for the registration key.")
        return create_user(body)

    def authenticate_password(username, password, address):
        rate_limit("auth:" + address)
        failed_bucket = "login-failed:" + address + ":" + username
        check_failed_login(failed_bucket)
        with database() as db:
            user = db.execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()
        salt = user["password"].split(":")[0] if user else "0"*32
        candidate = password_hash(password, salt)
        if not user or not hmac.compare_digest(candidate, user["password"]):
            rate_limit(failed_bucket, limit=10)
            raise HTTPException(401, "Incorrect username or password.")
        with database(True) as db:
            db.execute("DELETE FROM attempts WHERE bucket=?", (digest(failed_bucket),))
        return user

    @app.post("/v1/auth/login")
    def login(body: Credentials, request: Request):
        user = authenticate_password(body.username, body.password, request.client.host if request.client else "unknown")
        with database(True) as db:
            return session(db, user)

    @app.post("/v1/auth/logout")
    def logout(user: User, auth: Annotated[HTTPAuthorizationCredentials, Depends(bearer)]):
        with database(True) as db:
            db.execute("DELETE FROM sessions WHERE token=?", (digest(auth.credentials),))
        return {"ok": True}

    @app.delete("/v1/auth/account")
    def delete_account(body: ConfirmPassword, user: User):
        rate_limit("delete:" + user["id"])
        candidate = password_hash(body.password, user["password"].split(":")[0])
        if not hmac.compare_digest(candidate, user["password"]):
            raise HTTPException(401, "Incorrect password.")
        with database(True) as db:
            db.execute("DELETE FROM users WHERE id=?", (user["id"],))
        return {"ok": True}

    @app.get("/v1/recipes")
    def recipes(user: User):
        with database() as db:
            return [json.loads(r[0]) for r in db.execute("SELECT body FROM recipes WHERE user_id=? ORDER BY id", (user["id"],))]

    @app.put("/v1/recipes/{rid}")
    def put_recipe(rid: str, recipe: Recipe, request: Request, user: User):
        if rid != recipe.id:
            raise HTTPException(422, "Recipe ID does not match.")
        updated = timestamp(recipe.updatedAt)
        for date in [recipe.createdAt, recipe.deletedAt, recipe.lastCookedAt]:
            if date:
                timestamp(date)
        if updated > time.time() + 300:
            raise HTTPException(422, "Your device clock is ahead. Correct it before syncing.")
        if recipe.deletedAt:
            recipe.photos = []
            recipe.recipePhotos = []
            recipe.bannerPhoto = None
        with database(True) as db:
            existing = db.execute("SELECT * FROM recipes WHERE user_id=? AND id=?", (user["id"], rid)).fetchone()
            previous = json.loads(existing["body"]) if existing else {}
            if existing and existing["updated"] >= updated:
                return previous
            # Older apps omit both fields on every edit. Keep photos they can't edit.
            if (not recipe.deletedAt and request.headers.get("X-Pinch-Recipe-Photos") != "1"
                    and not {"recipePhotos", "bannerPhoto"} & recipe.model_fields_set):
                recipe.recipePhotos = previous.get("recipePhotos", [])
                recipe.bannerPhoto = previous.get("bannerPhoto")
            exclude = {"recipePhotos"} if not recipe.recipePhotos else None
            body = recipe.model_dump_json(by_alias=True, exclude_none=True, exclude=exclude)
            names = set(recipe.photos + recipe.recipePhotos)
            for name in names:
                if not db.execute("SELECT 1 FROM photos WHERE user_id=? AND name=?", (user["id"], name)).fetchone():
                    raise HTTPException(409, "Upload all photos before saving this recipe.")
            check_quota(db, user["id"], len(body.encode()) - (len(existing["body"].encode()) if existing else 0))
            db.execute("INSERT INTO recipes VALUES (?,?,?,?) ON CONFLICT(user_id,id) DO UPDATE SET body=excluded.body,updated=excluded.updated", (user["id"], rid, body, updated))
            for name in set(cookbooks.photo_files(previous)) - names:
                referenced = db.execute("""SELECT 1 FROM recipes WHERE user_id=:user AND (
                    EXISTS (SELECT 1 FROM json_each(body, '$.photos') WHERE value=:name) OR
                    EXISTS (SELECT 1 FROM json_each(body, '$.recipePhotos') WHERE value=:name)
                ) LIMIT 1""", {"user": user["id"], "name": name}).fetchone()
                if not referenced:
                    db.execute("DELETE FROM photos WHERE user_id=? AND name=?", (user["id"], name))
        return json.loads(body)

    @app.put("/v1/photos/{name}")
    async def put_photo(name: str, request: Request, user: User):
        if not re.fullmatch(PHOTO, name):
            raise HTTPException(422, "Invalid photo name.")
        data = request.state.pinch_photo
        if not data.startswith(b"\xff\xd8\xff") or len(data) > MAX_PHOTO:
            raise HTTPException(422, "Scanned pages must be JPEGs no larger than 12 MB.")
        with database(True) as db:
            old = db.execute("SELECT body FROM photos WHERE user_id=? AND name=?", (user["id"], name)).fetchone()
            if old:
                if old[0] != data:
                    raise HTTPException(409, "A different photo already has this name.")
            else:
                check_quota(db, user["id"], len(data))
                db.execute("INSERT INTO photos VALUES (?,?,?)", (user["id"], name, data))
        return {"ok": True}

    @app.get("/v1/photos/{name}")
    def photo(name: str, user: User):
        with database() as db:
            row = db.execute("SELECT body FROM photos WHERE user_id=? AND name=?", (user["id"], name)).fetchone()
            if not row:
                raise HTTPException(404, "Scanned page not found.")
            return Response(row[0], media_type="image/jpeg", headers={"Cache-Control": "private, no-store"})

    @app.get("/v1/groups")
    def groups(user: User):
        with database() as db:
            return [group_summary(db, g, user["id"]) for g in db.execute("SELECT g.* FROM groups g JOIN members m ON m.group_id=g.id WHERE m.user_id=? ORDER BY g.name", (user["id"],))]

    @app.post("/v1/groups", status_code=201)
    def create_group(body: GroupName, user: User):
        gid, code = str(uuid.uuid4()), secrets.token_urlsafe(18)
        with database(True) as db:
            if db.execute("SELECT 1 FROM cookbooks WHERE owner=? UNION SELECT 1 FROM cookbook_members WHERE user_id=? LIMIT 1", (user["id"], user["id"])).fetchone():
                raise HTTPException(410, "Update Pinch to organize and share recipes with cookbooks.")
            if db.execute("SELECT COUNT(*) FROM members WHERE user_id=?", (user["id"],)).fetchone()[0] >= 100:
                raise HTTPException(409, "You can belong to up to 100 groups.")
            db.execute("INSERT INTO groups VALUES (?,?,?,?)", (gid, body.name, user["id"], digest(code)))
            db.execute("INSERT INTO members VALUES (?,?)", (gid, user["id"]))
        return {"id": gid, "name": body.name, "isOwner": True, "inviteCode": code}

    @app.post("/v1/groups/join")
    def join(body: Invite, user: User):
        rate_limit("join:"+user["id"])
        with database(True) as db:
            group = invited_group(db, body.code)
            already = db.execute("SELECT 1 FROM members WHERE group_id=? AND user_id=?", (group["id"], user["id"])).fetchone()
            if not already and db.execute("SELECT COUNT(*) FROM members WHERE user_id=?", (user["id"],)).fetchone()[0] >= 100:
                raise HTTPException(409, "You can belong to up to 100 groups.")
            db.execute("INSERT OR IGNORE INTO members VALUES (?,?)", (group["id"], user["id"]))
        return {"id": group["id"], "name": group["name"], "isOwner": group["owner"] == user["id"]}

    @app.post("/v1/groups/{gid}/invite")
    def rotate_invite(gid: str, user: User):
        code = secrets.token_urlsafe(18)
        with database(True) as db:
            group = membership(db, gid, user["id"])
            if group["owner"] != user["id"]:
                raise HTTPException(403, "Only the group owner can create invites.")
            db.execute("UPDATE groups SET invite=? WHERE id=?", (digest(code), gid))
            db.execute("DELETE FROM group_invites WHERE group_id=?", (gid,))
        return {"inviteCode": code}

    @app.post("/v1/groups/{gid}/invitations")
    def new_invitation(gid: str, user: User):
        rate_limit("invite:" + user["id"])
        code = secrets.token_urlsafe(24)
        with database(True) as db:
            owner_group(db, gid, user["id"])
            db.execute("INSERT INTO group_invites VALUES (?,?)", (digest(code), gid))
        return {"inviteCode": code}

    @app.get("/v1/invitations/{code}")
    def invitation(code: str, request: Request):
        rate_limit("invitation:" + (request.client.host if request.client else "unknown"), 60)
        with database() as db:
            group = invited_group(db, code)
            owner = db.execute("SELECT username FROM users WHERE id=?", (group["owner"],)).fetchone()[0]
            return {"id": group["id"], "name": group["name"], "invitedBy": owner}

    @app.post("/v1/auth/register-invite", status_code=201)
    def register_invited(body: InviteCredentials, request: Request):
        rate_limit("auth:" + (request.client.host if request.client else "unknown"))
        with database() as db:
            invited_group(db, body.code)
        hashed = password_hash(body.password)
        user = {"id": str(uuid.uuid4()), "username": body.username}
        try:
            with database(True) as db:
                group = invited_group(db, body.code)
                db.execute("INSERT INTO users VALUES (?,?,?)", (user["id"], body.username, hashed))
                db.execute("INSERT INTO members VALUES (?,?)", (group["id"], user["id"]))
                return session(db, user)
        except sqlite3.IntegrityError:
            raise HTTPException(409, "That username is already taken. Sign in or choose another.")

    @app.get("/join", response_class=HTMLResponse)
    def invitation_page():
        # Codes stay in the fragment, outside HTTP requests, referrers and access logs.
        return HTMLResponse((Path(__file__).parent / "invitation.html").read_text(), headers={"Referrer-Policy": "no-referrer", "Cache-Control": "no-store"})

    @app.patch("/v1/groups/{gid}")
    def rename_group(gid: str, body: GroupName, user: User):
        with database(True) as db:
            owner_group(db, gid, user["id"])
            db.execute("UPDATE groups SET name=? WHERE id=?", (body.name, gid))
        return {"ok": True}

    @app.get("/v1/groups/{gid}/members")
    def group_members(gid: str, user: User):
        with database() as db:
            group = membership(db, gid, user["id"])
            return [{"id": r["id"], "username": r["username"], "isOwner": r["id"] == group["owner"], "isYou": r["id"] == user["id"]} for r in db.execute("SELECT u.id,u.username FROM members m JOIN users u ON u.id=m.user_id WHERE m.group_id=? ORDER BY u.username", (gid,))]

    @app.delete("/v1/groups/{gid}/members/{uid}")
    def remove_member(gid: str, uid: str, user: User):
        with database(True) as db:
            owner_group(db, gid, user["id"])
            if uid == user["id"]:
                raise HTTPException(409, "Transfer ownership before leaving the group.")
            membership(db, gid, uid)
            db.execute("DELETE FROM shares WHERE group_id=? AND user_id=?", (gid, uid))
            db.execute("DELETE FROM members WHERE group_id=? AND user_id=?", (gid, uid))
            db.execute("DELETE FROM group_invites WHERE group_id=?", (gid,))
            db.execute("UPDATE groups SET invite=? WHERE id=?", (digest(secrets.token_urlsafe(24)), gid))
        return {"ok": True}

    @app.post("/v1/groups/{gid}/owner")
    def transfer_owner(gid: str, body: TransferOwner, user: User):
        with database(True) as db:
            owner_group(db, gid, user["id"])
            membership(db, gid, body.userId)
            db.execute("UPDATE groups SET owner=? WHERE id=?", (body.userId, gid))
        return {"ok": True}

    @app.delete("/v1/groups/{gid}")
    def leave_group(gid: str, user: User):
        with database(True) as db:
            group = membership(db, gid, user["id"])
            if group["owner"] == user["id"]:
                db.execute("DELETE FROM groups WHERE id=?", (gid,))
            else:
                db.execute("DELETE FROM shares WHERE group_id=? AND user_id=?", (gid, user["id"]))
                db.execute("DELETE FROM members WHERE group_id=? AND user_id=?", (gid, user["id"]))
        return {"ok": True}

    @app.get("/v1/groups/{gid}/recipes")
    def group_recipes(gid: str, user: User):
        with database() as db:
            group = membership(db, gid, user["id"])
            return [shared_item(db, s, group, user["id"]) for s in db.execute("SELECT s.*,u.username FROM shares s JOIN users u ON u.id=s.user_id WHERE s.group_id=? ORDER BY s.shared_at DESC,s.id", (gid,))]

    @app.get("/v1/groups/{gid}/recipes/{sid}")
    def get_shared_recipe(gid: str, sid: str, user: User):
        with database() as db:
            group = membership(db, gid, user["id"])
            row = db.execute("SELECT s.*,u.username FROM shares s JOIN users u ON u.id=s.user_id WHERE s.group_id=? AND s.id=?", (gid, sid)).fetchone()
            if not row:
                raise HTTPException(404, "This recipe is no longer shared.")
            return shared_item(db, row, group, user["id"])

    @app.post("/v1/groups/{gid}/recipes")
    def share_recipe(gid: str, body: Share, user: User):
        with database(True) as db:
            membership(db, gid, user["id"])
            row = db.execute("SELECT body FROM recipes WHERE user_id=? AND id=?", (user["id"], body.recipeId)).fetchone()
            if not row or json.loads(row[0]).get("deletedAt"):
                raise HTTPException(404, "Back up this recipe before sharing it.")
            recipe = json.loads(row[0])
            # Personal cooking activity and OCR text are not part of a shared card.
            recipe.update(isFavorite=False, rawText="")
            recipe.pop("lastCookedAt", None)
            recipe.pop("origin", None)
            encoded = json.dumps(recipe, ensure_ascii=False)
            old = db.execute("SELECT id FROM shares WHERE group_id=? AND user_id=? AND recipe_id=?", (gid, user["id"], body.recipeId)).fetchone()
            sid = old[0] if old else str(uuid.uuid4())
            if old:
                db.execute("DELETE FROM shares WHERE id=?", (sid,))
            images = [(name, db.execute("SELECT body FROM photos WHERE user_id=? AND name=?", (user["id"], name)).fetchone()[0]) for name in cookbooks.photo_files(recipe)]
            check_quota(db, user["id"], len(encoded.encode()) + sum(len(data) for _, data in images))
            db.execute("INSERT INTO shares VALUES (?,?,?,?,?,?)", (sid, gid, user["id"], body.recipeId, encoded, time.time()))
            db.executemany("INSERT INTO share_photos VALUES (?,?,?)", [(sid, name, data) for name, data in images])
        return {"ok": True}

    @app.post("/v1/groups/{gid}/recipes/{sid}/save")
    def save_shared_recipe(gid: str, sid: str, user: User):
        # One transaction captures the current text and pages and deduplicates across devices.
        with database(True) as db:
            group = membership(db, gid, user["id"])
            row = db.execute("SELECT s.*,u.username FROM shares s JOIN users u ON u.id=s.user_id WHERE s.id=? AND s.group_id=?", (sid, gid)).fetchone()
            if not row:
                raise HTTPException(404, "This recipe is no longer shared.")
            existing = db.execute("SELECT r.body FROM saved_shares s JOIN recipes r ON r.user_id=s.user_id AND r.id=s.recipe_id WHERE s.user_id=? AND s.share_id=?", (user["id"], sid)).fetchone()
            if existing and not json.loads(existing[0]).get("deletedAt"):
                return json.loads(existing[0])
            recipe = json.loads(row["body"])
            source_images = [db.execute("SELECT body FROM share_photos WHERE share_id=? AND name=?", (sid, name)).fetchone()[0] for name in cookbooks.photo_files(recipe)]
            images = cookbooks.copy_photo_files(recipe, source_images)
            now = datetime.now(timezone.utc).isoformat(timespec="milliseconds")
            recipe.update(id=str(uuid.uuid4()), createdAt=now, updatedAt=now,
                          origin={"groupId": gid, "shareId": sid, "groupName": group["name"], "sharedBy": row["username"]})
            encoded = json.dumps(recipe, ensure_ascii=False)
            check_quota(db, user["id"], len(encoded.encode()) + sum(len(data) for _, data in images))
            db.executemany("INSERT INTO photos VALUES (?,?,?)", [(user["id"], name, data) for name, data in images])
            db.execute("INSERT INTO recipes VALUES (?,?,?,?)", (user["id"], recipe["id"], encoded, timestamp(now)))
            db.execute("INSERT INTO saved_shares VALUES (?,?,?) ON CONFLICT(user_id,share_id) DO UPDATE SET recipe_id=excluded.recipe_id", (user["id"], sid, recipe["id"]))
            return recipe

    @app.get("/v1/groups/{gid}/recipes/{sid}/photos/{name}")
    def shared_photo(gid: str, sid: str, name: str, user: User):
        with database() as db:
            membership(db, gid, user["id"])
            row = db.execute("SELECT p.body FROM share_photos p JOIN shares s ON s.id=p.share_id WHERE s.group_id=? AND s.id=? AND p.name=?", (gid, sid, name)).fetchone()
            if not row:
                raise HTTPException(404, "Scanned page not found.")
            return Response(row[0], media_type="image/jpeg", headers={"Cache-Control": "private, no-store"})

    @app.delete("/v1/groups/{gid}/recipes/{sid}")
    def remove_share(gid: str, sid: str, user: User):
        with database(True) as db:
            group = membership(db, gid, user["id"])
            row = db.execute("SELECT user_id FROM shares WHERE group_id=? AND id=?", (gid, sid)).fetchone()
            if not row:
                raise HTTPException(404, "Shared recipe not found.")
            if row[0] != user["id"] and group["owner"] != user["id"]:
                raise HTTPException(403, "Only the person who shared it or the group owner can remove it.")
            db.execute("DELETE FROM shares WHERE id=?", (sid,))
        return {"ok": True}

    cookbooks.install(app, database, current_user, check_quota, rate_limit, Credentials, password_hash, session)
    if managed_config:
        app.add_api_route("/v1/managed/login", login, methods=["POST"])
        managed.install(app, managed_config, database, rate_limit, current_user, Credentials, create_user, authenticate_password, session)

    if plugin_origin:
        from pinch_plugin import build_plugin
        plugin, plugin_app = build_plugin(
            database, rate_limit, password_hash, check_quota, Recipe, plugin_origin,
            extra_redirects=filter(None, os.getenv("PINCH_OAUTH_REDIRECT_URIS", "").split(",")),
            challenge=os.getenv("PINCH_OPENAI_CHALLENGE", ""),
        )
        app.mount("/", plugin_app)

    return app


app = create_app()
