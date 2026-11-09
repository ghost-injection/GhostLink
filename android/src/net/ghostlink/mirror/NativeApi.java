package net.ghostlink.mirror;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

final class NativeApi {
 static volatile long lastPrompt;
 static String desktopAction="";
 static String desktopId="";
 static long desktopRequested;
 static synchronized void queueDesktop(String action){desktopAction=action;desktopId=UUID.randomUUID().toString();desktopRequested=SystemClock.elapsedRealtime();}
 static synchronized JSONObject pollDesktop()throws JSONException{
  String action=SystemClock.elapsedRealtime()-desktopRequested<120000?desktopAction:"";
  return new JSONObject().put("action",action).put("id",desktopId);
 }
 static synchronized void ackDesktop(String id){if(id.equals(desktopId))desktopAction="";}
 static String line(InputStream in)throws IOException{
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();int b;
  while((b=in.read())!=-1&&b!='\n'){if(bytes.size()>=8192)throw new IOException("Header too large");if(b!='\r')bytes.write(b);}
  if(b==-1&&bytes.size()==0)throw new EOFException();return bytes.toString("UTF-8");
 }
 static void reply(Socket socket,int code,String type,byte[] data)throws IOException{
  header(socket,code,type,data.length);socket.getOutputStream().write(data);socket.getOutputStream().flush();
 }
 static void header(Socket s,int code,String type,long length)throws IOException{
  s.getOutputStream().write(("HTTP/1.1 "+code+" Response\r\nContent-Type: "+type+"\r\nContent-Length: "+length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
 }
 static void json(Socket s,int code,Object value)throws IOException{reply(s,code,"application/json",value.toString().getBytes(StandardCharsets.UTF_8));}
 static void requestShare(LinkService service){
  long now=SystemClock.elapsedRealtime();if(now-lastPrompt<15000)return;lastPrompt=now;
  Intent open=new Intent(service,MainActivity.class).putExtra("native_share",true);
  PendingIntent approve=PendingIntent.getActivity(service,52,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  NotificationManager nm=service.getSystemService(NotificationManager.class);
  nm.createNotificationChannel(new NotificationChannel("approval","Sharing requests",NotificationManager.IMPORTANCE_HIGH));
  nm.notify(4,new Notification.Builder(service,"approval").setSmallIcon(R.drawable.icon).setContentTitle("Your PC requests screen sharing")
   .setContentText("Tap to approve Ghost Link screen sharing. Files do not need screen approval.").setContentIntent(approve).setAutoCancel(true).build());
 }
 static void handle(LinkService service,Socket socket){
  try(Socket s=socket){
   s.setSoTimeout(15000);
   android.content.SharedPreferences prefs=service.getSharedPreferences("link",0);
   if(!prefs.getBoolean("armed",false)||!s.getInetAddress().getHostAddress().equals(prefs.getString("pc","")))return;
   InputStream in=new BufferedInputStream(s.getInputStream());String first=line(in);String[] parts=first.split(" ");
   if(parts.length!=3)return;Map<String,String> headers=new HashMap<>();int total=0;
   for(String h;!(h=line(in)).isEmpty();){total+=h.length();if(total>16384)return;int colon=h.indexOf(':');if(colon<1)return;
    String key=h.substring(0,colon).toLowerCase(Locale.ROOT);if(headers.containsKey(key))return;headers.put(key,h.substring(colon+1).trim());}
   String token=prefs.getString("token","");
   if(token.length()<32||!MessageDigest.isEqual(headers.getOrDefault("authorization","").getBytes(StandardCharsets.UTF_8),("Bearer "+token).getBytes(StandardCharsets.UTF_8))){json(s,403,new JSONObject().put("error","unauthorized"));return;}
   String method=parts[0],path=parts[1];
   if(headers.containsKey("transfer-encoding")){json(s,400,new JSONObject().put("error","Content-Length required"));return;}
   long length=Long.parseLong(headers.getOrDefault("content-length","0"));
   if(length<0||length>SharedFiles.MAX){json(s,413,new JSONObject().put("error","File limit is 256 MiB"));return;}
   try{
    if(method.equals("GET")&&path.equals("/status")){
     JSONObject status=new JSONObject().put("name","S25 Ultra").put("ip",LinkService.tailIp()).put("adb_port",LinkService.port(service))
      .put("wireless_debugging",android.provider.Settings.Global.getInt(service.getContentResolver(),"adb_wifi_enabled",0)==1)
      .put("armed",true).put("recovery_state",LinkRecovery.state).put("recovery_detail",LinkRecovery.detail).put("auto_recovery",true)
      .put("port_source",LinkService.adbPort>0?"discovered":"saved").put("native_share",true).put("file_transfer",true)
      .put("screen_active",ScreenService.active||ControlService.sharing()).put("auto_screen",ControlService.instance!=null)
      .put("control_enabled",ControlService.instance!=null).put("screen_error",ScreenService.error);
     json(s,200,status);
    }else if(method.equals("GET")&&path.equals("/desktop/poll")){
     json(s,200,pollDesktop());
    }else if(method.equals("POST")&&path.startsWith("/desktop/ack/")){
     ackDesktop(path.substring(13));json(s,200,new JSONObject().put("accepted",true));
    }else if(method.equals("POST")&&path.equals("/arm")){
     if(service.recovery!=null)service.recovery.request();json(s,202,new JSONObject().put("requested",true));
    }else if(method.equals("POST")&&path.equals("/share/request")){
     if(!ScreenService.active)requestShare(service);json(s,202,new JSONObject().put("active",ScreenService.active));
    }else if(method.equals("POST")&&path.equals("/share/stop")){
     ControlService.stopSharing();
     service.stopService(new Intent(service,ScreenService.class));json(s,200,new JSONObject().put("stopped",true));
    }else if(method.equals("GET")&&path.equals("/screen")){
     if(!ScreenService.active){reply(s,200,"image/jpeg",ControlService.capture());return;}
     ScreenService.lastRead=SystemClock.elapsedRealtime();byte[] frame=ScreenService.frame;
     if(!ScreenService.active||frame==null)json(s,409,new JSONObject().put("error",ScreenService.error.isEmpty()?"Approve Share screen in Ghost Link on the phone":ScreenService.error));
     else reply(s,200,"image/jpeg",frame);
    }else if(method.equals("POST")&&path.equals("/control")){
     if(!ControlService.allowed()){json(s,409,new JSONObject().put("error","Share the screen and enable Ghost Link control in phone Accessibility settings"));return;}
     if(length>2048)throw new IOException("Control request too large");
     byte[] body=new byte[(int)length];new DataInputStream(in).readFully(body);
     JSONObject action=new JSONObject(new String(body,StandardCharsets.UTF_8));
     if(!Arrays.asList("gesture","home","back","recents").contains(action.optString("action")))throw new IOException("Unsupported control action");
     ControlService.perform(action);json(s,202,new JSONObject().put("accepted",true));
    }else if(method.equals("GET")&&path.equals("/files"))json(s,200,SharedFiles.list(service));
    else if(method.equals("POST")&&path.equals("/files")){
     if(!headers.containsKey("content-length"))throw new IOException("Content-Length required");
     String name=new String(Base64.getDecoder().decode(headers.getOrDefault("x-file-name","ZmlsZQ==")),StandardCharsets.UTF_8);
     String sha=headers.getOrDefault("x-file-sha256","");if(!sha.matches("[0-9a-f]{64}"))throw new IOException("SHA-256 required");
     json(s,201,SharedFiles.save(service,in,length,name,sha,true));
    }else if(method.equals("GET")&&path.startsWith("/files/")){
     File file=SharedFiles.file(service,path.substring(7));header(s,200,"application/octet-stream",file.length());
     try(InputStream fileIn=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=fileIn.read(b))>=0)s.getOutputStream().write(b,0,n);}
    }else if(method.equals("DELETE")&&path.startsWith("/files/")){
     SharedFiles.remove(service,path.substring(7));json(s,200,new JSONObject().put("removed",true));
    }else json(s,404,new JSONObject().put("error","not found"));
   }catch(Exception e){json(s,400,new JSONObject().put("error",e.getMessage()==null?"Request failed":e.getMessage()));}
  }catch(Exception ignored){}
 }
}
