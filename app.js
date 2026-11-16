(()=>{'use strict';
const $=id=>document.getElementById(id);const CHUNK=64*1024;let peer=null,sessionId='',connections=new Map(),files=[],toastTimer,activeConnection=null,transferCancelled=false,wifiPc=null,localMode=false,pendingLocalConnection=null,qrStream=null,qrScanActive=false;
const dict={ar:{storagePick:'اختيار مجلد الحفظ',storageHint:'تُكتب الأجزاء مباشرة في التخزين، دون تجميع الملف في الذاكرة.',peer:'اتصال مباشر بين الأجهزة',eyebrow:'نقل بسيط. اتصال مباشر.',headline:'أرسل ملفاتك<br><em>بلا تعقيد.</em>',intro:'أنشئ جلسة، شارك الرابط، وانقل ملفاتك مباشرة بين الأجهزة.',sendTab:'إرسال ملفات',receiveTab:'استقبال ملفات',sendTitle:'أنشئ جلسة إرسال',notReady:'غير متصل',sendHint:'ابدأ جلسة مؤقتة، ثم أرسل رابطها إلى الجهاز المستقبل.',create:'إنشاء جلسة ورابط',shareLink:'رابط الانضمام',copy:'نسخ',sessionCode:'رمز الجلسة',copyCode:'نسخ الرمز',chooseFiles:'اختر الملفات',dropTitle:'أضف الملفات للإرسال',dropHint:'اضغط للاختيار أو اسحب الملفات إلى هنا',multiHint:'يمكنك اختيار عدة ملفات',sendNow:'إرسال الملفات إلى الأجهزة المتصلة',receiveTitle:'انضم إلى جلسة',waiting:'بانتظار الاتصال',receiveHint:'افتح رابط المرسل، أو أدخل رمز الجلسة الذي وصلك.',roomCode:'رمز الجلسة أو رابطها',join:'اتصال',receiving:'جارٍ استقبال الملفات',f1title:'من جهاز إلى جهاز',f1text:'الملفات تنتقل عبر اتصال WebRTC بين الطرفين متى أمكن.',f2title:'رابط ورمز QR',f2text:'شارك الجلسة بالرابط أو امسح الرمز من الجهاز الآخر.',f3title:'مناسب للشاشات والريموت',f3text:'عناصر كبيرة وتنقل واضح بلوحة المفاتيح.',footer:'وصلة — لا يتطلب تثبيت تطبيق.',footnote:'لا تغلق الصفحة أثناء النقل.',connected:'متصل',connecting:'جارٍ الاتصال',noPeers:'لا يوجد جهاز مستقبل متصل بعد.',selectFirst:'اختر ملفًا واحدًا على الأقل.',sent:'اكتمل الإرسال.',received:'اكتمل الاستقبال — تنزيل الملف جاهز.',copied:'تم النسخ.',badCode:'أدخل رمز جلسة صحيحًا أو رابطًا صالحًا.',peerMissing:'تعذر تحميل مكتبة الاتصال. تحقق من الإنترنت ثم أعد تحميل الصفحة.',peerError:'تعذر الاتصال بخدمة PeerJS. حاول مجددًا بعد قليل.',joined:'تم الاتصال بالمرسل.',disconnected:'انقطع اتصال أحد الأجهزة.',file:'ملف',files:'ملفات',total:'الإجمالي',failed:'تعذر إرسال بعض الملفات.',sendTo:'إرسال إلى',joining:'جارٍ الانضمام إلى الجلسة…',stop:'إيقاف الاتصال',stopped:'تم إيقاف الاتصال.',cancelled:'أُوقف النقل.',fileTooLarge:'تعذر تجهيز الملف في ذاكرة هذا المتصفح.'},en:{storagePick:'Choose save folder',storageHint:'Chunks are written directly to storage without assembling the file in memory.',peer:'Direct device connection',eyebrow:'Simple transfer. Direct connection.',headline:'Send your files<br><em>without the hassle.</em>',intro:'Create a session, share its link, and transfer files between devices.',sendTab:'Send files',receiveTab:'Receive files',sendTitle:'Create a sending session',notReady:'Offline',sendHint:'Start a temporary session, then share its link with the receiving device.',create:'Create session & link',shareLink:'Join link',copy:'Copy',sessionCode:'Session code',copyCode:'Copy code',chooseFiles:'Choose files',dropTitle:'Add files to send',dropHint:'Tap to choose or drag files here',multiHint:'You can select multiple files',sendNow:'Send files to connected devices',receiveTitle:'Join a session',waiting:'Waiting for connection',receiveHint:'Open the sender link or enter the session code you received.',roomCode:'Session code or link',join:'Connect',receiving:'Receiving files',f1title:'Device to device',f1text:'Files travel over a WebRTC peer connection when possible.',f2title:'Link and QR code',f2text:'Share the session link or scan its QR code.',f3title:'TV and remote friendly',f3text:'Large controls and clear keyboard navigation.',footer:'Wasla — no app installation required.',footnote:'Keep this page open during transfer.',connected:'Connected',connecting:'Connecting',noPeers:'No receiving device is connected yet.',selectFirst:'Choose at least one file.',sent:'Transfer complete.',received:'Transfer complete — download is ready.',copied:'Copied.',badCode:'Enter a valid session code or link.',peerMissing:'Connection library failed to load. Check your internet and reload.',peerError:'Could not reach PeerJS. Please try again shortly.',joined:'Connected to sender.',disconnected:'A device disconnected.',file:'file',files:'files',total:'Total',failed:'Some files could not be sent.',sendTo:'Send to',joining:'Joining session…',stop:'Stop connection',stopped:'Connection stopped.',cancelled:'Transfer stopped.',fileTooLarge:'This browser could not prepare the file in available memory.'}};
let lang='ar';
function tr(k){return dict[lang][k]||k}function toast(s){const el=$('toast');el.textContent=s;el.classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>el.classList.remove('show'),2800)}
function fmt(n){if(!Number.isFinite(n))return'';const u=['B','KB','MB','GB','TB'];let i=0;while(n>=1024&&i<u.length-1){n/=1024;i++}return n.toFixed(i?1:0)+' '+u[i]}
function setTab(which){const send=which==='send',receive=which==='receive',local=which==='local';$('sendPanel').classList.toggle('hidden',!send);$('receivePanel').classList.toggle('hidden',!receive);$('localPanel').classList.toggle('hidden',!local);[['sendTab',send],['receiveTab',receive],['localTab',local]].forEach(([id,on])=>{$(id).classList.toggle('active',on);$(id).setAttribute('aria-selected',String(on))})}
function peerOptions(){return{host:'0.peerjs.com',port:443,path:'/',secure:true,debug:1}}
function makePeer(){return new Promise((resolve,reject)=>{if(!window.Peer)return reject(Error('PEER_MISSING'));let id;try{const bytes=new Uint8Array(16);if(window.crypto&&crypto.getRandomValues)crypto.getRandomValues(bytes);else for(let i=0;i<bytes.length;i++)bytes[i]=Math.floor(Math.random()*256);id='w'+Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('')}catch(e){return reject(e)}let p,done=false;const finishError=e=>{if(done)return;done=true;clearTimeout(timer);try{p&&p.destroy()}catch(_){}reject(e)};const timer=setTimeout(()=>finishError(Error('PEER_TIMEOUT')),18000);try{p=new Peer(id,peerOptions());p.on('open',openedId=>{if(done)return;done=true;clearTimeout(timer);resolve({p,id:openedId})});p.on('error',e=>{console.error('PeerJS signaling error',e);finishError(e)});p.on('disconnected',()=>console.warn('PeerJS signaling disconnected'))}catch(e){finishError(e)}})}
function setSessionState(text,online=false){$('sessionState').textContent=text;$('sessionState').classList.toggle('online',online)}
async function createSession(){if(peer&&!peer.destroyed){toast(lang==='ar'?'الجلسة منشأة بالفعل.':'Session already exists.');return} $('createSession').disabled=true;setSessionState(tr('connecting'));try{const x=await makePeer();peer=x.p;sessionId=x.id;peer.on('connection',conn=>attachSenderConnection(conn));peer.on('disconnected',()=>{setSessionState(tr('connecting'));try{peer.reconnect()}catch(e){}});peer.on('close',()=>setSessionState(tr('notReady')));const url=new URL(location.href);url.search='';url.searchParams.set('join',sessionId);$('shareLink').value=url.toString();$('sessionCode').textContent=sessionId;$('shareArea').classList.remove('hidden');$('sendFilesArea').classList.remove('hidden');$('createSession').classList.add('hidden');$('stopSender').classList.remove('hidden');setSessionState(tr('connected'),true);if(window.QRCode){$('qrCode').innerHTML='';new QRCode($('qrCode'),{text:url.toString(),width:100,height:100,colorDark:'#10241f',colorLight:'#ffffff'})}toast(tr('connected'))}catch(e){console.error(e);setSessionState(tr('notReady'));toast(e.message==='PEER_MISSING'?tr('peerMissing'):tr('peerError'))}finally{$('createSession').disabled=false}}
function activateLocalTransferUI(){localMode=true;$('localPanel').classList.add('hidden');$('sendPanel').classList.remove('hidden');$('receivePanel').classList.remove('hidden');$('sendFilesArea').classList.remove('hidden');$('localPairArea').classList.add('hidden');$('pairRequest').classList.add('hidden');$('stopReceiver').classList.remove('hidden');$('localStop').classList.remove('hidden');$('receiveState').textContent=lang==='ar'?'متصل محليًا — جاهز للإرسال والاستقبال':'Connected locally — ready to send and receive';$('receiveState').classList.add('online');$('sessionState').textContent=lang==='ar'?'متصل محليًا':'Connected locally';$('sessionState').classList.add('online');$('sendTab').classList.add('active');$('receiveTab').classList.remove('active');$('localTab').classList.remove('active');$('sendTab').setAttribute('aria-selected','true');$('receiveTab').setAttribute('aria-selected','false');$('localTab').setAttribute('aria-selected','false');renderPeers();renderFiles()}
function attachSenderConnection(conn,requireLocal=false){if(connections.has(conn.peer))return;connections.set(conn.peer,conn);activeConnection=conn;const opened=()=>{renderPeers();if(requireLocal){localState(lang==='ar'?'تم قبول الجهاز؛ جارٍ تأكيد الاتصال المحلي…':'Device accepted; confirming local connection…');try{conn.send(JSON.stringify({type:'local-status-request'}))}catch(e){console.warn('Local status request failed',e)}}else toast(tr('connected'))};conn.on('open',opened);conn.on('data',d=>{let handled=false;if(typeof d==='string'){try{const m=JSON.parse(d);if(m.type==='local-ready'&&requireLocal){activateLocalTransferUI();localState(lang==='ar'?'نجح الاقتران المحلي؛ الجهازان جاهزان للإرسال والاستقبال':'Local pairing successful; both devices ready to send and receive',true);toast(lang==='ar'?'تم الاقتران؛ الإرسال والاستقبال متاحان على الجهازين.':'Paired; sending and receiving are ready on both devices.');handled=true}else if(m.type==='local-status-request'&&conn._waslaLocalVerified){conn.send(JSON.stringify({type:'local-ready'}));handled=true}else if(m.type==='done'){toast(tr('sent'));handled=true}else if(m.type==='chunk-ack'&&conn._waslaAckResolve){const resolve=conn._waslaAckResolve;conn._waslaAckResolve=null;resolve();handled=true}}catch(e){}}if(!handled)handleIncoming(d)});conn.on('close',()=>{if(conn._waslaAckResolve){const resolve=conn._waslaAckResolve;conn._waslaAckResolve=null;resolve()}connections.delete(conn.peer);renderPeers();if(requireLocal)localState(lang==='ar'?'انقطع الاتصال المحلي':'Local connection closed');toast(tr('disconnected'))});conn.on('error',e=>console.warn('Data connection',e));if(conn.open)opened()}
function renderPeers(){$('peerCount').textContent=connections.size+' '+(lang==='ar'?'أجهزة متصلة':'connected');$('sendFiles').disabled=!connections.size||!files.length}
function renderFiles(){$('fileList').innerHTML='';files.forEach((f,i)=>{const el=document.createElement('div');el.className='file-item';const sym=document.createElement('span');sym.className='file-symbol';sym.textContent='▤';const info=document.createElement('div');info.className='file-info';const name=document.createElement('strong');name.textContent=f.name;const size=document.createElement('small');size.textContent=fmt(f.size);info.append(name,size);const rm=document.createElement('button');rm.className='file-remove';rm.type='button';rm.textContent='×';rm.setAttribute('aria-label',lang==='ar'?'إزالة الملف':'Remove file');rm.onclick=()=>{files.splice(i,1);renderFiles()};el.append(sym,info,rm);$('fileList').append(el)});$('sendFiles').disabled=!connections.size||!files.length}
function addFiles(list){for(const f of Array.from(list||[])){if(!files.some(x=>x.name===f.name&&x.size===f.size&&x.lastModified===f.lastModified))files.push(f)}renderFiles()}
function waitChunkAck(conn){return new Promise((resolve,reject)=>{const timer=setTimeout(()=>{conn._waslaAckResolve=null;try{conn.close()}catch(e){}reject(Error('ack-timeout'))},30000);conn._waslaAckResolve=()=>{clearTimeout(timer);resolve()}})}
function waitBuffer(conn){return new Promise((resolve,reject)=>{const dc=conn.dataChannel;if(!dc||dc.readyState!=='open')return reject(Error('closed'));const check=()=>{if(dc.readyState!=='open')return reject(Error('closed'));if(dc.bufferedAmount<1024*1024)return resolve();setTimeout(check,30)};check()})}
async function sendOne(conn,file,onProgress){const id=crypto.randomUUID?crypto.randomUUID():String(Date.now())+Math.random();if(!conn.open)throw Error('closed');conn.send(JSON.stringify({type:'file-start',id,name:file.name,size:file.size,mime:file.type||'application/octet-stream'}));let sent=0;while(sent<file.size){if(transferCancelled||!conn.open)throw Error('cancelled');await waitBuffer(conn);const chunk=await file.slice(sent,Math.min(sent+CHUNK,file.size)).arrayBuffer();if(transferCancelled||!conn.open)throw Error('cancelled');const ack=waitChunkAck(conn);conn.send(chunk);await ack;sent+=chunk.byteLength;onProgress(sent/file.size)}if(transferCancelled||!conn.open)throw Error('cancelled');conn.send(JSON.stringify({type:'file-end',id}));}
async function sendAll(){if(!files.length)return toast(tr('selectFirst'));const peers=[...connections.values()].filter(c=>c.open);if(!peers.length)return toast(tr('noPeers'));transferCancelled=false;$('sendFiles').disabled=true;$('sendProgress').classList.remove('hidden');let total=files.reduce((s,f)=>s+f.size,0),overallTotal=total*peers.length,completed=0,failed=false;try{for(const conn of peers){for(const file of files){if(transferCancelled)break;try{await sendOne(conn,file,p=>{const pct=overallTotal?Math.min(100,Math.round((completed+p*file.size)/overallTotal*100)):100;$('sendBar').style.width=pct+'%';$('sendPercent').textContent=pct+'%';$('sendStatus').textContent=tr('sendTo')+' '+conn.peer.slice(0,7)+' · '+file.name})}catch(e){if(e.message!=='cancelled'){failed=true;console.error(e)}}completed+=file.size}}if(transferCancelled){$('sendStatus').textContent=tr('cancelled');toast(tr('cancelled'));return}$('sendBar').style.width='100%';$('sendPercent').textContent='100%';$('sendStatus').textContent=failed?tr('failed'):tr('sent');toast(failed?tr('failed'):tr('sent'))}finally{$('sendFiles').disabled=false}}
function extractId(raw){let s=raw.trim();try{if(s.includes('://')){const u=new URL(s);s=u.searchParams.get('join')||u.searchParams.get('room')||''}}catch(e){}return /^[A-Za-z0-9_-]{5,100}$/.test(s)?s:''}
async function joinSession(){if(peer&&!peer.destroyed){toast(tr('connected'));return}const id=extractId($('roomInput').value||new URLSearchParams(location.search).get('join')||'');if(!id)return toast(tr('badCode'));$('joinSession').disabled=true;$('receiveState').textContent=tr('connecting');try{const x=await makePeer();peer=x.p;const conn=peer.connect(id,{reliable:true,serialization:'binary'});activeConnection=conn;let opened=false;const timer=setTimeout(()=>{if(!opened){conn.close();toast(tr('peerError'));$('receiveState').textContent=tr('waiting');$('joinSession').disabled=false}},25000);conn.on('open',()=>{opened=true;clearTimeout(timer);$('receiveState').textContent=tr('connected');$('receiveState').classList.add('online');$('joinSession').disabled=true;$('stopReceiver').classList.remove('hidden');toast(tr('joined'))});conn.on('data',handleIncoming);conn.on('close',()=>{closePartial();activeConnection=null;$('stopReceiver').classList.add('hidden');$('receiveState').textContent=tr('waiting');$('receiveState').classList.remove('online');$('joinSession').disabled=false;toast(tr('disconnected'))});conn.on('error',e=>{console.error(e);toast(tr('peerError'))})}catch(e){console.error(e);toast(e.message==='PEER_MISSING'?tr('peerMissing'):tr('peerError'));$('joinSession').disabled=false;$('receiveState').textContent=tr('waiting')}}
let incoming=null,incomingQueue=Promise.resolve(),saveDirectory=null;
function safeFileName(name){return String(name||'file').replace(/[\\/:*?"<>|\u0000-\u001f]/g,'_').slice(0,180)||'file'}
async function chooseSaveDirectory(){if(!window.showDirectoryPicker){toast(lang==='ar'?'هذا المتصفح لا يدعم اختيار مجلد مباشر؛ سيُستخدم التخزين المحلي البديل.':'Folder picking is not supported here; local storage fallback will be used.');return}try{saveDirectory=await window.showDirectoryPicker({mode:'readwrite'});$('storageStatus').textContent=lang==='ar'?'الحفظ المباشر في المجلد المختار':'Saving directly to selected folder';toast(lang==='ar'?'تم اختيار مجلد الحفظ.':'Save folder selected.')}catch(e){if(e.name!=='AbortError')toast(lang==='ar'?'تعذر اختيار المجلد.':'Could not select folder.')}}
let chunkDbPromise=null;
function openChunkDb(){
 if(!('indexedDB'in window))return Promise.reject(Error('NO_LOCAL_STORAGE'));
 if(!chunkDbPromise)chunkDbPromise=new Promise((resolve,reject)=>{
  const req=indexedDB.open('wasla-receive-cache',1);
  req.onupgradeneeded=()=>{const db=req.result;if(!db.objectStoreNames.contains('chunks'))db.createObjectStore('chunks',{keyPath:['fileId','index']})};
  req.onsuccess=()=>resolve(req.result);
  req.onerror=()=>reject(req.error||Error('IDB_OPEN_FAILED'));
 });
 return chunkDbPromise;
}
function idbRange(fileId){return IDBKeyRange.bound([fileId,0],[fileId,Number.MAX_SAFE_INTEGER])}
async function deleteStoredChunks(db,fileId){
 await new Promise((resolve,reject)=>{
  const tx=db.transaction('chunks','readwrite'),store=tx.objectStore('chunks'),req=store.openCursor(idbRange(fileId));
  req.onsuccess=()=>{const cursor=req.result;if(cursor){cursor.delete();cursor.continue()}};
  tx.oncomplete=resolve;tx.onerror=()=>reject(tx.error||Error('IDB_DELETE_FAILED'));tx.onabort=()=>reject(tx.error||Error('IDB_DELETE_FAILED'));
 });
}
async function createIndexedDbTarget(name,mime){
 const db=await openChunkDb(),fileId=(crypto.randomUUID?crypto.randomUUID():String(Date.now())+Math.random()),state={index:0};
 const writer={
  write:part=>new Promise((resolve,reject)=>{
   let tx;try{tx=db.transaction('chunks','readwrite');tx.objectStore('chunks').put({fileId,index:state.index++,data:part})}catch(e){reject(e);return}
   tx.oncomplete=resolve;tx.onerror=()=>reject(tx.error||Error('IDB_WRITE_FAILED'));tx.onabort=()=>reject(tx.error||Error('IDB_WRITE_FAILED'));
  }),
  close:async()=>{},
  abort:()=>deleteStoredChunks(db,fileId)
 };
 const handle={
  getFile:async()=>{
   const records=await new Promise((resolve,reject)=>{
    const tx=db.transaction('chunks','readonly'),req=tx.objectStore('chunks').getAll(idbRange(fileId));
    req.onsuccess=()=>resolve(req.result||[]);req.onerror=()=>reject(req.error||Error('IDB_READ_FAILED'));
   });
   records.sort((a,b)=>a.index-b.index);
   return new Blob(records.map(x=>x.data),{type:mime||'application/octet-stream'});
  },
  cleanup:()=>deleteStoredChunks(db,fileId)
 };
 return{writer,handle,kind:'indexeddb',name};
}
async function createIncomingTarget(meta){
 const name=safeFileName(meta.name);
 if(saveDirectory){
  let target=name,n=1;
  while(true){try{await saveDirectory.getFileHandle(target);target=name.replace(/(\\.[^.]+)?$/,(_,ext)=>(ext||'')+' ('+(n++)+')')}catch(e){if(e.name==='NotFoundError')break;throw e}}
  const handle=await saveDirectory.getFileHandle(target,{create:true});
  return{writer:await handle.createWritable(),handle,kind:'device',name:target};
 }
 if(navigator.storage&&navigator.storage.getDirectory){
  try{
   if(navigator.storage.persist)await navigator.storage.persist();
   if(navigator.storage.estimate){const estimate=await navigator.storage.estimate();const free=(estimate.quota||0)-(estimate.usage||0);if(Number.isFinite(free)&&free<meta.size)throw Error('NO_SPACE')}
   const root=await navigator.storage.getDirectory();
   const internalName='wasla-'+(crypto.randomUUID?crypto.randomUUID():Date.now())+'.part';
   const handle=await root.getFileHandle(internalName,{create:true});
   return{writer:await handle.createWritable(),handle,kind:'opfs',name};
  }catch(e){if(e.message==='NO_SPACE')throw e;console.warn('OPFS unavailable; using IndexedDB fallback',e)}
 }
 return createIndexedDbTarget(name,meta.mime);
}
async function processIncoming(data){
 if(data instanceof Blob)data=await data.arrayBuffer();
 if(typeof data==='string'){
  let m;try{m=JSON.parse(data)}catch(e){return}
  if(m.type==='file-start'){
   if(incoming)await closePartial();
   const meta={id:m.id,name:m.name,size:Number(m.size),mime:m.mime||'application/octet-stream'};
   if(!Number.isFinite(meta.size)||meta.size<0)throw Error('BAD_SIZE');
   const target=await createIncomingTarget(meta);
   incoming={...meta,...target,received:0};
   $('receiveProgress').classList.remove('hidden');$('receiveStatus').textContent=meta.name;$('receivePercent').textContent='0%';$('receiveBar').style.width='0%';
   $('storageStatus').textContent=target.kind==='device'?(lang==='ar'?'يُكتب مباشرة في مجلد الهاتف':'Writing directly to device folder'):target.kind==='indexeddb'?(lang==='ar'?'يُحفظ تدريجيًا في قاعدة بيانات المتصفح، ثم يظهر رابط التنزيل بعد اكتمال الملف':'Saved incrementally in browser storage; a download link appears when complete'):(lang==='ar'?'يُكتب تدريجيًا في التخزين المحلي للموقع':'Writing incrementally to local site storage');
  }else if(m.type==='file-end'&&incoming&&m.id===incoming.id){
   if(incoming.received!==incoming.size)throw Error('SIZE_MISMATCH');
   const done=incoming;incoming=null;
   await done.writer.close();
   const file=await done.handle.getFile();
   const item=document.createElement('div');item.className='file-item';
   const sym=document.createElement('span');sym.className='file-symbol';sym.textContent='✓';
   const info=document.createElement('div');info.className='file-info';
   const name=document.createElement('strong');name.textContent=done.name;
   const size=document.createElement('small');size.textContent=fmt(done.size)+(done.kind==='device'?(lang==='ar'?' · حُفظ في المجلد المختار':' · Saved in selected folder'):(lang==='ar'?' · محفوظ محليًا':' · Saved locally'));
   info.append(name,size);item.append(sym,info);
   if(done.kind==='opfs'||done.kind==='indexeddb'){const url=URL.createObjectURL(file);const a=document.createElement('a');a.href=url;a.download=done.name;a.className='download-link';a.textContent='⬇ '+(lang==='ar'?'تنزيل':'Download');if(done.kind==='indexeddb')a.addEventListener('click',()=>setTimeout(()=>{URL.revokeObjectURL(url);done.handle.cleanup().catch(console.warn)},60000),{once:true});item.append(a)}
   $('receivedList').prepend(item);$('receivePercent').textContent='100%';$('receiveBar').style.width='100%';toast(tr('received'));
  }
  return;
 }
 if(data instanceof ArrayBuffer||ArrayBuffer.isView(data)){
  if(!incoming)return;
  const part=data instanceof ArrayBuffer?data:data.buffer.slice(data.byteOffset,data.byteOffset+data.byteLength);
  await incoming.writer.write(part);
  incoming.received+=part.byteLength;
  if(activeConnection&&activeConnection.open)activeConnection.send(JSON.stringify({type:'chunk-ack',id:incoming.id,received:incoming.received}));
  const pct=incoming.size?Math.min(100,Math.round(incoming.received/incoming.size*100)):100;
  $('receivePercent').textContent=pct+'%';$('receiveBar').style.width=pct+'%';
 }
}
function handleIncoming(data){incomingQueue=incomingQueue.then(()=>processIncoming(data)).catch(async e=>{console.error('Receive/storage error',e);if(incoming)await closePartial();const msg=e.message==='NO_SPACE'?(lang==='ar'?'مساحة التخزين غير كافية على الجهاز.':'Not enough device storage.'):(lang==='ar'?'تعذرت الكتابة إلى التخزين. تحقق من المساحة والصلاحيات.':'Could not write to storage. Check space and permissions.');$('receiveStatus').textContent=msg;toast(msg);if(activeConnection)activeConnection.close()})}
async function closePartial(){if(!incoming)return;const partial=incoming;incoming=null;try{if(partial.writer.abort)await partial.writer.abort();else await partial.writer.close()}catch(e){console.warn('Partial file cleanup failed',e)}
 $('receiveStatus').textContent=lang==='ar'?'انقطع النقل؛ أُوقف حفظ الملف غير المكتمل':'Transfer interrupted; incomplete file was discarded';
}
function stopSender(){transferCancelled=true;if(wifiPc){try{wifiPc.close()}catch(e){}wifiPc=null;}for(const c of connections.values()){try{c.close()}catch(e){}}connections.clear();if(peer){try{peer.destroy()}catch(e){}}peer=null;sessionId='';$('shareArea').classList.add('hidden');$('sendFilesArea').classList.add('hidden');$('stopSender').classList.add('hidden');$('createSession').classList.remove('hidden');setSessionState(tr('notReady'));renderPeers();toast(tr('stopped'))}
function stopReceiver(){transferCancelled=true;if(wifiPc){try{wifiPc.close()}catch(e){}wifiPc=null;}closePartial();if(activeConnection){try{activeConnection.close()}catch(e){}activeConnection=null}if(peer){try{peer.destroy()}catch(e){}}peer=null;incoming=null;$('stopReceiver').classList.add('hidden');$('joinSession').disabled=false;$('receiveState').textContent=tr('waiting');$('receiveState').classList.remove('online');toast(tr('stopped'))}
function switchLang(){lang=lang==='ar'?'en':'ar';document.documentElement.lang=lang;document.documentElement.dir=lang==='ar'?'rtl':'ltr';document.querySelectorAll('[data-i18n]').forEach(el=>{const k=el.dataset.i18n;if(dict[lang][k])el.innerHTML=dict[lang][k]});$('langBtn').textContent=lang==='ar'?'English':'العربية';$('sessionState').textContent=sessionId?tr('connected'):tr('notReady');$('receiveState').textContent=peer&&$('receiveState').classList.contains('online')?tr('connected'):tr('waiting');renderFiles();renderPeers()}



let localPairingId='',localPendingRole='';
function deviceLabel(){const ua=navigator.userAgent||'';if(/smart-tv|smarttv|tizen|web0s|webos|hbbtv|netcast|googletv|aftb|aftt/i.test(ua))return 'تلفاز';if(/ipad|tablet/i.test(ua))return 'جهاز لوحي';if(/android|iphone|mobile/i.test(ua))return 'هاتف';return 'حاسوب'}
function deviceName(){return deviceLabel()+' · '+(navigator.platform||'متصفح')}
function localState(message,online=false){$('discoveryState').textContent=message;$('discoveryState').classList.toggle('online',online)}
function localUrl(id){const u=new URL(location.href);u.search='';u.hash='';u.searchParams.set('localJoin',id);return u.toString()}
async function createLocalPairing(){
 if(peer&&!peer.destroyed){toast(lang==='ar'?'هناك جلسة اتصال نشطة بالفعل.':'A connection session is already active.');return}
 $('localCreate').disabled=true;localMode=true;localState(lang==='ar'?'جارٍ إنشاء جلسة الاقتران…':'Creating pairing session…');
 try{const x=await makePeer();peer=x.p;localPairingId=x.id;
  peer.on('connection',conn=>showLocalPairRequest(conn));
  peer.on('disconnected',()=>localState(lang==='ar'?'انقطع اتصال الإشارات؛ قد يستمر النقل القائم':'Signaling disconnected; an active transfer may continue'));
  peer.on('close',()=>localState(lang==='ar'?'انتهت الجلسة':'Session ended'));
  const link=localUrl(localPairingId);$('localQr').innerHTML='';
  if(!window.QRCode)throw Error('QR_MISSING');
  new QRCode($('localQr'),{text:link,width:190,height:190,colorDark:'#10241f',colorLight:'#ffffff'});
  $('localPairCode').textContent=localPairingId;$('localPairArea').classList.remove('hidden');
  $('localCreate').classList.add('hidden');$('localStop').classList.remove('hidden');
  localState(lang==='ar'?'بانتظار مسح الرمز وطلب الاقتران':'Waiting for QR scan and pairing request',true);
 }catch(e){console.error(e);localMode=false;localState(lang==='ar'?'تعذر إنشاء الاقتران':'Could not create pairing');toast(e.message==='PEER_MISSING'?tr('peerMissing'):lang==='ar'?'تعذر إنشاء جلسة الاقتران. تحقق من الإنترنت.':'Could not create pairing. Check internet.')}
 finally{$('localCreate').disabled=false}
}
function showLocalPairRequest(conn){if(pendingLocalConnection){try{conn.close()}catch(_){}return}pendingLocalConnection=conn;localPendingRole='host';const meta=conn.metadata||{};localState(lang==='ar'?'تم مسح رمز QR؛ بانتظار موافقة الجهاز الثاني':'QR scanned; waiting for approval on the second device');conn.on('close',()=>{if(pendingLocalConnection===conn){pendingLocalConnection=null;localPendingRole='';localState(lang==='ar'?'بانتظار مسح رمز جديد':'Waiting for another QR scan',true)}})}
function approveLocalPair(){const conn=pendingLocalConnection;if(!conn)return;const role=localPendingRole;pendingLocalConnection=null;localPendingRole='';$('pairRequest').classList.add('hidden');if(role==='scanner'){conn._waslaLocalVerified=true;connections.set(conn.peer,conn);activeConnection=conn;renderPeers();activateLocalTransferUI();localState(lang==='ar'?'تمت الموافقة؛ الجهازان متصلان وجاهزان':'Approved; both devices are connected and ready',true);try{conn.send(JSON.stringify({type:'local-ready'}))}catch(e){console.warn('Local approval confirmation failed',e)}toast(lang==='ar'?'تم الاقتران؛ الإرسال والاستقبال متاحان على الجهازين.':'Paired; sending and receiving are ready on both devices.')}else{attachSenderConnection(conn,true);localState(lang==='ar'?'تمت الموافقة؛ جارٍ تأكيد الاتصال المحلي':'Accepted; confirming local connection')}}
function rejectLocalPair(){const conn=pendingLocalConnection;pendingLocalConnection=null;$('pairRequest').classList.add('hidden');if(conn)try{conn.close()}catch(_){}localState(lang==='ar'?'تم رفض طلب الاقتران':'Pairing request rejected',true)}
function privateAddress(value){
 const a=String(value||'').toLowerCase();if(!a)return false;
 if(a.endsWith('.local')||a==='localhost'||a==='::1')return true;
 if(a.includes(':'))return a.startsWith('fc')||a.startsWith('fd')||a.startsWith('fe80:')||a.startsWith('::ffff:192.168.')||a.startsWith('::ffff:10.');
 const p=a.split('.').map(Number);if(p.length!==4||p.some(n=>!Number.isInteger(n)||n<0||n>255))return false;
 return p[0]===10||(p[0]===172&&p[1]>=16&&p[1]<=31)||(p[0]===192&&p[1]===168)||(p[0]===169&&p[1]===254)||p[0]===127;
}
async function isLocalWebRTCPath(conn){
 const pc=conn.peerConnection||conn._peerConnection||conn._pc;if(!pc||typeof pc.getStats!=='function')return false;
 const stats=await pc.getStats();let pair=null;
 stats.forEach(r=>{if(r.type==='transport'&&r.selectedCandidatePairId)pair=stats.get(r.selectedCandidatePairId)});
 if(!pair)stats.forEach(r=>{if(r.type==='candidate-pair'&&(r.selected||r.nominated)&&r.state==='succeeded')pair=r});
 if(!pair)return false;const l=stats.get(pair.localCandidateId),r=stats.get(pair.remoteCandidateId);if(!l||!r)return false;
 return l.candidateType==='host'&&r.candidateType==='host'&&privateAddress(l.address||l.ip)&&privateAddress(r.address||r.ip);
}
async function verifyLocalConnection(conn,timeoutMs=15000){
 const started=Date.now();let lastError=null;
 while(Date.now()-started<timeoutMs){
  if(!conn||conn.open===false)return false;
  try{if(await isLocalWebRTCPath(conn))return true}catch(e){lastError=e}
  await new Promise(resolve=>setTimeout(resolve,400));
 }
 if(lastError)console.warn('LAN route verification',lastError);
 return false;
}
async function startLocalJoin(raw){
 let id=String(raw||'').trim();try{const u=new URL(id);id=u.searchParams.get('localJoin')||u.searchParams.get('join')||''}catch(_){}
 if(!/^[A-Za-z0-9_-]{5,100}$/.test(id)){toast(lang==='ar'?'رابط أو رمز الاقتران غير صالح.':'Invalid pairing link or code.');return}
 if(peer&&!peer.destroyed){toast(lang==='ar'?'أوقف الجلسة الحالية أولًا.':'Stop the current session first.');return}
 localMode=true;$('localJoin').disabled=true;localState(lang==='ar'?'جارٍ الاتصال بالطرف الآخر…':'Connecting to the other device…');
 try{const x=await makePeer();peer=x.p;const conn=peer.connect(id,{reliable:true,serialization:'binary',metadata:{name:deviceName(),kind:deviceLabel()}});activeConnection=conn;
  conn.on('open',async()=>{if(!(await verifyLocalConnection(conn))){toast(lang==='ar'?'لم يتم اختيار مسار محلي مباشر؛ أُغلق الاتصال لمنع نقل الملفات عبر الإنترنت.':'No direct LAN route was selected; closed to prevent internet file transfer.');conn.close();return}
   conn._waslaLocalVerified=true;localPendingRole='scanner';pendingLocalConnection=conn;$('pairRequestName').textContent=lang==='ar'?'الجهاز الذي أنشأ رمز QR':'Device that created the QR code';$('pairRequest').classList.remove('hidden');localState(lang==='ar'?'تم التحقق من الاتصال المحلي؛ بانتظار موافقتك':'Local connection verified; waiting for your approval',true);toast(lang==='ar'?'تحقق الاتصال؛ اضغط موافقة لبدء النقل.':'Connection verified; approve to start transfer.');  });
  conn.on('data',data=>{let handled=false;if(typeof data==='string'){try{const message=JSON.parse(data);if(message.type==='local-status-request'&&conn._waslaLocalVerified){conn.send(JSON.stringify({type:'local-ready'}));handled=true}else if(message.type==='chunk-ack'&&conn._waslaAckResolve){const resolve=conn._waslaAckResolve;conn._waslaAckResolve=null;resolve();handled=true}else if(message.type==='done'){toast(tr('sent'));handled=true}}catch(_){}}if(!handled)handleIncoming(data)});
  conn.on('close',()=>{if(activeConnection===conn)activeConnection=null;closePartial();$('receiveState').textContent=tr('waiting');$('receiveState').classList.remove('online');$('stopReceiver').classList.add('hidden')});
  conn.on('error',e=>{console.warn('Local receiver connection',e);toast(lang==='ar'?'تعذر إكمال الاتصال المحلي.':'Could not complete local connection.')});
 }catch(e){console.error(e);localState(lang==='ar'?'تعذر الاتصال':'Connection failed');toast(lang==='ar'?'تعذر الاقتران. أبقِ الإنترنت متاحًا أثناء إنشاء الاتصال.':'Pairing failed. Keep internet available while establishing the connection.')}
 finally{$('localJoin').disabled=false}
}
async function startQrScanner(){
 if(!navigator.mediaDevices||!navigator.mediaDevices.getUserMedia||!('BarcodeDetector'in window)){toast(lang==='ar'?'المتصفح لا يدعم مسح QR بالكاميرا؛ استخدم حقل الرابط أو الرمز.':'Camera QR scanning is unsupported; use the link/code field.');return}
 try{const detector=new BarcodeDetector({formats:['qr_code']});qrStream=await navigator.mediaDevices.getUserMedia({video:{facingMode:{ideal:'environment'}},audio:false});const video=$('qrVideo');video.srcObject=qrStream;await video.play();$('qrScanner').classList.remove('hidden');qrScanActive=true;
  const scan=async()=>{if(!qrScanActive)return;try{const codes=await detector.detect(video);if(codes.length&&codes[0].rawValue){const value=codes[0].rawValue;stopQrScanner();$('localCodeInput').value=value;await startLocalJoin(value);return}}catch(e){console.warn('QR scan',e)}requestAnimationFrame(scan)};requestAnimationFrame(scan);
 }catch(e){console.warn('Camera access',e);toast(lang==='ar'?'تعذر فتح الكاميرا؛ تحقق من الإذن أو أدخل الرابط يدويًا.':'Could not open camera; check permission or enter the link manually.')}
}
function stopQrScanner(){qrScanActive=false;if(qrStream){qrStream.getTracks().forEach(t=>t.stop());qrStream=null}const v=$('qrVideo');if(v)v.srcObject=null;$('qrScanner').classList.add('hidden')}
function stopLocalPairing(){
 stopQrScanner();localMode=false;localPairingId='';if(pendingLocalConnection){try{pendingLocalConnection.close()}catch(_){}pendingLocalConnection=null}
 $('pairRequest').classList.add('hidden');for(const c of connections.values())try{c.close()}catch(_){}connections.clear();
 if(activeConnection){try{activeConnection.close()}catch(_){}activeConnection=null}if(peer){try{peer.destroy()}catch(_){}peer=null}
 $('localPairArea').classList.add('hidden');$('localCreate').classList.remove('hidden');$('localStop').classList.add('hidden');
 $('receiveState').textContent=tr('waiting');$('receiveState').classList.remove('online');$('stopReceiver').classList.add('hidden');localState(lang==='ar'?'غير متصل':'Not connected');renderPeers();toast(tr('stopped'));
}
$('localCreate').onclick=createLocalPairing;$('localScan').onclick=startQrScanner;$('localScannerStop').onclick=stopQrScanner;
$('localJoin').onclick=()=>startLocalJoin($('localCodeInput').value);$('localCodeInput').onkeydown=e=>{if(e.key==='Enter')startLocalJoin($('localCodeInput').value)};
$('localStop').onclick=stopLocalPairing;$('pairAccept').onclick=approveLocalPair;$('pairReject').onclick=rejectLocalPair;
$('chooseSaveDirectory').onclick=chooseSaveDirectory;$('sendTab').onclick=()=>setTab('send');$('receiveTab').onclick=()=>setTab('receive');$('localTab').onclick=()=>setTab('local');$('langBtn').onclick=switchLang;$('createSession').onclick=createSession;$('joinSession').onclick=joinSession;$('stopSender').onclick=stopSender;$('stopReceiver').onclick=stopReceiver;$('sendFiles').onclick=sendAll;$('fileInput').onchange=e=>addFiles(e.target.files);$('copyLink').onclick=()=>navigator.clipboard.writeText($('shareLink').value).then(()=>toast(tr('copied'))).catch(()=>{$('shareLink').select();document.execCommand('copy');toast(tr('copied'))});$('copyCode').onclick=()=>navigator.clipboard.writeText(sessionId).then(()=>toast(tr('copied')));$('roomInput').onkeydown=e=>{if(e.key==='Enter')joinSession()};const dz=$('dropzone');['dragenter','dragover'].forEach(n=>dz.addEventListener(n,e=>{e.preventDefault();dz.classList.add('drag')}));['dragleave','drop'].forEach(n=>dz.addEventListener(n,e=>{e.preventDefault();dz.classList.remove('drag')}));dz.addEventListener('drop',e=>addFiles(e.dataTransfer.files));
const localJoin=new URLSearchParams(location.search).get('localJoin');if(localJoin){$('localCodeInput').value=localJoin;setTab('local');setTimeout(()=>startLocalJoin(localJoin),150)}const join=new URLSearchParams(location.search).get('join');if(join){$('roomInput').value=join;setTab('receive');setTimeout(()=>{$('receivePanel').scrollIntoView({behavior:'smooth',block:'center'})},100)}
if('serviceWorker'in navigator&&location.protocol==='https:')window.addEventListener('load',()=>navigator.serviceWorker.register('./sw.js').catch(e=>console.warn('SW',e)));
})();