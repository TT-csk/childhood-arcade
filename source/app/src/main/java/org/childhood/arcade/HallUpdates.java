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
    static void begin(final Activity a,final String json){phase(a,"downloading",null);new Thread(new Runnable(){public void run(){String result=prepare(a,json);try{JSONObject r=new JSONObject(result);if(r.has("error"))phase(a,"failed",r.getString("error"));}catch(Exception ignored){}}},"hall-update").start();}
    static void install(final Activity a){a.runOnUiThread(new Runnable(){public void run(){try{if(android.os.Build.VERSION.SDK_INT>=26){boolean allowed=(Boolean)PackageManager.class.getMethod("canRequestPackageInstalls").invoke(a.getPackageManager());if(!allowed){phase(a,"awaiting_permission",null);Intent permission=new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES",Uri.parse("package:"+a.getPackageName()));a.startActivityForResult(permission,44);return;}}Intent i=new Intent(Intent.ACTION_VIEW);i.setDataAndType(Uri.parse("content://"+a.getPackageName()+".updates/hall-update.apk"),"application/vnd.android.package-archive");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);i.putExtra(Intent.EXTRA_RETURN_RESULT,true);phase(a,"awaiting_installation",null);a.startActivityForResult(i,43);}catch(Exception e){phase(a,"failed","无法打开系统安装，请检查安装权限；备份已保留");}}});}
    static void result(Activity a,int request,int result){if(request==44){try{if(android.os.Build.VERSION.SDK_INT>=26&&(Boolean)PackageManager.class.getMethod("canRequestPackageInstalls").invoke(a.getPackageManager()))install(a);else phase(a,"cancelled",null);}catch(Exception e){phase(a,"failed","安装权限未开启");}}else if(request==43&&result!=Activity.RESULT_OK)phase(a,"cancelled",null);}
    static void complete(Activity a){a.getSharedPreferences("hall-update",0).edit().remove("expected").remove("phase").remove("error").commit();job="{\"phase\":\"done\"}";}
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
    static String prepare(final Activity activity,String json){File stage=new File(activity.getCacheDir(),"update.part");try{
        JSONObject release=new JSONObject(json);long length=release.getLong("bytes");if(length<1||length>268435456)throw new IOException("APK大小无效");if(activity.getCacheDir().getUsableSpace()<length+10485760)throw new IOException("本机剩余空间不足，备份已保留");long received=downloadFile(release.getString("url"),stage,length,new Progress(){public void received(long bytes,long total){try{job=new JSONObject().put("phase","downloading").put("label","正在下载APK："+(bytes*100/total)+"%（"+(bytes/1048576)+" MB）").toString();}catch(Exception ignored){}}});if(received!=length||!fileDigest(stage).equals(release.getString("sha256")))throw new IOException("APK校验失败，备份已保留");
        PackageManager pm=activity.getPackageManager();PackageInfo next=pm.getPackageArchiveInfo(stage.getAbsolutePath(),PackageManager.GET_SIGNATURES),current=pm.getPackageInfo(activity.getPackageName(),PackageManager.GET_SIGNATURES);
        validateArchive(next,current,activity.getPackageName());
        File ready=new File(activity.getCacheDir(),"hall-update.apk");if(ready.exists()&&!ready.delete())throw new IOException("无法准备安装文件");if(!stage.renameTo(ready))throw new IOException("无法提交安装文件");
        activity.getSharedPreferences("hall-update",0).edit().putInt("expected",next.versionCode).putString("version",release.getString("version")).commit();
        install(activity);
        return "{\"phase\":\"awaiting_installation\"}";
    }catch(Exception e){stage.delete();return "{\"error\":"+JSONObject.quote(e.getMessage())+"}";}}
}
