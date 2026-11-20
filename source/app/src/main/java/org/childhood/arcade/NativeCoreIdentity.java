package org.childhood.arcade;

/** Core identities remain ABI-specific; game battery bytes use narrowly matched core pairs. */
final class NativeCoreIdentity {
 private static final String[][] BATTERY_PAIRS={
  {"fceumm-native-81ebd3e72d96","fceumm-native-05dc86015ca0"},
  {"mgba-native-a976a807a7bd","mgba-native-b5a6ed40ede7"},
  {"picodrive-native-c1cc15edda64","picodrive-native-92d28b94fd93"},
  {"snes9x2005-native-7e15337d8caa","snes9x2005-native-5ac303f71adc"},
  {"snes9x2005plus-native-ada4dbf17de5","snes9x2005plus-native-0492439e52a1"}
 };
 static String arm64(String name){
  switch(name){
   case "libfceumm.so":return "fceumm-native-05dc86015ca0";
   case "libmgba.so":return "mgba-native-b5a6ed40ede7";
   case "libpicodrive.so":return "picodrive-native-92d28b94fd93";
   case "libsnes9x2005.so":return "snes9x2005-native-5ac303f71adc";
   case "libsnes9x2005plus.so":return "snes9x2005plus-native-0492439e52a1";
   case "libarcade.so":return "fba2012-native-657036bd812a";
   case "libfbneo.so":return "fbneo-native-82637a59dbe0";
   case "libfbneo3g.so":return "fbneo-3g-native-665a6ba8d0b9";
   case "libfbneo5g.so":return "fbneo-5g-native-c24dc98d08ae";
   case "libfbneo5w.so":return "fbneo-fc-native-f79c0f55b980";
   default:throw new IllegalArgumentException("未知64位核心");
  }
 }
 static boolean stateCompatible(String previous,String current,String hash){
  if(previous.equals(current))return true;
  boolean pair=(previous.equals("fceumm-native-81ebd3e72d96")&&current.equals("fceumm-native-05dc86015ca0"))||(current.equals("fceumm-native-81ebd3e72d96")&&previous.equals("fceumm-native-05dc86015ca0"));
  if(!pair)return false;
  switch(hash){
   case "cf698e3f62a9fae73defd900f7c84e8f983117f4c82461d54b5510357d74269a":
   case "a3dbc447dc64dbb92fe90fbccab48da4bb35b8813d0384b23d7ca60c56cd05cf":
   case "e568f888f836ef65dbe8ef9deb8796d348940598b1e483689af4ce09053c07c2":
   case "a02e6d3e619a47930149298a6bc4f90a30af1939e7905a2a1c7115bc6eb92bf2":
   case "24b8f17d2739d8cd5b2ce8b4741a2556296c71317f272a793b10f8cae5c33b78":return true;
   default:return false;
  }
 }
 static boolean batteryCompatible(String previous,String current){
  if(previous.equals(current))return true;
  for(String[] pair:BATTERY_PAIRS)if((previous.equals(pair[0])&&current.equals(pair[1]))||(previous.equals(pair[1])&&current.equals(pair[0])))return true;
  return false;
 }
}
