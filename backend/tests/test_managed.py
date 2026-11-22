import re
from urllib.parse import parse_qs, urlsplit

import pytest
from fastapi.testclient import TestClient

import managed
from server import create_app
from test_server import KEY, PASSWORD

ORIGIN = "https://recipes.lmbrt.net"
LOGIN = "/v1/managed/oauth/authorize"
TOKEN = "/v1/managed/oauth/token"
CREDENTIALS = {"username": "homecook", "password": PASSWORD}


@pytest.fixture
def hosted(tmp_path, monkeypatch):
    monkeypatch.setenv("PINCH_MANAGED_URL", ORIGIN)
    monkeypatch.setenv("PINCH_MANAGED_OPENROUTER_KEY", "only-on-the-server")
    monkeypatch.setenv("PINCH_MANAGED_AI_PER_ACCOUNT", "2")
    monkeypatch.setenv("PINCH_MANAGED_AI_PER_DAY", "3")
    with TestClient(create_app(tmp_path, KEY), base_url=ORIGIN) as client:
        yield client


def register(client, username="homecook"):
    response = client.post("/v1/managed/register", json={**CREDENTIALS, "username": username})
    assert response.status_code == 201
    assert "only-on-the-server" not in response.text
    return {"Authorization": "Bearer " + response.json()["token"]}


def test_managed_signup_is_opt_in_on_the_server(tmp_path, monkeypatch):
    monkeypatch.delenv("PINCH_MANAGED_URL", raising=False)
    with TestClient(create_app(tmp_path, KEY)) as client:
        assert client.post("/v1/managed/register", json=CREDENTIALS).status_code == 404
        assert client.get(LOGIN).status_code == 404
        assert client.post(managed.AI_PATH, json={}).status_code == 404
        assert client.post("/v1/auth/register", json=CREDENTIALS).status_code == 403
        assert client.post("/v1/auth/register", json={**CREDENTIALS, "registrationKey": KEY}).status_code == 201


def test_managed_accounts_share_password_login_and_backups(hosted):
    registered = register(hosted)
    login = hosted.post("/v1/managed/login", json=CREDENTIALS)
    assert login.json()["username"] == "homecook"
    assert hosted.get("/v1/recipes", headers=registered).json() == []
    assert hosted.post("/v1/auth/register", json=CREDENTIALS).status_code == 403
    headers = {"Authorization": "Bearer " + login.json()["token"]}
    assert hosted.post("/v1/auth/logout", headers=headers).status_code == 200
    assert hosted.get("/v1/recipes", headers=headers).status_code == 401


def test_browser_login_requires_csrf_and_single_use_pkce(hosted):
    register(hosted)
    verifier, state = "a" * 43, "b" * 43
    authorization = {"client_id": managed.CLIENT, "response_type": "code", "redirect_uri": managed.REDIRECT,
                     "code_challenge": managed.challenge_for(verifier), "code_challenge_method": "S256", "state": state}
    assert hosted.get(LOGIN, params={**authorization, "redirect_uri": "https://other.example"}).status_code == 400
    start = hosted.get(LOGIN, params=authorization)
    assert start.status_code == 200 and "no-store" in start.headers["Cache-Control"]
    form = {name: re.search(f'name="{name}" value="([^"]+)"', start.text)[1] for name in ("flow", "csrf")}
    assert hosted.post(LOGIN, data={**form, **CREDENTIALS}).status_code == 403
    credentials = {**form, **CREDENTIALS}
    assert hosted.post(LOGIN, data={**credentials, "password": "wrong password"}, headers={"Origin": ORIGIN}).status_code == 401
    response = hosted.post(LOGIN, data=credentials, headers={"Origin": ORIGIN}, follow_redirects=False)
    assert response.status_code == 303
    redirect = urlsplit(response.headers["Location"])
    assert f"{redirect.scheme}://{redirect.netloc}" == managed.REDIRECT
    query = parse_qs(redirect.query)
    assert query["state"] == [state] and set(query) == {"code", "state"}
    token_form = {"grant_type": "authorization_code", "client_id": managed.CLIENT, "redirect_uri": managed.REDIRECT,
                  "code": query["code"][0], "code_verifier": verifier}
    assert hosted.post(TOKEN, data={**token_form, "code_verifier": "x" * 43}).json() == {"error": "invalid_grant"}
    token = hosted.post(TOKEN, data=token_form)
    assert token.status_code == 200 and token.json()["token_type"] == "Bearer"
    auth = {"Authorization": "Bearer " + token.json()["access_token"]}
    assert hosted.get("/v1/recipes", headers=auth).status_code == 200
    assert hosted.post(TOKEN, data=token_form).json() == {"error": "invalid_grant"}


def test_ai_uses_host_model_and_key_and_applies_budgets(hosted, monkeypatch):
    calls = []

    async def fake_completion(body, key):
        calls.append((body, key))
        return {"choices": [{"message": {"content": '{"ingredientNumbersByStep":[[1]]}'}}]}

    monkeypatch.setattr(managed, "complete", fake_completion)
    user, another = register(hosted), register(hosted, "second-cook")
    completion = {"model": "ignored-client-model", "tools": ["ignored"], "temperature": 1,
                  "messages": [{"role": "system", "content": "Assign ingredients to steps."},
                               {"role": "user", "content": [{"type": "text", "text": "Flour"}]}],
                  "response_format": {"type": "json_schema", "json_schema": {"name": "cook_mode_ingredients", "schema": {"type": "object"}}}}
    assert hosted.post(managed.AI_PATH, json=completion).status_code == 401
    remote_image = [{"type": "image_url", "image_url": {"url": "https://other.example/photo.jpg"}}]
    assert hosted.post(managed.AI_PATH, headers=user, json={**completion, "messages": [completion["messages"][0], {"role": "user", "content": remote_image}]}).status_code == 422
    for headers, status in [(user, 200), (user, 200), (user, 429), (another, 200), (another, 429)]:
        assert hosted.post(managed.AI_PATH, headers=headers, json=completion).status_code == status
    assert len(calls) == 3
    body, key = calls[0]
    assert key == "only-on-the-server"
    assert body["model"] == "deepseek/deepseek-v4.1-flash" and body["temperature"] == 0 and "tools" not in body
    assert body["provider"] == {"require_parameters": True}
