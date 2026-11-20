package org.childhood.arcade;
import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Export-only fallback for older TVs that have no document picker. */
final class LegacyBackupExporter {
 static final int PERMISSION=84;
 private final MainActivity activity;
 LegacyBackupExporter(MainActivity a){activity=a;}
 void start(){if(Build.VERSION.SDK_INT>=29){activity.legacyBackupFinished(false);return;}if(Build.VERSION.SDK_INT>=23&&activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},PERMISSION);return;}roots();}
 void permissionResult(boolean granted){if(granted)roots();else activity.legacyBackupFinished(false);}
 private void roots(){final ArrayList<File> dirs=new ArrayList<File>();File local=Environment.getExternalStorageDirectory();if(local!=null&&local.isDirectory())dirs.add(local);File[] extra=new File("/storage").listFiles();if(extra!=null)for(File f:extra){String n=f.getName();if(f.isDirectory()&&!n.equals("emulated")&&!n.equals("self")&&!n.equals("sdcard0"))dirs.add(f);}if(dirs.isEmpty()){activity.legacyBackupFinished(false);return;}CharSequence[] labels=new CharSequence[dirs.size()];for(int i=0;i<dirs.size();i++)labels[i]=(i==0?"电视本地存储":"存储设备 "+i)+" · "+dirs.get(i).getPath();new AlertDialog.Builder(activity).setTitle("选择备份保存位置").setItems(labels,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){browse(dirs.get(n),dirs.get(n));}}).setNegativeButton("取消",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){activity.legacyBackupFinished(false);}}).setOnCancelListener(new DialogInterface.OnCancelListener(){public void onCancel(DialogInterface d){activity.legacyBackupFinished(false);}}).show();}
 private void browse(final File dir,final File root){File[] all=dir.listFiles();if(all==null){activity.legacyBackupFinished(false);return;}final ArrayList<File> dirs=new ArrayList<File>();for(File f:all)if(!f.isHidden()&&f.isDirectory())dirs.add(f);Collections.sort(dirs,new Comparator<File>(){public int compare(File a,File b){return a.getName().compareToIgnoreCase(b.getName());}});final boolean up=!dir.equals(root);CharSequence[] labels=new CharSequence[1+(up?1:0)+dirs.size()];labels[0]="保存到当前文件夹";if(up)labels[1]="返回上一级";for(int i=0;i<dirs.size();i++)labels[1+(up?1:0)+i]="[文件夹] "+dirs.get(i).getName();new AlertDialog.Builder(activity).setTitle("选择备份文件夹").setItems(labels,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){if(n==0){save(dir);return;}if(up&&n==1){browse(dir.getParentFile(),root);return;}browse(dirs.get(n-1-(up?1:0)),root);}}).setNegativeButton("返回位置",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){roots();}}).setOnCancelListener(new DialogInterface.OnCancelListener(){public void onCancel(DialogInterface d){activity.legacyBackupFinished(false);}}).show();}
 private void save(File dir){boolean ok=false;File file=null;try{String data=activity.legacyBackupData();if(data==null)throw new Exception();String base="童年游戏厅_备份_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());for(int n=0;n<100;n++){File candidate=new File(dir,base+(n==0?"":"_"+n)+".json");if(candidate.createNewFile()){file=candidate;break;}}if(file==null)throw new Exception();try(FileOutputStream out=new FileOutputStream(file)){out.write(data.getBytes("UTF-8"));out.getFD().sync();}ok=true;}catch(Exception e){if(file!=null)file.delete();}activity.legacyBackupFinished(ok);}
}
