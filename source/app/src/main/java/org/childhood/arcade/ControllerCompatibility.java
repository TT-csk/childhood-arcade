package org.childhood.arcade;
/** Some Android TV pads report button events as keyboard/DPAD, despite a joystick-capable device. */
public final class ControllerCompatibility {
 private ControllerCompatibility(){}
 public static boolean hasPadSource(int source){return (source&0x401)==0x401||(source&0x1000010)==0x1000010;}
 public static boolean isGamepad(int eventSource,int deviceSources,int key,int deviceId){return hasPadSource(eventSource)||hasPadSource(deviceSources)||(deviceId>=0&&((key>=96&&key<=110)||(key>=188&&key<=203)));}
 /** Recover a learned pad identity only when its descriptor is now the same
  * hardware's non-pad interface. Never match merely by controller name/order. */
 public static String reconnectKey(String raw,String[] profiles,String[] nonPadSiblings,String[] activePads){
  for(String p:profiles)if(raw.equals(p))return raw;
  String match=null;
  for(String p:profiles){boolean sibling=false,active=false;
   for(String k:nonPadSiblings)if(p.equals(k))sibling=true;
   for(String k:activePads)if(p.equals(k))active=true;
   if(sibling&&!active){if(match!=null&&!match.equals(p))return raw;match=p;}
  }
  return match==null?raw:match;
 }
}
