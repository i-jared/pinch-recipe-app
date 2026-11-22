"""Private cookbooks with optional collaborators and links into the owner's library."""
from datetime import datetime, timezone
import hashlib
import json
import secrets
import sqlite3
import time
from typing import Annotated, Literal
import uuid

from fastapi import Depends, HTTPException, Request, Response
from pydantic import BaseModel, Field, field_validator

ID = r'[a-zA-Z0-9_-]{1,80}'
SCHEMA = '''
CREATE TABLE IF NOT EXISTS cookbooks(id TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, name TEXT NOT NULL, updated_at TEXT NOT NULL, deleted_at TEXT);
CREATE TABLE IF NOT EXISTS cookbook_members(book_id TEXT REFERENCES cookbooks(id) ON DELETE CASCADE, user_id TEXT REFERENCES users(id) ON DELETE CASCADE, role TEXT NOT NULL CHECK(role IN ('editor','viewer')), PRIMARY KEY(book_id,user_id));
CREATE TABLE IF NOT EXISTS cookbook_entries(book_id TEXT REFERENCES cookbooks(id) ON DELETE CASCADE, recipe_id TEXT NOT NULL, updated_at TEXT NOT NULL, removed INTEGER NOT NULL DEFAULT 0, contributed_by TEXT NOT NULL, PRIMARY KEY(book_id,recipe_id));
CREATE TABLE IF NOT EXISTS cookbook_invites(code TEXT PRIMARY KEY, book_id TEXT REFERENCES cookbooks(id) ON DELETE CASCADE, role TEXT NOT NULL CHECK(role IN ('editor','viewer')));
CREATE TABLE IF NOT EXISTS cookbook_copies(owner_id TEXT REFERENCES users(id) ON DELETE CASCADE, source_user TEXT NOT NULL, source_id TEXT NOT NULL, recipe_id TEXT NOT NULL, PRIMARY KEY(owner_id,source_user,source_id));
'''


def now():
    return datetime.now(timezone.utc).isoformat(timespec='milliseconds')


def stamp(value):
    try:
        date = datetime.fromisoformat(value.replace('Z', '+00:00'))
        if date.tzinfo is None or date.timestamp() > time.time() + 300:
            raise ValueError()
        return date.timestamp()
    except (ValueError, OverflowError):
        raise HTTPException(422, 'Check your device clock before syncing cookbooks.')


class Entry(BaseModel):
    recipeId: str = Field(pattern=f'^{ID}$')
    updatedAt: str = Field(max_length=40)
    removed: bool = False


class Document(BaseModel):
    id: str = Field(pattern=f'^{ID}$')
    name: str = Field(min_length=1, max_length=80)
    updatedAt: str = Field(max_length=40)
    deletedAt: str | None = Field(default=None, max_length=40)
    entries: list[Entry] = Field(default_factory=list, max_length=10000)

    @field_validator('name')
    @classmethod
    def trim(cls, value):
        if not value.strip():
            raise ValueError('Choose a cookbook name.')
        return value.strip()


class Access(BaseModel):
    role: Literal['editor', 'viewer'] = 'viewer'


class AddRecipe(BaseModel):
    recipeId: str = Field(pattern=f'^{ID}$')


class Join(BaseModel):
    code: str = Field(min_length=1, max_length=128)


def digest(value):
    return hashlib.sha256(value.strip().encode()).hexdigest()


def public_recipe(recipe):
    recipe = dict(recipe)
    recipe.update(isFavorite=False, rawText='')
    recipe.pop('lastCookedAt', None)
    recipe.pop('origin', None)
    return recipe


def photo_files(recipe):
    return list(dict.fromkeys(recipe.get('photos', []) + recipe.get('recipePhotos', [])))


def copy_photo_files(recipe, images):
    """Give a recipe copy its own files and keep its banner pointed at the copied photo."""
    names = {name: str(uuid.uuid4()) + '.jpg' for name in photo_files(recipe)}
    recipe['photos'] = [names[name] for name in recipe.get('photos', [])]
    if 'recipePhotos' in recipe:
        recipe['recipePhotos'] = [names[name] for name in recipe['recipePhotos']]
    if recipe.get('bannerPhoto'):
        recipe['bannerPhoto'] = names[recipe['bannerPhoto']]
    return list(zip(names.values(), images, strict=True))


def content_key(recipe):
    content = public_recipe(recipe)
    for key in ('id', 'createdAt', 'updatedAt'):
        content.pop(key, None)
    return json.dumps(content, ensure_ascii=False, sort_keys=True)


def copy_recipe(db, owner, source_user, source_id, recipe, images, origin, check_quota=None):
    """Reuse a contribution across cookbooks without duplicating a personal recipe."""
    if owner == source_user:
        existing = db.execute('SELECT body FROM recipes WHERE user_id=? AND id=?', (owner, source_id)).fetchone()
        if existing and not json.loads(existing[0]).get('deletedAt'):
            return json.loads(existing[0])
    link = db.execute('SELECT recipe_id FROM cookbook_copies WHERE owner_id=? AND source_user=? AND source_id=?', (owner, source_user, source_id)).fetchone()
    if link:
        existing = db.execute('SELECT body FROM recipes WHERE user_id=? AND id=?', (owner, link[0])).fetchone()
        if existing and not json.loads(existing[0]).get('deletedAt'):
            return json.loads(existing[0])
    result = public_recipe(recipe)
    date = now()
    files = copy_photo_files(result, images)
    result.update(id=str(uuid.uuid4()), createdAt=date, updatedAt=date, origin=origin)
    result.pop('deletedAt', None)
    encoded = json.dumps(result, ensure_ascii=False)
    if check_quota:
        check_quota(db, owner, len(encoded.encode()) + sum(len(data) for _, data in files))
    db.executemany('INSERT INTO photos VALUES (?,?,?)', [(owner, name, data) for name, data in files])
    db.execute('INSERT INTO recipes VALUES (?,?,?,?)', (owner, result['id'], encoded, stamp(date)))
    db.execute('INSERT INTO cookbook_copies VALUES (?,?,?,?) ON CONFLICT(owner_id,source_user,source_id) DO UPDATE SET recipe_id=excluded.recipe_id', (owner, source_user, source_id, result['id']))
    return result


def migrate(db):
    """Keep old groups and their invitation links usable as cookbooks after upgrading."""
    db.executescript(SCHEMA)
    db.execute('BEGIN IMMEDIATE')
    for group in db.execute('SELECT * FROM groups').fetchall():
        if db.execute('SELECT 1 FROM cookbooks WHERE id=?', (group['id'],)).fetchone():
            continue
        date = now()
        db.execute('INSERT INTO cookbooks VALUES (?,?,?,?,NULL)', (group['id'], group['owner'], group['name'], date))
        db.execute("INSERT INTO cookbook_members SELECT group_id,user_id,'editor' FROM members WHERE group_id=? AND user_id!=?", (group['id'], group['owner']))
        codes = [group['invite']] + [row[0] for row in db.execute('SELECT code FROM group_invites WHERE group_id=?', (group['id'],))]
        db.executemany("INSERT OR IGNORE INTO cookbook_invites VALUES (?,?,'editor')", [(code, group['id']) for code in codes])
        for share in db.execute('SELECT s.*,u.username FROM shares s JOIN users u ON u.id=s.user_id WHERE s.group_id=?', (group['id'],)).fetchall():
            recipe = json.loads(share['body'])
            images = [db.execute('SELECT body FROM share_photos WHERE share_id=? AND name=?', (share['id'], name)).fetchone()[0] for name in photo_files(recipe)]
            origin = dict(groupId=group['id'], shareId=share['id'], groupName=group['name'], sharedBy=share['username'])
            original = db.execute('SELECT body FROM recipes WHERE user_id=? AND id=?', (group['owner'], share['recipe_id'])).fetchone() if group['owner'] == share['user_id'] else None
            if original and content_key(json.loads(original[0])) == content_key(recipe):
                saved = json.loads(original[0])
            else:
                # Old shares were snapshots. Never expose later private edits while migrating.
                fingerprint = hashlib.sha256(content_key(recipe).encode() + b''.join(hashlib.sha256(data).digest() for data in images)).hexdigest()
                saved = copy_recipe(db, group['owner'], 'legacy:'+share['user_id'], fingerprint, recipe, images, origin)
            db.execute('INSERT OR IGNORE INTO cookbook_entries VALUES (?,?,?,0,?)', (group['id'], saved['id'], date, share['username']))


def install(app, database, current_user, check_quota, rate_limit, credentials, password_hash, session):
    User = Annotated[dict, Depends(current_user)]

    class InvitedCredentials(credentials):
        code: str = Field(min_length=1, max_length=128)

    def access(db, bid, uid, minimum=None, deleted=False):
        book = db.execute('SELECT b.*,m.role,u.username owner_name FROM cookbooks b JOIN users u ON u.id=b.owner LEFT JOIN cookbook_members m ON m.book_id=b.id AND m.user_id=? WHERE b.id=?', (uid, bid)).fetchone()
        if not book or (book['owner'] != uid and (book['role'] is None or book['deleted_at'])) or (book['deleted_at'] and not deleted):
            raise HTTPException(404, 'Cookbook not found. You may no longer have access.')
        role = 'owner' if book['owner'] == uid else book['role']
        if minimum == 'owner' and role != 'owner' or minimum == 'editor' and role == 'viewer':
            raise HTTPException(403, 'Only the owner can do that.' if minimum == 'owner' else 'You have viewer access. Ask the owner for editor access to add or remove recipes.')
        return book, role

    def document(db, book):
        return dict(id=book['id'], name=book['name'], updatedAt=book['updated_at'], deletedAt=book['deleted_at'], entries=[dict(recipeId=e['recipe_id'], updatedAt=e['updated_at'], removed=bool(e['removed'])) for e in db.execute('SELECT * FROM cookbook_entries WHERE book_id=?', (book['id'],))])

    def contents(db, book):
        return db.execute("SELECT e.*,r.body FROM cookbook_entries e JOIN recipes r ON r.user_id=? AND r.id=e.recipe_id WHERE e.book_id=? AND e.removed=0 AND json_extract(r.body,'$.deletedAt') IS NULL ORDER BY e.updated_at DESC,e.recipe_id", (book['owner'], book['id'])).fetchall()

    def summary(db, book, role, uid):
        rows = contents(db, book) if not book['deleted_at'] else []
        names = [book['owner_name']] + [r[0] for r in db.execute('SELECT u.username FROM cookbook_members m JOIN users u ON u.id=m.user_id WHERE m.book_id=? ORDER BY u.username', (book['id'],))]
        mine = [r['recipe_id'] for r in rows] if role == 'owner' else [r[0] for r in db.execute('SELECT c.source_id FROM cookbook_copies c JOIN cookbook_entries e ON e.recipe_id=c.recipe_id AND e.book_id=? WHERE c.owner_id=? AND c.source_user=? AND e.removed=0', (book['id'], book['owner'], uid))]
        return dict(mySharedRecipeIds=mine, id=book['id'], name=book['name'], isOwner=role == 'owner', role=role, ownerName=book['owner_name'], memberCount=len(names), memberNames=names, recipeCount=len(rows), latestActivity=max([max(stamp(r['updated_at']), stamp(json.loads(r['body'])['updatedAt'])) for r in rows], default=0), previews=[{k: v for k, v in json.loads(r['body']).items() if k in {'id','title','emoji','palette','pattern','createdAt','updatedAt'}} for r in rows[:3]], document=document(db, book) if role == 'owner' else None)

    def item(db, book, role, row, uid):
        recipe = public_recipe(json.loads(row['body']))
        saved = recipe['id'] if book['owner'] == uid else None
        link = db.execute("SELECT r.id FROM cookbook_copies c JOIN recipes r ON r.user_id=c.owner_id AND r.id=c.recipe_id WHERE c.owner_id=? AND c.source_user=? AND c.source_id=? AND json_extract(r.body,'$.deletedAt') IS NULL", (uid, book['owner'], recipe['id'])).fetchone()
        return dict(id=recipe['id'], recipe=recipe, sharedBy=row['contributed_by'], sharedAt=stamp(row['updated_at']), canRemove=role != 'viewer', savedRecipeId=saved or (link[0] if link else None))

    def find_item(db, book, rid):
        row = next((r for r in contents(db, book) if r['recipe_id'] == rid), None)
        if not row:
            raise HTTPException(404, 'This recipe is no longer in the cookbook.')
        return row

    def invite(db, code):
        row = db.execute('SELECT i.*,b.name,b.owner,u.username FROM cookbook_invites i JOIN cookbooks b ON b.id=i.book_id JOIN users u ON u.id=b.owner WHERE i.code=? AND b.deleted_at IS NULL', (digest(code),)).fetchone()
        if not row:
            raise HTTPException(404, 'This invitation has expired. Ask the owner for a new one.')
        return row

    @app.get('/v1/cookbooks')
    def books(user: User):
        with database() as db:
            ids = db.execute('SELECT id FROM cookbooks WHERE owner=? UNION SELECT book_id FROM cookbook_members WHERE user_id=?', (user['id'], user['id'])).fetchall()
            result = []
            for row in ids:
                try:
                    book, role = access(db, row[0], user['id'], deleted=True)
                    result.append(summary(db, book, role, user['id']))
                except HTTPException as error:
                    if error.status_code != 404: raise
            return result

    @app.put('/v1/cookbooks/{bid}')
    def put_book(bid: str, body: Document, user: User):
        if body.id != bid:
            raise HTTPException(422, 'Cookbook ID does not match.')
        updated = stamp(body.updatedAt)
        if body.deletedAt: stamp(body.deletedAt)
        for entry in body.entries: stamp(entry.updatedAt)
        with database(True) as db:
            old = db.execute('SELECT * FROM cookbooks WHERE id=?', (bid,)).fetchone()
            if old and old['owner'] != user['id']:
                raise HTTPException(403, 'Only the owner can change cookbook details.')
            if not old:
                if db.execute('SELECT count(*) FROM cookbooks WHERE owner=? AND deleted_at IS NULL', (user['id'],)).fetchone()[0] >= 100:
                    raise HTTPException(409, 'You can have up to 100 cookbooks.')
                db.execute('INSERT INTO cookbooks VALUES (?,?,?,?,?)', (bid, user['id'], body.name, body.updatedAt, body.deletedAt))
            elif updated > stamp(old['updated_at']):
                db.execute('UPDATE cookbooks SET name=?,updated_at=?,deleted_at=? WHERE id=?', (body.name, body.updatedAt, body.deletedAt or old['deleted_at'], bid))
            # Deletion is permanent, including when another device renamed the book later.
            if body.deletedAt:
                db.execute('UPDATE cookbooks SET deleted_at=COALESCE(deleted_at,?) WHERE id=?', (body.deletedAt, bid))
            book, role = access(db, bid, user['id'], deleted=True)
            if book['deleted_at']:
                db.execute('DELETE FROM cookbook_invites WHERE book_id=?', (bid,))
            else:
                for entry in body.entries:
                    old_entry = db.execute('SELECT * FROM cookbook_entries WHERE book_id=? AND recipe_id=?', (bid, entry.recipeId)).fetchone()
                    if old_entry and (stamp(old_entry['updated_at']) > stamp(entry.updatedAt) or (stamp(old_entry['updated_at']) == stamp(entry.updatedAt) and (old_entry['removed'] or not entry.removed))): continue
                    recipe = db.execute('SELECT body FROM recipes WHERE user_id=? AND id=?', (user['id'], entry.recipeId)).fetchone()
                    if not entry.removed and (not recipe or json.loads(recipe[0]).get('deletedAt')):
                        raise HTTPException(409, 'Back up the recipe before adding it to a cookbook.')
                    db.execute('INSERT INTO cookbook_entries VALUES (?,?,?,?,?) ON CONFLICT(book_id,recipe_id) DO UPDATE SET updated_at=excluded.updated_at,removed=excluded.removed', (bid, entry.recipeId, entry.updatedAt, int(entry.removed), user['username']))
            return summary(db, book, role, user['id'])

    @app.get('/v1/cookbooks/{bid}/recipes')
    def book_recipes(bid: str, user: User):
        with database() as db:
            book, role = access(db, bid, user['id'])
            return [item(db, book, role, row, user['id']) for row in contents(db, book)]

    @app.get('/v1/cookbooks/{bid}/recipes/{rid}')
    def book_recipe(bid: str, rid: str, user: User):
        with database() as db:
            book, role = access(db, bid, user['id'])
            return item(db, book, role, find_item(db, book, rid), user['id'])

    @app.get('/v1/cookbooks/{bid}/recipes/{rid}/photos/{name}')
    def book_photo(bid: str, rid: str, name: str, user: User):
        with database() as db:
            book, _ = access(db, bid, user['id'])
            recipe = json.loads(find_item(db, book, rid)['body'])
            row = db.execute('SELECT body FROM photos WHERE user_id=? AND name=?', (book['owner'], name)).fetchone() if name in photo_files(recipe) else None
            if not row: raise HTTPException(404, 'Photo not found.')
            return Response(row[0], media_type='image/jpeg', headers={'Cache-Control':'private, no-store'})

    @app.post('/v1/cookbooks/{bid}/recipes')
    def add(bid: str, body: AddRecipe, user: User):
        with database(True) as db:
            book, _ = access(db, bid, user['id'], 'editor')
            row = db.execute('SELECT body FROM recipes WHERE user_id=? AND id=?', (user['id'], body.recipeId)).fetchone()
            if not row or json.loads(row[0]).get('deletedAt'): raise HTTPException(404, 'Recipe not found in your library.')
            recipe = json.loads(row[0])
            images = [db.execute('SELECT body FROM photos WHERE user_id=? AND name=?', (user['id'], name)).fetchone()[0] for name in photo_files(recipe)]
            origin = dict(groupId=bid, shareId=recipe['id'], groupName=book['name'], sharedBy=user['username'])
            saved = copy_recipe(db, book['owner'], user['id'], recipe['id'], recipe, images, origin, check_quota)
            date = now()
            db.execute('INSERT INTO cookbook_entries VALUES (?,?,?,0,?) ON CONFLICT(book_id,recipe_id) DO UPDATE SET removed=0,updated_at=excluded.updated_at', (bid, saved['id'], date, user['username']))
            return {'recipeId': saved['id']}

    @app.delete('/v1/cookbooks/{bid}/recipes/{rid}')
    def remove(bid: str, rid: str, user: User):
        with database(True) as db:
            book, _ = access(db, bid, user['id'], 'editor')
            find_item(db, book, rid)
            db.execute('UPDATE cookbook_entries SET removed=1,updated_at=? WHERE book_id=? AND recipe_id=?', (now(), bid, rid))
        return {'ok':True}

    @app.post('/v1/cookbooks/{bid}/recipes/{rid}/save')
    def save(bid: str, rid: str, user: User):
        with database(True) as db:
            book, _ = access(db, bid, user['id'])
            row = find_item(db, book, rid)
            recipe = json.loads(row['body'])
            images = [db.execute('SELECT body FROM photos WHERE user_id=? AND name=?', (book['owner'], name)).fetchone()[0] for name in photo_files(recipe)]
            return copy_recipe(db, user['id'], book['owner'], rid, recipe, images, dict(groupId=bid, shareId=rid, groupName=book['name'], sharedBy=row['contributed_by']), check_quota)

    @app.get('/v1/cookbooks/{bid}/members')
    def members(bid: str, user: User):
        with database() as db:
            book, _ = access(db, bid, user['id'])
            rows = [dict(id=book['owner'], username=book['owner_name'], role='owner')]
            rows += [dict(row) for row in db.execute('SELECT u.id,u.username,m.role FROM cookbook_members m JOIN users u ON u.id=m.user_id WHERE m.book_id=? ORDER BY u.username', (bid,))]
            return [dict(row, isOwner=row['role']=='owner', isYou=row['id']==user['id']) for row in rows]

    @app.patch('/v1/cookbooks/{bid}/members/{uid}')
    def change_role(bid: str, uid: str, body: Access, user: User):
        with database(True) as db:
            access(db, bid, user['id'], 'owner')
            if not db.execute('UPDATE cookbook_members SET role=? WHERE book_id=? AND user_id=?', (body.role, bid, uid)).rowcount:
                raise HTTPException(404, 'Collaborator not found.')
            db.execute('DELETE FROM cookbook_invites WHERE book_id=?', (bid,))
        return {'ok':True}

    @app.delete('/v1/cookbooks/{bid}/members/{uid}')
    def remove_member(bid: str, uid: str, user: User):
        with database(True) as db:
            access(db, bid, user['id'], 'owner')
            if uid == user['id']: raise HTTPException(409, 'Delete the cookbook to stop sharing it.')
            db.execute('DELETE FROM cookbook_members WHERE book_id=? AND user_id=?', (bid, uid))
            db.execute('DELETE FROM cookbook_invites WHERE book_id=?', (bid,))
        return {'ok':True}

    @app.delete('/v1/cookbooks/{bid}/membership')
    def leave(bid: str, user: User):
        with database(True) as db:
            book, role = access(db, bid, user['id'])
            if role == 'owner': raise HTTPException(409, 'Delete your cookbook from its details.')
            db.execute('DELETE FROM cookbook_members WHERE book_id=? AND user_id=?', (bid, user['id']))
        return {'ok':True}

    @app.post('/v1/cookbooks/{bid}/invitations')
    def create_invitation(bid: str, body: Access, user: User):
        rate_limit('cookbook-invites:'+user['id'], 100)
        with database(True) as db:
            access(db, bid, user['id'], 'owner')
            code = secrets.token_urlsafe(18)
            db.execute('INSERT INTO cookbook_invites VALUES (?,?,?)', (digest(code), bid, body.role))
        return {'inviteCode':code}

    @app.delete('/v1/cookbooks/{bid}/invitations')
    def revoke_invites(bid: str, user: User):
        with database(True) as db:
            access(db, bid, user['id'], 'owner')
            db.execute('DELETE FROM cookbook_invites WHERE book_id=?', (bid,))
        return {'ok':True}

    @app.get('/v1/cookbook-invitations/{code}')
    def preview(code: str, request: Request):
        rate_limit('cookbook-preview:'+(request.client.host if request.client else 'unknown'), 100)
        with database() as db:
            row = invite(db, code)
            return dict(id=row['book_id'], name=row['name'], invitedBy=row['username'], role=row['role'])

    def join_invitation(db, row, uid):
        if row['owner'] != uid:
            if db.execute('SELECT count(*) FROM cookbook_members WHERE user_id=?', (uid,)).fetchone()[0] >= 100 and not db.execute('SELECT 1 FROM cookbook_members WHERE book_id=? AND user_id=?', (row['book_id'], uid)).fetchone():
                raise HTTPException(409, 'You can join up to 100 shared cookbooks.')
            db.execute('INSERT OR IGNORE INTO cookbook_members VALUES (?,?,?)', (row['book_id'], uid, row['role']))

    @app.post('/v1/cookbooks/join')
    def join_book(body: Join, user: User):
        rate_limit('cookbook-join:'+user['id'])
        with database(True) as db:
            row = invite(db, body.code)
            join_invitation(db, row, user['id'])
        return {'ok':True}

    @app.post('/v1/auth/register-cookbook-invite', status_code=201)
    def register_invited(body: InvitedCredentials, request: Request):
        rate_limit('auth:'+(request.client.host if request.client else 'unknown'))
        with database() as db: invite(db, body.code)
        hashed = password_hash(body.password)
        user = dict(id=str(uuid.uuid4()), username=body.username)
        try:
            with database(True) as db:
                row = invite(db, body.code)
                db.execute('INSERT INTO users VALUES (?,?,?)', (user['id'], user['username'], hashed))
                join_invitation(db, row, user['id'])
                return session(db, user)
        except sqlite3.IntegrityError:
            raise HTTPException(409, 'That username is taken. Sign in or choose another username.') from None
