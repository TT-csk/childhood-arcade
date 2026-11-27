package org.childhood.arcade;
/** Installer decisions shared by Android transport and executable JVM checks. */
final class UpdateInstallPolicy {
    static boolean useLegacyFile(int api,boolean contentSupported){return api<24&&!contentSupported;}
    static long beforeDownload(long apkBytes){return apkBytes*4+64L*1024*1024;}
    static long beforeInstall(long apkBytes,long nativeBytes){return apkBytes+nativeBytes+64L*1024*1024;}
    static boolean installed(int current,int expected){return expected>0&&current>=expected;}
    static String incomplete(int code){
        String reason=code==-4?"系统报告安装空间不足":code==0?"系统未完成安装，可能已返回或安装器未接收文件":"系统安装未完成（错误码 "+code+"）";
        return reason+"；当前版本和备份保留，重试将优先复用已校验安装包";
    }
}
