(function(root,factory){if(typeof module==='object'&&module.exports)module.exports=factory();else root.ArcadeLib=factory();}(this,function(){
 'use strict';
 function normalize(s){return String(s||'').toUpperCase().replace(/[\s\-_,，。·]/g,'');}
 function matches(g,q){var text=normalize(g.title+' '+(g.aliases||'')+' '+(g.version||''));return !q||text.indexOf(normalize(q))>=0;}
 function sha256(bytes){
  var k=[],h=[],n=2;while(k.length<64){var prime=true;for(var d=2;d*d<=n;d++)if(n%d===0){prime=false;break;}if(prime){if(h.length<8)h.push((Math.sqrt(n)%1*4294967296)|0);k.push((Math.pow(n,1/3)%1*4294967296)|0);}n++;}
  var len=bytes.length,padded=((len+9+63)>>6)<<6,data=new Uint8Array(padded);data.set(bytes);data[len]=128;var bits=len*8;
  for(var j=0;j<8;j++)data[padded-1-j]=Math.floor(bits/Math.pow(256,j))&255;
  function r(x,n){return (x>>>n)|(x<<(32-n));}
  var w=new Int32Array(64);
  for(var off=0;off<padded;off+=64){for(var i=0;i<16;i++){var p=off+i*4;w[i]=(data[p]<<24)|(data[p+1]<<16)|(data[p+2]<<8)|data[p+3];}
   for(i=16;i<64;i++){var a=w[i-15],b=w[i-2];w[i]=((r(a,7)^r(a,18)^(a>>>3))+w[i-16]+(r(b,17)^r(b,19)^(b>>>10))+w[i-7])|0;}
   var a=h[0],b=h[1],c=h[2],d=h[3],e=h[4],f=h[5],g=h[6],v=h[7];
   for(i=0;i<64;i++){var t1=(v+(r(e,6)^r(e,11)^r(e,25))+((e&f)^((~e)&g))+k[i]+w[i])|0;var t2=((r(a,2)^r(a,13)^r(a,22))+((a&b)^(a&c)^(b&c)))|0;v=g;g=f;f=e;e=(d+t1)|0;d=c;c=b;b=a;a=(t1+t2)|0;}
   var vals=[a,b,c,d,e,f,g,v];for(i=0;i<8;i++)h[i]=(h[i]+vals[i])|0;
  }
  return h.map(function(x){return ('00000000'+(x>>>0).toString(16)).slice(-8);}).join('');
 }
 var classicHomeOrder=['contra','mario','battlecity','chipdale','chipdale2','snowbros','tmnt2','superc','rivercity','kuniofight','jackal','dragon2','mario3','kof97','kof98','kage','ninjagaiden','adventure','bomberman','mightyfinalfight','megaman2','kirby','battletoads','circus','kunioleague'];
 function homeId(g){return g.group||g.id;}
 function validHomeOrder(value){var seen={};return Array.isArray(value)&&value.length<=1000&&!value.some(function(id){if(typeof id!=='string'||!(/^[a-z0-9-]{1,100}$/).test(id)||seen[id])return true;seen[id]=true;return false;});}
 function resolveHomeOrder(games,saved){var available={},base=[],seen={},out=[];games.forEach(function(g){var id=homeId(g);if(!available[id]){available[id]=true;base.push(id);}});var ids=(validHomeOrder(saved)?saved:[]).concat(classicHomeOrder,base);ids.forEach(function(id){if(available[id]&&!seen[id]){seen[id]=true;out.push(id);}});return out;}
 function sortHomeGames(list,all,saved){var ranks={},order=resolveHomeOrder(all,saved);order.forEach(function(id,i){ranks[id]=i;});return list.map(function(g,i){return {game:g,index:i};}).sort(function(a,b){return ranks[homeId(a.game)]-ranks[homeId(b.game)]||a.index-b.index;}).map(function(x){return x.game;});}
 function moveHomeGame(order,id,index){var copy=order.slice(),from=copy.indexOf(id);if(from<0)return copy;copy.splice(from,1);copy.splice(Math.max(0,Math.min(copy.length,index)),0,id);return copy;}
 function validateBackup(value){if(!value||value.schema!==1||value.app!=='childhood-arcade'||!Array.isArray(value.entries)||value.entries.length>1000)throw new Error('这不是有效的游戏厅备份');
  var seen={};value.entries.forEach(function(x){if(!x||typeof x.key!=='string'||!(/^(slot:[a-f0-9]{64}:[0-3]|battery:[a-f0-9]{64}|prefs)$/).test(x.key)||seen[x.key])throw new Error('备份含有无效或重复条目');seen[x.key]=true;if(x.key.indexOf('slot:')===0&&(!x.value||x.value.schema!==1||x.value.hash!==x.key.split(':')[1]||!x.value.state))throw new Error('备份中的游戏版本不匹配');if(x.key.indexOf('battery:')===0)validateBattery(x.value,x.key.slice(8));if(x.key==='prefs'){var p=x.value;if(!p||typeof p!=='object'||Array.isArray(p))throw new Error('备份设置格式无效');if(typeof p.volume!=='number'||!isFinite(p.volume)||p.volume<0||p.volume>100||['original','integer'].indexOf(p.scale)<0||typeof p.welcome!=='boolean')throw new Error('备份设置超出有效范围');if(p.homeOrder!==undefined&&!validHomeOrder(p.homeOrder))throw new Error('备份中的首页顺序格式无效');['favorites','recent'].forEach(function(k){if(!Array.isArray(p[k])||p[k].length>1000||p[k].some(function(id){return typeof id!=='string'||!(/^[a-z0-9-]{1,100}$/).test(id);}))throw new Error('备份中的游戏列表格式无效');});}});return value;
 }
 function validateBattery(v,hash,engine){if(!v||v.schema!==1||!((v.format==='gba-sram-base64'&&(/^mgba-(ejs|native)-[a-f0-9]{12}$/).test(v.engine))||(v.format==='md-sram-base64'&&(/^picodrive-(ejs|native)-[a-f0-9]{12}$/).test(v.engine))||(v.format==='sfc-sram-base64'&&(/^(snes9x-ejs|snes9x2005(?:plus)?-native)-[a-f0-9]{12}$/).test(v.engine)))||v.hash!==hash||!(/^[a-f0-9]{64}$/).test(hash)||engine&&v.engine!==engine||!Number.isInteger(v.bytes)||v.bytes<1||v.bytes>1048576||typeof v.data!=='string'||v.data.length>1398104||!(/^[a-f0-9]{64}$/).test(v.sha256))throw new Error('游戏内存档身份或大小无效');var raw;try{raw=atob(v.data);}catch(e){throw new Error('游戏内存档编码无效');}var bytes=new Uint8Array(raw.length);for(var i=0;i<raw.length;i++)bytes[i]=raw.charCodeAt(i);if(bytes.length!==v.bytes||sha256(bytes)!==v.sha256)throw new Error('游戏内存档校验失败，原数据已保留');return bytes;}
 function isArcade(g){return !!g&&['neogeo','cps1','cps2','konami','pgm','sega','irem'].indexOf(g.system)>=0;}
 function isHandheld(g){return !!g&&g.system==='gba';}
 function isMd(g){return !!g&&g.system==='md';}
 function isSfc(g){return !!g&&g.system==='sfc';}
 function isExternal(g){return isArcade(g)||isHandheld(g)||isMd(g)||isSfc(g);}
 return {isSfc:isSfc,isMd:isMd,validateBattery:validateBattery,isArcade:isArcade,isHandheld:isHandheld,isExternal:isExternal,normalize:normalize,matches:matches,sha256:sha256,validateBackup:validateBackup,resolveHomeOrder:resolveHomeOrder,sortHomeGames:sortHomeGames,moveHomeGame:moveHomeGame,validHomeOrder:validHomeOrder};
}));
