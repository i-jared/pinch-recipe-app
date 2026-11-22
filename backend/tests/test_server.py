from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json
import sqlite3

import pytest
from fastapi.testclient import TestClient
from server import create_app, MAX_JSON, MAX_PHOTO

KEY = "test-registration-key-that-is-long-enough"
PASSWORD = "a strong test password"
JPEG = b"\xff\xd8\xff\xe0test-jpeg\xff\xd9"


@pytest.fixture
def client(tmp_path):
    with TestClient(create_app(tmp_path, KEY)) as client:
        client.data_dir = tmp_path
        yield client


def account(client, name):
    response = client.post('/v1/auth/register', json={"username": name, "password": PASSWORD, "registrationKey": KEY})
    assert response.status_code == 201, response.text
    return {"Authorization": "Bearer " + response.json()["token"]}


def recipe(**values):
    now = datetime.now(timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z')
    return dict(id="recipe-1", title="Family bread", createdAt=now, updatedAt=now,
                ingredients=[{"id": "flour", "text": "2 cups flour", "isHeader": False}], steps=[], **values)


def put(client, user, body):
    return client.put('/v1/recipes/' + body['id'], headers=user, json=body)


def test_banner_photos_survive_older_app_edits_and_can_be_removed(client):
    user = account(client, 'owner')
    body = recipe(photos=['scan.jpg'], recipePhotos=['dish.jpg'], bannerPhoto='dish.jpg')
    assert client.put('/v1/photos/scan.jpg', headers=user, content=JPEG).status_code == 200
    assert put(client, user, body).status_code == 409
    assert client.put('/v1/photos/dish.jpg', headers=user, content=JPEG+b'dish').status_code == 200
    assert put(client, user, dict(body, bannerPhoto='scan.jpg')).status_code == 422
    saved = put(client, user, body)
    assert saved.status_code == 200 and saved.json()['bannerPhoto'] == 'dish.jpg'

    legacy = {key: value for key, value in body.items() if key not in {'recipePhotos', 'bannerPhoto'}}
    first_update = datetime.now(timezone.utc) + timedelta(seconds=1)
    updated = put(client, user, dict(legacy, title='Updated', updatedAt=first_update.isoformat())).json()
    assert updated['title'] == 'Updated'
    assert updated['recipePhotos'] == ['dish.jpg'] and updated['bannerPhoto'] == 'dish.jpg'

    headers = dict(user, **{'X-Pinch-Recipe-Photos': '1'})
    removed = put(client, headers, dict(legacy, updatedAt=(first_update + timedelta(seconds=1)).isoformat()))
    assert removed.status_code == 200 and 'bannerPhoto' not in removed.json()
    assert client.get('/v1/photos/dish.jpg', headers=user).status_code == 404
    assert client.get('/v1/photos/scan.jpg', headers=user).status_code == 200


def test_authentication_and_private_backups(client):
    alice, bob = account(client, 'Alice'), account(client, 'bob')
    assert client.get('/v1/recipes').status_code == 401
    assert client.post('/v1/auth/register', json={"username": "carol", "password": PASSWORD, "registrationKey": "wrong"}).status_code == 403
    assert put(client, alice, recipe()).status_code == 200
    assert len(client.get('/v1/recipes', headers=alice).json()) == 1
    assert client.get('/v1/recipes', headers=bob).json() == []
    login = client.post('/v1/auth/login', json={"username": "ALICE", "password": PASSWORD})
    assert login.status_code == 200
    restored = client.get('/v1/recipes', headers={"Authorization": "Bearer " + login.json()['token']}).json()
    assert restored[0]['title'] == 'Family bread'
    assert client.post('/v1/auth/logout', headers=alice).status_code == 200
    assert client.get('/v1/recipes', headers=alice).status_code == 401
    assert client.get('/v1/recipes', headers={"Authorization": "Bearer " + login.json()['token']}).status_code == 200


def test_photos_are_private_and_recipe_commit_requires_them(client):
    alice, bob = account(client, 'alice'), account(client, 'bob')
    body = recipe(photos=['page-1.jpg'])
    assert put(client, alice, body).status_code == 409
    assert client.put('/v1/photos/page-1.jpg', headers=alice, content=JPEG).status_code == 200
    assert client.put('/v1/photos/page-1.jpg', headers=alice, content=JPEG).status_code == 200
    assert client.put('/v1/photos/page-1.jpg', headers=alice, content=JPEG+b'changed').status_code == 409
    assert put(client, alice, body).status_code == 200
    assert client.get('/v1/photos/page-1.jpg', headers=alice).content == JPEG
    assert client.get('/v1/photos/page-1.jpg', headers=bob).status_code == 404
    assert client.put('/v1/photos/bad.jpg', headers=alice, content=b'not a JPEG').status_code == 422


def test_stale_writes_tombstones_equal_timestamp_and_concurrency(client):
    user = account(client, 'alice')
    body = recipe()
    older = deepcopy(body)
    older['updatedAt'] = '2020-01-01T00:00:00.000Z'
    assert put(client, user, body).status_code == 200
    older['title'] = 'stale edit'
    assert put(client, user, older).json()['title'] == 'Family bread'
    tie = dict(body, title='same time')
    assert put(client, user, tie).json()['title'] == 'Family bread'
    versions = [dict(body, title=f'Edit {i}', updatedAt=(datetime.now(timezone.utc)+timedelta(seconds=i)).isoformat()) for i in range(10)]
    with ThreadPoolExecutor(max_workers=5) as pool:
        responses = list(pool.map(lambda value: put(client, user, value), reversed(versions)))
    assert all(r.status_code == 200 for r in responses)
    assert client.get('/v1/recipes', headers=user).json()[0]['title'] == 'Edit 9'
    deleted = dict(body, updatedAt=(datetime.now(timezone.utc)+timedelta(seconds=20)).isoformat(), deletedAt=body['updatedAt'])
    assert put(client, user, deleted).status_code == 200
    assert put(client, user, body).json()['deletedAt'] == body['updatedAt']
    assert len(client.get('/v1/recipes', headers=user).json()) == 1  # retain tombstone


def test_group_sharing_membership_and_independent_copies(client):
    alice, bob, eve = [account(client, name) for name in ['alice', 'bob', 'eve']]
    body = recipe(photos=['page.jpg'], isFavorite=True, rawText='private OCR', lastCookedAt='2025-01-01T00:00:00Z')
    client.put('/v1/photos/page.jpg', headers=alice, content=JPEG)
    put(client, alice, body)
    group = client.post('/v1/groups', headers=alice, json={"name": "Family"}).json()
    path = '/v1/groups/' + group['id']
    assert client.get(path+'/recipes', headers=bob).status_code == 404
    assert client.post('/v1/groups/join', headers=bob, json={"code": group['inviteCode']}).status_code == 200
    assert client.post(path+'/recipes', headers=bob, json={"recipeId": body['id']}).status_code == 404
    assert client.post(path+'/recipes', headers=alice, json={"recipeId": body['id']}).status_code == 200
    shares = client.get(path+'/recipes', headers=bob).json()
    assert len(shares) == 1
    shared = shares[0]
    assert not shared['canRemove'] and shared['sharedBy'] == 'alice'
    assert shared['recipe']['isFavorite'] is False
    assert shared['recipe']['rawText'] == ''
    assert 'lastCookedAt' not in shared['recipe']
    photo = path+'/recipes/'+shared['id']+'/photos/page.jpg'
    assert client.get(photo, headers=bob).content == JPEG
    assert client.get(photo, headers=eve).status_code == 404
    assert client.delete(path+'/recipes/'+shared['id'], headers=bob).status_code == 403
    assert client.post(path+'/invite', headers=bob).status_code == 403
    assert client.post(path+'/recipes', headers=alice, json={"recipeId": body['id']}).status_code == 200
    assert len(client.get(path+'/recipes', headers=bob).json()) == 1  # idempotent re-share
    deleted = dict(body, photos=[], updatedAt=(datetime.now(timezone.utc)+timedelta(seconds=1)).isoformat(), deletedAt=body['updatedAt'])
    assert put(client, alice, deleted).status_code == 200
    assert client.get('/v1/photos/page.jpg', headers=alice).status_code == 404
    assert client.get(photo, headers=bob).content == JPEG  # snapshot survives private delete
    fresh = client.post(path+'/invite', headers=alice).json()['inviteCode']
    assert client.post('/v1/groups/join', headers=eve, json={"code": group['inviteCode']}).status_code == 404
    assert client.post('/v1/groups/join', headers=eve, json={"code": fresh}).status_code == 200
    assert client.delete(path, headers=bob).status_code == 200
    assert client.get(photo, headers=bob).status_code == 404
    assert client.delete(path, headers=alice).status_code == 200
    assert client.get(path+'/recipes', headers=eve).status_code == 404


def test_member_can_share_and_owner_can_moderate(client):
    alice, bob = account(client, 'alice'), account(client, 'bob')
    group = client.post('/v1/groups', headers=alice, json={"name": "Friends"}).json()
    path = '/v1/groups/'+group['id']
    client.post('/v1/groups/join', headers=bob, json={"code": group['inviteCode']})
    put(client, bob, recipe())
    client.post(path+'/recipes', headers=bob, json={"recipeId": 'recipe-1'})
    share = client.get(path+'/recipes', headers=alice).json()[0]
    assert share['canRemove']
    assert client.delete(path+'/recipes/'+share['id'], headers=alice).status_code == 200
    client.post(path+'/recipes', headers=bob, json={"recipeId": 'recipe-1'})
    client.delete(path, headers=bob)
    assert client.get(path+'/recipes', headers=alice).json() == []


@pytest.mark.parametrize('change', [
    {'id': '../escape'}, {'photos': ['../escape.jpg']}, {'photos': ['bad/name.jpg']},
    {'updatedAt': 'tomorrow'}, {'createdAt': '2025-01-01'}, {'schema': 2},
    {'updatedAt': '2999-01-01T00:00:00Z'}, {'ingredients': [{'text': 7}]},
])
def test_invalid_recipe_input(client, change):
    user = account(client, 'alice')
    body = recipe()
    body.update(change)
    response = client.put('/v1/recipes/recipe-1', headers=user, json=body)
    assert response.status_code == 422, response.text
    assert client.get('/v1/recipes', headers=user).json() == []


def test_limits_passwords_and_expiry(client):
    user = account(client, 'alice')
    bad = client.post('/v1/auth/login', json={'username': 'alice', 'password': 'secret'})
    assert bad.status_code == 422 and 'secret' not in bad.text
    assert client.put('/v1/recipes/recipe-1', headers=user, content=b' '*(MAX_JSON+1)).status_code == 413
    assert client.put('/v1/photos/large.jpg', headers=user, content=JPEG+b' ' * MAX_PHOTO).status_code == 413
    for _ in range(21):
        response = client.post('/v1/auth/login', json={'username': 'alice', 'password': 'wrong password here'})
    assert response.status_code == 429
    with sqlite3.connect(client.data_dir / 'pinch.sqlite3') as db:
        assert db.execute('SELECT password FROM users').fetchone()[0] != PASSWORD
        assert db.execute('SELECT token FROM sessions').fetchone()[0] != user['Authorization'][7:]
        db.execute('UPDATE sessions SET expires=0')
    assert client.get('/v1/recipes', headers=user).status_code == 401


def test_ten_character_password_for_signup_login_and_account_confirmation(client):
    credentials = {'username': 'tencook', 'password': '1234567890', 'registrationKey': KEY}
    too_short = dict(credentials, password='123456789')
    assert client.post('/v1/auth/register', json=too_short).status_code == 422
    registered = client.post('/v1/auth/register', json=credentials)
    assert registered.status_code == 201, registered.text
    assert client.post('/v1/auth/login', json=too_short).status_code == 422
    login = client.post('/v1/auth/login', json=credentials)
    assert login.status_code == 200, login.text
    headers = {'Authorization': 'Bearer ' + login.json()['token']}
    assert client.request('DELETE', '/v1/auth/account', headers=headers, json={'password': too_short['password']}).status_code == 422
    assert client.request('DELETE', '/v1/auth/account', headers=headers, json={'password': credentials['password']}).status_code == 200


def test_restart_and_registration_disabled(tmp_path):
    with TestClient(create_app(tmp_path, KEY)) as first:
        user = account(first, 'alice')
        put(first, user, recipe())
    with TestClient(create_app(tmp_path, '')) as second:
        assert len(second.get('/v1/recipes', headers=user).json()) == 1
        assert second.get('/health').json()['apiVersion'] == 1
        assert second.post('/v1/auth/register', json={'username': 'bob', 'password': PASSWORD, 'registrationKey': KEY}).status_code == 403


def test_quota_applies_to_orphan_uploads_and_group_copies(tmp_path, monkeypatch):
    monkeypatch.setenv('PINCH_USER_QUOTA_MB', '1')
    with TestClient(create_app(tmp_path, KEY)) as client:
        user = account(client, 'alice')
        data = JPEG + b' ' * (600*1024)
        assert client.put('/v1/photos/page.jpg', headers=user, content=data).status_code == 200
        assert client.put('/v1/photos/extra.jpg', headers=user, content=data).status_code == 413
        put(client, user, recipe(photos=['page.jpg']))
        group = client.post('/v1/groups', headers=user, json={'name': 'Family'}).json()
        assert client.post('/v1/groups/'+group['id']+'/recipes', headers=user, json={'recipeId': 'recipe-1'}).status_code == 413
        assert client.get('/v1/groups/'+group['id']+'/recipes', headers=user).json() == []


def test_account_deletion_cascades_and_requires_password(client):
    alice, bob = account(client, 'alice'), account(client, 'bob')
    group = client.post('/v1/groups', headers=alice, json={'name': 'Family'}).json()
    client.post('/v1/groups/join', headers=bob, json={'code': group['inviteCode']})
    put(client, alice, recipe())
    client.post('/v1/groups/'+group['id']+'/recipes', headers=alice, json={'recipeId': 'recipe-1'})
    assert client.request('DELETE', '/v1/auth/account', headers=alice, json={'password': 'wrong password here'}).status_code == 401
    assert client.request('DELETE', '/v1/auth/account', headers=alice, json={'password': PASSWORD}).status_code == 200
    assert client.get('/v1/recipes', headers=alice).status_code == 401
    assert client.get('/v1/groups', headers=bob).json() == []
    with sqlite3.connect(client.data_dir/'pinch.sqlite3') as db:
        for table in ['recipes', 'photos', 'groups', 'members', 'shares', 'share_photos']:
            assert db.execute('SELECT COUNT(*) FROM '+table).fetchone()[0] == 0


def test_reverse_proxy_prefix_keeps_docs_and_large_photos_working(tmp_path, monkeypatch):
    monkeypatch.setenv("PINCH_ROOT_PATH", "/recipes")
    # Caddy strips the prefix before forwarding. FastAPI uses root_path to generate URLs.
    with TestClient(create_app(tmp_path, KEY)) as client:
        assert client.get("/health").status_code == 200
        assert "/recipes/openapi.json" in client.get("/docs").text
        assert client.get("/openapi.json").json()["servers"] == [{"url": "/recipes"}]
        user = account(client, "prefix-check")
        photo = JPEG + b" " * (MAX_JSON + 1)
        assert client.put("/v1/photos/prefix-page.jpg", headers=user, content=photo).status_code == 200
        assert put(client, user, recipe(photos=["prefix-page.jpg"])).status_code == 200
        assert client.get("/v1/photos/prefix-page.jpg", headers=user).content == photo


def test_oauth_plugin_requires_an_origin_without_mobile_prefix(tmp_path, monkeypatch):
    monkeypatch.setenv("PINCH_ROOT_PATH", "/recipes")
    with pytest.raises(ValueError, match="own origin"):
        create_app(tmp_path, KEY, public_url="https://pinch.example.com")
