from datetime import datetime, timedelta, timezone
from test_server import client, account, put, recipe, JPEG, PASSWORD


def doc(id='book-1', name='Family favorites', entries=None, **fields):
    return dict(id=id, name=name, entries=entries or [], updatedAt=datetime.now(timezone.utc).isoformat(), **fields)


def setup(client):
    owner, editor, viewer, outsider = [account(client, name) for name in ['owner','editor','viewer','outsider']]
    book = client.put('/v1/cookbooks/book-1', headers=owner, json=doc()).json()
    for user, role in [(editor,'editor'),(viewer,'viewer')]:
        code = client.post('/v1/cookbooks/book-1/invitations', headers=owner, json={'role':role}).json()['inviteCode']
        assert client.get('/v1/cookbook-invitations/'+code).json()['role'] == role
        assert client.post('/v1/cookbooks/join', headers=user, json={'code':code}).status_code == 200
    return owner, editor, viewer, outsider, book


def test_editors_add_to_owners_library_and_viewers_cannot_write(client):
    owner, editor, viewer, outsider, book = setup(client)
    client.put('/v1/photos/page.jpg', headers=editor, content=JPEG)
    put(client, editor, recipe(photos=['page.jpg'], rawText='private OCR', isFavorite=True))
    path = '/v1/cookbooks/book-1/recipes'
    assert client.post(path, headers=viewer, json={'recipeId':'recipe-1'}).status_code == 403
    assert client.get(path, headers=outsider).status_code == 404
    rid = client.post(path, headers=editor, json={'recipeId':'recipe-1'}).json()['recipeId']
    library = client.get('/v1/recipes', headers=owner).json()
    assert len(library) == 1 and library[0]['id'] == rid
    assert library[0]['origin']['sharedBy'] == 'editor'
    assert library[0]['rawText'] == '' and not library[0]['isFavorite']
    page = library[0]['photos'][0]
    assert client.get('/v1/photos/'+page, headers=owner).content == JPEG
    assert client.get(path+'/'+rid+'/photos/'+page, headers=viewer).content == JPEG
    assert client.get(path+'/'+rid+'/photos/not-in-this-recipe.jpg', headers=viewer).status_code == 404
    assert client.delete(path+'/'+rid, headers=viewer).status_code == 403
    assert client.put('/v1/cookbooks/book-1', headers=editor, json=doc(name='Renamed')).status_code == 403
    assert client.post(path, headers=editor, json={'recipeId':'recipe-1'}).json()['recipeId'] == rid
    client.put('/v1/cookbooks/book-2', headers=owner, json=doc(id='book-2'))
    code = client.post('/v1/cookbooks/book-2/invitations', headers=owner, json={'role':'editor'}).json()['inviteCode']
    client.post('/v1/cookbooks/join', headers=editor, json={'code':code})
    assert client.post('/v1/cookbooks/book-2/recipes', headers=editor, json={'recipeId':'recipe-1'}).json()['recipeId'] == rid
    assert len(client.get('/v1/recipes', headers=owner).json()) == 1
    assert client.delete(path+'/'+rid, headers=editor).status_code == 200
    assert len(client.get('/v1/recipes', headers=owner).json()) == 1
    assert client.get(path, headers=viewer).json() == []


def test_owner_offline_membership_changes_merge_with_new_contributions(client):
    owner, editor, viewer, outsider, book = setup(client)
    put(client, owner, recipe())
    before = datetime.now(timezone.utc).isoformat()
    own = doc(entries=[dict(recipeId='recipe-1', updatedAt=before, removed=False)])
    assert client.put('/v1/cookbooks/book-1', headers=owner, json=own).status_code == 200
    put(client, editor, dict(recipe(), id='recipe-2'))
    rid = client.post('/v1/cookbooks/book-1/recipes', headers=editor, json={'recipeId':'recipe-2'}).json()['recipeId']
    own['entries'][0].update(removed=True, updatedAt=datetime.now(timezone.utc).isoformat())
    response = client.put('/v1/cookbooks/book-1', headers=owner, json=own)
    assert response.status_code == 200
    entries = {e['recipeId']:e for e in response.json()['document']['entries']}
    assert entries['recipe-1']['removed'] and not entries[rid]['removed']
    # An offline device with an older add cannot undo that removal.
    own['entries'][0].update(removed=False, updatedAt=before)
    response = client.put('/v1/cookbooks/book-1', headers=owner, json=own)
    assert next(e for e in response.json()['document']['entries'] if e['recipeId']=='recipe-1')['removed']
    # A rename on another device must not keep an offline-deleted cookbook shared.
    renamed = doc(name='Renamed')
    renamed['updatedAt'] = (datetime.now(timezone.utc)+timedelta(seconds=1)).isoformat()
    client.put('/v1/cookbooks/book-1', headers=owner, json=renamed)
    deleted = doc(deletedAt=datetime.now(timezone.utc).isoformat())
    client.put('/v1/cookbooks/book-1', headers=owner, json=deleted)
    assert client.get('/v1/cookbooks/book-1/recipes', headers=viewer).status_code == 404
    assert len(client.get('/v1/recipes', headers=owner).json()) == 2
    assert client.get('/v1/cookbooks', headers=owner).json()[0]['document']['deletedAt']


def test_copying_a_shared_recipe_preserves_the_banner(client):
    owner, editor, viewer, outsider, book = setup(client)
    assert client.put('/v1/photos/first.jpg', headers=editor, content=JPEG+b'first').status_code == 200
    assert client.put('/v1/photos/second.jpg', headers=editor, content=JPEG+b'second').status_code == 200
    assert put(client, editor, recipe(recipePhotos=['first.jpg', 'second.jpg'], bannerPhoto='second.jpg')).status_code == 200
    path = '/v1/cookbooks/book-1/recipes'
    shared = client.post(path, headers=editor, json={'recipeId': 'recipe-1'})
    assert shared.status_code == 200
    item = path + '/' + shared.json()['recipeId']
    owner_recipe = client.get(item, headers=viewer).json()['recipe']
    banner = owner_recipe['bannerPhoto']
    assert banner == owner_recipe['recipePhotos'][1] and banner != 'second.jpg'
    assert client.get(item + '/photos/' + banner, headers=viewer).content == JPEG+b'second'
    assert client.get(item + '/photos/' + banner, headers=outsider).status_code == 404
    saved = client.post(item + '/save', headers=viewer).json()
    assert saved['bannerPhoto'] == saved['recipePhotos'][1] and saved['bannerPhoto'] != banner
    assert client.get('/v1/photos/' + saved['bannerPhoto'], headers=viewer).content == JPEG+b'second'


def test_roles_revocation_and_invitation_signup(client):
    owner, editor, viewer, outsider, book = setup(client)
    path = '/v1/cookbooks/book-1'
    assert client.post(path+'/invitations', headers=editor, json={'role':'editor'}).status_code == 403
    people = client.get(path+'/members', headers=owner).json()
    editor_id = next(p['id'] for p in people if p['username']=='editor')
    code = client.post(path+'/invitations', headers=owner, json={'role':'editor'}).json()['inviteCode']
    assert client.patch(path+'/members/'+editor_id, headers=viewer, json={'role':'viewer'}).status_code == 403
    assert client.patch(path+'/members/'+editor_id, headers=owner, json={'role':'viewer'}).status_code == 200
    assert client.get('/v1/cookbook-invitations/'+code).status_code == 404
    assert client.get('/v1/cookbooks', headers=editor).json()[0]['role']=='viewer'
    assert client.post(path+'/recipes', headers=editor, json={'recipeId':'recipe-1'}).status_code == 403
    code = client.post(path+'/invitations', headers=owner, json={'role':'viewer'}).json()['inviteCode']
    signup = client.post('/v1/auth/register-cookbook-invite', json={'username':'newcook','password':'1234567890','code':code})
    assert signup.status_code == 201, signup.text
    new_user = {'Authorization':'Bearer '+signup.json()['token']}
    assert client.get('/v1/cookbooks', headers=new_user).json()[0]['role']=='viewer'
    assert client.delete(path+'/members/'+editor_id, headers=owner).status_code == 200
    assert client.get(path+'/recipes', headers=editor).status_code == 404
    assert client.delete(path+'/membership', headers=viewer).status_code == 200
    assert client.get('/v1/cookbooks', headers=viewer).json() == []


def test_shared_content_follows_owner_edits_and_saved_copy_is_independent(client):
    owner, editor, viewer, outsider, book = setup(client)
    original = dict(recipe(), title='Bread')
    put(client, owner, original)
    client.put('/v1/cookbooks/book-1', headers=owner, json=doc(entries=[dict(recipeId='recipe-1',updatedAt=doc()['updatedAt'])]))
    path = '/v1/cookbooks/book-1/recipes/recipe-1'
    saved = client.post(path+'/save', headers=viewer).json()
    assert saved['id'] != 'recipe-1'
    assert client.post(path+'/save', headers=viewer).json()['id'] == saved['id']
    original.update(title='Better bread', updatedAt=(datetime.now(timezone.utc)+timedelta(seconds=1)).isoformat())
    put(client, owner, original)
    assert client.get(path, headers=viewer).json()['recipe']['title'] == 'Better bread'
    assert client.get('/v1/recipes', headers=viewer).json()[0]['title'] == 'Bread'


def test_group_migration_keeps_recipes_people_and_invites(tmp_path):
    from fastapi.testclient import TestClient
    from server import create_app
    from test_server import KEY
    with TestClient(create_app(tmp_path,KEY)) as first:
        owner, editor = account(first,'owner'),account(first,'editor')
        group = first.post('/v1/groups',headers=owner,json={'name':'Family'}).json()
        first.post('/v1/groups/join',headers=editor,json={'code':group['inviteCode']})
        put(first,editor,recipe())
        first.post('/v1/groups/'+group['id']+'/recipes',headers=editor,json={'recipeId':'recipe-1'})
    for _ in range(2):
        with TestClient(create_app(tmp_path,KEY)) as restarted:
            books = restarted.get('/v1/cookbooks',headers=owner).json()
            assert len(books)==1 and books[0]['recipeCount']==1
            assert len(restarted.get('/v1/recipes',headers=owner).json())==1
            assert restarted.get('/v1/cookbooks',headers=editor).json()[0]['role']=='editor'
            assert restarted.get('/v1/cookbook-invitations/'+group['inviteCode']).status_code==200
            # Old clients cannot keep using snapshot access after migration.
            assert restarted.get('/v1/groups',headers=editor).status_code==410
            assert restarted.get('/v1/groups/'+group['id']+'/recipes',headers=editor).status_code==410
            assert restarted.get('/v1/invitations/'+group['inviteCode']).status_code==410
            people=restarted.get('/v1/cookbooks/'+group['id']+'/members',headers=owner).json()
            editor_id=next(p['id'] for p in people if p['username']=='editor')
            if _ == 1:
                restarted.delete('/v1/cookbooks/'+group['id']+'/members/'+editor_id,headers=owner)
                assert restarted.get('/v1/cookbooks/'+group['id']+'/recipes',headers=editor).status_code==404
                assert restarted.post('/v1/groups/join',headers=editor,json={'code':group['inviteCode']}).status_code==410
                assert restarted.get('/v1/cookbook-invitations/'+group['inviteCode']).status_code==404


def test_migration_preserves_snapshot_without_publishing_private_edits(tmp_path):
    from fastapi.testclient import TestClient
    from server import create_app
    from test_server import KEY
    with TestClient(create_app(tmp_path,KEY)) as first:
        owner = account(first,'owner')
        group = first.post('/v1/groups',headers=owner,json={'name':'Family'}).json()
        original = recipe(notes='Shared note')
        put(first,owner,original)
        first.post('/v1/groups/'+group['id']+'/recipes',headers=owner,json={'recipeId':'recipe-1'})
        original.update(notes='Private note', updatedAt=(datetime.now(timezone.utc)+timedelta(seconds=1)).isoformat())
        put(first,owner,original)
    with TestClient(create_app(tmp_path,KEY)) as restarted:
        shared = restarted.get('/v1/cookbooks/'+group['id']+'/recipes',headers=owner).json()
        assert shared[0]['recipe']['notes']=='Shared note'
        assert shared[0]['recipe']['id']!='recipe-1'
        private = restarted.get('/v1/recipes',headers=owner).json()
        assert next(r for r in private if r['id']=='recipe-1')['notes']=='Private note'
