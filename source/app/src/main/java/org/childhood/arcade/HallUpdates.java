package org.childhood.arcade;
import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import org.json.*;
import javax.net.ssl.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.net.URL;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.Enumeration;

final class HallUpdates {
    private static volatile SSLSocketFactory compatibleTls;
    static void configureTls(Activity a){try{
        TrustManagerFactory system=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());system.init((KeyStore)null);
        KeyStore extra=KeyStore.getInstance(KeyStore.getDefaultType());extra.load(null,null);try(InputStream in=a.getAssets().open("isrg-root-x1.pem")){extra.setCertificateEntry("isrg-root-x1",CertificateFactory.getInstance("X.509").generateCertificate(in));}
        TrustManagerFactory supplemental=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());supplemental.init(extra);
        final X509TrustManager primary=trustManager(system),fallback=trustManager(supplemental);
        X509TrustManager combined=new X509TrustManager(){public X509Certificate[] getAcceptedIssuers(){X509Certificate[] x=primary.getAcceptedIssuers(),y=fallback.getAcceptedIssuers(),z=Arrays.copyOf(x,x.length+y.length);System.arraycopy(y,0,z,x.length,y.length);return z;}public void checkClientTrusted(X509Certificate[] c,String t)throws CertificateException{primary.checkClientTrusted(c,t);}public void checkServerTrusted(X509Certificate[] c,String t)throws CertificateException{try{primary.checkServerTrusted(c,t);}catch(CertificateException e){fallback.checkServerTrusted(c,t);}}};
        SSLContext context=SSLContext.getInstance("TLS");context.init(null,new TrustManager[]{combined},null);compatibleTls=context.getSocketFactory();
    }catch(Exception e){compatibleTls=null;}}
    private static X509TrustManager trustManager(TrustManagerFactory f)throws IOException{for(TrustManager t:f.getTrustManagers())if(t instanceof X509TrustManager)return (X509TrustManager)t;throw new IOException("系统证书验证不可用");}
    private static HttpsURLConnection connect(URL url)throws IOException{HttpsURLConnection c=(HttpsURLConnection)url.openConnection();SSLSocketFactory tls=compatibleTls;if(tls!=null)c.setSSLSocketFactory(tls);return c;}
    interface Progress {void received(long bytes,long total);}
    private static volatile String job="{\"phase\":\"idle\"}";
    static String status(Activity a){try{android.content.SharedPreferences p=a.getSharedPreferences("hall-update",0);int expected=p.getInt("expected",0);if(expected>0&&a.getPackageManager().getPackageInfo(a.getPackageName(),0).versionCode>=expected)return new JSONObject().put("phase","installed").put("version",p.getString("version","")).toString();String phase=p.getString("phase","");if(phase.equals("awaiting_permission")||phase.equals("awaiting_installation")||phase.equals("cancelled")||phase.equals("failed")){JSONObject o=new JSONObject().put("phase",phase);String error=p.getString("error",null);if(error!=null)o.put("error",error);return o.toString();}return job;}catch(Exception e){return "{\"phase\":\"failed\",\"error\":\"无法核对安装版本\"}";}}
    static void phase(Activity a,String phase,String error){a.getSharedPreferences("hall-update",0).edit().putString("phase",phase).putString("error",error).commit();try{JSONObject o=new JSONObject().put("phase",phase);if(error!=null)o.put("error",error);job=o.toString();}catch(Exception ignored){}}
    private static final AtomicBoolean preparing=new AtomicBoolean();
    static void begin(final Activity a,final String json){
        if(!preparing.compareAndSet(false,true))return;
        phase(a,"downloading",null);
        new Thread(new Runnable(){public void run(){try{String result=prepare(a,json);JSONObject r=new JSONObject(result);if(r.has("error"))phase(a,"failed",r.getString("error"));}catch(Exception e){phase(a,"failed","更新准备失败，备份已保留");}finally{preparing.set(false);}}},"hall-update").start();
    }
    private static Intent installIntent(Uri uri){
        Intent i=new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri,"application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.putExtra(Intent.EXTRA_RETURN_RESULT,true);
        return i;
    }
    private static boolean available(Activity a,Intent i){return !a.getPackageManager().queryIntentActivities(i,PackageManager.MATCH_DEFAULT_ONLY).isEmpty();}
    static void install(final Activity a){
        new Thread(new Runnable(){public void run(){try{
            File ready=readyFile(a);
            if(!ready.isFile())throw new IOException("已下载的安装包不存在，请重新准备更新");
            Intent candidate=installIntent(Uri.parse("content://"+a.getPackageName()+".updates/hall-update.apk"));
            if(!available(a,candidate)){
                // file:// is allowed only before Android 7; modern devices keep URI grants.
                if(!UpdateInstallPolicy.useLegacyFile(android.os.Build.VERSION.SDK_INT,false))throw new IOException("系统没有可接收安装包的安装器，请使用对应APK手动覆盖更新");
                // Legacy downloads already live in this app's external updates directory.
                File copy=ready;
                candidate=installIntent(Uri.fromFile(copy));
                if(!available(a,candidate))throw new IOException("系统没有可接收APK文件的安装器");
            }
            requireInstallSpace(ready);
            final Intent selected=candidate;
            a.runOnUiThread(new Runnable(){public void run(){try{
                if(android.os.Build.VERSION.SDK_INT>=26){boolean allowed=(Boolean)PackageManager.class.getMethod("canRequestPackageInstalls").invoke(a.getPackageManager());if(!allowed){phase(a,"awaiting_permission",null);a.startActivityForResult(new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES",Uri.parse("package:"+a.getPackageName())),44);return;}}
                android.util.Log.i("HallUpdates","Opening system installer: "+selected.getData().getScheme());
                phase(a,"awaiting_installation",null);a.startActivityForResult(selected,43);
            }catch(Exception e){android.util.Log.e("HallUpdates","Installer launch failed",e);phase(a,"failed","无法打开系统安装："+e.getClass().getSimpleName()+"；备份已保留");}}});
        }catch(Exception e){android.util.Log.e("HallUpdates","Installer preparation failed",e);phase(a,"failed",e.getMessage());}}},"hall-installer").start();
    }
    static void result(Activity a,int request,int result,Intent data){
        if(request==44){try{if(android.os.Build.VERSION.SDK_INT>=26&&(Boolean)PackageManager.class.getMethod("canRequestPackageInstalls").invoke(a.getPackageManager()))install(a);else phase(a,"failed","安装权限尚未开启，当前版本和备份保留");}catch(Exception e){phase(a,"failed","无法核对安装权限，备份已保留");}}
        else if(request==43){try{
            int expected=a.getSharedPreferences("hall-update",0).getInt("expected",0);
            if(UpdateInstallPolicy.installed(a.getPackageManager().getPackageInfo(a.getPackageName(),0).versionCode,expected))return;
            int installResult=data==null?0:data.getIntExtra("android.intent.extra.INSTALL_RESULT",0);
            android.util.Log.w("HallUpdates","Installer returned without target version: activity="+result+", package="+installResult);
            phase(a,"failed",UpdateInstallPolicy.incomplete(installResult));
        }catch(Exception e){phase(a,"failed","未能核对目标版本，当前数据和备份保留");}}
    }
    static File updateDirectory(android.content.Context c)throws IOException{
        File dir=android.os.Build.VERSION.SDK_INT<24?c.getExternalFilesDir("updates"):new File(c.getFilesDir(),"updates");
        if(dir==null||(!dir.isDirectory()&&!dir.mkdirs()))throw new IOException("无法准备安装目录");
        return dir;
    }
    static File readyFile(android.content.Context c)throws IOException{return new File(updateDirectory(c),"hall-update.apk");}
    private static void requireDownloadSpace(File dir,long bytes)throws IOException{
        long needed=UpdateInstallPolicy.beforeDownload(bytes);
        if(dir.getUsableSpace()<needed)throw new IOException(spaceMessage(dir.getUsableSpace(),needed));
    }
    private static void requireInstallSpace(File apk)throws IOException{
        long nativeBytes=0;try(ZipFile zip=new ZipFile(apk)){Enumeration<? extends ZipEntry> entries=zip.entries();while(entries.hasMoreElements()){ZipEntry e=entries.nextElement();if(e.getName().startsWith("lib/")&&e.getName().endsWith(".so")){if(e.getSize()<0)throw new IOException("无法核对核心解压空间");nativeBytes+=e.getSize();}}}
        long needed=UpdateInstallPolicy.beforeInstall(apk.length(),nativeBytes);
        if(apk.getParentFile().getUsableSpace()<needed)throw new IOException(spaceMessage(apk.getParentFile().getUsableSpace(),needed));
    }
    private static String spaceMessage(long free,long needed){return "安装空间不足：当前可用约 "+(free/1048576)+" MB，预计还需至少 "+((needed+1048575)/1048576)+" MB；包含系统安装和核心解压空间。备份和已校验APK保留，腾出空间后重试";}
    static void complete(Activity a){
        // Only called after the new package, resources and personal data have been verified.
        try{File dir=updateDirectory(a);new File(dir,"hall-update.apk").delete();new File(dir,"update.part").delete();}catch(IOException ignored){}
        new File(a.getCacheDir(),"hall-update.apk").delete();new File(a.getCacheDir(),"update.part").delete();
        a.getSharedPreferences("hall-update",0).edit().remove("expected").remove("phase").remove("error").commit();job="{\"phase\":\"done\"}";
    }
    static long downloadFile(String address,File file,long max)throws Exception{return downloadFile(address,file,max,null);}
    static long downloadFile(String address,File file,long max,Progress progress)throws Exception{
        URL url=new URL(address);if(!url.getProtocol().equals("https"))throw new IOException("仅接受HTTPS下载");
        for(int redirect=0;redirect<6;redirect++){HttpsURLConnection c=connect(url);c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);try{int code=c.getResponseCode();if(code>=300&&code<400){URL next=new URL(url,c.getHeaderField("Location"));if(!next.getProtocol().equals("https"))throw new IOException("不安全重定向");url=next;continue;}if(code!=200)throw new IOException("下载失败："+code);long total=0,last=0;try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(file)){byte[] buf=new byte[32768];int n;while((n=in.read(buf))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("下载已中断");total+=n;if(total>max)throw new IOException("下载文件超过限制");out.write(buf,0,n);if(progress!=null&&(total-last>=524288||total==max)){progress.received(total,max);last=total;}}out.getFD().sync();}return total;}finally{c.disconnect();}}
        throw new IOException("下载重定向次数过多");
    }
    static String fileDigest(File file)throws Exception{return streamDigest(new FileInputStream(file));}
    static String streamDigest(InputStream source)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=source){byte[] buf=new byte[32768];int n;while((n=in.read(buf))!=-1)md.update(buf,0,n);}StringBuilder s=new StringBuilder();for(byte b:md.digest())s.append(String.format("%02x",b&255));return s.toString();}
    static byte[] download(String address,int max)throws Exception{
        URL url=new URL(address);if(!url.getProtocol().equals("https"))throw new IOException("仅接受HTTPS下载");
        for(int redirect=0;redirect<6;redirect++){HttpsURLConnection c=connect(url);c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);try{int code=c.getResponseCode();if(code>=300&&code<400){URL next=new URL(url,c.getHeaderField("Location"));if(!next.getProtocol().equals("https"))throw new IOException("不安全的重定向");url=next;continue;}if(code!=200)throw new IOException("下载失败："+code);if(c.getContentLength()>max)throw new IOException("下载文件过大");try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[32768];int n;while((n=in.read(buf))!=-1){if(out.size()+n>max)throw new IOException("下载文件超过限制");out.write(buf,0,n);}return out.toByteArray();}}finally{c.disconnect();}}
        throw new IOException("下载重定向次数过多");
    }
    static String digest(byte[] bytes)throws Exception{byte[] d=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder s=new StringBuilder();for(byte b:d)s.append(String.format("%02x",b&255));return s.toString();}
    static void validateArchive(PackageInfo next,PackageInfo current,String packageName)throws IOException{if(next==null||!packageName.equals(next.packageName)||next.versionCode<=current.versionCode||next.signatures==null||next.signatures.length==0||!Arrays.equals(next.signatures,current.signatures))throw new IOException("安装包身份、版本或签名不匹配");}
    static String prepare(final Activity activity,String json){File stage=null;try{
        File directory=updateDirectory(activity);stage=new File(directory,"update.part");
        JSONObject release=new JSONObject(json);long length=release.getLong("bytes");if(length<1||length>268435456)throw new IOException("APK大小无效");
        File cached=readyFile(activity);
        if(cached.isFile()&&cached.length()==length&&fileDigest(cached).equals(release.getString("sha256"))){
            PackageManager manager=activity.getPackageManager();PackageInfo next=manager.getPackageArchiveInfo(cached.getAbsolutePath(),PackageManager.GET_SIGNATURES);
            validateArchive(next,manager.getPackageInfo(activity.getPackageName(),PackageManager.GET_SIGNATURES),activity.getPackageName());
            activity.getSharedPreferences("hall-update",0).edit().putInt("expected",next.versionCode).putString("version",release.getString("version")).commit();
            phase(activity,"preparing_installation",null);install(activity);return "{\"phase\":\"preparing_installation\"}";
        }
        requireDownloadSpace(directory,length);long received=downloadFile(release.getString("url"),stage,length,new Progress(){public void received(long bytes,long total){try{job=new JSONObject().put("phase","downloading").put("label","正在下载APK："+(bytes*100/total)+"%（"+(bytes/1048576)+" MB）").toString();}catch(Exception ignored){}}});if(received!=length||!fileDigest(stage).equals(release.getString("sha256")))throw new IOException("APK校验失败，备份已保留");
        PackageManager pm=activity.getPackageManager();PackageInfo next=pm.getPackageArchiveInfo(stage.getAbsolutePath(),PackageManager.GET_SIGNATURES),current=pm.getPackageInfo(activity.getPackageName(),PackageManager.GET_SIGNATURES);
        validateArchive(next,current,activity.getPackageName());
        File ready=readyFile(activity);if(ready.exists()&&!ready.delete())throw new IOException("无法准备安装文件");if(!stage.renameTo(ready))throw new IOException("无法提交安装文件");
        activity.getSharedPreferences("hall-update",0).edit().putInt("expected",next.versionCode).putString("version",release.getString("version")).commit();
        install(activity);
        return "{\"phase\":\"awaiting_installation\"}";
    }catch(Exception e){if(stage!=null)stage.delete();return "{\"error\":"+JSONObject.quote(e.getMessage())+"}";}}
}
