package org.childhood.arcade;
import java.io.*;import java.util.*;
/** Finds legacy raw NES saves without deleting or replacing them. */
final class LegacyNesSaves {
 static final class Item{final String hash;final byte[] data;Item(String h,byte[] d){hash=h;data=d;}}
 static java.util.List<Item> read(File nesDir,File storeDir)throws IOException{
  ArrayList<Item> result=new ArrayList<Item>();if(!nesDir.exists())return result;File[] files=nesDir.listFiles();if(files==null)throw new IOException("无法检查旧游戏内存档");HashSet<String> seen=new HashSet<String>();
  for(File f:files){String name=f.getName();if(!name.matches("[a-f0-9]{64}\\.srm(?:\\.bak)?"))continue;String hash=name.substring(0,64);if(!seen.add(hash))continue;File target=new File(storeDir,"battery:"+hash+".json");if(target.exists()||new File(target.getPath()+".bak").exists())continue;File primary=new File(nesDir,hash+".srm"),backup=new File(primary.getPath()+".bak");File src=backup.isFile()?backup:primary;if(!src.isFile()||src.length()<1||src.length()>1048576)throw new IOException("旧游戏内存档大小异常，备份已停止");ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(src)){byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1){out.write(block,0,n);if(out.size()>1048576)throw new IOException("旧游戏内存档过大，备份已停止");}}result.add(new Item(hash,out.toByteArray()));}
  return result;
 }
}
