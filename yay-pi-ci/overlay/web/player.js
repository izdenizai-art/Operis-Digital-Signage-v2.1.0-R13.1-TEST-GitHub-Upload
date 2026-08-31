(async function(){
const app=document.getElementById('app');
let lastRev=null;
let setupVisible=false;

async function config(){return (await fetch('/api/local/config',{cache:'no-store'})).json()}

function parseServerUrl(serverUrl){
  if(!serverUrl)return {protocol:'http',host:'',port:''};
  try{
    const u=new URL(serverUrl);
    return {protocol:u.protocol.replace(':','')||'http',host:u.hostname||'',port:u.port||''};
  }catch(_){return {protocol:'http',host:'',port:''}}
}

function setup(c){
  if(setupVisible)return;
  setupVisible=true;
  const current=parseServerUrl(c&&c.server_url);
  app.innerHTML='<div class="setup"><h1>Operis Digital Signage</h1><h2>Sunucu Bağlantı Ayarı</h2><p>Sunucu adresi ve port manuel girilir. Alanlar siz yazarken otomatik yenilenmez.</p><label for="protocol">Protokol</label><select id="protocol"><option value="http">http://</option><option value="https">https://</option></select><label for="host">Sunucu adresi / IP</label><input id="host" type="text" autocomplete="off" spellcheck="false" placeholder="10.20.1.2"><label for="port">Port</label><input id="port" type="number" inputmode="numeric" min="1" max="65535" autocomplete="off" placeholder="Port"><button id="save">Kaydet</button><div id="msg" class="status"></div></div>';
  document.getElementById('protocol').value=current.protocol;
  document.getElementById('host').value=current.host;
  document.getElementById('port').value=current.port;
  document.getElementById('save').onclick=async()=>{
    const protocol=document.getElementById('protocol').value;
    const host=document.getElementById('host').value.trim();
    const port=document.getElementById('port').value.trim();
    const msg=document.getElementById('msg');
    if(!host){msg.textContent='Sunucu adresi / IP zorunludur.';return}
    if(!/^\d+$/.test(port)){msg.textContent='Port zorunludur ve sayısal olmalıdır.';return}
    const portNumber=Number(port);
    if(portNumber<1||portNumber>65535){msg.textContent='Port 1-65535 arasında olmalıdır.';return}
    const server_url=`${protocol}://${host}:${portNumber}`;
    const r=await fetch('/api/local/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({server_url})});
    const d=await r.json();
    if(!d.ok){msg.textContent=d.error||'Kayıt başarısız.';return}
    msg.textContent='Kaydedildi. Bağlantı başlatılıyor.';
    setupVisible=false;
    lastRev=null;
    await tick();
  };
}

async function tick(){
  try{
    const c=await config();
    if(!c.configured){if(!setupVisible)setup(c);return}
    setupVisible=false;
    const p=await (await fetch('/api/local/publication',{cache:'no-store'})).json();
    if(p&&p.publicationId&&(p.revision!==lastRev)){YAYRenderer.render(p,app);lastRev=p.revision}
    if(!p.publicationId&&lastRev===null)app.innerHTML='<div class="setup"><h2>Yayın bekleniyor</h2><p>Cihaz sunucuya bağlanacak; yayın sunucudan gönderildiğinde burada açılacaktır.</p></div>';
  }catch(e){if(lastRev===null&&!setupVisible)app.innerHTML='<div class="setup"><h2>Yerel player hatası</h2></div>'}
}

await tick();
setInterval(tick,5000);
})();
