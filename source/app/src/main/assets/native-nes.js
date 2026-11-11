(function(){
  'use strict';
  var Base=window.ArcadeEngine,bridge=window.NativeArcade,ID='fceumm-native-81ebd3e72d96';
  function result(raw){var r=JSON.parse(raw);if(r.error)throw new Error(r.error);return r;}
  function NativeEngine(canvas){
    if(!bridge||!bridge.nativeNesAvailable||!bridge.nativeNesAvailable())return new Base(canvas);
    this.canvas=canvas;this.legacy=null;this.binary=null;this.game=null;this.hash=null;this.engineId=ID;this.volume=.7;this.kind='nes';this.active=false;this.onError=null;this.healthTimer=null;
    var self=this;
    Object.defineProperty(this,'nativeActive',{get:function(){return self.active&&!self.legacy;}});
    Object.defineProperty(this,'frames',{get:function(){return self.legacy?self.legacy.frames:self.stats().frames||0;}});
    Object.defineProperty(this,'running',{get:function(){return self.legacy?self.legacy.running:!!self.stats().running;}});
    Object.defineProperty(this,'audio',{get:function(){if(self.legacy)return self.legacy.audio;return self.active?{state:self.stats().running?'running':'suspended'}:null;}});
    Object.defineProperty(this,'audioError',{get:function(){return self.legacy?(self.legacy.audioError||'旧版进度正以兼容模式运行；从头开始可使用流畅模式。'):null;}});
    Object.defineProperty(this,'nes',{get:function(){return self.legacy?self.legacy.nes:null;}});
    Object.defineProperty(this,'rom',{get:function(){return self.binary;}});
    window.addEventListener('resize',function(){if(self.active&&!self.legacy)self.syncBounds();});
  }
  NativeEngine.prototype.stats=function(){return bridge&&bridge.nativeNesStats?JSON.parse(bridge.nativeNesStats()):{};};
  NativeEngine.prototype.ensureAudio=function(){if(this.legacy)this.legacy.ensureAudio();};
  NativeEngine.prototype.load=function(binary,hash,game){this.close();this.binary=binary;this.hash=hash;this.game=game;this.kind=ArcadeLib.isSfc(game)?'sfc':ArcadeLib.isMd(game)?'md':ArcadeLib.isHandheld(game)?'gba':ArcadeLib.isArcade(game)?'arcade':'nes';var loaded=this.kind==='sfc'?result(bridge.nativeSfcStart(game.path.split('/').pop(),hash)):this.kind==='md'?result(bridge.nativeMdStart(game.path.split('/').pop(),hash)):this.kind==='gba'?result(bridge.nativeGbaStart(game.path.split('/').pop(),hash)):this.kind==='arcade'?result(bridge.nativeArcadeStart(game.path.split('/').pop(),hash)):result(bridge.nativeNesStart(btoa(binary),hash));this.engineId=loaded.engine||ID;this.active=true;this.setVolume(this.volume);var self=this;this.healthTimer=setInterval(function(){if(!self.active||self.legacy)return;try{var s=self.stats();if(s.error){self.pause();if(self.onError)self.onError(new Error(s.error));}}catch(e){self.pause();if(self.onError)self.onError(e);}},1000);return Promise.resolve();};
  NativeEngine.prototype.syncBounds=function(){var r=this.canvas.getBoundingClientRect();bridge.nativeNesBounds(r.left,r.top,r.width,r.height,innerWidth,innerHeight);};
  NativeEngine.prototype.applyScale=function(mode){var self=this;if(this.legacy)return this.legacy.applyScale(mode);requestAnimationFrame(function(){self.syncBounds();});};
  NativeEngine.prototype.resume=function(){if(this.legacy)return this.legacy.resume();if(this.active){document.documentElement.classList.add('native-running');this.syncBounds();bridge.nativeNesResume();}};
  NativeEngine.prototype.pause=function(){if(this.legacy)return this.legacy.pause();bridge.nativeNesPause();if(this.kind==='gba'||this.kind==='md'||this.kind==='sfc'){var health=this.stats();if(health.error&&window.Arcade&&window.Arcade.storageError)window.Arcade.storageError(health.error);}document.documentElement.classList.remove('native-running');};
  NativeEngine.prototype.releaseAll=function(){if(this.legacy)return this.legacy.releaseAll();bridge.nativeNesRelease();};
  NativeEngine.prototype.button=function(player,button,down){if(this.legacy)return this.legacy.button(player,button,down);if(this.active)bridge.nativeNesButton(player,button,!!down);};
  NativeEngine.prototype.snapshot=function(){return this.legacy?this.legacy.snapshot():result(bridge.nativeNesSnapshot());};
  NativeEngine.prototype.restore=function(save){
    var self=this;if(!save||save.schema!==1||save.hash!==this.hash||!save.state)throw new Error('存档与当前游戏版本不匹配');
    if(save.engine==='jsnes-2.1.0'&&this.kind==='nes'){
      // Retain old progress with its original interpreter; never relabel its state.
      if(this.legacy)return this.legacy.restore(save);
      var legacy=new Base(this.canvas);legacy.ensureAudio();return legacy.load(this.binary,this.hash,this.game).then(function(){try{legacy.restore(save);}catch(e){legacy.close();throw e;}bridge.nativeNesClose();self.legacy=legacy;self.legacy.setVolume(self.volume);self.legacy.onError=function(e){if(self.onError)self.onError(e);};});
    }
    if(save.engine!==this.engineId||save.state.format!=='libretro-base64'||typeof save.state.data!=='string'||save.state.data.length>12*1024*1024)throw new Error('存档与当前游戏版本不匹配');
    var raw;try{raw=atob(save.state.data);}catch(e){throw new Error('存档校验失败，当前进度已保留');}var bytes=new Uint8Array(raw.length);for(var i=0;i<raw.length;i++)bytes[i]=raw.charCodeAt(i);
    if(bytes.length!==save.state.bytes||ArcadeLib.sha256(bytes)!==save.state.sha256)throw new Error('存档校验失败，当前进度已保留');
    if(this.legacy)throw new Error('请从游戏详情重新打开，再读取此进度；当前进度已保留');
    result(bridge.nativeNesRestore(JSON.stringify(save)));
  };
  NativeEngine.prototype.thumbnail=function(){return this.legacy?this.legacy.thumbnail():bridge.nativeNesImage();};
  NativeEngine.prototype.setVolume=function(value){this.volume=value;if(this.legacy)this.legacy.setVolume(value);else bridge.nativeNesVolume(value);};
  NativeEngine.prototype.close=function(){if(this.healthTimer){clearInterval(this.healthTimer);this.healthTimer=null;}if(this.legacy){var audio=this.legacy.audio;this.legacy.close();if(audio&&audio.state!=='closed')audio.close().catch(function(){});this.legacy=null;}bridge.nativeNesClose();if(this.kind==='gba'||this.kind==='md'||this.kind==='sfc'){var health=this.stats();if(health.error&&window.Arcade&&window.Arcade.storageError)window.Arcade.storageError(health.error);}document.documentElement.classList.remove('native-running');this.active=false;};
  window.ArcadeEngine=NativeEngine;
}());
