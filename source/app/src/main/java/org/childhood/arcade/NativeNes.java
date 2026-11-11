package org.childhood.arcade;

import android.app.Activity;
import android.graphics.*;
import android.media.*;
import android.util.Base64;
import android.view.View;
import android.opengl.GLSurfaceView;
import android.opengl.GLES20;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.khronos.egl.EGLConfig;
import android.widget.FrameLayout;
import com.sun.jna.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.json.JSONObject;

/** Libretro host. Game execution and audio run outside the legacy WebView. */
public final class NativeNes {
    public static final String SFC_FAST_ENGINE="snes9x2005-native-7e15337d8caa";
    public static final String SFC_ENGINE="snes9x2005plus-native-ada4dbf17de5";
    public static final String MD_ENGINE="picodrive-native-c1cc15edda64";
    public static final String GBA_ENGINE="mgba-native-a976a807a7bd";
    public static final String ARCADE_ENGINE="fba2012-native-749e6f6079e0";
    public static final String ENGINE="fceumm-native-81ebd3e72d96";
    public interface CoreLog extends Callback {void callback(int level,String format);}
    private final CoreLog coreLog=new CoreLog(){public void callback(int level,String format){if(level>=2)android.util.Log.w("ArcadeCore",format);}};
    public interface Environment extends Callback {byte callback(int command,Pointer data);}
    public interface Video extends Callback {void callback(Pointer data,int width,int height,NativeLong pitch);}
    public interface Sample extends Callback {void callback(short left,short right);}
    public interface AudioBatch extends Callback {NativeLong callback(Pointer data,NativeLong frames);}
    public interface Poll extends Callback {void callback();}
    public interface Input extends Callback {short callback(int port,int device,int index,int id);}
    private final Activity activity;
    private final FrameLayout parent;
    private final Object lock=new Object(), imageLock=new Object();
    private final AtomicIntegerArray buttons=new AtomicIntegerArray(2);
    private final GameView view;
    private NativeLibrary core;
    private Function run,serialize,unserialize,stateSize;
    private Memory romMemory,gameInfo,pathMemory,directory;
    private Thread thread;
    private AudioTrack track;
    private volatile boolean running=false,stop=false,loaded=false;
    private volatile long frames=0,resumeFrames=0,resumeTime=0,audioFrames=0,drawFrames=0,lateFrames=0;
    private volatile double cpuMs=0,fps=60.0988;
    private String hash=null,error=null,engineId=ENGINE,library="libfceumm.so",loadedLibrary=null;
    private boolean arcade=false,handheld=false,megadrive=false,superNintendo=false;
    private final java.util.HashMap<String,Memory> coreOptions=new java.util.HashMap<String,Memory>();
    private float volume=.7f;
    private int pixelFormat=0,sampleRate=48000;
    private ByteBuffer picture;
    private byte[] packed;
    private int pictureWidth=0,pictureHeight=0,pictureBytes=2;
    private volatile long presentedFrames=0,resumePresented=0,inputSamples=0;
    private final AtomicLong inputStamp=new AtomicLong();
    private volatile double inputLatencyMs=0;
    private short[] sound=new short[4096];
    private File batteryFile,gameFile;
    private boolean initialized=false;
    private String lastBatteryDigest=null;
    private boolean batteryReady=false;
    private volatile int reportedSaveBytes=0;

    private final Environment environment=new Environment(){public byte callback(int cmd,Pointer p){
        switch(cmd&0xffff){
        case 27:p.setPointer(0,CallbackReference.getFunctionPointer(coreLog));return 1;
        case 3:p.setByte(0,(byte)1);return 1; // can duplicate video frames
        case 10:int format=p.getInt(0);if(format==1||format==2){pixelFormat=format;return 1;}return 0;
        case 9:case 31:case 30:p.setPointer(0,directory);return 1;
        case 15:String key=p.getPointer(0).getString(0);Memory value=coreOptions.get(key);p.setPointer(Native.POINTER_SIZE,value);return (byte)(value==null?0:1);
        case 16:int step=Native.POINTER_SIZE*2;for(int i=0;i<200;i++){Pointer keyPointer=p.getPointer((long)i*step);if(keyPointer==null)break;String k=keyPointer.getString(0),spec=p.getPointer((long)i*step+Native.POINTER_SIZE).getString(0);String selected=spec.substring(spec.indexOf(';')+1).trim().split("\\|")[0];if((k.equals("picodrive_input1")||k.equals("picodrive_input2"))&&spec.contains("6 button pad"))selected="6 button pad";if(k.toLowerCase().contains("neogeo")&&spec.contains("MVS"))selected="MVS";Memory text=new Memory(selected.getBytes().length+1);text.setString(0,selected);coreOptions.put(k,text);}return 1;
        case 11:case 35:case 37:return 1; // variables, input descriptors, controller/geometry info
        case 17:p.setByte(0,(byte)0);return 1; // options unchanged
        default:return 0;
        }
    }};
    private final Video video=new Video(){public void callback(Pointer data,int width,int height,NativeLong pitch){
        if(data==null||Pointer.nativeValue(data)==0)return;
        if(width<1||height<1||width>1024||height>1024)return;
        int bytes=pixelFormat==1?4:2;
        synchronized(imageLock){
            int length=width*height*bytes;
            if(picture==null||picture.capacity()!=length){picture=ByteBuffer.allocateDirect(length);packed=new byte[length];}
            pictureWidth=width;pictureHeight=height;pictureBytes=bytes;picture.position(0);
            int stride=pitch.intValue();
            if(stride==width*bytes)picture.put(data.getByteBuffer(0,length));
            else{for(int y=0;y<height;y++)data.read((long)y*stride,packed,y*width*bytes,width*bytes);picture.put(packed);}
            picture.position(0);
        }
        view.requestRender();drawFrames++;
    }};
    private final Sample sample=new Sample(){public void callback(short l,short r){if(track!=null&&running){short[] pair={l,r};track.write(pair,0,2);audioFrames++;}}};
    private final AudioBatch batch=new AudioBatch(){public NativeLong callback(Pointer data,NativeLong count){
        int n=count.intValue();if(n<0||n>16384)return new NativeLong(0);
        if(track!=null&&running&&n>0){
            if(sound.length<n*2)sound=new short[n*2];data.read(0,sound,0,n*2);
            int offset=0;while(offset<n*2&&running){int wrote=track.write(sound,offset,n*2-offset);if(wrote<=0)break;offset+=wrote;}
            audioFrames+=offset/2;
        }
        return count;
    }};
    private final Poll poll=new Poll(){public void callback(){long t=inputStamp.getAndSet(0);if(t>0){inputLatencyMs=(System.nanoTime()-t)/1e6;inputSamples++;}}};
    private final Input input=new Input(){public short callback(int port,int device,int index,int id){
        if(port<0||port>1||index!=0||device!=1)return 0;
        int mask=buttons.get(port);if(id==256)return (short)mask;
        return (short)((id>=0&&id<16&&(mask&(1<<id))!=0)?1:0);
    }};
    public NativeNes(Activity activity,FrameLayout parent){
        this.activity=activity;this.parent=parent;view=new GameView(activity);view.setVisibility(View.GONE);view.setFocusable(false);view.setClickable(false);parent.addView(view,0);
        String root=activity.getFilesDir().getAbsolutePath()+"/nes";new File(root).mkdirs();directory=new Memory(1024);directory.setString(0,root);
    }
    private Function fn(String name){return core.getFunction(name);}
    private void initLibrary(){
        if(core!=null&&library.equals(loadedLibrary))return;
        if(core!=null){core.close();core=null;}coreOptions.clear();
        System.setProperty("jna.boot.library.path",activity.getApplicationInfo().nativeLibraryDir);
        System.setProperty("jna.nosys","true");System.setProperty("jna.nounpack","true");
        core=NativeLibrary.getInstance(activity.getApplicationInfo().nativeLibraryDir+"/"+library);
        loadedLibrary=library;
        fn("retro_set_environment").invokeVoid(new Object[]{environment});fn("retro_set_video_refresh").invokeVoid(new Object[]{video});fn("retro_set_audio_sample").invokeVoid(new Object[]{sample});fn("retro_set_audio_sample_batch").invokeVoid(new Object[]{batch});fn("retro_set_input_poll").invokeVoid(new Object[]{poll});fn("retro_set_input_state").invokeVoid(new Object[]{input});
        run=fn("retro_run");serialize=fn("retro_serialize");unserialize=fn("retro_unserialize");stateSize=fn("retro_serialize_size");
    }
    public String load(String encoded,String expected){
        try{
            close();arcade=false;handheld=false;megadrive=false;superNintendo=false;engineId=ENGINE;library="libfceumm.so";directory.setString(0,activity.getFilesDir().getAbsolutePath()+"/nes");byte[] rom=Base64.decode(encoded,Base64.DEFAULT);
            if(rom.length>8*1024*1024||expected==null||!expected.matches("[a-f0-9]{64}")||!digest(rom).equals(expected))throw new IOException("游戏文件校验失败");
            synchronized(lock){
                initLibrary();fn("retro_init").invokeVoid(new Object[]{});initialized=true;
                romMemory=new Memory(rom.length);romMemory.write(0,rom,0,rom.length);gameFile=new File(activity.getCacheDir(),"running-game.nes");FileOutputStream romFile=new FileOutputStream(gameFile);romFile.write(rom);romFile.close();pathMemory=new Memory(1024);pathMemory.setString(0,gameFile.getAbsolutePath());int size=Native.POINTER_SIZE;gameInfo=new Memory(size*4);gameInfo.clear();gameInfo.setPointer(0,pathMemory);gameInfo.setPointer(size,romMemory);gameInfo.setNativeLong(size*2,new NativeLong(rom.length));
                if(fn("retro_load_game").invokeInt(new Object[]{gameInfo})==0)throw new IOException("当前游戏暂不受支持");
                finishLoad(expected);
            }
            return startThread();
        }catch(Throwable e){error=e.toString();try{close();}catch(Throwable ignored){}return error(e);}
    }
    public String loadArcade(String name,String expected){return loadExternal(name,expected,false,false,false);}
    public String loadGba(String name,String expected){return loadExternal(name,expected,true,false,false);}
    public String loadMd(String name,String expected){return loadExternal(name,expected,false,true,false);}
    public String loadSfc(String name,String expected){return loadExternal(name,expected,false,false,true);}
    private String loadExternal(String name,String expected,boolean gba,boolean md,boolean sfc){
        try{
            close();handheld=gba;megadrive=md;superNintendo=sfc;arcade=!gba&&!md&&!sfc;engineId=sfc?("sfc-kirby-superstar.sfc".equals(name)?SFC_FAST_ENGINE:SFC_ENGINE):md?MD_ENGINE:gba?GBA_ENGINE:ARCADE_ENGINE;library=sfc?("sfc-kirby-superstar.sfc".equals(name)?"libsnes9x2005.so":"libsnes9x2005plus.so"):md?"libpicodrive.so":gba?"libmgba.so":"libarcade.so";
            if(name==null||!name.matches(sfc?"[a-z0-9-]{1,60}\\.sfc":md?"[a-z0-9-]{1,60}\\.md":gba?"[a-z0-9-]{1,60}\\.gba":"[a-z0-9-]{1,60}\\.zip")||expected==null||!expected.matches("[a-f0-9]{64}"))throw new IOException((sfc?"SFC":md?"MD":gba?"GBA":"街机")+"目录无效");
            File folder=new File(activity.getFilesDir(),sfc?"sfc/roms":md?"md/roms":gba?"gba/roms":"arcade/roms");gameFile=new File(folder,name);
            if(!gameFile.isFile())throw new IOException((sfc?"SFC":md?"MD":gba?"GBA":"街机")+"资源尚未安装完整，请重新更新游戏厅");
            if(!fileDigest(gameFile).equals(expected))throw new IOException((sfc?"SFC":md?"MD":gba?"GBA":"街机")+"资源校验失败，请重新更新游戏厅");
            directory.setString(0,folder.getAbsolutePath());
            synchronized(lock){
                initLibrary();fn("retro_init").invokeVoid(new Object[]{});initialized=true;
                pathMemory=new Memory(1024);pathMemory.setString(0,gameFile.getAbsolutePath());int size=Native.POINTER_SIZE;gameInfo=new Memory(size*4);gameInfo.clear();gameInfo.setPointer(0,pathMemory);
                if(sfc){if(gameFile.length()<1||gameFile.length()>16*1024*1024)throw new IOException("SFC游戏大小无效");byte[] data=new byte[(int)gameFile.length()];try(java.io.DataInputStream in=new java.io.DataInputStream(new FileInputStream(gameFile))){in.readFully(data);}romMemory=new Memory(data.length);romMemory.write(0,data,0,data.length);gameInfo.setPointer(size,romMemory);gameInfo.setNativeLong(size*2,new NativeLong(data.length));}
                if(fn("retro_load_game").invokeInt(new Object[]{gameInfo})==0)throw new IOException(sfc?"SFC游戏文件或内核不匹配":md?"MD游戏文件或内核不匹配":gba?"GBA游戏文件或内核不匹配":"街机文件或BIOS与内核不匹配");
                finishLoad(expected);
                int bytes=((NativeLong)stateSize.invoke(NativeLong.class,new Object[]{})).intValue();if(bytes<=0)throw new IOException("当前游戏未能完成初始化");
            }
            return startThread();
        }catch(Throwable e){error=e.toString();try{close();}catch(Throwable ignored){}return error(e);}
    }
    private void finishLoad(String expected)throws Exception{
        loaded=true;hash=expected;lastBatteryDigest=null;batteryReady=false;reportedSaveBytes=0;frames=audioFrames=drawFrames=lateFrames=0;error=null;
        Memory av=new Memory(64);av.clear();fn("retro_get_system_av_info").invokeVoid(new Object[]{av});fps=av.getDouble(24);double rate=av.getDouble(32);
        if((Double.isNaN(fps)||Double.isInfinite(fps))||fps<40||fps>70)throw new IOException("游戏时钟异常");
        if((Double.isNaN(rate)||Double.isInfinite(rate))||rate<8000||rate>96000)throw new IOException("声音时钟异常");sampleRate=(int)Math.round(rate);
        int min=AudioTrack.getMinBufferSize(sampleRate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
        track=new AudioTrack(AudioManager.STREAM_MUSIC,sampleRate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,(int)(sampleRate/fps)*8),AudioTrack.MODE_STREAM);
        if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException("游戏声音初始化失败");track.setVolume(volume);
        batteryFile=new File(activity.getFilesDir(),"nes/"+hash+".srm");loadBattery();batteryReady=true;
        // Produce a first picture without advancing audible output.
        run.invokeVoid(new Object[]{});frames=1;releaseAll();
        for(int player=0;player<2;player++)fn("retro_set_controller_port_device").invokeVoid(new Object[]{player,1});
    }
    private String startThread(){stop=false;thread=new Thread(new Runnable(){public void run(){loop();}},"Retro-native");thread.start();return "{\"ok\":true,\"engine\":\""+engineId+"\"}";}
    private void loop(){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        long next=0;final long period=(long)(1000000000.0/fps);
        try{while(!stop){
            if(!running){next=0;Thread.sleep(3);continue;}
            long begin=System.nanoTime();
            synchronized(lock){if(!running||!loaded)continue;run.invokeVoid(new Object[]{});frames++;if((handheld||megadrive||superNintendo)&&frames%300==0)saveBattery();}
            long now=System.nanoTime();cpuMs=cpuMs*.95+(now-begin)/1000000.0*.05;
            if(next==0)next=begin;next+=period;
            if(now>next+period){lateFrames++;next=now;}
            long sleep=next-System.nanoTime();if(sleep>0)Thread.sleep(sleep/1000000,(int)(sleep%1000000));
        }}catch(Throwable e){error=e.toString();running=false;hide();}
    }
    public void resume(){synchronized(lock){if(!loaded||running)return;releaseAll();track.play();resumeTime=System.nanoTime();resumeFrames=frames;resumePresented=presentedFrames;running=true;}activity.runOnUiThread(new Runnable(){public void run(){view.setVisibility(View.VISIBLE);}});}
    public void pause(){running=false;synchronized(lock){releaseAll();if(track!=null){track.pause();track.flush();}if(loaded)saveBattery();}hide();}
    private void hide(){activity.runOnUiThread(new Runnable(){public void run(){view.setVisibility(View.GONE);}});}
    public void close(){
        running=false;stop=true;hide();Thread old=thread;thread=null;
        if(old!=null&&old!=Thread.currentThread()){try{old.join(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}if(old.isAlive())throw new IllegalStateException("游戏尚未停止");}
        synchronized(lock){releaseAll();if(loaded){saveBattery();fn("retro_unload_game").invokeVoid(new Object[]{});}if(initialized){fn("retro_deinit").invokeVoid(new Object[]{});initialized=false;}loaded=false;if(gameFile!=null){if(!arcade&&!handheld&&!megadrive&&!superNintendo)gameFile.delete();gameFile=null;}hash=null;if(track!=null){track.pause();track.flush();track.release();track=null;}romMemory=gameInfo=pathMemory=null;if((arcade||handheld||megadrive||superNintendo)&&core!=null){core.close();core=null;loadedLibrary=null;coreOptions.clear();}}
    }
    public void releaseAll(){buttons.set(0,0);buttons.set(1,0);}
    public boolean isRunning(){return running;}
    public boolean isArcade(){return arcade;}
    public boolean isGba(){return handheld;}
    public boolean isMd(){return megadrive;}
    public boolean isSfc(){return superNintendo;}
    public void button(int player,int button,boolean down){if(down)inputStamp.set(System.nanoTime());if(handheld&&player!=1)return;int[] mapping=megadrive?new int[]{1,0,2,3,4,5,6,7,8,9,10,11}:(arcade||superNintendo)?new int[]{0,8,2,3,4,5,6,7,1,9,10,11}:handheld?new int[]{8,0,2,3,4,5,6,7,1,9,10,11}:new int[]{8,0,2,3,4,5,6,7};if(player<1||player>2||button<0||button>=mapping.length)return;int index=player-1,mask=1<<mapping[button],old,now;do{old=buttons.get(index);now=down?(old|mask):(old&~mask);}while(!buttons.compareAndSet(index,old,now));}
    public void volume(float value){volume=Math.max(0,Math.min(1,value));synchronized(lock){if(track!=null)track.setVolume(volume);}}
    public void bounds(final float left,final float top,final float width,final float height,final float viewportWidth,final float viewportHeight){if(width<1||height<1)return;activity.runOnUiThread(new Runnable(){public void run(){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(Math.round(width*parent.getWidth()/viewportWidth),Math.round(height*parent.getHeight()/viewportHeight));p.leftMargin=Math.round(left*parent.getWidth()/viewportWidth);p.topMargin=Math.round(top*parent.getHeight()/viewportHeight);view.setLayoutParams(p);}});}
    public String snapshot(){
        synchronized(lock){try{if(!loaded)throw new IOException("没有正在运行的游戏");int n=((NativeLong)stateSize.invoke(NativeLong.class,new Object[]{})).intValue();if(n<1||n>8*1024*1024)throw new IOException("存档大小异常");Memory state=new Memory(n);if(serialize.invokeInt(new Object[]{state,new NativeLong(n)})==0)throw new IOException("保存进度失败");byte[] bytes=state.getByteArray(0,n);JSONObject obj=new JSONObject();obj.put("schema",1);obj.put("engine",engineId);obj.put("hash",hash);obj.put("frames",frames);JSONObject data=new JSONObject();data.put("format","libretro-base64");data.put("data",Base64.encodeToString(bytes,Base64.NO_WRAP));data.put("bytes",n);data.put("sha256",digest(bytes));obj.put("state",data);return obj.toString();}catch(Throwable e){return error(e);}}
    }
    public String restore(String json){
        synchronized(lock){try{if(!loaded)throw new IOException("没有正在运行的游戏");JSONObject save=new JSONObject(json),s=save.getJSONObject("state");if(save.getInt("schema")!=1||!engineId.equals(save.getString("engine"))||!hash.equals(save.getString("hash"))||!"libretro-base64".equals(s.getString("format")))throw new IOException("存档与当前游戏版本不匹配");byte[] bytes=Base64.decode(s.getString("data"),Base64.DEFAULT);int n=((NativeLong)stateSize.invoke(NativeLong.class,new Object[]{})).intValue();if(bytes.length!=n||bytes.length!=s.getInt("bytes")||!digest(bytes).equals(s.getString("sha256")))throw new IOException("存档校验失败，当前进度已保留");Memory previous=new Memory(n);if(serialize.invokeInt(new Object[]{previous,new NativeLong(n)})==0)throw new IOException("无法保护当前进度");Memory data=new Memory(n);data.write(0,bytes,0,n);if(unserialize.invokeInt(new Object[]{data,new NativeLong(n)})==0){unserialize.invokeInt(new Object[]{previous,new NativeLong(n)});throw new IOException("存档无法读取，当前进度已保留");}frames=save.getLong("frames");releaseAll();if(track!=null){track.pause();track.flush();}return "{\"ok\":true}";}catch(Throwable e){return error(e);}}
    }
    public String image(){synchronized(imageLock){if(picture==null)return null;Bitmap bitmap=Bitmap.createBitmap(pictureWidth,pictureHeight,pictureBytes==4?Bitmap.Config.ARGB_8888:Bitmap.Config.RGB_565);bitmap.setHasAlpha(false);picture.position(0);bitmap.copyPixelsFromBuffer(picture);picture.position(0);ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);bitmap.recycle();return "data:image/png;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);}}
    public String stats(){try{JSONObject out=new JSONObject();out.put("engine",engineId);out.put("reportedSaveBytes",reportedSaveBytes);out.put("running",running);out.put("loaded",loaded);out.put("frames",frames);out.put("fps",running&&resumeTime>0?(frames-resumeFrames)*1e9/(System.nanoTime()-resumeTime):0);out.put("targetFps",fps);out.put("frameAndAudioMs",cpuMs);out.put("audioFrames",audioFrames);out.put("drawFrames",drawFrames);out.put("presentedFrames",presentedFrames);out.put("presentationFps",running&&resumeTime>0?(presentedFrames-resumePresented)*1e9/(System.nanoTime()-resumeTime):0);out.put("inputLatencyMs",inputLatencyMs);out.put("inputSamples",inputSamples);out.put("pixelFormat",pixelFormat);out.put("audioPlayedFrames",track==null?0:(track.getPlaybackHeadPosition()&0xffffffffL));out.put("lateFrames",lateFrames);out.put("sampleRate",sampleRate);out.put("buttons1",buttons.get(0));out.put("buttons2",buttons.get(1));out.put("error",error);return out.toString();}catch(Exception e){return "{}";}}
    private File handheldBatteryFile(){return new File(new File(activity.getFilesDir(),"store"),"battery:"+hash+".json");}
    private void saveHandheldBattery(){try{int n=((NativeLong)fn("retro_get_memory_size").invoke(NativeLong.class,new Object[]{0})).intValue();reportedSaveBytes=n;if(handheld)n=GbaSaveMemory.capacity(n);if(n<1||n>1048576||hash==null)return;Pointer data=(Pointer)fn("retro_get_memory_data").invoke(Pointer.class,new Object[]{0});if(data==null)return;byte[] bytes=data.getByteArray(0,n);String sum=digest(bytes);if(sum.equals(lastBatteryDigest))return;JSONObject value=new JSONObject().put("schema",1).put("format",superNintendo?"sfc-sram-base64":megadrive?"md-sram-base64":"gba-sram-base64").put("hash",hash).put("engine",engineId).put("bytes",n).put("sha256",sum).put("data",Base64.encodeToString(bytes,Base64.NO_WRAP));File target=handheldBatteryFile();target.getParentFile().mkdirs();android.util.AtomicFile file=new android.util.AtomicFile(target);FileOutputStream out=null;try{out=file.startWrite();out.write(value.toString().getBytes("UTF-8"));file.finishWrite(out);lastBatteryDigest=sum;}catch(Exception e){file.failWrite(out);throw e;}}catch(Throwable e){error="游戏内存档未能写入，原文件已保留："+e.toString();}}
    private void loadHandheldBattery()throws Exception{File file=handheldBatteryFile();if(!file.exists())return;if(file.length()>1500000)throw new IOException("游戏内存档过大，原文件已保留");ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new android.util.AtomicFile(file).openRead()){byte[] chunk=new byte[8192];int n;while((n=in.read(chunk))>0)out.write(chunk,0,n);}JSONObject value=new JSONObject(new String(out.toByteArray(),"UTF-8"));if(value.getInt("schema")!=1||!value.getString("format").equals(superNintendo?"sfc-sram-base64":megadrive?"md-sram-base64":"gba-sram-base64")||!value.getString("hash").equals(hash)||!value.getString("engine").equals(engineId))throw new IOException("游戏内存档来自不同版本或内核，原文件已保留");byte[] bytes=Base64.decode(value.getString("data"),Base64.DEFAULT);int n=((NativeLong)fn("retro_get_memory_size").invoke(NativeLong.class,new Object[]{0})).intValue();reportedSaveBytes=n;if(value.getInt("bytes")!=bytes.length||!digest(bytes).equals(value.getString("sha256")))throw new IOException("游戏内存档校验失败，原文件已保留");if(handheld){bytes=GbaSaveMemory.expand(bytes,n);n=bytes.length;}else if(bytes.length!=n||n<1||n>1048576)throw new IOException("游戏内存档大小不匹配，原文件已保留");Pointer memory=(Pointer)fn("retro_get_memory_data").invoke(Pointer.class,new Object[]{0});if(memory==null)throw new IOException("游戏存档内存未就绪");memory.write(0,bytes,0,n);lastBatteryDigest=value.getString("sha256");}
    private void saveBattery(){if(!batteryReady)return;if(handheld||megadrive||superNintendo){saveHandheldBattery();return;}try{int n=((NativeLong)fn("retro_get_memory_size").invoke(NativeLong.class,new Object[]{0})).intValue();if(n<=0||n>1024*1024||batteryFile==null)return;Pointer p=(Pointer)fn("retro_get_memory_data").invoke(Pointer.class,new Object[]{0});if(p==null)return;android.util.AtomicFile file=new android.util.AtomicFile(batteryFile);FileOutputStream stream=null;try{stream=file.startWrite();stream.write(p.getByteArray(0,n));file.finishWrite(stream);}catch(IOException e){file.failWrite(stream);}}catch(Throwable ignored){}}
    private void loadBattery()throws Exception{if(handheld||megadrive||superNintendo){loadHandheldBattery();return;}try{if(batteryFile==null||!batteryFile.exists())return;int n=((NativeLong)fn("retro_get_memory_size").invoke(NativeLong.class,new Object[]{0})).intValue();if(n<1||n>1024*1024)return;byte[] data=new byte[n];FileInputStream in=new android.util.AtomicFile(batteryFile).openRead();int count=0,k;while(count<n&&(k=in.read(data,count,n-count))>0)count+=k;in.close();if(count==n){Pointer p=(Pointer)fn("retro_get_memory_data").invoke(Pointer.class,new Object[]{0});if(p!=null)p.write(0,data,0,n);}}catch(Throwable ignored){}}
    private static String fileDigest(File file)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[262144];int n;while((n=in.read(b))>0)md.update(b,0,n);}StringBuilder text=new StringBuilder();for(byte value:md.digest())text.append(String.format("%02x",value&255));return text.toString();}
    private static String digest(byte[] bytes)throws Exception{byte[] d=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder s=new StringBuilder();for(byte b:d)s.append(String.format("%02x",b&255));return s.toString();}
    private static String error(Throwable e){try{return new JSONObject().put("error",e.getMessage()==null?e.toString():e.getMessage()).toString();}catch(Exception ignored){return "{\"error\":\"游戏运行失败\"}";}}
    private final class GameView extends GLSurfaceView implements GLSurfaceView.Renderer {
        private final FloatBuffer vertices=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final FloatBuffer texture=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        private int program,tex,position,uv,isXrgb,textureWidth=0,textureHeight=0,textureBytes=0;
        GameView(Activity context){
            super(context);setEGLContextClientVersion(2);setPreserveEGLContextOnPause(true);
            vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);texture.put(new float[]{0,1,1,1,0,0,1,0}).position(0);
            setRenderer(this);setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        }
        private int shader(int type,String source){int shader=GLES20.glCreateShader(type);GLES20.glShaderSource(shader,source);GLES20.glCompileShader(shader);int[] ok={0};GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader));return shader;}
        public void onSurfaceCreated(GL10 gl,EGLConfig config){
            program=GLES20.glCreateProgram();int vs=shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 a;attribute vec2 t;varying vec2 v;void main(){gl_Position=vec4(a,0.,1.);v=t;}");int fs=shader(GLES20.GL_FRAGMENT_SHADER,"precision mediump float;uniform sampler2D image;uniform int xrgb;varying vec2 v;void main(){vec4 c=texture2D(image,v);gl_FragColor=xrgb==1?vec4(c.b,c.g,c.r,1.):vec4(c.rgb,1.);}");
            GLES20.glAttachShader(program,vs);GLES20.glAttachShader(program,fs);GLES20.glLinkProgram(program);GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs);GLES20.glUseProgram(program);position=GLES20.glGetAttribLocation(program,"a");uv=GLES20.glGetAttribLocation(program,"t");isXrgb=GLES20.glGetUniformLocation(program,"xrgb");int[] ids={0};GLES20.glGenTextures(1,ids,0);tex=ids[0];GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,tex);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT,1);textureWidth=textureHeight=textureBytes=0;
        }
        public void onSurfaceChanged(GL10 gl,int width,int height){GLES20.glViewport(0,0,width,height);}
        public void onDrawFrame(GL10 gl){
            GLES20.glClearColor(0,0,0,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);GLES20.glUseProgram(program);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,tex);
            synchronized(imageLock){if(picture==null)return;picture.position(0);int format=pictureBytes==2?GLES20.GL_RGB:GLES20.GL_RGBA,type=pictureBytes==2?GLES20.GL_UNSIGNED_SHORT_5_6_5:GLES20.GL_UNSIGNED_BYTE;
                if(textureWidth!=pictureWidth||textureHeight!=pictureHeight||textureBytes!=pictureBytes){GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,format,pictureWidth,pictureHeight,0,format,type,picture);textureWidth=pictureWidth;textureHeight=pictureHeight;textureBytes=pictureBytes;}
                else GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,pictureWidth,pictureHeight,format,type,picture);picture.position(0);
            }
            GLES20.glUniform1i(isXrgb,pictureBytes==4?1:0);vertices.position(0);texture.position(0);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,vertices);GLES20.glEnableVertexAttribArray(position);GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,0,texture);GLES20.glEnableVertexAttribArray(uv);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);presentedFrames++;
        }
    }
}
