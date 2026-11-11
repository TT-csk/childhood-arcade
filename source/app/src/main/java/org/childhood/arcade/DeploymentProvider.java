package org.childhood.arcade;
import android.content.*;import android.database.Cursor;import android.net.Uri;import android.os.*;import android.util.Base64;import org.json.*;import java.io.*;import java.security.MessageDigest;import java.util.zip.*;
/** ADB-only release transport. No debug flag, network server or arbitrary path API. */
public final class DeploymentProvider extends ContentProvider {
 private void shellOnly(){int uid=Binder.getCallingUid();if(uid!=2000&&uid!=0)throw new SecurityException("ADB shell required");}
 public boolean onCreate(){try{recoverRestore();return true;}catch(Exception e){throw new IllegalStateException("Restore recovery failed",e);}}
 private File root(){return getContext().getFilesDir();}
 private String hash(File file)throws Exception{MessageDigest h=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[262144];int n;while((n=in.read(b))!=-1)h.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:h.digest())s.append(String.format("%02x",b&255));return s.toString();}
 private JSONObject entry(String name)throws Exception{try(InputStream in=getContext().getAssets().open("deployment-resources.json")){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1)b.write(block,0,n);JSONArray files=new JSONObject(b.toString("UTF-8")).getJSONArray("files");for(int i=0;i<files.length();i++)if(files.getJSONObject(i).getString("name").equals(name))return files.getJSONObject(i);}throw new IOException("Unknown resource");}
 private File target(String name){String dir=name.endsWith(".gba")?"gba":name.endsWith(".md")?"md":name.endsWith(".sfc")?"sfc":"arcade";return new File(root(),dir+"/roms/"+name);}
 @Override public synchronized Bundle call(String method,String arg,Bundle extras){shellOnly();JSONObject result=new JSONObject();try{
  if("capabilities".equals(method)){result.put("protocol",1);}
  else if("restore".equals(method)){restore(arg);result.put("restored",true);}
  else if("health".equals(method)){android.content.SharedPreferences prefs=getContext().getSharedPreferences("deployment",0);if(prefs.getLong("health_at",0)<=0||android.os.SystemClock.elapsedRealtime()-prefs.getLong("health_at",0)<0||android.os.SystemClock.elapsedRealtime()-prefs.getLong("health_at",0)>5000)throw new IOException("Stale health");result=new JSONObject(prefs.getString("health","{}"));}
  else if("verify".equals(method)||"install".equals(method)){
   if(arg==null||!arg.matches("[a-z0-9-]{1,60}\\.(zip|gba|md|sfc)"))throw new IOException("Invalid resource name");JSONObject e=entry(arg);File dest=target(arg);String expected=e.getString("sha256");
   if(dest.exists()&&(!dest.isFile()||!hash(dest).equals(expected)))throw new IOException("Existing different resource preserved");
   if("install".equals(method)&&!dest.exists()){
    File source=new File("/data/local/tmp/childhood-arcade-"+expected+".zip");if(!source.isFile()||source.length()!=e.getLong("bytes")||!hash(source).equals(expected))throw new IOException("Staged resource mismatch");File folder=dest.getParentFile();if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Cannot create resource directory");File temp=File.createTempFile(".resource-",".part",folder);try{try(InputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(temp)){byte[] b=new byte[262144];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.getFD().sync();}if(!hash(temp).equals(expected)||!temp.renameTo(dest))throw new IOException("Resource commit failed");}finally{temp.delete();}
   }result.put("verified",dest.isFile()&&hash(dest).equals(expected)).put("bytes",dest.isFile()?dest.length():0).put("sha256",expected);
  }else throw new IOException("Unknown deployment method");
 }catch(Exception e){try{result.put("error",e.toString());}catch(Exception ignored){}}Bundle out=new Bundle();try{out.putString("result",Base64.encodeToString(result.toString().getBytes("UTF-8"),Base64.NO_WRAP));}catch(Exception e){throw new IllegalStateException(e);}return out;}
 private boolean allowed(String p){return p.matches("store/(prefs|slot:[a-f0-9]{64}:[0-3]|battery:[a-f0-9]{64})\\.json")||p.matches("nes/[a-f0-9]{64}\\.srm");}
 private void copy(File a,File b)throws Exception{if(a.isDirectory()){b.mkdirs();File[] children=a.listFiles();if(children!=null)for(File child:children)copy(child,new File(b,child.getName()));}else{b.getParentFile().mkdirs();try(InputStream in=new FileInputStream(a);FileOutputStream out=new FileOutputStream(b)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}}}
 private void remove(File f){if(f.isDirectory()){File[] children=f.listFiles();if(children!=null)for(File c:children)remove(c);}f.delete();}
 private void recoverRestore()throws Exception{
  File journal=new File(root(),"restore.journal");File stage=new File(root(),"restore.stage");
  if(journal.isFile()){
   String text;try(InputStream in=new FileInputStream(journal)){ByteArrayOutputStream b=new ByteArrayOutputStream();int c;while((c=in.read())!=-1){if(b.size()>1024)throw new IOException("Invalid restore journal");b.write(c);}text=b.toString("UTF-8");}JSONObject state=new JSONObject(text);
   for(String name:new String[]{"store","nes"}){File current=new File(root(),name),previous=new File(root(),name+".restore.previous");if(previous.exists()){remove(current);if(!previous.renameTo(current))throw new IOException("Cannot recover original data");}else if(!state.getBoolean(name)&&!new File(stage,name).exists())remove(current);}
   if(!journal.delete())throw new IOException("Cannot clear restore journal");
  }
  remove(stage);for(String name:new String[]{"store","nes"})remove(new File(root(),name+".restore.previous"));
 }
 private void restore(String expected)throws Exception{
  if(expected==null||!expected.matches("[a-f0-9]{64}"))throw new IOException("Invalid backup hash");File source=new File("/data/local/tmp/childhood-arcade-backup-"+expected+".zip");if(!source.isFile()||source.length()>256L*1024*1024||!hash(source).equals(expected))throw new IOException("Backup checksum mismatch");
  File stage=new File(root(),"restore.stage");if(stage.exists())throw new IOException("Previous restore stage requires inspection");stage.mkdirs();boolean first=false,second=false;
  try{
   for(String name:new String[]{"store","nes"}){File old=new File(root(),name);File prior=new File(root(),name+".restore.previous");if(prior.exists())throw new IOException("Previous rollback data exists");if(old.exists())copy(old,new File(stage,name));else new File(stage,name).mkdirs();}
   java.util.HashSet<String> seen=new java.util.HashSet<String>();long total=0;try(ZipInputStream z=new ZipInputStream(new FileInputStream(source))){ZipEntry e;while((e=z.getNextEntry())!=null){String name=e.getName();if(!allowed(name)||!seen.add(name)||seen.size()>1000)throw new IOException("Invalid backup entry");File dest=new File(stage,name);try(FileOutputStream out=new FileOutputStream(dest)){byte[] b=new byte[8192];int n;while((n=z.read(b))!=-1){total+=n;if(total>256L*1024*1024)throw new IOException("Backup too large");out.write(b,0,n);}out.getFD().sync();}}}
   File journal=new File(root(),"restore.journal");JSONObject original=new JSONObject();for(String name:new String[]{"store","nes"})original.put(name,new File(root(),name).exists());try(FileOutputStream out=new FileOutputStream(journal)){out.write(original.toString().getBytes("UTF-8"));out.getFD().sync();}
   for(String name:new String[]{"store","nes"}){File old=new File(root(),name),prior=new File(root(),name+".restore.previous");if(old.exists()&&!old.renameTo(prior))throw new IOException("Cannot stage original backup");if(name.equals("store"))first=true;else second=true;if(!new File(stage,name).renameTo(old))throw new IOException("Cannot commit restore");}
   if(!journal.delete())throw new IOException("Cannot commit restore journal");
   remove(new File(root(),"store.restore.previous"));remove(new File(root(),"nes.restore.previous"));
  }catch(Exception error){for(String name:new String[]{"store","nes"}){boolean moved=name.equals("store")?first:second;if(moved){File old=new File(root(),name),prior=new File(root(),name+".restore.previous");remove(old);if(prior.exists()&&!prior.renameTo(old))throw new IOException("Restore failed; rollback data preserved",error);}}File journal=new File(root(),"restore.journal");if(journal.exists()&&!journal.delete())throw new IOException("Cannot clear rollback journal",error);throw error;}finally{remove(stage);}
 }
 @Override public synchronized ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{shellOnly();if(!"/backup".equals(uri.getPath())||!"r".equals(mode))throw new FileNotFoundException("Read-only backup endpoint");try{
  File backup=File.createTempFile("backup-",".zip",getContext().getCacheDir());long total=0;try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(backup))){for(String dir:new String[]{"store","nes"}){File[] files=new File(root(),dir).listFiles();if(files==null)continue;for(File f:files){String name=dir+"/"+f.getName();if(!allowed(name)||!f.isFile())continue;total+=f.length();if(total>256L*1024*1024)throw new IOException("Backup too large");out.putNextEntry(new ZipEntry(name));try(InputStream in=new FileInputStream(f)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}out.closeEntry();}}}
  ParcelFileDescriptor fd=ParcelFileDescriptor.open(backup,ParcelFileDescriptor.MODE_READ_ONLY);backup.delete();return fd;
 }catch(Exception e){throw new FileNotFoundException(e.toString());}}
 public String getType(Uri u){return "application/octet-stream";}public Cursor query(Uri u,String[]p,String s,String[]a,String o){throw new SecurityException();}public Uri insert(Uri u,ContentValues v){throw new SecurityException();}public int delete(Uri u,String s,String[]a){throw new SecurityException();}public int update(Uri u,ContentValues v,String s,String[]a){throw new SecurityException();}
}
