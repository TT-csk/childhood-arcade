package org.childhood.arcade;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Bounded, read-only discovery shared by the TV browser and desktop tests. */
final class PackFileScanner {
 interface Stop { boolean stopped(); }
 static final class Result {
  final ArrayList<File> zips=new ArrayList<File>();
  int examined,unreadable; boolean limited;
 }
 private static final HashSet<String> OMIT=new HashSet<String>(Arrays.asList("android","lost.dir","lost+found","$recycle.bin","system volume information"));
 static boolean inside(File root,File entry)throws IOException {String r=root.getCanonicalPath(),p=entry.getCanonicalPath();return p.equals(r)||p.startsWith(r+File.separator);}
 static boolean zip(File f){return f.isFile()&&f.getName().toLowerCase(Locale.US).endsWith(".zip");}
 static Result scan(File root,int maxDepth,int maxEntries,int maxResults,long millis,Stop stop)throws IOException {
  Result out=new Result();ArrayDeque<File> dirs=new ArrayDeque<File>();ArrayDeque<Integer> depths=new ArrayDeque<Integer>();HashSet<String> seen=new HashSet<String>();dirs.add(root);depths.add(0);long until=System.nanoTime()+millis*1000000L;
  while(!dirs.isEmpty()){
   if(stop.stopped())break;if(System.nanoTime()>until){out.limited=true;break;}
   File dir=dirs.remove();int depth=depths.remove();if(!inside(root,dir)||!seen.add(dir.getCanonicalPath()))continue;
   File[] all=dir.listFiles();if(all==null){out.unreadable++;continue;}Arrays.sort(all,ORDER);
   for(File f:all){if(stop.stopped())return out;if(++out.examined>maxEntries||out.zips.size()>=maxResults||System.nanoTime()>until){out.limited=true;return out;}
    if(f.isHidden()||OMIT.contains(f.getName().toLowerCase(Locale.US))||!inside(root,f))continue;
    if(zip(f))out.zips.add(f);else if(f.isDirectory()){if(depth<maxDepth){dirs.add(f);depths.add(depth+1);}else out.limited=true;}
   }
  }return out;
 }
 static final Comparator<File> ORDER=new Comparator<File>(){public int compare(File a,File b){int x=priority(a),y=priority(b);return x!=y?x-y:a.getName().compareToIgnoreCase(b.getName());}};
 private static int priority(File f){if(f.isFile())return 0;String n=f.getName().toLowerCase(Locale.US);return Arrays.asList("download","downloads","下载","童年游戏厅","game-pack","games","游戏","资源").contains(n)?1:2;}
}
