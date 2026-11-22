"""Local MCP Apps preview backed by the real tools and an isolated sample database.

Run from backend: uv run python tests/plugin_preview.py
Never deploy this development-only harness.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
from pathlib import Path
import sys
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient
from server import create_app
from test_plugin import ORIGIN, account, connect, KEY, DRAFT, tool, put, recipe

HOST = "http://127.0.0.1:8788"
SHELL = """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Pinch plugin · local preview</title>
<style>body{margin:0;background:#faf8f1}aside{padding:8px 24px;background:#173e34;color:white;font:12px system-ui;text-align:center}iframe{border:0;width:100%;min-height:850px;display:block}</style>
<aside>Local plugin preview · isolated sample recipes · not connected to ChatGPT</aside><iframe src="/widget" title="Pinch recipe box"></iframe>
<script>const frame=document.querySelector('iframe');window.addEventListener('message',async e=>{if(e.source!==frame.contentWindow||e.origin!==location.origin||e.data?.jsonrpc!=='2.0')return;const m=e.data;let result={};try{if(m.method==='ui/initialize')result={protocolVersion:'2026-01-26',hostInfo:{name:'Pinch local preview',version:'1'},hostCapabilities:{serverTools:{},updateModelContext:{},openLinks:{}},hostContext:{theme:'light',displayMode:'fullscreen'}};else if(m.method==='tools/call'){const r=await fetch('/tool',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(m.params)});result=await r.json();}else if(m.method==='ui/notifications/size-changed'){frame.style.height=m.params.height+'px';}else if(m.method==='ui/open-link'){result={isError:true};}if(m.id!==undefined)frame.contentWindow.postMessage({jsonrpc:'2.0',id:m.id,result},location.origin);}catch(err){frame.contentWindow.postMessage({jsonrpc:'2.0',id:m.id,error:{code:-32603,message:'Preview failed'}},location.origin);}});</script></html>"""


def main():
    logging.getLogger("httpx").setLevel(logging.WARNING)
    with TemporaryDirectory(prefix="pinch-preview-") as data, TestClient(create_app(data, KEY, public_url=ORIGIN), base_url=ORIGIN) as client:
        mobile = account(client, "reviewer")
        bread = recipe()
        bread.update(title="Family bread", servings="1 loaf", prepTime="20 min", cookTime="35 min", emoji="🍞", tags=["Baking"],
                     summary="A little flour, a slow afternoon, and something warm for the table.",
                     steps=[{"id":"knead","text":"Mix the dough and knead until smooth.","isHeader":False},{"id":"bake","text":"Let rise, shape into a loaf, and bake until golden.","isHeader":False}])
        put(client, mobile, bread)
        token = connect(client, "reviewer")["access_token"]
        tool(client, token, "save_recipe", request_id="preview-pasta", recipe={**DRAFT,"title":"Sunday lemon pasta","summary":"Bright, buttery, and on the table before the evening gets away.","prepTime":"10 min","cookTime":"15 min","totalTime":"25 min"})
        tool(client, token, "save_recipe", request_id="preview-soup", recipe={**DRAFT,"title":"Roasted tomato soup","summary":"The recipe you reach for when the weather turns.","servings":"6 bowls","totalTime":"45 min","ingredients":[{"text":"6 ripe tomatoes"},{"text":"2 cups vegetable broth"}],"steps":[{"text":"Roast tomatoes, then blend with warm broth."}]})
        tool(client, token, "set_recipe_favorite", recipe_id="recipe-1", favorite=True)

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def respond(self, status, body, content_type):
                payload=body.encode()
                self.send_response(status);self.send_header('Content-Type',content_type);self.send_header('Content-Length',str(len(payload)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(payload)

            def do_GET(self):
                if self.path=='/':self.respond(200,SHELL,'text/html; charset=utf-8')
                elif self.path=='/widget':self.respond(200,Path(__file__).resolve().parents[1].joinpath('plugin_ui.html').read_text(),'text/html; charset=utf-8')
                else:self.respond(404,'Not found','text/plain')

            def do_POST(self):
                length=int(self.headers.get('Content-Length','0'))
                if self.path!='/tool' or self.headers.get('Origin')!=HOST or not 0<length<1024*1024:
                    return self.respond(403,'{}','application/json')
                args=json.loads(self.rfile.read(length))
                result=tool(client,token,args['name'],**args.get('arguments',{}))
                self.respond(200,json.dumps(result),'application/json')

        print(f"Pinch sample preview: {HOST}",flush=True)
        ThreadingHTTPServer(('127.0.0.1',8788),Handler).serve_forever()


if __name__=='__main__':
    main()
