const TTL_SECONDS = 60 * 60 * 3;
const cors = {'Access-Control-Allow-Origin':'*','Access-Control-Allow-Methods':'GET,POST,OPTIONS','Access-Control-Allow-Headers':'Content-Type'};
export default {
 async fetch(request,env){
  const url=new URL(request.url);
  if(request.method==='OPTIONS')return new Response(null,{headers:cors});
  if(url.pathname==='/health')return Response.json({ok:true,service:'signaling-only'},{headers:cors});
  if(url.pathname!=='/ws')return new Response('Direct Transfer signaling',{status:200,headers:cors});
  if(request.headers.get('Upgrade')!=='websocket')return new Response('WebSocket required',{status:426});
  const room=url.searchParams.get('room'),role=url.searchParams.get('role'),token=url.searchParams.get('token');
  if(!room||!token||!['sender','receiver'].includes(role)||room.length>80||token.length>100)return new Response('Invalid session',{status:400});
  const id=env.ROOMS.idFromName(room);
  return env.ROOMS.get(id).fetch(request);
 }
};
export class Room {
 constructor(state,env){this.state=state;this.env=env;this.sockets=new Map();this.meta=null}
 async fetch(request){
  const url=new URL(request.url),room=url.searchParams.get('room'),role=url.searchParams.get('role'),token=url.searchParams.get('token');
  const now=Date.now();
  let meta=await this.state.storage.get('meta');
  if(role==='sender'){
   if(!meta){meta={token,created:now,expires:now+TTL_SECONDS*1000,cancelled:false};await this.state.storage.put('meta',meta);await this.state.storage.setAlarm(meta.expires)}
   if(meta.token!==token)return new Response('Unauthorized',{status:403});
  }else{
   if(!meta||meta.cancelled||meta.expires<now)return new Response('Session expired',{status:410});
  }
  const pair=new WebSocketPair(),client=pair[0],server=pair[1];server.accept();
  const peerId=crypto.randomUUID();
  this.sockets.set(peerId,{ws:server,role});
  server.addEventListener('message',e=>this.onMessage(peerId,e.data));
  server.addEventListener('close',()=>this.remove(peerId));
  server.addEventListener('error',()=>this.remove(peerId));
  if(role==='receiver')this.sendToRole('sender',{type:'join-request',peerId,device:'New device'});
  server.send(JSON.stringify({type:'ready',peerId,expires:meta.expires}));
  return new Response(null,{status:101,webSocket:client});
 }
 send(peerId,data){const p=this.sockets.get(peerId);if(p?.ws.readyState===1)p.ws.send(JSON.stringify(data))}
 sendToRole(role,data){for(const [id,p] of this.sockets)if(p.role===role)this.send(id,data)}
 onMessage(peerId,raw){
  let m;try{m=JSON.parse(raw)}catch{return}
  const sender=this.sockets.get(peerId);if(!sender)return;
  if(m.type==='join-request')return;
  if(m.type==='approve-peer'||m.type==='deny-peer'){
   const target=this.sockets.get(m.peerId);if(!target||sender.role!=='sender')return;
   this.send(m.peerId,{type:m.type==='approve-peer'?'join-approved':'join-rejected',peerId});
   return;
  }
  if(m.type==='join-approved'||m.type==='join-rejected'){
   if(sender.role!=='receiver')return;
   this.sendToRole('sender',{type:m.type,peerId,...(m.device?{device:m.device}:{})});
   return;
  }
  if(m.type==='signal'){
   const target=this.sockets.get(m.peerId);if(!target)return;
   this.send(m.peerId,{type:'signal',peerId,data:m.data});return;
  }
  if(m.type==='cancel-session'&&sender.role==='sender'){
   this.state.storage.get('meta').then(meta=>{if(meta){meta.cancelled=true;this.state.storage.put('meta',meta)}});
   this.sendToRole('receiver',{type:'session-ended'});
   for(const p of this.sockets.values())p.ws.close(1000,'session cancelled');
  }
 }
 remove(id){this.sockets.delete(id)}
 async alarm(){for(const p of this.sockets.values())p.ws.close(1000,'expired');this.sockets.clear();await this.state.storage.deleteAll()}
}
