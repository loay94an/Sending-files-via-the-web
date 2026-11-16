const C='wasla-peerjs-v4',A=['./','./index.html','./styles.css','./app.js','./manifest.webmanifest','./icon.svg'];
self.addEventListener('install',e=>e.waitUntil(caches.open(C).then(c=>c.addAll(A)).then(()=>self.skipWaiting())));
self.addEventListener('activate',e=>e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x.startsWith('wasla-peerjs-')&&x!==C).map(x=>caches.delete(x)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',e=>{
 const u=new URL(e.request.url);
 if(e.request.method!=='GET'||u.origin!==location.origin)return;
 const fresh=e.request.mode==='navigate'||u.pathname.endsWith('/app.js')||u.pathname==='/app.js';
 if(fresh){
  e.respondWith(fetch(e.request).then(r=>{
   if(r.ok){const copy=r.clone();caches.open(C).then(c=>c.put(e.request,copy));}
   return r;
  }).catch(()=>caches.match(e.request).then(c=>c||caches.match('./index.html'))));
  return;
 }
 e.respondWith(caches.match(e.request).then(c=>c||fetch(e.request).then(r=>{
  if(r.ok)caches.open(C).then(x=>x.put(e.request,r.clone()));
  return r;
 })));
});