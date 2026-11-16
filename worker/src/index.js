const TTL_SECONDS=60*60*3;
export default {async fetch(request,env){
 const url=new URL(request.url);
 if(request.method==='OPTIONS')return new Response(null,{headers:{'Access-Control-Allow-Origin':'*','Access-Control-Allow-Methods':'GET,OPTIONS','Access-Control-Allow-Headers':'Content-Type'}});
 if(url.pathname==='/health')return Response.json({ok:true,service:'signaling-only'});
 if(url.pathname!=='/ws')return new Response('Direct Transfer signaling');
 if(request.headers.get('Upgrade')!=='websocket')return new Response('WebSocket required',{status:426});
 const room=url.searchParams.get('room'),role=url.searchParams.get('role'),token=url.searchParams.get('token');
 if(!room||!token||!['sender','receiver'].includes(role)||room.length>80||token.length>100)return new Response('Invalid session',{status:400});
 return env.ROOMS.get(env.ROOMS.idFromName(room)).fetch(request);
}};
export class Room{
 constructor(state){this.state=state;this.sockets=new Map();this.senderId=null}
 async fetch(request){
  const u=new URL(request.url),role=u.searchParams.get('role'),token=u.searchParams.get('token'),now=Date.now();
  let meta=await this.state.storage.get('meta');
  if(role==='sender'){
   if(!meta){meta={token,created:now,expires:now+TTL_SECONDS*1000,cancelled:false};await this.state.storage.put('meta',meta);await this.state.storage.setAlarm(meta.expires)}
   if(meta.token!==token||meta.cancelled||meta.expires<now)return new Response('Session unavailable',{status:410});
  }else if(!meta||meta.token!==token||meta.cancelled||meta.expires<now)return new Response('Session unavailable',{status:410});
  const pair=new WebSocketPair(),client=pair[0],ws=pair[1];ws.accept();
  const peerId=crypto.randomUUID();this.sockets.set(peerId,{ws,role});
  if(role==='sender')this.senderId=peerId;
  ws.addEventListener('message',e=>this.onMessage(peerId,e.data));
  ws.addEventListener('close',()=>this.remove(peerId));ws.addEventListener('error',()=>this.remove(peerId));
  ws.send(JSON.stringify({type:'ready',peerId,expires:meta.expires}));
  return new Response(null,{status:101,webSocket:client});
 }
 send(id,msg){const p=this.sockets.get(id);if(p?.ws.readyState===1)p.ws.send(JSON.stringify(msg))}
 onMessage(from,raw){
  let m;try{m=JSON.parse(raw)}catch{return}
  const source=this.sockets.get(from);if(!source)return;
  if(m.type==='join-request'&&source.role==='receiver'){this.send(this.senderId,{type:'join-request',peerId:from,device:String(m.device||'Device').slice(0,100)});return}
  if((m.type==='approve-peer'||m.type==='deny-peer')&&source.role==='sender'){
   const target=this.sockets.get(m.peerId);if(target?.role==='receiver')this.send(m.peerId,{type:m.type==='approve-peer'?'join-approved':'join-rejected',senderPeerId:from});return;
  }
  if(m.type==='signal'&&typeof m.peerId==='string'&&m.data){
   const target=this.sockets.get(m.peerId);if(!target)return;
   this.send(m.peerId,{type:'signal',peerId:from,fromPeerId:from,data:m.data});return;
  }
  if(m.type==='cancel-session'&&source.role==='sender'){
   this.state.storage.get('meta').then(meta=>{if(meta){meta.cancelled=true;this.state.storage.put('meta',meta)}});
   for(const [id,p] of this.sockets){this.send(id,{type:'session-ended'});try{p.ws.close(1000,'cancelled')}catch{}}
  }
 }
 remove(id){this.sockets.delete(id);if(this.senderId===id)this.senderId=null}
 async alarm(){for(const p of this.sockets.values())try{p.ws.close(1000,'expired')}catch{}this.sockets.clear();await this.state.storage.deleteAll()}
}