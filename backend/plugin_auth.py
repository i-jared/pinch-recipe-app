"""Persistent OAuth for Pinch's MCP endpoint. Mobile sessions remain separate."""

import hashlib
import hmac
from html import escape
import json
import re
import secrets
import time
from urllib.parse import urlencode, urlsplit

from mcp.server.auth.provider import (
    AccessToken, AuthorizationCode, AuthorizationParams, AuthorizeError,
    RefreshToken, RegistrationError, TokenError, construct_redirect_uri,
)
from mcp.shared.auth import OAuthClientInformationFull, OAuthToken
from pydantic import AnyHttpUrl
from starlette.responses import HTMLResponse, JSONResponse, RedirectResponse, Response

SCOPES = ["recipes:read", "recipes:write"]
SCHEMA = """
CREATE TABLE IF NOT EXISTS oauth_clients(id TEXT PRIMARY KEY, body TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS oauth_flows(id TEXT PRIMARY KEY, body TEXT NOT NULL, csrf TEXT, expires REAL NOT NULL);
CREATE TABLE IF NOT EXISTS oauth_codes(id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, body TEXT NOT NULL, expires REAL NOT NULL);
CREATE TABLE IF NOT EXISTS oauth_grants(id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, client_id TEXT NOT NULL, scopes TEXT NOT NULL, access TEXT UNIQUE NOT NULL, refresh TEXT UNIQUE NOT NULL, access_expires REAL NOT NULL, refresh_expires REAL NOT NULL);
CREATE INDEX IF NOT EXISTS oauth_grants_user ON oauth_grants(user_id);
CREATE TABLE IF NOT EXISTS plugin_creations(user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, request_id TEXT NOT NULL, fingerprint TEXT NOT NULL, recipe_id TEXT NOT NULL, PRIMARY KEY(user_id,request_id));
"""


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def validate_origin(value):
    parts = urlsplit(value)
    if (parts.scheme not in {"http", "https"} or not parts.hostname or parts.username
            or parts.password or parts.query or parts.fragment or parts.path not in {"", "/"}
            or (parts.scheme != "https" and parts.hostname not in {"localhost", "127.0.0.1"})):
        raise ValueError("PINCH_PUBLIC_URL must be an HTTPS origin (HTTP loopback is allowed for development).")
    return str(AnyHttpUrl(value)).rstrip("/")


class PinchOAuth:
    def __init__(self, database, rate_limit, password_hash, origin, extra_redirects=()):
        self.database, self.rate_limit, self.password_hash = database, rate_limit, password_hash
        self.origin = validate_origin(origin)
        self.resource = self.origin + "/mcp"
        self.extra_redirects = set(extra_redirects)

    def allowed_redirect(self, uri):
        # DCR never fetches client-supplied URLs. Only OpenAI callbacks or exact
        # operator-configured development/client callbacks may receive a code.
        parts = urlsplit(uri)
        return uri in self.extra_redirects or (
            parts.scheme == "https" and parts.netloc == "chatgpt.com"
            and not parts.query and not parts.fragment
            and (parts.path == "/connector_platform_oauth_redirect"
                 or re.fullmatch(r"/connector/oauth/[A-Za-z0-9_-]+", parts.path) is not None))

    async def get_client(self, client_id):
        with self.database() as db:
            row = db.execute("SELECT body FROM oauth_clients WHERE id=?", (client_id,)).fetchone()
        return OAuthClientInformationFull.model_validate_json(row[0]) if row else None

    async def register_client(self, client_info):
        if (not client_info.redirect_uris or len(client_info.redirect_uris) > 10
                or any(not self.allowed_redirect(str(u)) for u in client_info.redirect_uris)):
            raise RegistrationError("invalid_redirect_uri", "Use an OpenAI callback or a callback configured by the server operator.")
        # Public clients use PKCE, so the database need not hold client secrets.
        if client_info.token_endpoint_auth_method != "none":
            raise RegistrationError("invalid_client_metadata", "Select token_endpoint_auth_method=none; S256 PKCE is required.")
        if len(client_info.model_dump_json()) > 12000:
            raise RegistrationError("invalid_client_metadata", "Client metadata is too large.")
        with self.database(True) as db:
            if db.execute("SELECT COUNT(*) FROM oauth_clients").fetchone()[0] >= 1000:
                raise RegistrationError("invalid_client_metadata", "Client registration capacity reached. Contact the server operator.")
            db.execute("INSERT INTO oauth_clients VALUES (?,?)", (client_info.client_id, client_info.model_dump_json()))

    async def authorize(self, client, params):
        if params.resource != self.resource:
            raise AuthorizeError("invalid_request", "The resource must be this server's MCP endpoint.")
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}", params.code_challenge):
            raise AuthorizeError("invalid_request", "Use an S256 PKCE challenge.")
        if not params.scopes or not set(params.scopes).issubset(SCOPES):
            raise AuthorizeError("invalid_scope", "Request recipes:read and optionally recipes:write.")
        flow = secrets.token_urlsafe(32)
        body = {"client_id": client.client_id, "params": params.model_dump(mode="json")}
        with self.database(True) as db:
            db.execute("DELETE FROM oauth_flows WHERE expires<?", (time.time(),))
            db.execute("DELETE FROM oauth_codes WHERE expires<?", (time.time(),))
            db.execute("DELETE FROM oauth_grants WHERE refresh_expires<?", (time.time(),))
            db.execute("INSERT INTO oauth_flows VALUES (?,?,NULL,?)", (digest(flow), json.dumps(body), time.time()+600))
        return self.origin + "/plugin/connect?" + urlencode({"flow": flow})

    async def connect(self, request):
        form = await request.form() if request.method == "POST" else request.query_params
        flow = str(form.get("flow", ""))
        with self.database() as db:
            row = db.execute("SELECT * FROM oauth_flows WHERE id=? AND expires>?", (digest(flow), time.time())).fetchone()
        if not row:
            return HTMLResponse("This connection request expired. Connect Pinch again.", status_code=400)
        body = json.loads(row["body"])
        params = AuthorizationParams.model_validate(body["params"])
        client = await self.get_client(body["client_id"])
        cookie_name = "pinch_connect_" + digest(flow)[:16]
        error = ""
        if request.method == "POST":
            csrf = str(form.get("csrf", ""))
            cookie = request.cookies.get(cookie_name, "")
            if (request.headers.get("origin") != self.origin or not csrf or not cookie
                    or not hmac.compare_digest(digest(csrf), row["csrf"] or "")
                    or not hmac.compare_digest(csrf, cookie)):
                return HTMLResponse("Connection check failed. Connect Pinch again.", status_code=403)
            if form.get("decision") == "deny":
                with self.database(True) as db:
                    db.execute("DELETE FROM oauth_flows WHERE id=?", (digest(flow),))
                response = RedirectResponse(construct_redirect_uri(str(params.redirect_uri), error="access_denied", state=params.state, iss=self.origin), 303)
                response.delete_cookie(cookie_name, path="/plugin/connect")
                return response
            username, password = str(form.get("username", "")).lower(), str(form.get("password", ""))
            self.rate_limit("plugin-login:" + (request.client.host if request.client else "unknown"))
            self.rate_limit("plugin-user:" + username)
            if not re.fullmatch(r"[a-z0-9_.-]{3,64}", username) or not 10 <= len(password) <= 256:
                error = "Check your username and password."
            else:
                with self.database() as db:
                    user = db.execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()
                salt = user["password"].split(":")[0] if user else "0"*32
                candidate = self.password_hash(password, salt)
                if not user or not hmac.compare_digest(candidate, user["password"]):
                    error = "Check your username and password."
                else:
                    code = secrets.token_urlsafe(32)
                    code_data = AuthorizationCode(
                        code="", client_id=client.client_id, subject=user["id"],
                        expires_at=time.time()+120, **params.model_dump(exclude={"state"}))
                    with self.database(True) as db:
                        # Single use, even when two login POSTs race.
                        consumed = db.execute("DELETE FROM oauth_flows WHERE id=? AND expires>?", (digest(flow), time.time())).rowcount
                        if not consumed:
                            return HTMLResponse("Connection request already used.", status_code=400)
                        current = db.execute("SELECT password FROM users WHERE id=?", (user["id"],)).fetchone()
                        if not current or current[0] != candidate:
                            return HTMLResponse("Account changed. Connect again.", status_code=400)
                        db.execute("INSERT INTO oauth_codes VALUES (?,?,?,?)", (digest(code), user["id"], code_data.model_dump_json(), code_data.expires_at))
                    response = RedirectResponse(construct_redirect_uri(str(params.redirect_uri), code=code, state=params.state, iss=self.origin), 303)
                    response.delete_cookie(cookie_name, path="/plugin/connect")
                    return response
        csrf = secrets.token_urlsafe(32)
        with self.database(True) as db:
            db.execute("UPDATE oauth_flows SET csrf=? WHERE id=?", (digest(csrf), digest(flow)))
        permissions = "Read your synced recipes"
        if "recipes:write" in params.scopes:
            permissions += ", save new recipes, and change favorites"
        page = f"""<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Connect Pinch</title><style>body{{margin:0;background:#f7f5ed;color:#173e34;font:17px system-ui;display:grid;min-height:100svh;place-items:center}}main{{max-width:420px;padding:36px}}h1{{font:42px Georgia;margin:16px 0}}label{{display:block;margin:20px 0 6px}}input,button{{box-sizing:border-box;width:100%;padding:14px;border:1px solid #aac7b6;border-radius:10px;font:inherit}}button{{margin-top:16px;cursor:pointer;background:#205d48;color:white}}button[value=deny]{{background:transparent;color:#173e34}}small{{display:block;line-height:1.6}}.error{{color:#a02e32}}a{{color:inherit}}</style>
<main><small>PINCH · YOUR RECIPE BOX</small><h1>A place at the table.</h1><p>Connect <strong>{escape(client.client_name or "your AI assistant")}</strong> to Pinch.</p>
<small>Permissions: {escape(permissions)}. Only this server account is connected. Recipes stored only on your phone must sync first.</small>
<p class="error" role="alert">{escape(error)}</p><form method="post" action="/plugin/connect">
<input type="hidden" name="flow" value="{escape(flow, quote=True)}"><input type="hidden" name="csrf" value="{csrf}">
<label for="username">Pinch username</label><input id="username" name="username" autocomplete="username" required maxlength="64">
<label for="password">Password</label><input id="password" name="password" type="password" autocomplete="current-password" required minlength="10" maxlength="256">
<button name="decision" value="allow">Connect library</button><button name="decision" value="deny" formnovalidate>Cancel</button></form>
<p><small>Use the account from Pinch → Settings → Backup &amp; sync. Your password stays with this Pinch server. Disconnect in ChatGPT to revoke the connection.</small></p></main></html>"""
        response = HTMLResponse(page, status_code=401 if error else 200)
        response.set_cookie(cookie_name, csrf, max_age=600, httponly=True, secure=self.origin.startswith("https:"), samesite="lax", path="/plugin/connect")
        return response

    async def load_authorization_code(self, client, authorization_code):
        with self.database() as db:
            row = db.execute("SELECT body FROM oauth_codes WHERE id=? AND expires>?", (digest(authorization_code), time.time())).fetchone()
        if not row:
            return None
        code = AuthorizationCode.model_validate_json(row[0])
        code.code = authorization_code
        return code if code.client_id == client.client_id else None

    def issue(self, db, user_id, client_id, scopes, grant_id=None, refresh_expires=None):
        access, refresh = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        if grant_id:
            db.execute("DELETE FROM oauth_grants WHERE id=?", (grant_id,))
        db.execute("INSERT INTO oauth_grants VALUES (?,?,?,?,?,?,?,?)", (
            grant_id or secrets.token_urlsafe(24), user_id, client_id, json.dumps(scopes),
            digest(access), digest(refresh), time.time()+3600, refresh_expires or time.time()+90*86400))
        return OAuthToken(access_token=access, token_type="Bearer", expires_in=3600, refresh_token=refresh, scope=" ".join(scopes))

    async def exchange_authorization_code(self, client, authorization_code):
        with self.database(True) as db:
            removed = db.execute("DELETE FROM oauth_codes WHERE id=? AND expires>?", (digest(authorization_code.code), time.time())).rowcount
            if not removed:
                raise TokenError("invalid_grant", "Authorization code expired or already used.")
            return self.issue(db, authorization_code.subject, client.client_id, authorization_code.scopes)

    async def load_refresh_token(self, client, refresh_token):
        with self.database() as db:
            row = db.execute("SELECT * FROM oauth_grants WHERE refresh=? AND client_id=? AND refresh_expires>?", (digest(refresh_token), client.client_id, time.time())).fetchone()
        return RefreshToken(token=refresh_token, client_id=row["client_id"], subject=row["user_id"], scopes=json.loads(row["scopes"]), expires_at=int(row["refresh_expires"]), resource=self.resource) if row else None

    async def exchange_refresh_token(self, client, refresh_token, scopes):
        with self.database(True) as db:
            row = db.execute("SELECT * FROM oauth_grants WHERE refresh=? AND client_id=? AND refresh_expires>?", (digest(refresh_token.token), client.client_id, time.time())).fetchone()
            if not row:
                raise TokenError("invalid_grant", "Refresh token expired or already used. Connect again.")
            return self.issue(db, row["user_id"], client.client_id, scopes, row["id"], row["refresh_expires"])

    async def load_access_token(self, token):
        with self.database() as db:
            row = db.execute("SELECT * FROM oauth_grants WHERE access=? AND access_expires>?", (digest(token), time.time())).fetchone()
        return AccessToken(token=token, client_id=row["client_id"], subject=row["user_id"], scopes=json.loads(row["scopes"]), expires_at=int(row["access_expires"]), resource=self.resource) if row else None

    async def revoke_token(self, token):
        with self.database(True) as db:
            db.execute("DELETE FROM oauth_grants WHERE access=? OR refresh=?", (digest(token.token), digest(token.token)))

    async def revoke(self, request):
        # The SDK 1.x revocation schema requires client_secret even for public
        # clients. RFC 7009 permits revocation with the issued public client ID.
        form = await request.form()
        client_id, token = str(form.get("client_id", "")), str(form.get("token", ""))
        if not token or not client_id:
            return JSONResponse({"error": "invalid_request"}, 400)
        if not await self.get_client(client_id):
            return JSONResponse({"error": "invalid_client"}, 401)
        with self.database(True) as db:
            db.execute("DELETE FROM oauth_grants WHERE client_id=? AND (access=? OR refresh=?)", (client_id, digest(token), digest(token)))
        return Response(status_code=200)


class OAuthBoundary:
    """Constrain OAuth requests and add issuer identification to SDK redirects."""
    def __init__(self, app, provider):
        self.app, self.provider = app, provider

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        from starlette.requests import Request
        request = Request(scope, receive)
        path = scope["path"]
        if request.headers.get("host") != urlsplit(self.provider.origin).netloc:
            return await JSONResponse({"error": "invalid_host"}, 421)(scope, receive, send)
        if path in {"/register", "/authorize", "/token", "/revoke"}:
            self.provider.rate_limit("oauth:" + path + ":" + (request.client.host if request.client else "unknown"), limit=120)
        if path == "/token" and request.method == "POST":
            body = await request.body()
            form = await request.form()
            if form.getlist("resource") != [self.provider.resource]:
                return await JSONResponse({"error": "invalid_target"}, 400, headers={"Cache-Control": "no-store"})(scope, receive, send)
            delivered = False

            async def replay():
                nonlocal delivered
                if delivered:
                    return await receive()
                delivered = True
                return {"type": "http.request", "body": body, "more_body": False}
            receive_body = replay
        else:
            receive_body = receive

        async def secure_send(message):
            if message["type"] == "http.response.start":
                headers = list(message.get("headers", []))
                if path == "/authorize":
                    headers = [(k, construct_redirect_uri(v.decode(), iss=self.provider.origin).encode()
                                if k == b"location" and not v.decode().startswith(self.provider.origin + "/plugin/connect?") else v)
                               for k, v in headers]
                headers += [(b"cache-control", b"no-store"), (b"referrer-policy", b"no-referrer"), (b"x-content-type-options", b"nosniff")]
                if path == "/plugin/connect":
                    headers += [(b"content-security-policy", b"default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")]
                message = {**message, "headers": headers}
            await send(message)
        await self.app(scope, receive_body, secure_send)
