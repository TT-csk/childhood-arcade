(function(root){'use strict';
root.createControllerGuide=function(c){
 var M=root.HallModel,g=null,prompted={},basic=[0,1,8,9,3,2,20],extra=[10,11,12,13,14,15];
 function label(n){return n===2?'选择 / 投币':n===3?'开始':n===20?'暂停 / 菜单':M.inputName(n);}
 function deviceName(id){return id.replace(/\s*\[[^\]]*\]$/,'').replace(/\s+#\d+$/,'');}
 function connected(){return g&&c.controllers().indexOf(g.device)>=0;}
 function open(player,id){var cfg=c.config(),ids=c.controllers(),assigned=M.assignments(ids,cfg.devices);id=id||ids.filter(function(k){return assigned[k]===player;})[0];if(!id||ids.indexOf(id)<0){c.toast('请先连接并分配当前玩家手柄');return;}g={device:id,player:player,phase:'intro',steps:basic.slice(),index:0,buttons:{},held:null,pressed:{},status:''};c.render();}
 function close(){g=null;c.render();}
 function back(){if(!g)return false;if(c.state.busy)return true;if(g.phase==='intro'||g.phase==='test'){close();return true;}c.dialog('放弃本次识别？','<p>尚未保存的识别结果会放弃，原来的设置保持。</p>',[{text:'放弃识别',fn:close},{text:'继续识别',primary:true,fn:c.render}],1);return true;}
 function next(){g.held=null;g.index++;if(g.index>=g.steps.length)g.phase='review';g.status='';c.render();}
 function physical(source,down,player,id){if(!g||c.state.dialog||c.state.page!=='settings')return false;var capturing=g.phase==='capture',testing=g.phase==='test';if(!capturing&&!testing)return g.phase==='disconnected';if(id!==g.device||player!==g.player)return true;
  if(testing){var target=M.deviceInput(c.config(),id,source);if(target!==null){g.pressed[target]=down;c.render();}return true;}
  if(!down){if(g.held===source)next();return true;}
  if(g.held!==null)return true;
  if(!M.physicalButton(source)){g.status='请按实体按钮；方向键继续用于浏览。';c.render();return true;}
  var occupied=Object.prototype.hasOwnProperty.call(g.buttons,source);if(occupied){g.status=M.inputName(source)+' 已用于 '+label(g.buttons[source])+'，请换一个按钮或跳过。';c.render();return true;}
  g.buttons[source]=g.steps[g.index];g.held=source;g.status='已收到 '+M.inputName(source)+'，松开后继续。';c.render();return true;
 }
 function adapt(source,id){if(g&&g.phase==='review'&&g.device===id)return M.deviceInput({deviceInputs:preview()},id,source);return M.deviceInput(c.config(),id,source);}
 function preview(){var v={};v[g.device]={buttons:g.buttons};return v;}
 function render(){if(!g)return '';var b=c.btn,e=c.esc,head='<div class="v21-guide-head"><h3>手柄按键引导</h3><p>'+g.player+'P · '+e(deviceName(g.device))+'</p></div>',body='',actions='',chips=basic.map(function(n,i){var recorded=Object.keys(g.buttons).some(function(k){return g.buttons[k]===n;});return '<span class="v21-guide-step'+(recorded?' done':'')+(g.phase==='capture'&&g.steps[g.index]===n?' current':'')+'">'+e(label(n))+'</span>';}).join('');
  if(g.phase==='intro'){body='<h4>把 A / B / X / Y 都认清楚</h4><p>按提示逐个按下手柄按钮，再设置开始、选择 / 投币和可选暂停键。没有的键可以跳过。</p><p>这次识别只保存到当前这只手柄，已有游戏改键继续生效。</p>';actions=b('开始识别','guide-start','','primary')+b('取消','guide-close');}
  if(g.phase==='capture'){var target=g.steps[g.index];body='<p class="v21-guide-progress">第 '+(g.index+1)+' / '+g.steps.length+' 步</p><div class="v21-guide-target">'+e(label(target))+'</div><p class="v21-guide-instruction">按一下你希望作为「'+e(label(target))+'」的实体按钮</p><p class="v21-guide-status" role="status">'+e(g.status||'按下后松开，自动进入下一项。')+'</p>';actions=b('没有这个键，跳过','guide-skip')+b('上一步','guide-prev','',null,null,g.index===0)+b('放弃识别','guide-close');}
  if(g.phase==='review'||g.phase==='test'){body='<h4>'+(g.phase==='test'?'按一按，看看是否对应':'核对按键，再保存')+'</h4><div class="v21-guide-results">'+g.steps.map(function(n){var raw=Object.keys(g.buttons).filter(function(k){return g.buttons[k]===n;})[0],pressed=!!g.pressed[n];return '<div class="v21-guide-result'+(pressed?' pressed':'')+'"><strong>'+e(label(n))+'</strong><span>'+e(raw===undefined?'已跳过':M.inputName(Number(raw)))+'</span><small>'+(g.phase==='test'?(pressed?'已按下':'已松开'):'')+'</small></div>';}).join('')+'</div><p>'+e(g.status||(g.phase==='test'?'已保存。用手柄测试，完成后用遥控器返回。':'只保存这只手柄的按键。保存后可以立即测试；未识别的通用按钮暂不能使用。'))+'</p>';actions=g.phase==='test'?b('完成，返回设置','guide-finish','','primary')+b('重新识别','guide-restart'):b('保存并测试','guide-save','','primary',null,!Object.keys(g.buttons).length)+b('重新识别','guide-restart')+(g.steps.length===basic.length?b('补充肩键等按键','guide-more'):'')+b('放弃识别','guide-close');}
  if(g.phase==='disconnected'){body='<h4>当前手柄已断开</h4><p>识别草稿已保留。连接回同一只手柄后继续；更换设备请先退出并重新选择。</p><p role="status">'+e(g.status)+'</p>';actions=b('重新连接后继续','guide-reconnect','','primary')+b('退出引导','guide-close');}
  return '<div class="v2-modal-backdrop"><section class="v2-modal v21-guide" role="dialog" aria-modal="true" aria-label="手柄按键引导">'+head+'<div class="v21-guide-steps">'+chips+'</div>'+body+'<div class="v2-actions">'+actions+'</div><p class="v21-guide-foot">识别时按手柄实体键；遥控器可选择跳过、保存或取消。</p></section></div>';
 }
 function action(name){if(name.indexOf('guide-')!==0)return false;if(name==='guide-open'){open(c.player());return true;}if(!g)return true;if(c.state.busy)return true;switch(name){
  case'guide-start':g.phase='capture';g.index=0;g.status='';c.render();break;
  case'guide-close':back();break;
  case'guide-finish':close();break;
  case'guide-restart':open(g.player,g.device);break;
  case'guide-skip':if(g.held!==null){g.status='请先松开按钮再继续。';c.render();break;}next();break;
  case'guide-prev':if(g.held!==null){g.status='请先松开按钮再继续。';c.render();break;}g.index=Math.max(0,g.index-1);Object.keys(g.buttons).forEach(function(k){if(g.buttons[k]===g.steps[g.index])delete g.buttons[k];});g.status='';c.render();break;
  case'guide-more':g.steps=basic.concat(extra);g.index=basic.length;g.phase='capture';c.render();break;
  case'guide-reconnect':if(!connected()){g.status='尚未检测到这只手柄，请检查连接。';c.render();break;}g.phase=g.beforeDisconnect||'capture';g.status='';c.render();break;
  case'guide-save':if(!connected()){disconnect();break;}var cfg=c.config(),previous=M.clone(cfg.deviceInputs||{}),current=g;cfg.deviceInputs=cfg.deviceInputs||{};cfg.deviceInputs[g.device]={buttons:M.clone(g.buttons)};try{M.validConfig(cfg);}catch(err){cfg.deviceInputs=previous;g.status=err.message;c.render();break;}c.state.busy=true;c.render();c.save().then(function(){c.state.busy=false;c.apply();if(g!==current)return;g.phase='test';g.pressed={};g.status='';c.render();}).catch(function(err){cfg.deviceInputs=previous;c.state.busy=false;if(g===current){g.status='保存失败：'+err.message+'。原设置保留，请重试。';c.render();}});break;
 }return true;}
 function disconnect(){if(!g||connected()||g.phase==='disconnected')return;if(g.phase==='capture'&&g.held!==null)delete g.buttons[g.held];g.beforeDisconnect=g.phase;g.phase='disconnected';g.held=null;g.pressed={};g.status='';c.render();}
 function unknown(source,player,id){if(g||!M.physicalButton(source)||prompted[id]||c.state.dialog)return;prompted[id]=true;if(c.state.page==='playing'){c.toast('收到尚未配置的手柄按钮，请暂停后到手柄设置完成 A/B/X/Y 引导');return;}c.dialog('收到尚未配置的手柄按钮','<p>'+c.esc(deviceName(id))+' 发来了「'+c.esc(M.inputName(source))+'」。按提示识别 A/B/X/Y 后即可尝试使用。</p>',[{text:'开始按键引导',primary:true,fn:function(){c.enter();open(player,id);}},{text:'稍后',fn:c.render}]);}
 return {open:open,render:render,action:action,back:back,input:physical,adapt:adapt,unknown:unknown,disconnect:disconnect,active:function(){return !!g;},capturesAxes:function(){return !!g&&(g.phase==='capture'||g.phase==='test'||g.phase==='disconnected');}};
};
}(this));
