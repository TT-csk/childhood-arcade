package org.childhood.arcade;
/** Binary directions for retro games. Measured SHANWAN idle bias reached -0.53. */
public final class ControllerDirections {
 private static final float PRESS=.65f, RELEASE=.60f;
 private ControllerDirections(){}
 private static float finite(float v){return Float.isNaN(v)||Float.isInfinite(v)?0:v;}
 private static boolean negative(float value,boolean held){return value<-(held?RELEASE:PRESS);}
 private static boolean positive(float value,boolean held){return value>(held?RELEASE:PRESS);}
 public static boolean[] update(float hatX,float hatY,float stickX,float stickY,boolean[] old){
  hatX=finite(hatX);hatY=finite(hatY);stickX=finite(stickX);stickY=finite(stickY);
  if(old==null||old.length!=4)old=new boolean[4];
  boolean xHat=Math.abs(hatX)>.5f,yHat=Math.abs(hatY)>.5f;
  return new boolean[]{yHat?hatY<0:negative(stickY,old[0]),yHat?hatY>0:positive(stickY,old[1]),xHat?hatX<0:negative(stickX,old[2]),xHat?hatX>0:positive(stickX,old[3])};
 }
}
