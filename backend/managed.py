"""Pinch-hosted sign-in and recipe AI. Enabled only on the managed server."""

import base64
import hashlib
import hmac
import os
import re
import secrets
import time
from dataclasses import dataclass
from html import escape
from typing import Annotated, Literal
from urllib.parse import urlencode, urlsplit

import httpx
from fastapi import Depends, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

SCHEMA = """
CREATE TABLE IF NOT EXISTS managed_authorizations(id TEXT PRIMARY KEY, challenge TEXT NOT NULL, state TEXT NOT NULL, csrf TEXT NOT NULL, expires REAL NOT NULL);
CREATE TABLE IF NOT EXISTS managed_codes(id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, challenge TEXT NOT NULL, expires REAL NOT NULL);
CREATE TABLE IF NOT EXISTS managed_ai_usage(day INTEGER NOT NULL, account TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(day, account));
"""
CLIENT = "pinch-mobile"
REDIRECT = "net.lmbrt.recipes.pinch://oauth"
CODE = r"[A-Za-z0-9_-]{43}"
VERIFIER = r"[A-Za-z0-9._~-]{43,128}"
AI_PATH = "/v1/managed/ai"
OPENROUTER = "https://openrouter.ai/api/v1/chat/completions"


@dataclass(frozen=True)
class Config:
    origin: str
    key: str
    model: str
    requests_per_account: int
    requests_per_day: int

    @classmethod
    def from_env(cls):
        origin = os.getenv("PINCH_MANAGED_URL", "").rstrip("/")
        if not origin:
            return None
        parts = urlsplit(origin)
        if parts.scheme != "https" or not parts.hostname or parts.username or parts.path or parts.query or parts.fragment:
            raise ValueError("PINCH_MANAGED_URL must be a public HTTPS origin.")
        key = os.getenv("PINCH_MANAGED_OPENROUTER_KEY", "").strip()
        if not key:
            raise ValueError("PINCH_MANAGED_OPENROUTER_KEY is required for managed hosting.")
        account_limit = int(os.getenv("PINCH_MANAGED_AI_PER_ACCOUNT", "30"))
        global_limit = int(os.getenv("PINCH_MANAGED_AI_PER_DAY", "300"))
        if account_limit < 1 or global_limit < 1:
            raise ValueError("Managed AI limits must be positive.")
        return cls(origin, key, os.getenv("PINCH_MANAGED_MODEL", "deepseek/deepseek-v4.1-flash"), account_limit, global_limit)


def hash_value(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def challenge_for(verifier: str) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()


class Part(BaseModel):
    type: Literal["text", "image_url"]
    text: str | None = Field(default=None, max_length=200_000)
    image_url: dict[str, str] | None = None

    def request_part(self):
        if self.type == "text" and self.text is not None:
            return {"type": "text", "text": self.text}
        url = (self.image_url or {}).get("url", "")
        if self.type == "image_url" and url.startswith("data:image/jpeg;base64,"):
            return {"type": "image_url", "image_url": {"url": url}}
        raise HTTPException(422, "Unsupported recipe content.")


class Message(BaseModel):
    role: Literal["system", "user"]
    content: str | list[Part]


class Completion(BaseModel):
    messages: list[Message] = Field(min_length=2, max_length=2)
    response_format: dict
    max_tokens: int = Field(default=8192, ge=1, le=8192)

    def upstream_body(self, model):
        system, user = self.messages
        if (system.role != "system" or not isinstance(system.content, str) or len(system.content) > 12_000
                or user.role != "user" or not isinstance(user.content, list) or not 1 <= len(user.content) <= 12):
            raise HTTPException(422, "Unsupported recipe request.")
        format_type = self.response_format.get("type")
        body = {"model": model, "temperature": 0, "max_tokens": self.max_tokens, "messages": [
            {"role": "system", "content": system.content},
            {"role": "user", "content": [part.request_part() for part in user.content]},
        ]}
        if format_type == "json_schema":
            schema = self.response_format.get("json_schema")
            if (not isinstance(schema, dict) or schema.get("name") not in ("recipe", "cook_mode_ingredients")
                    or not isinstance(schema.get("schema"), dict) or len(str(schema["schema"])) > 16_000):
                raise HTTPException(422, "Unsupported recipe response format.")
            body.update(response_format={"type": "json_schema", "json_schema": {
                "name": schema["name"], "schema": schema["schema"], "strict": True,
            }}, provider={"require_parameters": True}, reasoning={"effort": "none"})
        elif format_type == "json_object":
            body["response_format"] = {"type": "json_object"}
        else:
            raise HTTPException(422, "Unsupported recipe response format.")
        return body


async def complete(body, key):
    async with httpx.AsyncClient(timeout=100, trust_env=False) as client:
        response = await client.post(OPENROUTER, json=body, headers={
            "Authorization": f"Bearer {key}", "X-Title": "Pinch",
        })
        if response.status_code == 429:
            raise HTTPException(429, "Recipe reading is busy. Try again shortly.")
        if response.status_code in (400, 404, 422) and body["response_format"]["type"] == "json_schema":
            raise HTTPException(422, "Try the simpler JSON response format.")
        response.raise_for_status()
        result = response.json()
        if not isinstance(result, dict) or result.get("error") or not isinstance(result.get("choices"), list):
            raise ValueError("Invalid completion")
        return result


def reserve_request(db, uid, config):
    day = int(time.time()) // 86400
    db.execute("DELETE FROM managed_ai_usage WHERE day<?", (day,))
    for account, limit in ((uid, config.requests_per_account), ("all", config.requests_per_day)):
        row = db.execute("SELECT count FROM managed_ai_usage WHERE day=? AND account=?", (day, account)).fetchone()
        if row and row[0] >= limit:
            raise HTTPException(429, "Today's recipe reading limit has been reached. Try again tomorrow.")
        db.execute("INSERT INTO managed_ai_usage VALUES (?,?,1) ON CONFLICT(day,account) DO UPDATE SET count=count+1", (day, account))


PAGE_HEADERS = {"Cache-Control": "no-store", "Referrer-Policy": "no-referrer",
                "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'"}


def sign_in_page(flow, csrf, error=""):
    page = f"""<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sign in to Pinch</title><style>body{{margin:0;min-height:100svh;display:grid;place-items:center;background:#f7f5ed;color:#173e34;font:17px system-ui}}main{{width:min(360px,85vw)}}h1{{font:36px Georgia}}label{{display:block;margin:16px 0 6px}}input,button{{width:100%;box-sizing:border-box;padding:12px;font:inherit;border:1px solid #98b7a6;border-radius:10px}}button{{margin:20px 0;background:#205d48;color:white}}small{{line-height:1.5}}p{{color:#942a2a}}</style>
<main><h1>Sign in to Pinch</h1><small>Pinch syncs your library and uses OpenRouter to read recipes and match ingredients to cooking steps.</small>
<p role="alert">{escape(error)}</p><form method="post" action="/v1/managed/oauth/authorize">
<input type="hidden" name="flow" value="{flow}"><input type="hidden" name="csrf" value="{csrf}">
<label for="username">Username</label><input id="username" name="username" autocomplete="username" required maxlength="64">
<label for="password">Password</label><input id="password" name="password" type="password" autocomplete="current-password" required maxlength="256">
<button>Sign in</button></form></main></html>"""
    return HTMLResponse(page, status_code=401 if error else 200, headers=PAGE_HEADERS)


def install(app, config, database, rate_limit, current_user, credentials, create_user, authenticate_password, session):
    User = Annotated[dict, Depends(current_user)]

    @app.post("/v1/managed/register", status_code=201)
    def register(body: credentials, request: Request):
        rate_limit("managed-registration:" + (request.client.host if request.client else "unknown"), limit=5)
        return create_user(body)

    @app.post(AI_PATH)
    async def recipe_ai(body: Completion, user: User):
        payload = body.upstream_body(config.model)
        def charge():
            with database(True) as db:
                reserve_request(db, user["id"], config)
        await run_in_threadpool(charge)
        try:
            return await complete(payload, config.key)
        except (httpx.HTTPError, ValueError):
            raise HTTPException(503, "Recipe reading is temporarily unavailable.") from None

    @app.get("/v1/managed/oauth/authorize")
    def authorize(request: Request, client_id: str, redirect_uri: str, response_type: str,
                  state: str, code_challenge: str, code_challenge_method: str):
        rate_limit("managed-oauth:" + (request.client.host if request.client else "unknown"), limit=30)
        if (client_id != CLIENT or redirect_uri != REDIRECT or response_type != "code" or code_challenge_method != "S256"
                or not re.fullmatch(CODE, code_challenge) or not re.fullmatch(CODE, state)):
            raise HTTPException(400, "Invalid Pinch authorization request.")
        flow, csrf = (secrets.token_urlsafe(32) for _ in range(2))
        with database(True) as db:
            db.execute("DELETE FROM managed_authorizations WHERE expires<?", (time.time(),))
            db.execute("DELETE FROM managed_codes WHERE expires<?", (time.time(),))
            db.execute("INSERT INTO managed_authorizations VALUES (?,?,?,?,?)",
                       (hash_value(flow), code_challenge, state, hash_value(csrf), time.time()+600))
        response = sign_in_page(flow, csrf)
        response.set_cookie("pinch_oauth_"+flow[:16], csrf, max_age=600, secure=True, httponly=True, samesite="lax",
                            path="/v1/managed/oauth/authorize")
        return response

    @app.post("/v1/managed/oauth/authorize")
    async def sign_in(request: Request):
        form = await request.form()
        flow, csrf = str(form.get("flow", "")), str(form.get("csrf", ""))
        if (request.headers.get("origin") != config.origin or not re.fullmatch(CODE, flow) or not re.fullmatch(CODE, csrf)
                or not hmac.compare_digest(csrf, request.cookies.get("pinch_oauth_"+flow[:16], ""))):
            raise HTTPException(403, "Start sign-in again from Pinch.")
        with database() as db:
            row = db.execute("SELECT * FROM managed_authorizations WHERE id=? AND expires>?", (hash_value(flow), time.time())).fetchone()
        if not row or not hmac.compare_digest(hash_value(csrf), row["csrf"]):
            raise HTTPException(400, "This sign-in has expired. Start again from Pinch.")
        username, password = str(form.get("username", "")).strip().lower(), str(form.get("password", ""))
        if not re.fullmatch(r"[a-z0-9_.-]{3,64}", username) or not 10 <= len(password) <= 256:
            return sign_in_page(flow, csrf, "Check your username and password.")
        try:
            user = await run_in_threadpool(authenticate_password, username, password, request.client.host if request.client else "unknown")
        except HTTPException as error:
            if error.status_code != 401:
                raise
            return sign_in_page(flow, csrf, "Check your username and password.")
        code = secrets.token_urlsafe(32)
        with database(True) as db:
            if not db.execute("DELETE FROM managed_authorizations WHERE id=? AND expires>?", (hash_value(flow), time.time())).rowcount:
                raise HTTPException(400, "This sign-in has already been used.")
            db.execute("INSERT INTO managed_codes VALUES (?,?,?,?)", (hash_value(code), user["id"], row["challenge"], time.time()+120))
        response = RedirectResponse(REDIRECT+"?"+urlencode({"code": code, "state": row["state"]}), 303, headers=PAGE_HEADERS)
        response.delete_cookie("pinch_oauth_"+flow[:16], path="/v1/managed/oauth/authorize", secure=True, httponly=True)
        return response

    @app.post("/v1/managed/oauth/token")
    async def token(request: Request):
        rate_limit("managed-token:" + (request.client.host if request.client else "unknown"), limit=30)
        form = await request.form()
        code, verifier = str(form.get("code", "")), str(form.get("code_verifier", ""))
        if (form.get("grant_type") != "authorization_code" or form.get("client_id") != CLIENT or form.get("redirect_uri") != REDIRECT
                or not re.fullmatch(CODE, code) or not re.fullmatch(VERIFIER, verifier)):
            return JSONResponse({"error": "invalid_request"}, 400, headers=PAGE_HEADERS)
        with database(True) as db:
            row = db.execute("SELECT u.*, c.challenge FROM managed_codes c JOIN users u ON u.id=c.user_id WHERE c.id=? AND c.expires>?",
                             (hash_value(code), time.time())).fetchone()
            if not row or not hmac.compare_digest(challenge_for(verifier), row["challenge"]):
                return JSONResponse({"error": "invalid_grant"}, 400, headers=PAGE_HEADERS)
            db.execute("DELETE FROM managed_codes WHERE id=?", (hash_value(code),))
            value = session(db, row)
        return JSONResponse({"access_token": value["token"], "token_type": "Bearer", "expires_in": 90*86400,
                             "username": value["username"]}, headers=PAGE_HEADERS)
