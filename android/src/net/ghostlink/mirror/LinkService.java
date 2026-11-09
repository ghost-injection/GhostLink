package net.ghostlink.mirror;

import android.app.*;
import android.content.*;
import android.os.*;
import android.net.nsd.*;
import android.provider.Settings;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class LinkService extends Service {
 static volatile boolean running=false; static volatile int adbPort=0; static volatile String state="DISARMED"; volatile boolean alive; ServerSocket server; NsdManager nsd; NsdManager.DiscoveryListener discovery; ExecutorService workers; Handler main=new Handler(); String currentBind=""; LinkRecovery recovery; String adbService=""; int discoveryGeneration; boolean discoveryStopping;
 public IBinder onBind(Intent i){return null;}
 public static boolean isTail(String ip){try{String[] p=ip.split("\\.");return p.length==4&&Integer.parseInt(p[0])==100&&Integer.parseInt(p[1])>=64&&Integer.parseInt(p[1])<=127&&Integer.parseInt(p[2])>=0&&Integer.parseInt(p[2])<=255&&Integer.parseInt(p[3])>=0&&Integer.parseInt(p[3])<=255;}catch(Exception e){return false;}}
 static String tailIp(){try{for(NetworkInterface n:Collections.list(NetworkInterface.getNetworkInterfaces()))for(InetAddress a:Collections.list(n.getInetAddresses()))if(isTail(a.getHostAddress()))return a.getHostAddress();}catch(Exception ignored){}return "";}
 static boolean own(InetAddress addr){try{return addr!=null&&NetworkInterface.getByInetAddress(addr)!=null;}catch(Exception e){return false;}}
 static int port(Context c){if(Settings.Global.getInt(c.getContentResolver(),"adb_wifi_enabled",0)!=1)return 0;if(adbPort>0)return adbPort;try{int p=Integer.parseInt(c.getSharedPreferences("link",0).getString("port","0"));return p>0&&p<65536?p:0;}catch(Exception e){return 0;}}
 static String summary(Context c){return state+"\n"+LinkRecovery.state+"\n"+LinkRecovery.detail+"\nTAILNET  "+(tailIp().isEmpty()?"OFFLINE":tailIp())+"\nTLS PORT "+port(c)+"\nPC       "+c.getSharedPreferences("link",0).getString("pc","NOT CONFIGURED");}
 public void onCreate(){super.onCreate();alive=running=true;workers=new ThreadPoolExecutor(3,3,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(8));NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("link","Remote access",NotificationManager.IMPORTANCE_LOW));Intent open=new Intent(this,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE);startForeground(1,new Notification.Builder(this,"link").setContentTitle("Ghost Link • Remote access armed").setContentText("Paired controllers can connect through Tailscale. Tap to manage.").setSmallIcon(net.ghostlink.mirror.R.drawable.icon).setContentIntent(pi).setOngoing(true).build());discover();recovery=new LinkRecovery(this);recovery.start();new Thread(this::listen,"tailnet-listener").start();}
 public int onStartCommand(Intent i,int flags,int id){if(i!=null&&"DISARM".equals(i.getAction())){getSharedPreferences("link",0).edit().putBoolean("armed",false).apply();ControlService.stopSharing();stopService(new Intent(this,ScreenService.class));}if(!getSharedPreferences("link",0).getBoolean("armed",false)){stopSelf();return START_NOT_STICKY;}if(recovery!=null)recovery.request();return START_STICKY;}
 void clearDiscoveredPort(){adbPort=0;adbService="";}
 void restartDiscovery(){
  if(!alive||discoveryStopping)return;
  clearDiscoveredPort();discoveryGeneration++;
  if(discovery==null){discover();return;}
  discoveryStopping=true;
  try{nsd.stopServiceDiscovery(discovery);}catch(Exception e){discoveryStopping=false;discovery=null;main.postDelayed(()->{if(alive)discover();},1500);}
 }
 void discover(){
  if(!alive||discovery!=null)return;
  nsd=(NsdManager)getSystemService(NSD_SERVICE);final int generation=++discoveryGeneration;
  discovery=new NsdManager.DiscoveryListener(){
   public void onDiscoveryStarted(String t){}
   public void onDiscoveryStopped(String t){main.post(()->{discovery=null;discoveryStopping=false;if(alive)discover();});}
   public void onStartDiscoveryFailed(String t,int c){main.post(()->{discovery=null;discoveryStopping=false;if(alive)main.postDelayed(()->discover(),5000);});}
   public void onStopDiscoveryFailed(String t,int c){main.post(()->{discoveryStopping=false;if(alive)discoveryGeneration=generation;});}
   public void onServiceLost(NsdServiceInfo s){main.post(()->{if(generation==discoveryGeneration&&s.getServiceName().equals(adbService))clearDiscoveredPort();});}
   public void onServiceFound(NsdServiceInfo s){nsd.resolveService(s,new NsdManager.ResolveListener(){
    public void onResolveFailed(NsdServiceInfo s,int e){}
    public void onServiceResolved(NsdServiceInfo s){main.post(()->{
     if(alive&&generation==discoveryGeneration&&own(s.getHost())&&Settings.Global.getInt(getContentResolver(),"adb_wifi_enabled",0)==1){adbService=s.getServiceName();adbPort=s.getPort();if(recovery!=null)recovery.wake();}
    });}
   });}
  };
  try{nsd.discoverServices("_adb-tls-connect._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery);}catch(Exception e){discovery=null;main.postDelayed(()->{if(alive)discover();},5000);}
 }
 void listen(){while(alive){try{String ip=tailIp();if(ip.isEmpty()){state="WAITING FOR TAILSCALE";Thread.sleep(2000);continue;}server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(ip,47831),8);server.setSoTimeout(2000);currentBind=ip;state="ARMED / PRIVATE LINK";while(alive&&ip.equals(tailIp())){try{Socket s=server.accept();try{workers.submit(()->handle(s));}catch(RejectedExecutionException busy){s.close();}}catch(SocketTimeoutException ignored){}}}catch(Exception e){if(alive)state="RECONNECTING TAILSCALE";}finally{try{if(server!=null)server.close();}catch(Exception ignored){}}try{Thread.sleep(1000);}catch(Exception ignored){}}}
 void handle(Socket socket){NativeApi.handle(this,socket);}
 void reply(Socket s,int code,String data)throws Exception{byte[] b=data.getBytes(StandardCharsets.UTF_8);OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 "+code+" OK\r\nContent-Type: application/json\r\nContent-Length: "+b.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(b);out.flush();}
 static String requestPc(Context c){return requestPc(c,"launch");}
 static String requestPc(Context c,String action){
  if(!java.util.Arrays.asList("launch","native","files","show").contains(action))return "Unknown desktop action";
  NativeApi.queueDesktop(action);
  return "Requested on PC. Keep Ghost Link running on the laptop.";
 }

 public void onDestroy(){ControlService.stopSharing();alive=running=false;if(recovery!=null)recovery.stop();main.removeCallbacksAndMessages(null);discoveryGeneration++;state="DISARMED";adbPort=0;try{server.close();}catch(Exception ignored){}try{nsd.stopServiceDiscovery(discovery);}catch(Exception ignored){}workers.shutdownNow();super.onDestroy();}
}



