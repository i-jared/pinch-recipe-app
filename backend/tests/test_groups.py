from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from test_server import client, account, put, recipe, JPEG, PASSWORD


def setup_group(client):
    owner, member, outsider = [account(client, n) for n in ['owner', 'member', 'outsider']]
    group = client.post('/v1/groups', headers=owner, json={'name': 'Family'}).json()
    path = '/v1/groups/' + group['id']
    assert client.post('/v1/groups/join', headers=member, json={'code': group['inviteCode']}).status_code == 200
    return owner, member, outsider, group, path


def test_member_controls_and_invite_revocation(client):
    owner, member, outsider, group, path = setup_group(client)
    assert client.get(path+'/members', headers=outsider).status_code == 404
    roster = client.get(path+'/members', headers=owner).json()
    mid = next(m['id'] for m in roster if m['username'] == 'member')
    oid = next(m['id'] for m in roster if m['isOwner'])
    assert client.patch(path, headers=member, json={'name': 'No'}).status_code == 403
    assert client.patch(path, headers=owner, json={'name': 'Our cookbook'}).status_code == 200
    code = client.post(path+'/invitations', headers=owner).json()['inviteCode']
    assert client.get('/v1/invitations/'+code).json()['name'] == 'Our cookbook'
    assert client.delete(path+'/members/'+oid, headers=owner).status_code == 409
    assert client.delete(path+'/members/'+mid, headers=member).status_code == 403
    assert client.delete(path+'/members/'+mid, headers=owner).status_code == 200
    for old in [code, group['inviteCode']]:
        assert client.post('/v1/groups/join', headers=member, json={'code': old}).status_code == 404
    assert client.get(path+'/recipes', headers=member).status_code == 404
    code = client.post(path+'/invitations', headers=owner).json()['inviteCode']
    client.post('/v1/groups/join', headers=member, json={'code': code})
    assert client.post(path+'/owner', headers=owner, json={'userId': mid}).status_code == 200
    assert client.patch(path, headers=owner, json={'name': 'No'}).status_code == 403
    assert client.delete(path, headers=owner).status_code == 200
    assert client.get('/v1/groups', headers=member).json()[0]['isOwner']


def test_invite_registration_without_operator_key(client):
    owner, member, outsider, group, path = setup_group(client)
    code = client.post(path+'/invitations', headers=owner).json()['inviteCode']
    body = {'username': 'newcook', 'password': '1234567890', 'code': 'invalid'}
    assert client.post('/v1/auth/register-invite', json=body).status_code == 404
    body['code'] = code
    response = client.post('/v1/auth/register-invite', json=body)
    assert response.status_code == 201
    user = {'Authorization': 'Bearer '+response.json()['token']}
    assert client.get('/v1/groups', headers=user).json()[0]['id'] == group['id']
    assert client.get('/v1/recipes', headers=user).json() == []
    code2 = client.post(path+'/invitations', headers=owner).json()['inviteCode']
    assert client.get('/v1/invitations/'+code).status_code == 200
    client.post(path+'/invite', headers=owner)
    assert client.get('/v1/invitations/'+code).status_code == 404
    assert client.get('/v1/invitations/'+code2).status_code == 404
    assert 'pinch://join?' in client.get('/join').text


def test_latest_snapshot_saved_once_and_survives_group_deletion(client):
    owner, member, outsider, group, path = setup_group(client)
    original = recipe(photos=['page.jpg'], isFavorite=True, rawText='private')
    client.put('/v1/photos/page.jpg', headers=owner, content=JPEG)
    put(client, owner, original)
    client.post(path+'/recipes', headers=owner, json={'recipeId': original['id']})
    first = client.get(path+'/recipes', headers=member).json()[0]
    updated = dict(original, title='Updated rolls', photos=['new.jpg'], updatedAt=(datetime.now(timezone.utc)+timedelta(seconds=1)).isoformat())
    client.put('/v1/photos/new.jpg', headers=owner, content=JPEG+b'new')
    put(client, owner, updated)
    client.post(path+'/recipes', headers=owner, json={'recipeId': original['id']})
    save_path = path+'/recipes/'+first['id']+'/save'
    assert client.post(save_path, headers=outsider).status_code == 404
    with ThreadPoolExecutor(max_workers=2) as pool:
        saved = list(pool.map(lambda _: client.post(save_path, headers=member).json(), range(2)))
    assert saved[0]['id'] == saved[1]['id']
    copy = saved[0]
    assert copy['title'] == 'Updated rolls'
    assert copy['origin']['sharedBy'] == 'owner'
    assert copy['rawText'] == '' and not copy['isFavorite']
    assert copy['photos'] != updated['photos']
    assert client.get('/v1/photos/'+copy['photos'][0], headers=member).content == JPEG+b'new'
    shared = client.get(path+'/recipes/'+first['id'], headers=member).json()
    assert shared['savedRecipeId'] == copy['id']
    summary = client.get('/v1/groups', headers=owner).json()[0]
    assert summary['memberCount'] == 2 and summary['recipeCount'] == 1
    assert summary['mySharedRecipeIds'] == [original['id']]
    assert summary['previews'][0]['title'] == 'Updated rolls'
    client.delete(path, headers=owner)
    assert client.get('/v1/recipes', headers=member).json()[0]['id'] == copy['id']
    assert client.get('/v1/photos/'+copy['photos'][0], headers=member).status_code == 200


def test_save_after_personal_delete_makes_new_copy(client):
    owner, member, outsider, group, path = setup_group(client)
    put(client, owner, recipe())
    client.post(path+'/recipes', headers=owner, json={'recipeId': 'recipe-1'})
    sid = client.get(path+'/recipes', headers=member).json()[0]['id']
    save_path = path+'/recipes/'+sid+'/save'
    copy = client.post(save_path, headers=member).json()
    now = (datetime.now(timezone.utc)+timedelta(seconds=1)).isoformat()
    assert put(client, member, dict(copy, deletedAt=now, updatedAt=now)).status_code == 200
    assert client.get(path+'/recipes', headers=member).json()[0]['savedRecipeId'] is None
    assert client.post(save_path, headers=member).json()['id'] != copy['id']


def test_existing_database_migrates_without_losing_groups_or_shares(tmp_path):
    import sqlite3
    from fastapi.testclient import TestClient
    from server import create_app
    from test_server import KEY
    with TestClient(create_app(tmp_path, KEY)) as first:
        owner, member, outsider, group, path = setup_group(first)
        put(first, owner, recipe())
        first.post(path+'/recipes', headers=owner, json={'recipeId': 'recipe-1'})
    # Recreate the pre-cookbook schema while preserving its data.
    with sqlite3.connect(tmp_path/'pinch.sqlite3') as db:
        db.execute('ALTER TABLE shares DROP COLUMN shared_at')
        db.execute('DROP TABLE group_invites')
        db.execute('DROP TABLE saved_shares')
    with TestClient(create_app(tmp_path, KEY)) as second:
        assert second.get('/v1/groups', headers=member).status_code == 410
        assert second.get('/v1/cookbooks', headers=member).json()[0]['id'] == group['id']
        path = '/v1/cookbooks/'+group['id']
        shares = second.get(path+'/recipes', headers=member).json()
        assert shares[0]['recipe']['title'] == 'Family bread'
        assert second.post(path+'/recipes/'+shares[0]['id']+'/save', headers=member).status_code == 200
        assert second.post('/v1/cookbooks/join', headers=outsider, json={'code': group['inviteCode']}).status_code == 200
