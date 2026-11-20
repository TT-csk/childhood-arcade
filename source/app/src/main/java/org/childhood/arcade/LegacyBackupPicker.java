package org.childhood.arcade;
import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
/** JSON backup file selection for older TVs without a document picker. */
final class LegacyBackupPicker {
 static final int PERMISSION=85;private final MainActivity a;LegacyBackupPicker(MainActivity a){this.a=a;}
 void start(){if(Build.VERSION.SDK_INT>=29){a.legacyFileFinished(null);return;}if(Build.VERSION.SDK_INT>=23&&a.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){a.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},PERMISSION);return;}roots();}
 void permissionResult(boolean granted){if(granted)roots();else a.legacyFileFinished(null);}
 private void roots(){final ArrayList<File> dirs=new ArrayList<File>();File local=Environment.getExternalStorageDirectory();if(local!=null&&local.isDirectory())dirs.add(local);File[] other=new File("/storage").listFiles();if(other!=null)for(File f:other){String n=f.getName();if(f.isDirectory()&&!n.equals("emulated")&&!n.equals("self")&&!n.equals("sdcard0"))dirs.add(f);}if(dirs.isEmpty()){a.legacyFileFinished(null);return;}CharSequence[] labels=new CharSequence[dirs.size()];for(int i=0;i<dirs.size();i++)labels[i]=(i==0?"电视本地存储":"存储设备 "+i)+" · "+dirs.get(i).getPath();new AlertDialog.Builder(a).setTitle("选择备份文件位置").setItems(labels,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){browse(dirs.get(n),dirs.get(n));}}).setNegativeButton("取消",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){a.legacyFileFinished(null);}}).setOnCancelListener(new DialogInterface.OnCancelListener(){public void onCancel(DialogInterface d){a.legacyFileFinished(null);}}).show();}
 private void browse(final File dir,final File root){File[] all=dir.listFiles();if(all==null){a.legacyFileFinished(null);return;}final ArrayList<File> files=new ArrayList<File>();for(File f:all)if(!f.isHidden()&&(f.isDirectory()||(f.isFile()&&f.getName().toLowerCase(Locale.US).endsWith(".json"))))files.add(f);Collections.sort(files,new Comparator<File>(){public int compare(File x,File y){if(x.isDirectory()!=y.isDirectory())return x.isDirectory()?-1:1;return x.getName().compareToIgnoreCase(y.getName());}});final boolean up=!dir.equals(root);CharSequence[] names=new CharSequence[files.size()+(up?1:0)];if(up)names[0]="返回上一级";for(int i=0;i<files.size();i++)names[i+(up?1:0)]=(files.get(i).isDirectory()?"[文件夹] ":"[备份] ")+files.get(i).getName();new AlertDialog.Builder(a).setTitle(files.isEmpty()?"此位置没有备份文件":"选择备份文件").setItems(names,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){if(up&&n==0){browse(dir.getParentFile(),root);return;}File f=files.get(n-(up?1:0));if(f.isDirectory())browse(f,root);else a.legacyFileFinished(Uri.fromFile(f));}}).setNegativeButton("返回位置",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){roots();}}).setOnCancelListener(new DialogInterface.OnCancelListener(){public void onCancel(DialogInterface d){a.legacyFileFinished(null);}}).show();}
}
