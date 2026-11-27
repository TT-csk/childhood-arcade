(function(root){'use strict';
root.createUsbLibrary=function(c){
 var active=false,epoch=0,timer=null,data={phase:'idle',sources:[],items:[]},error='',last=null,pending=null,signature='';
 if(c.watch)c.watch(function(){redraw();});
 function redraw(){if(!active)return;var el=document.activeElement,key=el&&el.getAttribute('data-focus');c.render(busy()?(c.operation().canCancel?'usb-cancel:':'usb-scan:'):key&&key.indexOf('usb-')===0&&key!=='usb-cancel:'?key:'usb-scan:');}
 function update(v){data=v||{};data.sources=data.sources||[];data.items=data.items||[];var job=c.operation(),key=JSON.stringify([data,job.busy,job.phase,job.label,job.percent,job.canCancel]);if(key!==signature){signature=key;redraw();}}
 function poll(id){if(!active||id!==epoch)return;c.snapshot().then(function(v){if(!active||id!==epoch)return;update(v);}).catch(function(e){error=e.message;redraw();}).then(function(){if(active&&id===epoch)timer=setTimeout(function(){poll(id);},300);});}
 function scan(){error='';last=null;update({phase:'scanning',sources:[],items:[],label:'正在查找U盘和可访问目录…'});c.scan().then(update).catch(function(e){error=e.message;redraw();});}
 function open(){active=true;epoch++;scan();poll(epoch);}
 function close(){active=false;epoch++;clearTimeout(timer);}
 function busy(){return !!pending;}
 function cancelAnd(next){var job=c.operation();if(job.phase==='committing'){c.toast('正在添加游戏，请稍候再返回');return;}c.cancel();if(pending)pending.then(next,function(){next();});else next();}
 function guard(next){if(!active||!busy())return false;if(c.operation().phase==='committing'){c.toast('正在添加游戏，请稍候再返回');return true;}c.dialog('取消本次导入？','<p>原游戏库和存档会保留。本次尚未完成的资源不会添加。</p>',[{text:'继续导入',primary:true,fn:c.render},{text:'取消并返回',fn:function(){cancelAnd(next);}}]);return true;}
 function start(work){if(busy())return;error='';last=null;var result;try{result=work();}catch(e){error=e.message;redraw();return;}pending=Promise.resolve(result).then(function(result){last=result;if(result)c.toast('资源导入完成，可拔出U盘');return result?c.imported(result):null;}).catch(function(e){error=e.message;}).then(function(){pending=null;redraw();});redraw();}
 function render(){var b=c.btn,e=c.esc,job=c.operation(),html='<h3>从U盘导入资源</h3><p class="usb-lead">找到标准资源ZIP，导入到电视后即可拔出U盘。</p>';
  if(busy()){html+='<div class="usb-progress" role="status"><h4>'+e(job.phase==='review'?'资源检查完成':job.phase==='committing'?'正在添加游戏':'正在读取与导入')+'</h4><p>'+e(job.label||'正在处理，请稍候…')+'</p>'+(job.percent!==undefined?'<progress max="100" value="'+job.percent+'" aria-label="当前阶段进度"></progress>':'')+(job.canCancel?b('取消本次导入','usb-cancel'):'<p>正在提交，完成后可继续操作。</p>')+'</div>';return html;}
  html+='<div class="v2-actions usb-toolbar">'+b('重新扫描','usb-scan','','primary')+(data.desktop?b('选择U盘文件夹','usb-directory'):!data.access||!data.access.readGranted?b('允许读取U盘','usb-access','storage'):'')+(!data.desktop&&data.access&&data.access.canAllFiles&&!data.access.allFilesGranted&&(data.unreadable||!data.sources.some(function(s){return s.readable;}))?b('授权文件访问','usb-access','all-files'):'')+(!data.desktop&&data.access&&data.access.canChooseDirectory?b('授权一个目录','usb-access','directory'):'')+b('选择单个ZIP','usb-single')+'</div>';
  if(data.desktop)html+='<p class="usb-note">电脑端请先选择U盘所在文件夹。电视端会扫描已连接的存储设备。</p>';
  if(error||data.error)html+='<div class="v2-notice usb-error" role="alert">'+e(error||data.error)+'</div>';
  if(last)html+='<div class="v2-notice usb-success"><strong>导入完成，可拔出U盘</strong><p>新增 '+Number(last.added||0)+'，已存在 '+Number(last.duplicates||0)+(last.repaired?'，补齐 '+Number(last.repaired)+' 款':'')+'。'+(last.reusedFiles!==undefined?'复用 '+Number(last.reusedFiles)+' 个资源文件。':'')+'</p></div>';
  html+='<div class="usb-sources"><strong>存储位置</strong>'+(data.sources.length?data.sources.map(function(s){return b(e(s.name)+(s.readable?'':' · 不可读'),'usb-folder',s.id,'',null,!s.readable);}).join(''):'<p>'+e(data.phase==='scanning'?'正在查找…':'尚未发现可访问位置。插入U盘后重新扫描，或允许读取存储。')+'</p>')+'</div>';
  if(data.directory)html+='<div class="usb-folder-heading"><strong>'+e(data.directory.name)+'</strong>'+b(data.directory.parent?'上一级':'返回扫描结果',data.directory.parent?'usb-folder':'usb-scan',data.directory.parent||'')+'</div>';
  html+='<p class="usb-status" role="status">'+e(data.label||(data.phase==='scanning'?'正在扫描…':'选择ZIP后检查格式、空间和重复资源。'))+'</p>';
  if(data.unreadable)html+='<p class="usb-note">有 '+Number(data.unreadable)+' 个位置暂时不可读，请检查授权与U盘连接。</p>';
  if(data.limited)html+='<p class="usb-note">本次扫描已达到目录深度、数量或耗时上限。可进入具体目录继续查找。</p>';
  html+='<div class="usb-file-list">'+data.items.map(function(f){var folder=f.kind==='directory',meta=folder?'文件夹':(f.bytes>=0?size(f.bytes):'大小待检查')+' · '+(f.version?'清单版本 '+f.version+'，待完整检查':'ZIP，待检查');return '<div class="usb-file"><div><strong>'+e(f.name)+'</strong><p>'+e(meta)+'</p><small>'+e(f.location||'')+'</small></div>'+b(folder?'打开':'检查并导入',folder?'usb-folder':'usb-pick',f.id,folder?'':'primary')+'</div>';}).join('')+'</div>';
  if(!data.items.length&&data.phase!=='scanning')html+='<div class="usb-empty"><h4>暂未找到ZIP</h4><p>可把资源ZIP放在U盘根目录或下载文件夹，重新扫描；也可以进入上面的存储位置查找。</p></div>';
  return html;
 }
 function size(bytes){return bytes<1048576?Math.ceil(bytes/1024)+' KB':(bytes/1048576).toFixed(1)+' MB';}
 function action(name,arg){if(name.indexOf('usb-')!==0)return false;switch(name){
  case'usb-scan':scan();break;
  case'usb-folder':error='';c.browse(arg).then(update).catch(function(e){error=e.message;redraw();});break;
  case'usb-directory':error='';c.directory().then(update).catch(function(e){if(e.name!=='AbortError')error=e.message;redraw();});break;
  case'usb-access':c.dialog('允许读取存储','<p>接下来会进入电视系统的文件访问或目录授权页面。允许后返回大厅重新扫描；游戏厅只读取源ZIP，导入内容写入电视内部。</p>',[{text:'进入系统授权',primary:true,fn:function(){c.access(arg);c.render();}},{text:'取消',fn:c.render}]);break;
  case'usb-pick':start(function(){return c.select(arg);});break;
  case'usb-single':start(c.single);break;
  case'usb-cancel':guard(c.render);break;
 }return true;}
 return {open:open,close:close,render:render,action:action,guard:guard,refresh:function(){if(active)poll(epoch);},active:function(){return active;}};
};
}(this));
