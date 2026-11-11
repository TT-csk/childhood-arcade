package org.childhood.arcade;
import java.io.*;import java.security.MessageDigest;import org.json.JSONObject;
/** Selected-app resource installation; no network, game extraction, or unrelated file access. */
public final class ResourceTool {
 
 private static String digest(File file)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[262144];int n;while((n=in.read(b))>0)md.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:md.digest())s.append(String.format("%02x",b&255));return s.toString();}
 public static void main(String[]args){try{
  if(args.length<3||(args.length<4&&args[0].equals("install"))||(!args[0].equals("install")&&!args[0].equals("verify")))throw new IOException("Invalid resource command");
  String name=args[0].equals("install")?args[2]:args[1],expected=args[0].equals("install")?args[3]:args[2];
  if(!name.matches("[a-z0-9-]{1,60}\\.(zip|gba|md|sfc)")||!expected.matches("[a-f0-9]{64}"))throw new IOException("Invalid resource identity");
  File ROOT=new File("/data/data/org.childhood.arcade/files/"+(name.endsWith(".sfc")?"sfc":name.endsWith(".md")?"md":name.endsWith(".gba")?"gba":"arcade")+"/roms");ROOT.mkdirs();File target=new File(ROOT,name);boolean existing=target.isFile();String current=existing?digest(target):null;
  if(args[0].equals("verify")){System.out.println("RESOURCE_JSON "+new JSONObject().put("verified",expected.equals(current)).put("bytes",existing?target.length():0));return;}
  if(existing&&!expected.equals(current))throw new IOException("Different resource version already exists; existing data preserved");
  if(!existing){File source=new File(args[1]).getCanonicalFile();String path=source.getPath();if(!path.startsWith("/data/local/tmp/childhood-arcade-")&&!path.startsWith("/data/local/tmp/arcade-native-bench/roms/"))throw new IOException("Invalid staging path");File part=new File(ROOT,name+".part");try{try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(part)){byte[] b=new byte[262144];int n;long total=0;while((n=in.read(b))>0){total+=n;if(total>256L*1024*1024)throw new IOException("Resource too large");out.write(b,0,n);}}if(!expected.equals(digest(part)))throw new IOException("Resource checksum mismatch");if(!part.renameTo(target))throw new IOException("Atomic resource installation failed");}finally{part.delete();}}
  System.out.println("RESOURCE_JSON "+new JSONObject().put("verified",true).put("status",existing?"already_verified":"installed").put("bytes",target.length()).put("sha256",expected));
 }catch(Throwable e){try{System.out.println("RESOURCE_JSON "+new JSONObject().put("error",e.toString()));}catch(Exception ignored){}System.exit(2);}}
}
