package org.childhood.arcade;

import android.app.Activity;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.AtomicFile;
import android.util.Base64;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.HashMap;

public class MainActivity extends Activity implements InputManager.InputDeviceListener {
    private WebView web;
    private NativeNes nativeNes;
    private ValueCallback<Uri[]> chooser;
    private String pendingBackup;
    private InputManager inputs;
    private final HashMap<Integer, boolean[]> axes = new HashMap<Integer, boolean[]>();
    private final HashMap<Integer, Integer> players = new HashMap<Integer, Integer>();
    private File storeDir;
    private final Object storeLock = new Object();
    private static final int PICK_FILE=10, EXPORT_FILE=11;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();
        storeDir=new File(getFilesDir(),"store");
        File recovery=new File(getFilesDir(),"store.previous");
        if(!storeDir.exists()&&recovery.exists())recovery.renameTo(storeDir);
        storeDir.mkdirs();
        WebView.setWebContentsDebuggingEnabled(false);
        getSharedPreferences("deployment",0).edit().putBoolean("page_loaded",false).remove("health").remove("health_at").apply();
        web=new WebView(this);
        WebSettings settings=web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setTextZoom(100);
        web.setBackgroundColor(0x00000000);
        web.addJavascriptInterface(new Bridge(),"NativeArcade");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,String url){return !(url.startsWith("file:///android_asset/")||url.startsWith("https://appassets.androidplatform.net/assets/"));}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request){
                String address=request.getUrl().toString();
                if(address.startsWith("https://appassets.androidplatform.net/assets/")){
                    String asset=request.getUrl().getPath().substring("/assets/".length());
                    if(asset.contains("..")||asset.startsWith("/"))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                    String mime=asset.endsWith(".js")?"text/javascript":asset.endsWith(".css")?"text/css":asset.endsWith(".html")?"text/html":asset.endsWith(".wasm")?"application/wasm":asset.endsWith(".png")?"image/png":asset.endsWith(".ttf")?"font/ttf":"application/octet-stream";
                    try{return new WebResourceResponse(mime,(mime.startsWith("text/")?"UTF-8":null),getAssets().open(asset));}catch(IOException e){return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",null,new ByteArrayInputStream(new byte[0]));}
                }
                String scheme=request.getUrl().getScheme();
                if("http".equals(scheme)||"https".equals(scheme))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                return null;
            }
            @Override public void onPageFinished(WebView v,String url){hideSystemUI();if(url.equals("https://appassets.androidplatform.net/assets/index.html"))getSharedPreferences("deployment",0).edit().putBoolean("page_loaded",true).apply();}
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> callback,FileChooserParams params){
                if(chooser!=null)chooser.onReceiveValue(null);
                chooser=callback;
                Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,params.getMode()==FileChooserParams.MODE_OPEN_MULTIPLE);
                try{startActivityForResult(intent,PICK_FILE);}catch(ActivityNotFoundException e){chooser.onReceiveValue(null);chooser=null;Toast.makeText(MainActivity.this,"电视没有可用的文件选择器，可通过电脑部署游戏文件",Toast.LENGTH_LONG).show();}
                return true;
            }
        });
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(0xff100f0c);
        root.addView(web,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);nativeNes=new NativeNes(this,root);
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
        web.post(new Runnable(){public void run(){if(web==null)return;
            web.evaluateJavascript("(function(){var a=window.Arcade;return {title:document.title,ready:!!a,state:a?a.getState():null,catalogCount:window.GAME_CATALOG?window.GAME_CATALOG.length:0,visible:document.visibilityState};})()",new ValueCallback<String>(){public void onReceiveValue(String value){getSharedPreferences("deployment",0).edit().putString("health",value).putLong("health_at",android.os.SystemClock.elapsedRealtime()).apply();}});
            web.postDelayed(this,1000);
        }});
        inputs=(InputManager)getSystemService(INPUT_SERVICE);inputs.registerInputDeviceListener(this,null);
    }
    private void hideSystemUI(){getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}
    private void js(final String value){if(web!=null)web.post(new Runnable(){public void run(){if(web!=null)web.evaluateJavascript("if(window.Arcade){"+value+"}",null);}});}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)hideSystemUI();}
    @Override protected void onPause(){if(nativeNes!=null)nativeNes.pause();super.onPause();js("Arcade.lifecyclePause()");}
    @Override protected void onDestroy(){if(nativeNes!=null)nativeNes.close();if(inputs!=null)inputs.unregisterInputDeviceListener(this);if(web!=null){web.removeJavascriptInterface("NativeArcade");web.destroy();web=null;}super.onDestroy();}
    private boolean gamepad(KeyEvent event){return (event.getSource()&InputDevice.SOURCE_GAMEPAD)==InputDevice.SOURCE_GAMEPAD||(event.getSource()&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK;}
    private int player(int id){
        Integer existing=players.get(id);if(existing!=null)return existing;
        int value=!players.containsValue(1)?1:!players.containsValue(2)?2:0;players.put(id,value);
        js("Arcade.nativeRegister("+id+","+value+")");return value;
    }
    private int nesButton(int code){if(nativeNes!=null&&nativeNes.isGba()){if(code==102||code==104)return 10;if(code==103||code==105)return 11;}if(nativeNes!=null&&(nativeNes.isArcade()||nativeNes.isMd()||nativeNes.isSfc())){if(code==99)return 8;if(code==100)return 9;if(code==102||code==104)return 10;if(code==103||code==105)return 11;}switch(code){case 19:return 4;case 20:return 5;case 21:return 6;case 22:return 7;case 96:case 100:return 0;case 97:case 99:return 1;case 108:return 3;case 109:return 2;default:return -1;}}
    @Override public boolean dispatchKeyEvent(KeyEvent e){
        int c=e.getKeyCode();
        if(c==KeyEvent.KEYCODE_VOLUME_UP||c==KeyEvent.KEYCODE_VOLUME_DOWN||c==KeyEvent.KEYCODE_VOLUME_MUTE)return super.dispatchKeyEvent(e);
        boolean handle=c==4||c==19||c==20||c==21||c==22||c==23||c==66||c==82||c==96||c==97||c==99||c==100||c==108||c==109||c==110||c==102||c==103||c==104||c==105;
        if(handle&&(e.getAction()==KeyEvent.ACTION_DOWN||e.getAction()==KeyEvent.ACTION_UP)){
            boolean pad=gamepad(e);int p=pad?player(e.getDeviceId()):1,b=nesButton(c);
            if(nativeNes!=null&&nativeNes.isRunning()&&b>=0){if(p>0)nativeNes.button(p,b,e.getAction()==KeyEvent.ACTION_DOWN);return true;}
            js("Arcade.nativeKey("+c+","+(e.getAction()==KeyEvent.ACTION_DOWN)+","+e.getDeviceId()+","+gamepad(e)+","+(e.getRepeatCount()>0)+")");return true;
        }
        return super.dispatchKeyEvent(e);
    }
    // WebView consumes joystick motion before Activity.onGenericMotionEvent.
    // Intercept at dispatch so hats/sticks follow the same native path as buttons.
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e){
        if((e.getSource()&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK&&e.getAction()==MotionEvent.ACTION_MOVE){
            // Preserve digital hats, but suppress noisy analog rest values before merging.
            boolean[] old=axes.get(e.getDeviceId());if(old==null)old=new boolean[4];
            boolean[] now=ControllerDirections.update(e.getAxisValue(MotionEvent.AXIS_HAT_X),e.getAxisValue(MotionEvent.AXIS_HAT_Y),e.getAxisValue(MotionEvent.AXIS_X),e.getAxisValue(MotionEvent.AXIS_Y),old);
            int p=player(e.getDeviceId());
            for(int i=0;i<4;i++)if(now[i]!=old[i]){if(nativeNes!=null&&nativeNes.isRunning()){if(p>0)nativeNes.button(p,4+i,now[i]);}else js("Arcade.nativeKey("+(19+i)+","+now[i]+","+e.getDeviceId()+",true,false)");}
            axes.put(e.getDeviceId(),now);return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }
    @Override public void onInputDeviceAdded(int id){}
    @Override public void onInputDeviceChanged(int id){}
    @Override public void onInputDeviceRemoved(int id){axes.remove(id);Integer p=players.remove(id);if(p!=null&&p>0&&nativeNes!=null&&nativeNes.isRunning())nativeNes.pause();js("Arcade.nativeDisconnected("+id+")");}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==PICK_FILE&&chooser!=null){Uri[] uris=null;if(result==RESULT_OK&&data!=null){if(data.getClipData()!=null){int n=data.getClipData().getItemCount();uris=new Uri[n];for(int i=0;i<n;i++)uris[i]=data.getClipData().getItemAt(i).getUri();}else if(data.getData()!=null)uris=new Uri[]{data.getData()};}chooser.onReceiveValue(uris);chooser=null;}
        if(request==EXPORT_FILE){boolean ok=false;if(result==RESULT_OK&&data!=null&&data.getData()!=null&&pendingBackup!=null){try{OutputStream out=getContentResolver().openOutputStream(data.getData());out.write(pendingBackup.getBytes("UTF-8"));out.close();ok=true;}catch(Exception ignored){}}pendingBackup=null;js("Arcade.backupResult("+ok+")");}
    }
    private File keyFile(File dir,String key)throws IOException {if(key==null||!key.matches("[a-z0-9:_-]{1,160}"))throw new IOException("Invalid key");return new File(dir,key+".json");}
    private byte[] readAll(InputStream in,int max)throws IOException{try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>max)throw new IOException("Data too large");out.write(buf,0,n);}return out.toByteArray();}finally{in.close();}}
    private void atomicWrite(File f,String value)throws IOException {AtomicFile a=new AtomicFile(f);FileOutputStream out=null;try{out=a.startWrite();out.write(value.getBytes("UTF-8"));a.finishWrite(out);}catch(IOException e){if(out!=null)a.failWrite(out);throw e;}}
    private void deleteTree(File file){if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);}file.delete();}
    public class Bridge {
        @JavascriptInterface public boolean nativeNesAvailable(){return new File(getApplicationInfo().nativeLibraryDir,"libfceumm.so").isFile();}
        @JavascriptInterface public String nativeSfcStart(String name,String hash){return nativeNes.loadSfc(name,hash);}
        @JavascriptInterface public String nativeMdStart(String name,String hash){return nativeNes.loadMd(name,hash);}
        @JavascriptInterface public String nativeGbaStart(String name,String hash){return nativeNes.loadGba(name,hash);}
        @JavascriptInterface public String nativeArcadeStart(String name,String hash){return nativeNes.loadArcade(name,hash);}
        @JavascriptInterface public String nativeNesStart(String data,String hash){return nativeNes.load(data,hash);}
        @JavascriptInterface public void nativeNesPause(){nativeNes.pause();}
        @JavascriptInterface public void nativeNesResume(){nativeNes.resume();}
        @JavascriptInterface public void nativeNesClose(){nativeNes.close();}
        @JavascriptInterface public void nativeNesButton(int player,int button,boolean down){nativeNes.button(player,button,down);}
        @JavascriptInterface public void nativeNesRelease(){nativeNes.releaseAll();}
        @JavascriptInterface public void nativeNesVolume(float value){nativeNes.volume(value);}
        @JavascriptInterface public void nativeNesBounds(float left,float top,float width,float height,float viewportWidth,float viewportHeight){nativeNes.bounds(left,top,width,height,viewportWidth,viewportHeight);}
        @JavascriptInterface public String nativeNesSnapshot(){return nativeNes.snapshot();}
        @JavascriptInterface public String nativeNesRestore(String value){return nativeNes.restore(value);}
        @JavascriptInterface public String nativeNesImage(){return nativeNes.image();}
        @JavascriptInterface public String nativeNesStats(){return nativeNes.stats();}
        @JavascriptInterface public String readAsset(String path){if(path==null||!path.matches("roms/[a-z0-9-]+\\.nes"))return null;try{return Base64.encodeToString(readAll(getAssets().open(path),8*1024*1024),Base64.NO_WRAP);}catch(Exception e){return null;}}
        @JavascriptInterface public String readStore(String key){synchronized(storeLock){try{File f=keyFile(storeDir,key);if(!f.exists())return null;return new String(readAll(new AtomicFile(f).openRead(),32*1024*1024),"UTF-8");}catch(Exception e){return "{\"__readError\":true}";}}}
        @JavascriptInterface public boolean writeStore(String key,String value){synchronized(storeLock){try{if(value==null||value.length()>32*1024*1024)return false;new JSONObject("{\"value\":"+value+"}");atomicWrite(keyFile(storeDir,key),value);return true;}catch(Exception e){return false;}}}
        @JavascriptInterface public String listStoreKeys(){synchronized(storeLock){JSONArray list=new JSONArray();File[] files=storeDir.listFiles();if(files!=null)for(File f:files)if(f.getName().endsWith(".json"))list.put(f.getName().substring(0,f.getName().length()-5));return list.toString();}}
        @JavascriptInterface public boolean writeBatch(String json){synchronized(storeLock){File stage=new File(getFilesDir(),"store.stage"),old=new File(getFilesDir(),"store.previous");boolean moved=false;try{
            JSONArray entries=new JSONArray(json);if(entries.length()>1000)return false;deleteTree(stage);stage.mkdirs();File[] originals=storeDir.listFiles();if(originals!=null)for(File f:originals)if(f.isFile()&&f.getName().endsWith(".json"))atomicWrite(new File(stage,f.getName()),new String(readAll(new FileInputStream(f),32*1024*1024),"UTF-8"));
            for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);String key=entry.getString("key");if(!key.equals("prefs")&&!key.matches("slot:[a-f0-9]{64}:[0-3]|battery:[a-f0-9]{64}"))throw new IOException("Invalid backup key");atomicWrite(keyFile(stage,key),entry.getJSONObject("value").toString());}
            deleteTree(old);if(!storeDir.renameTo(old))throw new IOException("Cannot stage original");moved=true;if(!stage.renameTo(storeDir))throw new IOException("Cannot commit backup");deleteTree(old);return true;
        }catch(Exception e){if(moved&&!storeDir.exists())old.renameTo(storeDir);deleteTree(stage);return false;}}}
        @JavascriptInterface public void finishApp(){runOnUiThread(new Runnable(){public void run(){finish();}});}
        @JavascriptInterface public void exportBackup(final String json){runOnUiThread(new Runnable(){public void run(){if(json==null||json.length()>50*1024*1024){js("Arcade.backupResult(false)");return;}pendingBackup=json;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/json");intent.putExtra(Intent.EXTRA_TITLE,"童年游戏厅_存档备份.json");try{startActivityForResult(intent,EXPORT_FILE);}catch(ActivityNotFoundException e){pendingBackup=null;js("Arcade.backupResult(false)");Toast.makeText(MainActivity.this,"电视没有文件选择器，可通过电脑备份存档",Toast.LENGTH_LONG).show();}}});}
    }
}
