"""Regression tests for proxy-aware login limits and bounded, authenticated uploads."""
import asyncio
import sqlite3

from fastapi.testclient import TestClient
import pytest
from uvicorn.middleware.proxy_headers import ProxyHeadersMiddleware

from server import create_app, digest, MAX_PHOTO
from test_server import account, KEY, PASSWORD, JPEG


def test_login_limits_follow_trusted_client_ips_and_not_global_usernames(tmp_path):
    app = ProxyHeadersMiddleware(create_app(tmp_path, KEY), trusted_hosts=['172.28.0.1'])
    with TestClient(app, client=('172.28.0.1', 1234)) as client:
        account(client, 'alice')
        attacker = {'X-Forwarded-For': '198.51.100.1'}
        legitimate = {'X-Forwarded-For': '203.0.113.2'}
        for _ in range(10):
            assert client.post('/v1/auth/login', headers=attacker,
                               json={'username': 'ALICE', 'password': 'an incorrect password'}).status_code == 401
        blocked = client.post('/v1/auth/login', headers=attacker,
                              json={'username': 'alice', 'password': PASSWORD})
        assert blocked.status_code == 429 and int(blocked.headers['Retry-After']) > 0
        assert client.post('/v1/auth/login', headers=legitimate,
                           json={'username': 'alice', 'password': PASSWORD}).status_code == 200
        # Different guesses also exhaust only this IP's general auth budget.
        for index in range(11):
            blocked = client.post('/v1/auth/login', headers=attacker,
                                  json={'username': f'guess{index}', 'password': PASSWORD})
        assert blocked.status_code == 429
        assert client.post('/v1/auth/login', headers=legitimate,
                           json={'username': 'alice', 'password': PASSWORD}).status_code == 200


def test_untrusted_peer_cannot_change_login_bucket_using_forwarded_headers(tmp_path):
    app = ProxyHeadersMiddleware(create_app(tmp_path, KEY), trusted_hosts=['172.28.0.1'])
    with TestClient(app, client=('203.0.113.7', 1234)) as client:
        account(client, 'alice')
        for index in range(20):
            response = client.post('/v1/auth/login', headers={'X-Forwarded-For': f'198.51.100.{index}'},
                                   json={'username': f'guess{index}', 'password': PASSWORD})
        assert response.status_code == 429
    with sqlite3.connect(tmp_path / 'pinch.sqlite3') as db:
        assert db.execute('SELECT count FROM attempts WHERE bucket=?',
                          (digest('auth:203.0.113.7'),)).fetchone()[0] == 21
        assert db.execute('SELECT count FROM attempts WHERE bucket=?',
                          (digest('auth:198.51.100.1'),)).fetchone() is None


def test_successful_login_clears_failed_pair_and_limits_survive_restart(tmp_path):
    with TestClient(create_app(tmp_path, KEY), client=('198.51.100.1', 1234)) as client:
        account(client, 'alice')
        assert client.post('/v1/auth/login', json={'username': 'alice', 'password': 'incorrect password'}).status_code == 401
        assert client.post('/v1/auth/login', json={'username': 'alice', 'password': PASSWORD}).status_code == 200
        with sqlite3.connect(tmp_path / 'pinch.sqlite3') as db:
            assert db.execute('SELECT count FROM attempts WHERE bucket=?',
                              (digest('login-failed:198.51.100.1:alice'),)).fetchone() is None
        for _ in range(10):
            client.post('/v1/auth/login', json={'username': 'alice', 'password': 'incorrect password'})
    with TestClient(create_app(tmp_path, KEY), client=('198.51.100.1', 1234)) as client:
        assert client.post('/v1/auth/login', json={'username': 'alice', 'password': PASSWORD}).status_code == 429
    with TestClient(create_app(tmp_path, KEY), client=('203.0.113.2', 1234)) as client:
        assert client.post('/v1/auth/login', json={'username': 'alice', 'password': PASSWORD}).status_code == 200


async def upload(app, receive, headers=None, path='/v1/photos/review.jpg'):
    scope = {'type': 'http', 'asgi': {'version': '3.0'}, 'http_version': '1.1', 'method': 'PUT',
             'scheme': 'http', 'path': path, 'raw_path': path.encode(), 'query_string': b'',
             'root_path': '', 'server': ('localhost', 8000), 'client': ('198.51.100.1', 1234),
             'headers': [(key.lower().encode(), value.encode()) for key, value in (headers or {}).items()]}
    messages = []

    async def send(message):
        messages.append(message)

    await app(scope, receive, send)
    return next((m['status'] for m in messages if m['type'] == 'http.response.start'), None)


async def forbidden_receive():
    raise AssertionError('Rejected request must not read its body')


@pytest.mark.parametrize('credential', ['missing', 'invalid', 'expired'])
@pytest.mark.parametrize('prefix', ['', '/recipes'])
def test_unauthenticated_photos_rejected_without_receiving_body(tmp_path, monkeypatch, credential, prefix):
    monkeypatch.setenv('PINCH_ROOT_PATH', prefix)
    app = create_app(tmp_path, KEY)
    with TestClient(app) as client:
        user = account(client, 'alice')
        headers = {} if credential == 'missing' else {'Authorization': 'Bearer invalid'}
        if credential == 'expired':
            headers = user
            with sqlite3.connect(tmp_path / 'pinch.sqlite3') as db:
                db.execute('UPDATE sessions SET expires=0')
        # Also reject before considering a huge Content-Length.
        headers = {**headers, 'Content-Length': str(MAX_PHOTO * 100)}
        assert asyncio.run(upload(app, forbidden_receive, headers, prefix+'/v1/photos/review.jpg')) == 401


def test_maximum_photo_works_and_actual_chunked_size_is_enforced(tmp_path):
    app = create_app(tmp_path, KEY)
    with TestClient(app) as client:
        user = account(client, 'alice')
        photo = JPEG + b' ' * (MAX_PHOTO - len(JPEG))
        assert client.put('/v1/photos/max.jpg', headers=user, content=photo).status_code == 200
        assert client.get('/v1/photos/max.jpg', headers=user).content == photo
        messages = iter([{'type': 'http.request', 'body': photo, 'more_body': True},
                         {'type': 'http.request', 'body': b'x', 'more_body': False}])
        async def receive():
            return next(messages)
        assert asyncio.run(upload(app, receive, user)) == 413
        assert client.get('/v1/photos/review.jpg', headers=user).status_code == 404
        assert asyncio.run(upload(app, forbidden_receive,
                                 {**user, 'Content-Length': str(MAX_PHOTO+1)})) == 413
        assert client.put('/v1/photos/after-limit.jpg', headers=user, content=JPEG).status_code == 200


def test_only_two_uploads_admitted_and_slots_released_after_success(tmp_path):
    app = create_app(tmp_path, KEY)
    with TestClient(app) as client:
        user = account(client, 'alice')
        async def check():
            entered = [asyncio.Event(), asyncio.Event()]
            finish = asyncio.Event()
            def receiver(index):
                first = True
                async def receive():
                    nonlocal first
                    if first:
                        first = False
                        entered[index].set()
                        return {'type': 'http.request', 'body': JPEG, 'more_body': True}
                    await finish.wait()
                    return {'type': 'http.request', 'body': b'', 'more_body': False}
                return receive
            tasks = [asyncio.create_task(upload(app, receiver(i), user, f'/v1/photos/{i}.jpg')) for i in range(2)]
            try:
                await asyncio.wait_for(asyncio.gather(*(event.wait() for event in entered)), 5)
                assert await upload(app, forbidden_receive, user) == 503
                # Unauthorized traffic is still a 401, even while all slots are occupied.
                assert await upload(app, forbidden_receive) == 401
                finish.set()
                assert await asyncio.gather(*tasks) == [200, 200]
            finally:
                finish.set()
                await asyncio.gather(*tasks, return_exceptions=True)
        asyncio.run(check())
        assert client.put('/v1/photos/after.jpg', headers=user, content=JPEG).status_code == 200


@pytest.mark.parametrize('reason', ['idle', 'total', 'disconnect', 'cancel'])
def test_failed_upload_releases_slot_and_does_not_save_partial_photo(tmp_path, monkeypatch, reason):
    monkeypatch.setenv('PINCH_MAX_UPLOADS', '1')
    monkeypatch.setenv('PINCH_UPLOAD_IDLE_SECONDS', '0.05' if reason == 'idle' else '0.2')
    monkeypatch.setenv('PINCH_UPLOAD_TOTAL_SECONDS', '0.08' if reason == 'total' else '1')
    app = create_app(tmp_path, KEY)
    with TestClient(app) as client:
        user = account(client, 'alice')
        async def check():
            started = asyncio.Event()
            async def receive():
                started.set()
                if reason == 'disconnect':
                    return {'type': 'http.disconnect'}
                if reason == 'total':
                    await asyncio.sleep(0.03)
                    return {'type': 'http.request', 'body': JPEG, 'more_body': True}
                await asyncio.Event().wait()
            task = asyncio.create_task(upload(app, receive, user))
            await asyncio.wait_for(started.wait(), 5)
            if reason == 'cancel':
                task.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await task
            else:
                assert await asyncio.wait_for(task, 2) == (None if reason == 'disconnect' else 408)
        asyncio.run(check())
        assert client.get('/v1/photos/review.jpg', headers=user).status_code == 404
        assert client.put('/v1/photos/after.jpg', headers=user, content=JPEG).status_code == 200


@pytest.mark.parametrize('setting,value', [('PINCH_TRUSTED_PROXY_IPS', '*'),
                                         ('PINCH_TRUSTED_PROXY_IPS', '172.28.0.0/16'),
                                         ('PINCH_MAX_UPLOADS', '0'),
                                         ('PINCH_UPLOAD_IDLE_SECONDS', 'nan'),
                                         ('PINCH_UPLOAD_TOTAL_SECONDS', '-1')])
def test_unsafe_or_invalid_security_configuration_refused(tmp_path, monkeypatch, setting, value):
    monkeypatch.setenv(setting, value)
    with pytest.raises(ValueError):
        create_app(tmp_path, KEY)
