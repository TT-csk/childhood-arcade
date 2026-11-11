(function(root,factory){if(typeof module==='object'&&module.exports)module.exports=factory(require('./vendor/jsnes.min.js'));else root.ArcadeEngine=factory(root.jsnes);}(this,function(jsnes){
  'use strict';
  function Engine(canvas){
    this.canvas=canvas;this.ctx=canvas.getContext('2d',{alpha:false});this.image=this.ctx.createImageData(256,240);this.running=false;this.frames=0;this.volume=0.7;this.last=0;this.credit=0;this.rom=null;this.hash=null;this.audio=null;this.audioError=null;this.onError=null;
    this.samples=new Float32Array(32768);this.read=0;this.write=0;this.buffered=0;this.mask=32767;
    var self=this;this.tick=function(now){
      if(!self.running)return;
      try{var elapsed=Math.min(100,Math.max(0,now-self.last));self.last=now;self.credit+=elapsed;var count=0;
        while(self.credit>=1000/60&&count<5){self.nes.frame();self.frames++;self.credit-=1000/60;count++;}
        self.raf=requestAnimationFrame(self.tick);
      }catch(e){self.pause();if(self.onError)self.onError(e);}
    };
  }
  Engine.prototype.ensureAudio=function(){
    if(this.audio){if(this.audio.state==='suspended')this.audio.resume().catch(function(){});return;}
    var AC=window.AudioContext||window.webkitAudioContext;if(!AC){this.audioError='当前设备不支持游戏声音';return;}
    try{var self=this;this.audio=new AC();this.node=this.audio.createScriptProcessor(1024,0,2);this.gain=this.audio.createGain();this.gain.gain.value=this.volume;this.node.connect(this.gain);this.gain.connect(this.audio.destination);
      this.node.onaudioprocess=function(e){var l=e.outputBuffer.getChannelData(0),r=e.outputBuffer.getChannelData(1);for(var i=0;i<l.length;i++){if(self.running&&self.buffered>=2){l[i]=self.samples[self.read];self.read=(self.read+1)&self.mask;r[i]=self.samples[self.read];self.read=(self.read+1)&self.mask;self.buffered-=2;}else{l[i]=0;r[i]=0;}}};
      if(this.audio.state==='suspended')this.audio.resume().catch(function(){});
    }catch(e){this.audioError='声音初始化失败，可继续无声游玩';}
  };
  Engine.prototype.load=function(binary,hash){
    this.pause();this.rom=binary;this.hash=hash;this.frames=0;var self=this;
    this.nes=new jsnes.NES({sampleRate:this.audio?this.audio.sampleRate:44100,onFrame:function(frame){var d=self.image.data;for(var i=0;i<61440;i++){var c=frame[i],p=i*4;d[p]=c&255;d[p+1]=(c>>8)&255;d[p+2]=(c>>16)&255;d[p+3]=255;}self.ctx.putImageData(self.image,0,0);},onAudioSample:function(l,r){if(self.buffered>30000)return;self.samples[self.write]=l;self.write=(self.write+1)&self.mask;self.samples[self.write]=r;self.write=(self.write+1)&self.mask;self.buffered+=2;}});
    this.nes.loadROM(binary);this.nes.frame();this.frames=1;this.releaseAll();
  };
  Engine.prototype.resume=function(){if(!this.nes||this.running)return;this.ensureAudio();this.running=true;this.credit=0;this.last=performance.now();this.raf=requestAnimationFrame(this.tick);};
  Engine.prototype.pause=function(){this.running=false;if(this.raf)cancelAnimationFrame(this.raf);this.releaseAll();this.read=this.write=this.buffered=0;};
  Engine.prototype.releaseAll=function(){if(this.nes)for(var p=1;p<=2;p++)for(var b=0;b<8;b++)this.nes.buttonUp(p,b);};
  Engine.prototype.button=function(player,button,down){if(this.running&&this.nes)this.nes[down?'buttonDown':'buttonUp'](player,button);};
  Engine.prototype.snapshot=function(){if(!this.nes)throw new Error('没有正在运行的游戏');var state=this.nes.toJSON();delete state.romData;return {schema:1,engine:'jsnes-2.1.0',hash:this.hash,frames:this.frames,state:state};};
  Engine.prototype.restore=function(save){if(!save||save.schema!==1||save.engine!=='jsnes-2.1.0'||save.hash!==this.hash||!save.state)throw new Error('存档与当前游戏版本不匹配');var previous=this.snapshot();
    try{save.state.romData=this.rom;this.nes.fromJSON(save.state);delete save.state.romData;this.frames=save.frames||0;this.releaseAll();this.read=this.write=this.buffered=0;}
    catch(e){previous.state.romData=this.rom;this.nes.loadROM(this.rom);this.nes.fromJSON(previous.state);this.releaseAll();throw new Error('存档无法读取，当前进度已保留');}
  };
  Engine.prototype.setVolume=function(v){this.volume=Math.max(0,Math.min(1,v));if(this.gain)this.gain.gain.value=this.volume;};
  Engine.prototype.thumbnail=function(){return this.canvas.toDataURL('image/png');};
  return Engine;
}));
