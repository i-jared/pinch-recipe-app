import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import re
import sqlite3
from urllib.parse import parse_qs, urlsplit

from fastapi.testclient import TestClient
import pytest

from server import create_app
from test_server import account, KEY, PASSWORD, put, recipe

ORIGIN = "https://recipes.test"
CALLBACK = "https://chatgpt.com/connector_platform_oauth_redirect"
VERIFIER = "a" * 64
CHALLENGE = base64.urlsafe_b64encode(hashlib.sha256(VERIFIER.encode()).digest()).decode().rstrip("=")
SCOPES = "recipes:read recipes:write"
DRAFT = {"title": "Lemon pasta", "servings": "4", "ingredients": [{"text": "200 g pasta"}, {"text": "1 lemon"}], "steps": [{"text": "Cook the pasta and toss with lemon."}], "tags": ["Dinner"], "sourceUrl": "https://example.org/pasta"}


@pytest.fixture
def plugin_client(tmp_path):
    with TestClient(create_app(tmp_path, KEY, public_url=ORIGIN), base_url=ORIGIN) as client:
        client.data_dir = tmp_path
        yield client


def register(client):
    result = client.post('/register', json={"client_name": "ChatGPT", "redirect_uris": [CALLBACK], "token_endpoint_auth_method": "none", "grant_types": ["authorization_code", "refresh_token"], "scope": SCOPES})
    assert result.status_code == 201, result.text
    return result.json()['client_id']


def begin(client, client_id=None, scopes=SCOPES, **overrides):
    cid = client_id or register(client)
    params = {"client_id": cid, "redirect_uri": CALLBACK, "response_type": "code", "state": "keep-state", "scope": scopes, "code_challenge": CHALLENGE, "code_challenge_method": "S256", "resource": ORIGIN+"/mcp", **overrides}
    response = client.get('/authorize', params=params, follow_redirects=False)
    assert response.status_code == 302, response.text
    return cid, response


def code(client, username='alice', scopes=SCOPES, password=PASSWORD):
    cid, response = begin(client, scopes=scopes)
    page = client.get(response.headers['location'])
    assert page.status_code == 200, page.text
    flow = re.search(r'name="flow" value="([^"]+)"', page.text)[1]
    csrf = re.search(r'name="csrf" value="([^"]+)"', page.text)[1]
    response = client.post('/plugin/connect', data={"flow": flow, "csrf": csrf, "username": username, "password": password, "decision": "allow"}, headers={"Origin": ORIGIN}, follow_redirects=False)
    assert response.status_code == 303, response.text
    params = parse_qs(urlsplit(response.headers['location']).query)
    assert params['state'] == ['keep-state'] and params['iss'] == [ORIGIN]
    return {"grant_type": "authorization_code", "code": params['code'][0], "client_id": cid, "redirect_uri": CALLBACK, "code_verifier": VERIFIER, "resource": ORIGIN+'/mcp'}


def connect(client, username='alice', scopes=SCOPES):
    data = code(client, username, scopes)
    response = client.post('/token', data=data)
    assert response.status_code == 200, response.text
    return {**response.json(), "client_id": data['client_id']}


def rpc(client, token, method, params=None):
    response = client.post('/mcp', json={"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}}, headers={"Authorization": "Bearer "+token, "Accept": "application/json, text/event-stream", "MCP-Protocol-Version": "2025-11-25"})
    assert response.status_code == 200, response.text
    return response.json()['result']


def tool(client, token, name, **args):
    return rpc(client, token, "tools/call", {"name": name, "arguments": args})


def test_oauth_discovery_and_transport(plugin_client):
    c = plugin_client
    mobile = account(c, 'alice')
    response = c.post('/mcp', json={})
    assert response.status_code == 401
    assert '/.well-known/oauth-protected-resource/mcp' in response.headers['www-authenticate']
    assert c.get('/.well-known/oauth-protected-resource/mcp').json()['resource'] == ORIGIN+'/mcp'
    metadata = c.get('/.well-known/oauth-authorization-server').json()
    assert metadata['issuer'] == ORIGIN and metadata['authorization_response_iss_parameter_supported']
    assert metadata['code_challenge_methods_supported'] == ['S256']
    assert metadata['token_endpoint_auth_methods_supported'] == ['none']
    tokens = connect(c)
    result = rpc(c, tokens['access_token'], 'initialize', {"protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}})
    assert result['serverInfo']['name'] == 'Pinch'
    # Neither credential grants access to the other API surface.
    assert c.get('/v1/recipes', headers={"Authorization": "Bearer "+tokens['access_token']}).status_code == 401
    assert c.post('/mcp', headers=mobile, json={}).status_code == 401
    assert c.post('/mcp', headers={"Authorization": "Bearer "+tokens['access_token'], "Host": "evil.test"}, json={}).status_code == 421
    tools = rpc(c, tokens['access_token'], 'tools/list')['tools']
    assert {t['name'] for t in tools} == {'search_recipes', 'get_recipe', 'save_recipe', 'set_recipe_favorite', 'open_recipe_library'}
    for t in tools:
        assert 'outputSchema' in t
        assert all(k in t['annotations'] for k in ('readOnlyHint', 'destructiveHint', 'openWorldHint'))
    ui = rpc(c, tokens['access_token'], 'resources/read', {'uri':'ui://pinch/library-v1.html'})
    assert 'text/html' in ui['contents'][0]['mimeType']
    assert 'ui/initialize' in ui['contents'][0]['text']
    assert ui['contents'][0]['_meta']['ui']['csp'] == {'connectDomains': [], 'resourceDomains': []}


def test_plugin_sign_in_accepts_ten_character_password(plugin_client):
    c = plugin_client
    password = '1234567890'
    assert c.post('/v1/auth/register', json={'username': 'tencook', 'password': password, 'registrationKey': KEY}).status_code == 201
    data = code(c, username='tencook', password=password)
    assert c.post('/token', data=data).status_code == 200


def test_pkce_resource_binding_replay_refresh_and_revocation(plugin_client):
    c=plugin_client
    account(c,'alice')
    data=code(c)
    assert c.post('/token',data={**data,'code_verifier':'wrong'}).status_code==400
    assert c.post('/token',data={**data,'resource':'https://other.test/mcp'}).status_code==400
    assert c.post('/token',data={**data,'redirect_uri':'https://evil.test/cb'}).status_code==400
    result=c.post('/token',data=data)
    assert result.status_code==200,result.text
    token=result.json()
    assert c.post('/token',data=data).status_code==400
    refresh={"client_id":data['client_id'],"grant_type":"refresh_token","refresh_token":token['refresh_token'],"resource":ORIGIN+'/mcp'}
    assert c.post('/token',data={**refresh,'scope':'recipes:delete'}).status_code==400
    assert c.post('/token',data={**refresh,'resource':'https://other.test/mcp'}).status_code==400
    result=c.post('/token',data=refresh)
    assert result.status_code==200,result.text
    rotated=result.json()
    assert rotated['refresh_token']!=token['refresh_token']
    assert c.post('/token',data=refresh).status_code==400
    assert c.post('/mcp',headers={'Authorization':'Bearer '+token['access_token']},json={}).status_code==401
    assert c.post('/revoke',data={'client_id':data['client_id'],'token':rotated['refresh_token']}).status_code==200
    assert c.post('/mcp',headers={'Authorization':'Bearer '+rotated['access_token']},json={}).status_code==401
    with sqlite3.connect(c.data_dir/'pinch.sqlite3') as db:
        assert db.execute('SELECT count(*) FROM oauth_grants').fetchone()[0]==0


def test_redirects_csrf_consent_denial_and_issuer(plugin_client):
    c=plugin_client
    cid=register(c)
    bad=c.post('/register',json={'redirect_uris':['https://evil.test/cb'],'token_endpoint_auth_method':'none','grant_types':['authorization_code','refresh_token']})
    assert bad.status_code==400
    _, response=begin(c,client_id=cid,resource='https://evil.test/mcp')
    assert parse_qs(urlsplit(response.headers['location']).query)['iss']==[ORIGIN]
    assert 'error=' in response.headers['location']
    _,response=begin(c,client_id=cid)
    page=c.get(response.headers['location'])
    assert "frame-ancestors 'none'" in page.headers['content-security-policy']
    assert page.headers['referrer-policy']=='no-referrer'
    flow=re.search(r'name="flow" value="([^"]+)"',page.text)[1]
    csrf=re.search(r'name="csrf" value="([^"]+)"',page.text)[1]
    data={'flow':flow,'csrf':csrf,'decision':'deny'}
    assert c.post('/plugin/connect',data=data,headers={'Origin':'https://evil.test'}).status_code==403
    assert c.post('/plugin/connect',data={**data,'csrf':'wrong'},headers={'Origin':ORIGIN}).status_code==403
    response=c.post('/plugin/connect',data=data,headers={'Origin':ORIGIN},follow_redirects=False)
    assert response.status_code==303
    assert parse_qs(urlsplit(response.headers['location']).query)['error']==['access_denied']
    assert c.post('/plugin/connect',data=data,headers={'Origin':ORIGIN}).status_code==400


def test_recipe_flow_mobile_sync_isolation_idempotency_and_scopes(plugin_client):
    c=plugin_client
    alice,bob=account(c,'alice'),account(c,'bob')
    put(c,alice,recipe())
    a=connect(c)['access_token'];b=connect(c,'bob')['access_token']
    read_only=connect(c,scopes='recipes:read')['access_token']
    search=tool(c,a,'search_recipes',query='flour')['structuredContent']
    assert len(search['recipes'])==1
    assert tool(c,b,'search_recipes')['structuredContent']['recipes']==[]
    assert tool(c,b,'get_recipe',recipe_id='recipe-1')['isError']
    found=tool(c,a,'get_recipe',recipe_id='recipe-1')['structuredContent']['recipe']
    assert found['title']=='Family bread' and 'rawText' not in found and 'photos' not in found
    assert tool(c,read_only,'save_recipe',request_id='test-save-1',recipe=DRAFT)['isError']
    assert tool(c,read_only,'set_recipe_favorite',recipe_id='recipe-1',favorite=True)['isError']
    saved=tool(c,a,'save_recipe',request_id='test-save-1',recipe=DRAFT)['structuredContent']
    rid=saved['recipe']['id']
    assert saved['created'] and len(saved['recipe']['ingredients'])==2
    again=tool(c,a,'save_recipe',request_id='test-save-1',recipe=DRAFT)['structuredContent']
    assert not again['created'] and again['recipe']['id']==rid
    assert tool(c,a,'save_recipe',request_id='test-save-1',recipe={**DRAFT,'title':'Different'})['isError']
    favorite=tool(c,a,'set_recipe_favorite',recipe_id=rid,favorite=True)['structuredContent']['recipe']
    repeated=tool(c,a,'set_recipe_favorite',recipe_id=rid,favorite=True)['structuredContent']['recipe']
    assert favorite['updatedAt']==repeated['updatedAt']
    synced=c.get('/v1/recipes',headers=alice).json()
    assert len(synced)==2 and any(r['id']==rid and r['isFavorite'] for r in synced)
    assert c.get('/v1/recipes',headers=bob).json()==[]
    assert tool(c,a,'search_recipes',favorites_only=True,tag='dinner')['structuredContent']['total']==1
    assert tool(c,a,'search_recipes',query='no such dish')['structuredContent']['total']==0


def test_concurrent_saves_pagination_tombstones_and_token_storage(plugin_client):
    c=plugin_client
    mobile=account(c,'alice');token=connect(c)
    with ThreadPoolExecutor(max_workers=4) as pool:
        results=list(pool.map(lambda _:tool(c,token['access_token'],'save_recipe',request_id='concurrent-1',recipe=DRAFT),range(4)))
    assert sum(r['structuredContent']['created'] for r in results)==1
    assert len(c.get('/v1/recipes',headers=mobile).json())==1
    put(c,mobile,recipe())
    first=tool(c,token['access_token'],'search_recipes',limit=1)['structuredContent']
    second=tool(c,token['access_token'],'search_recipes',limit=1,offset=first['next_offset'])['structuredContent']
    assert first['total']==2 and second['next_offset'] is None
    assert first['recipes'][0]['id']!=second['recipes'][0]['id']
    with sqlite3.connect(c.data_dir/'pinch.sqlite3') as db:
        body=json.loads(db.execute("SELECT body FROM recipes WHERE id='recipe-1'").fetchone()[0]);body['deletedAt']=body['updatedAt']
        db.execute("UPDATE recipes SET body=? WHERE id='recipe-1'",(json.dumps(body),))
        row=db.execute('SELECT access,refresh FROM oauth_grants').fetchone()
        assert token['access_token'] not in row and token['refresh_token'] not in row
    assert tool(c,token['access_token'],'get_recipe',recipe_id='recipe-1')['isError']
    assert tool(c,token['access_token'],'search_recipes')['structuredContent']['total']==1
    assert c.request('DELETE','/v1/auth/account',headers=mobile,json={'password':PASSWORD}).status_code==200
    assert c.post('/mcp',json={},headers={'Authorization':'Bearer '+token['access_token']}).status_code==401


def test_disabled_plugin_and_invalid_public_origin(tmp_path):
    with TestClient(create_app(tmp_path,KEY,public_url='')) as c:
        assert c.get('/.well-known/oauth-authorization-server').status_code==404
    with pytest.raises(ValueError):
        create_app(tmp_path,KEY,public_url='http://public.example.com')
    with pytest.raises(ValueError):
        create_app(tmp_path,KEY,public_url='https://public.example.com/subpath')


def test_token_expiry_restart_and_client_binding(tmp_path):
    with TestClient(create_app(tmp_path,KEY,public_url=ORIGIN),base_url=ORIGIN) as c:
        account(c,'alice')
        data=code(c)
        other=register(c)
        assert c.post('/token',data={**data,'client_id':other}).status_code==400
        token=c.post('/token',data=data).json()
        assert c.post('/revoke',data={'client_id':other,'token':token['access_token']}).status_code==200
        assert tool(c,token['access_token'],'search_recipes')['structuredContent']['total']==0
    with TestClient(create_app(tmp_path,KEY,public_url=ORIGIN),base_url=ORIGIN) as c:
        assert tool(c,token['access_token'],'search_recipes')['structuredContent']['total']==0
        with sqlite3.connect(tmp_path/'pinch.sqlite3') as db:
            db.execute('UPDATE oauth_grants SET access_expires=0')
        assert c.post('/mcp',json={},headers={'Authorization':'Bearer '+token['access_token']}).status_code==401
        refresh={'client_id':data['client_id'],'grant_type':'refresh_token','refresh_token':token['refresh_token'],'resource':ORIGIN+'/mcp'}
        assert c.post('/token',data={**refresh,'client_id':other}).status_code==400
        refreshed=c.post('/token',data=refresh)
        assert refreshed.status_code==200
        with sqlite3.connect(tmp_path/'pinch.sqlite3') as db:
            db.execute('UPDATE oauth_grants SET refresh_expires=0')
        assert c.post('/token',data={**refresh,'refresh_token':refreshed.json()['refresh_token']}).status_code==400


def test_input_validation_and_mobile_fields_are_preserved(plugin_client):
    c=plugin_client;mobile=account(c,'alice');token=connect(c)['access_token']
    body=recipe(rawText='Private OCR',notes='Kitchen note',lastCookedAt='2026-01-01T00:00:00Z')
    put(c,mobile,body)
    assert tool(c,token,'save_recipe',request_id='invalid-1',recipe={**DRAFT,'sourceUrl':'javascript:alert(1)'})['isError']
    assert tool(c,token,'save_recipe',request_id='invalid-2',recipe={**DRAFT,'ingredients':[]})['isError']
    assert tool(c,token,'search_recipes',limit=1000)['isError']
    assert tool(c,token,'get_recipe',recipe_id='../private')['isError']
    result=tool(c,token,'set_recipe_favorite',recipe_id='recipe-1',favorite=True)['structuredContent']['recipe']
    synced=c.get('/v1/recipes',headers=mobile).json()[0]
    assert synced['rawText']=='Private OCR' and synced['lastCookedAt']=='2026-01-01T00:00:00Z'
    assert 'rawText' not in result and 'lastCookedAt' not in result
    assert len(c.get('/v1/recipes',headers=mobile).json())==1


def test_save_respects_quota_and_login_rate_limits(tmp_path,monkeypatch):
    monkeypatch.setenv('PINCH_USER_QUOTA_MB','0')
    with TestClient(create_app(tmp_path,KEY,public_url=ORIGIN),base_url=ORIGIN) as c:
        account(c,'alice');token=connect(c)['access_token']
        assert tool(c,token,'save_recipe',request_id='quota-test',recipe=DRAFT)['isError']
        assert tool(c,token,'search_recipes')['structuredContent']['total']==0
        _,response=begin(c)
        url=response.headers['location']
        for attempt in range(20):
            page=c.get(url)
            flow=re.search(r'name="flow" value="([^"]+)"',page.text)[1]
            csrf=re.search(r'name="csrf" value="([^"]+)"',page.text)[1]
            result=c.post('/plugin/connect',data={'flow':flow,'csrf':csrf,'username':'alice','password':'wrong password','decision':'allow'},headers={'Origin':ORIGIN})
        assert result.status_code==429
