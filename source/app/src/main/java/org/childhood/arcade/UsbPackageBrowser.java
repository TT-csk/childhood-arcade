package org.childhood.arcade;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.Settings;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** No external file-manager dependency. Every import uses an issued read-only token. */
final class UsbPackageBrowser {
 static final int PERMISSION=184,TREE=185,ALL_FILES=186;
 private final MainActivity activity;
 private final HashMap<String,Entry> entries=new HashMap<String,Entry>();
 private volatile int generation=0;
 private volatile String state="{\"phase\":\"idle\",\"sources\":[],\"items\":[]}";
 private JSONArray sources=new JSONArray();
 private final BroadcastReceiver media=new BroadcastReceiver(){public void onReceive(Context c,Intent i){scan();}};
 private static final class Entry {
  final String token,source,name,parent; final File file,root; final Uri uri; final boolean directory;
  Entry(String token,String source,String name,String parent,File file,File root,Uri uri,boolean directory){this.token=token;this.source=source;this.name=name;this.parent=parent;this.file=file;this.root=root;this.uri=uri;this.directory=directory;}
 }
 UsbPackageBrowser(MainActivity a){activity=a;IntentFilter filter=new IntentFilter();for(String action:new String[]{Intent.ACTION_MEDIA_MOUNTED,Intent.ACTION_MEDIA_UNMOUNTED,Intent.ACTION_MEDIA_REMOVED,Intent.ACTION_MEDIA_EJECT,Intent.ACTION_MEDIA_BAD_REMOVAL})filter.addAction(action);filter.addDataScheme("file");if(Build.VERSION.SDK_INT>=33)a.registerReceiver(media,filter,Context.RECEIVER_NOT_EXPORTED);else a.registerReceiver(media,filter);}
 void close(){generation++;try{activity.unregisterReceiver(media);}catch(Exception ignored){}}
 String status(){return state;}
 private boolean permission(){return Build.VERSION.SDK_INT<23||activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED||(Build.VERSION.SDK_INT>=30&&Environment.isExternalStorageManager());}
 private JSONObject access()throws JSONException{return new JSONObject().put("readGranted",permission()).put("allFilesGranted",Build.VERSION.SDK_INT>=30&&Environment.isExternalStorageManager()).put("canAllFiles",Build.VERSION.SDK_INT>=30).put("canChooseDirectory",new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).resolveActivity(activity.getPackageManager())!=null);}
 private synchronized void publish(int ticket,JSONObject value){if(ticket!=generation)return;try{value.put("revision",ticket).put("sources",sources).put("access",access());state=value.toString();}catch(Exception ignored){}}
 private synchronized Entry add(int ticket,String source,String name,String parent,File f,File root,Uri uri,boolean directory)throws IOException{if(ticket!=generation)throw new IOException("扫描已更新");if(entries.size()>=16000)throw new IOException("目录记录过多，请重新扫描后继续");String token=UUID.randomUUID().toString();Entry e=new Entry(token,source,name,parent,f,root,uri,directory);entries.put(token,e);return e;}
 private JSONObject item(Entry e,long bytes,String relative)throws Exception{return new JSONObject().put("id",e.token).put("sourceId",e.source).put("name",e.name).put("kind",e.directory?"directory":"zip").put("bytes",bytes).put("location",relative).put("checked",false);}
 private JSONObject source(String id,String label,String kind,boolean readable)throws Exception{return new JSONObject().put("id",id).put("name",label).put("kind",kind).put("readable",readable);}
 private void rootFile(LinkedHashMap<String,File> roots,File f){if(f==null)return;try{File r=f.getCanonicalFile();if(r.isDirectory()&&!r.getPath().equals("/storage")&&!r.getPath().equals("/mnt"))roots.put(r.getPath(),r);}catch(Exception ignored){}}
 private LinkedHashMap<String,File> roots(){LinkedHashMap<String,File> roots=new LinkedHashMap<String,File>();rootFile(roots,Environment.getExternalStorageDirectory());
  if(Build.VERSION.SDK_INT>=24){StorageManager sm=(StorageManager)activity.getSystemService(Context.STORAGE_SERVICE);if(sm!=null)try{for(StorageVolume v:sm.getStorageVolumes()){if(Build.VERSION.SDK_INT>=30)rootFile(roots,v.getDirectory());else if(v.getUuid()!=null)rootFile(roots,new File("/storage",v.getUuid()));}}catch(Exception ignored){}}
  for(String mounted:new String[]{"/mnt/udisk","/mnt/usbhost1","/mnt/usbhost2","/udisk"})rootFile(roots,new File(mounted));
  for(String base:new String[]{"/storage","/mnt/usb","/mnt/usb_storage","/mnt/media_rw"}){File[] dirs=new File(base).listFiles();if(dirs!=null)for(File f:dirs){String n=f.getName();if(!Arrays.asList("emulated","self","legacy","sdcard0","sdcard","usb").contains(n)&&!(base.equals("/mnt/media_rw")&&new File("/storage",n).isDirectory()))rootFile(roots,f);}}
  return roots;
 }
 private String localRoot(){try{return Environment.getExternalStorageDirectory().getCanonicalPath();}catch(Exception e){return "";}}
 String scan(){final int ticket;synchronized(this){ticket=++generation;entries.clear();sources=new JSONArray();}try{publish(ticket,new JSONObject().put("phase","scanning").put("mode","scan").put("items",new JSONArray()).put("label","正在查找U盘和可访问目录…"));}catch(Exception ignored){}
  new Thread(new Runnable(){public void run(){try{JSONArray found=new JSONArray();HashSet<String> listed=new HashSet<String>();boolean limited=false;int unreadable=0;long until=System.nanoTime()+5000000000L;String local=localRoot();int removable=0;
   ArrayList<Entry> rootsToScan=new ArrayList<Entry>();ArrayList<File> discovered=new ArrayList<File>(roots().values());Collections.sort(discovered,new Comparator<File>(){public int compare(File a,File b){boolean al=a.getPath().equals(local),bl=b.getPath().equals(local);return al==bl?a.getPath().compareTo(b.getPath()):al?1:-1;}});
   for(File root:discovered){if(ticket!=generation)return;String kind=root.getCanonicalPath().equals(local)?"local":"removable",label=kind.equals("local")?"电视本地存储":"可移动存储 "+(++removable);Entry r=add(ticket,"",label,null,root,root,null,true);boolean readable=root.canRead()&&root.listFiles()!=null;synchronized(UsbPackageBrowser.this){if(ticket!=generation)return;sources.put(source(r.token,label,kind,readable));}if(readable)rootsToScan.add(r);else unreadable++;}
   for(Entry r:rootsToScan){if(ticket!=generation)return;if(found.length()>=300||System.nanoTime()>until){limited=true;break;}File root=r.root;long remaining=Math.max(1,(until-System.nanoTime())/1000000L);PackFileScanner.Result result=PackFileScanner.scan(root,3,6000,300-found.length(),remaining,new PackFileScanner.Stop(){public boolean stopped(){return ticket!=generation;}});limited|=result.limited;unreadable+=result.unreadable;
    for(File f:result.zips){if(ticket!=generation)return;if(!listed.add("file:"+f.getCanonicalPath()))continue;Entry e=add(ticket,r.token,f.getName(),r.token,f,root,null,false);JSONObject row=item(e,f.length(),r.name+" / "+relative(root,f));if(found.length()<20&&System.nanoTime()<until)peek(f,row);found.put(row);}
   }
   for(UriPermission grant:activity.getContentResolver().getPersistedUriPermissions()){if(ticket!=generation)return;if(!grant.isReadPermission()||!DocumentsContract.isTreeUri(grant.getUri()))continue;try{Uri tree=grant.getUri(),doc=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));String name=documentName(doc);Entry r=add(ticket,"",name,null,null,null,doc,true);synchronized(UsbPackageBrowser.this){if(ticket!=generation)return;sources.put(source(r.token,"已授权 · "+name,"granted",true));}limited|=scanTree(r,tree,found,ticket,until,listed);}catch(Exception e){unreadable++;}if(found.length()>=300){limited=true;break;}}
   publish(ticket,new JSONObject().put("phase","ready").put("mode","scan").put("items",found).put("limited",limited).put("unreadable",unreadable).put("removable",removable).put("label",found.length()>0?"找到 "+found.length()+" 个候选ZIP，选择后检查":"暂未找到ZIP，可浏览目录或重新扫描"));
  }catch(Exception e){fail(ticket,"扫描未完成："+message(e));}}},"usb-scan").start();return state;
 }
 private String relative(File root,File f)throws IOException{return f.getCanonicalPath().substring(root.getCanonicalPath().length()+1);}
 private void peek(File f,JSONObject row){try(ZipFile zip=new ZipFile(f)){ZipEntry entry=zip.getEntry("manifest.json");if(entry==null)entry=zip.getEntry("game-pack/manifest.json");if(entry==null||entry.getSize()>2097152)return;JSONObject m=new JSONObject(text(zip.getInputStream(entry),2097152));if(m.optInt("schema")==1&&(m.optString("kind").equals("game-resources")||(!m.has("kind")&&m.has("files"))))row.put("version",m.optString("version","旧版资源包")).put("manifestFound",true);}catch(Exception ignored){}}
 private String documentName(Uri doc)throws Exception{try(Cursor c=activity.getContentResolver().query(doc,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}return "资源目录";}
 private ArrayList<Entry> children(Entry parent,Uri tree,int ticket)throws Exception{
  ArrayList<Entry> result=new ArrayList<Entry>();if(parent.file!=null){if(!PackFileScanner.inside(parent.root,parent.file))throw new IOException("目录已变化，请重新扫描");File[] all=parent.file.listFiles();if(all==null)throw new IOException("目录不可读，请检查授权或U盘连接");Arrays.sort(all,PackFileScanner.ORDER);for(File f:all){if(ticket!=generation||result.size()>=400)break;if(!f.isHidden()&&PackFileScanner.inside(parent.root,f)&&(f.isDirectory()||PackFileScanner.zip(f)))result.add(add(ticket,parent.source.isEmpty()?parent.token:parent.source,f.getName(),parent.token,f,parent.root,null,f.isDirectory()));}}
  else{Uri base=tree==null?parent.uri:tree;String id=DocumentsContract.getDocumentId(parent.uri);Uri child=DocumentsContract.buildChildDocumentsUriUsingTree(base,id);try(Cursor cursor=activity.getContentResolver().query(child,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){if(cursor==null)throw new IOException("授权目录不可读");int examined=0;while(cursor.moveToNext()){if(ticket!=generation||result.size()>=400||++examined>6000)break;String name=cursor.getString(1),mime=cursor.getString(2);boolean dir=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);if(dir||name.toLowerCase(Locale.US).endsWith(".zip"))result.add(add(ticket,parent.source.isEmpty()?parent.token:parent.source,name,parent.token,null,null,DocumentsContract.buildDocumentUriUsingTree(base,cursor.getString(0)),dir));}}}
  return result;
 }
 private long size(Entry e){if(e.file!=null)return e.file.length();try(Cursor c=activity.getContentResolver().query(e.uri,new String[]{DocumentsContract.Document.COLUMN_SIZE},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getLong(0);}catch(Exception ignored){}return -1;}
 private boolean scanTree(Entry root,Uri tree,JSONArray found,int ticket,long until,Set<String> listed)throws Exception{ArrayDeque<Entry> dirs=new ArrayDeque<Entry>();ArrayDeque<Integer> depths=new ArrayDeque<Integer>();dirs.add(root);depths.add(0);int examined=0;boolean limit=false;while(!dirs.isEmpty()){if(ticket!=generation||System.nanoTime()>until||found.length()>=300)return true;Entry dir=dirs.remove();int depth=depths.remove();ArrayList<Entry> rows=children(dir,tree,ticket);if(rows.size()>=400)limit=true;for(Entry e:rows){if(++examined>6000)return true;if(e.directory){if(depth<3){dirs.add(e);depths.add(depth+1);}else limit=true;}else if(listed.add(documentIdentity(e.uri)))found.put(item(e,size(e),"已授权目录 / "+e.name));}}return limit;}
 private String documentIdentity(Uri uri){try{if("com.android.externalstorage.documents".equals(uri.getAuthority())){String[] parts=DocumentsContract.getDocumentId(uri).split(":",2);if(parts.length==2){File root=parts[0].equalsIgnoreCase("primary")?Environment.getExternalStorageDirectory():new File("/storage",parts[0]);return "file:"+new File(root,parts[1]).getCanonicalPath();}}}catch(Exception ignored){}return "uri:"+uri.toString();}
 String browse(final String token){final Entry dir;final int ticket;synchronized(this){dir=entries.get(token);ticket=++generation;}if(dir==null||!dir.directory){fail(ticket,"目录列表已变化，请重新扫描");return state;}try{publish(ticket,new JSONObject().put("phase","scanning").put("mode","folder").put("items",new JSONArray()).put("label","正在读取目录…"));}catch(Exception ignored){}
  new Thread(new Runnable(){public void run(){try{JSONArray list=new JSONArray();ArrayList<Entry> children=children(dir,null,ticket);for(Entry e:children)list.put(item(e,e.directory?0:size(e),e.name));publish(ticket,new JSONObject().put("phase","ready").put("mode","folder").put("directory",new JSONObject().put("id",dir.token).put("name",dir.name).put("parent",dir.parent==null?JSONObject.NULL:dir.parent)).put("items",list).put("limited",children.size()>=400).put("label",list.length()==0?"此目录没有文件夹或ZIP":"选择文件夹继续浏览，或选择ZIP检查"));}catch(Exception e){fail(ticket,message(e));}}},"usb-browse").start();return state;
 }
 String select(String token){Entry e;synchronized(this){e=entries.get(token);}if(e==null||e.directory)return error("文件列表已变化，请重新扫描");try{if(e.file!=null){if(!PackFileScanner.inside(e.root,e.file)||!e.file.isFile()||!e.file.canRead())throw new IOException("源文件不可读，可能已拔出U盘或授权已变化");}activity.resources.prepare(e.file!=null?Uri.fromFile(e.file):e.uri);return activity.resources.status();}catch(Exception x){return error(message(x));}}
 void request(final String mode){activity.runOnUiThread(new Runnable(){public void run(){try{if(mode.equals("directory")){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);activity.startActivityForResult(i,TREE);}else if(mode.equals("all-files")&&Build.VERSION.SDK_INT>=30){Intent i=new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+activity.getPackageName()));try{activity.startActivityForResult(i,ALL_FILES);}catch(ActivityNotFoundException noApp){activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),ALL_FILES);}}else if(Build.VERSION.SDK_INT>=23&&Build.VERSION.SDK_INT<=32&&!permission()){activity.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},PERMISSION);}else if(Build.VERSION.SDK_INT>=30&&!Environment.isExternalStorageManager()){request("all-files");}else scan();}catch(ActivityNotFoundException e){fail(generation,"系统未提供此授权入口。请使用已可读目录，或在电视系统设置中允许文件访问。");}}});}
 void permissionResult(boolean granted){if(granted)scan();else fail(generation,"读取存储的授权被拒绝，可重新授权；原游戏和存档保留");}
 void result(int request,int result,Intent data){if(request==ALL_FILES){if(Build.VERSION.SDK_INT>=30&&!Environment.isExternalStorageManager())fail(generation,"尚未允许文件访问，可使用已授权目录，或重新进入系统授权");else scan();return;}if(request!=TREE)return;if(result==MainActivity.RESULT_OK&&data!=null&&data.getData()!=null){try{activity.getContentResolver().takePersistableUriPermission(data.getData(),Intent.FLAG_GRANT_READ_URI_PERMISSION);scan();}catch(Exception e){fail(generation,"目录授权未能保存，请重新授权");}}else fail(generation,"已取消目录授权，可继续浏览已可访问的位置");}
 private void fail(int ticket,String message){try{publish(ticket,new JSONObject().put("phase","failed").put("items",new JSONArray()).put("error",message));}catch(Exception ignored){}}
 private static String error(String message){try{return new JSONObject().put("error",message).toString();}catch(Exception e){return "{}";}}
 private static String message(Exception e){return e.getMessage()==null?"文件读取失败，请检查U盘和权限":e.getMessage();}
 private static String text(InputStream stream,int limit)throws IOException{try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("清单过大");out.write(b,0,n);}return new String(out.toByteArray(),"UTF-8");}}
}
