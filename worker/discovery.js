const MAX_MESSAGE=32768;
export default {
 async fetch(request,env){
  const url=new URL(request.url);
  if(url.pathname!=='/ws')return new Response('Wasla discovery service',{headers:{'content-type':'text/plain; charset=utf-8'}});
  if(request.headers.get('Upgrade')!=='websocket')return new Response('WebSocket upgrade required',{status:426});
  const id=env.DISCOVERY.idFromName('wasla-discovery-hub');
  return env.DISCOVERY.get(id).fetch(request);
 }
};
export class Discovery {
 constructor(state){this.state=state;this.clients=new Map();}
 async fetch(request){
  const pair=new WebSocketPair(),client=pair[0],server=pair[1];
  const ip=request.headers.get('CF-Connecting-IP')||'unknown';
  const id=crypto.randomUUID();
  const record={id,ip,name:'جهاز',kind:'متصفح',socket:server};
  server.accept();
  this.clients.set(id,record);
  server.addEventListener('message',event=>this.onMessage(record,event.data));
  server.addEventListener('close',()=>this.remove(record));
  server.addEventListener('error',()=>this.remove(record));
  const peers=[...this.clients.values()].filter(x=>x.id!==id&&x.ip===ip).map(this.publicPeer);
  this.send(record,{type:'welcome',id,peers});
  this.broadcast(record,{type:'peer-up',peer:this.publicPeer(record)});
  return new Response(null,{status:101,webSocket:client});
 }
 publicPeer(record){return{id:record.id,name:record.name,kind:record.kind};}
 send(record,data){try{record.socket.send(JSON.stringify(data));}catch(e){this.remove(record);}}
 broadcast(source,data){for(const target of this.clients.values())if(target.id!==source.id&&target.ip===source.ip)this.send(target,data);}
 onMessage(record,raw){
  if(typeof raw!=='string'||raw.length>MAX_MESSAGE)return this.send(record,{type:'error',message:'invalid-message'});
  let msg;try{msg=JSON.parse(raw);}catch(e){return this.send(record,{type:'error',message:'invalid-json'});}
  if(msg.type==='hello'){
   record.name=String(msg.name||'جهاز').replace(/[<>\u0000-\u001f]/g,'').slice(0,48)||'جهاز';
   record.kind=String(msg.kind||'متصفح').replace(/[<>\u0000-\u001f]/g,'').slice(0,24)||'متصفح';
   this.broadcast(record,{type:'peer-up',peer:this.publicPeer(record)});
   return;
  }
  if(msg.type==='list'){
   const peers=[...this.clients.values()].filter(x=>x.id!==record.id&&x.ip===record.ip).map(this.publicPeer);
   return this.send(record,{type:'welcome',id:record.id,peers});
  }
  if(msg.type==='signal'){
   if(typeof msg.to!=='string'||!msg.signal||!['offer','answer'].includes(msg.signal.kind)||!msg.signal.description||typeof msg.signal.description.sdp!=='string')return this.send(record,{type:'error',message:'invalid-signal'});
   const target=this.clients.get(msg.to);
   if(!target||target.ip!==record.ip)return this.send(record,{type:'error',message:'peer-unavailable'});
   if(msg.signal.description.sdp.length>24000)return this.send(record,{type:'error',message:'signal-too-large'});
   this.send(target,{type:'signal',from:record.id,name:record.name,signal:msg.signal});
  }
 }
 remove(record){
  if(!this.clients.has(record.id))return;
  this.clients.delete(record.id);
  this.broadcast(record,{type:'peer-down',id:record.id});
 }
}
