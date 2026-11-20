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

/** Read-only fallback for older TVs without Android's document picker. */
final class LegacyPackPicker {
 static final int PERMISSION=83;
 private final MainActivity activity;
 LegacyPackPicker(MainActivity activity){this.activity=activity;}
 void start(){
  if(Build.VERSION.SDK_INT>=29){activity.resources.fail("电视没有可用的文件选择器，请安装兼容文件管理器后重试");return;}
  if(Build.VERSION.SDK_INT>=23&&activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){activity.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},PERMISSION);return;}
  roots();
 }
 void permissionResult(boolean granted){if(granted)roots();else activity.resources.fail("未允许读取存储，无法选择资源包");}
 private void roots(){
  final ArrayList<File> dirs=new ArrayList<File>();File local=Environment.getExternalStorageDirectory();if(local!=null&&local.isDirectory())dirs.add(local);
  File[] storage=new File("/storage").listFiles();if(storage!=null)for(File f:storage){String n=f.getName();if(f.isDirectory()&&!n.equals("emulated")&&!n.equals("self")&&!n.equals("sdcard0"))dirs.add(f);}
  if(dirs.isEmpty()){activity.resources.fail("没有找到可读取的存储设备，请连接U盘后重试");return;}
  CharSequence[] labels=new CharSequence[dirs.size()];for(int i=0;i<dirs.size();i++)labels[i]=(i==0?"电视本地存储":"存储设备 "+i)+" · "+dirs.get(i).getPath();
  new AlertDialog.Builder(activity).setTitle("选择资源包位置").setItems(labels,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){browse(dirs.get(n),dirs.get(n));}}).setNegativeButton("取消",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){activity.resources.cancel();}}).setOnCancelListener(new DialogInterface.OnCancelListener(){public void onCancel(DialogInterface d){activity.resources.cancel();}}).show();
 }
 private void browse(final File dir,final File root){
  File[] all=dir.listFiles();if(all==null){activity.resources.fail("无法读取此位置，请检查存储权限或U盘");return;}
  final ArrayList<File> files=new ArrayList<File>();for(File f:all)if(!f.isHidden()&&(f.isDirectory()||(f.isFile()&&f.getName().toLowerCase(java.util.Locale.US).endsWith(".zip"))))files.add(f);
  Collections.sort(files,new Comparator<File>(){public int compare(File a,File b){if(a.isDirectory()!=b.isDirectory())return a.isDirectory()?-1:1;return a.getName().compareToIgnoreCase(b.getName());}});
  final boolean up=!dir.equals(root);CharSequence[] names=new CharSequence[files.size()+(up?1:0)];if(up)names[0]="返回上一级";for(int i=0;i<files.size();i++)names[i+(up?1:0)]=(files.get(i).isDirectory()?"[文件夹]  ":"[ZIP]  ")+files.get(i).getName();
  new AlertDialog.Builder(activity).setTitle(files.isEmpty()?"此位置没有资源ZIP": "选择资源ZIP").setItems(names,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){if(up&&n==0){browse(dir.getParentFile(),root);return;}File f=files.get(n-(up?1:0));if(f.isDirectory())browse(f,root);else activity.resources.prepare(Uri.fromFile(f));}}).setNegativeButton("返回位置",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){roots();}}).setOnCancelListener(new DialogInterface.OnCancelListener(){public void onCancel(DialogInterface d){activity.resources.cancel();}}).show();
 }
}
