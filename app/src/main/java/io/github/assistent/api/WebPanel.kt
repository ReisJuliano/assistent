package io.github.assistent.api

/** Tiny control panel served at `/`. Token is kept in the browser's localStorage only. */
object WebPanel {
    val HTML = """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Assistent panel</title>
<style>
:root{--bg:#05070a;--card:#0e1319;--line:#1d2630;--fg:#e6f2f5;--mute:#7f8f99;--acc:#3de8ff}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,sans-serif}
main{max-width:960px;margin:0 auto;padding:16px;display:grid;gap:14px}
h1{font-size:20px;margin:4px 0}h2{font-size:14px;margin:0 0 8px;color:var(--mute);text-transform:uppercase;letter-spacing:.08em}
section{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px}
input,textarea,button{font:inherit;color:var(--fg);background:#0a0f14;border:1px solid var(--line);border-radius:10px;padding:9px 11px}
textarea{width:100%;min-height:340px;font:13px/1.45 ui-monospace,monospace}
button{cursor:pointer;background:#10232a;border-color:#1b4a55}button:hover{border-color:var(--acc)}
.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}.grow{flex:1;min-width:180px}
#state{font:13px ui-monospace,monospace;color:var(--mute);white-space:pre-wrap}
#log{max-height:260px;overflow:auto;font:13px ui-monospace,monospace}
.dot{width:10px;height:10px;border-radius:50%;background:#555;display:inline-block}.on{background:var(--acc);box-shadow:0 0 10px var(--acc)}
</style></head><body><main>
<div class="row"><span id="live" class="dot"></span><h1 class="grow">Assistent</h1>
<input id="token" type="password" placeholder="API token" class="grow"><button onclick="saveToken()">Connect</button></div>
<section><h2>Talk</h2><div class="row"><input id="msg" class="grow" placeholder="Say something…" onkeydown="if(event.key==='Enter')chat()">
<button onclick="chat()">Send</button><button onclick="post('/say',{text:val('msg')})">Speak text</button><button onclick="post('/listen')">Listen</button></div>
<p id="reply"></p></section>
<section><h2>Face</h2><div class="row" id="emotions"></div>
<div class="row" style="margin-top:8px"><button onclick="post('/sleep')">Sleep</button><button onclick="post('/wake')">Wake</button><button onclick="post('/stop')">Stop talking</button></div>
<div id="state"></div></section>
<section><h2>Config (JSON)</h2><textarea id="cfg" spellcheck="false"></textarea>
<div class="row" style="margin-top:8px"><button onclick="loadCfg()">Reload</button><button onclick="saveCfg()">Save</button><span id="cfgmsg"></span></div></section>
<section><h2>Events</h2><div id="log"></div></section>
</main><script>
const E=['neutral','happy','joy','sad','surprised','confused','angry','love','thinking','sleepy'];
const ${'$'}=id=>document.getElementById(id), val=id=>${'$'}(id).value;
let token=localStorage.getItem('token')||'';${'$'}('token').value=token;
E.forEach(e=>{const b=document.createElement('button');b.textContent=e;b.onclick=()=>post('/emotion',{emotion:e});${'$'}('emotions').append(b)});
async function api(path,opt={}){const r=await fetch('/api/v1'+path,{...opt,headers:{'Authorization':'Bearer '+token,'Content-Type':'application/json'}});
 const t=await r.text();let j;try{j=JSON.parse(t)}catch{j=t}if(!r.ok)throw new Error(j.error||t);return j}
const post=(p,b)=>api(p,{method:'POST',body:JSON.stringify(b||{})}).catch(e=>alertMsg(e));
function alertMsg(e){${'$'}('reply').textContent='⚠ '+e.message}
async function chat(){const text=val('msg');if(!text)return;${'$'}('msg').value='';${'$'}('reply').textContent='…';
 try{const r=await api('/chat',{method:'POST',body:JSON.stringify({text})});${'$'}('reply').textContent='['+r.emotion+'] '+r.reply}catch(e){alertMsg(e)}}
async function loadCfg(){try{${'$'}('cfg').value=JSON.stringify(await api('/config'),null,2);${'$'}('cfgmsg').textContent=''}catch(e){${'$'}('cfgmsg').textContent=e.message}}
async function saveCfg(){try{await api('/config',{method:'PUT',body:val('cfg')});${'$'}('cfgmsg').textContent='saved ✓'}catch(e){${'$'}('cfgmsg').textContent=e.message}}
async function refresh(){try{const s=await api('/state');${'$'}('state').textContent=JSON.stringify(s.state,null,1)}catch{}}
function saveToken(){token=val('token');localStorage.setItem('token',token);connect()}
let es;function connect(){if(es)es.close();loadCfg();refresh();
 es=new EventSource('/api/v1/events?token='+encodeURIComponent(token));
 es.onopen=()=>${'$'}('live').classList.add('on');es.onerror=()=>${'$'}('live').classList.remove('on');
 ['wake','sleep','user_said','reply','reminder','emotion','face_seen'].forEach(n=>es.addEventListener(n,ev=>{
  const d=document.createElement('div');d.textContent=new Date().toLocaleTimeString()+'  '+n+'  '+JSON.stringify(JSON.parse(ev.data).data);${'$'}('log').prepend(d);refresh()}))}
if(token)connect();
</script></body></html>
""".trimIndent()
}
