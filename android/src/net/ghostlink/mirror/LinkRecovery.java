package net.ghostlink.mirror;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.os.*;
import android.provider.Settings;

/** Owns recovery only while the user has armed the helper. Never approves pairing. */
final class LinkRecovery {
 static volatile String state="DISARMED", detail="Tap ARM to enable automatic recovery.";
 private final LinkService service;
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final RecoveryPolicy wirelessRetry=new RecoveryPolicy(), usbRetry=new RecoveryPolicy();
 private boolean active,usbConnected,registered;
 private long nextDiscovery;
 private ConnectivityManager connectivity;
 private final ConnectivityManager.NetworkCallback networks=new ConnectivityManager.NetworkCallback(){
  public void onAvailable(Network n){changed();}
  public void onLost(Network n){changed();}
  public void onLinkPropertiesChanged(Network n,LinkProperties p){changed();}
 };
 private final BroadcastReceiver usb=new BroadcastReceiver(){
  public void onReceive(Context c,Intent i){boolean connected=i.getBooleanExtra("connected",false);if(usbConnected!=connected){usbConnected=connected;usbRetry.reset();}wake();}
 };
 private final Runnable tick=new Runnable(){public void run(){if(!active)return;recover();handler.postDelayed(this,3000);}};
 private final Runnable networkChange=this::networkChanged;
 private void networkChanged(){if(!active)return;wirelessRetry.reset();service.restartDiscovery();wake();}
 LinkRecovery(LinkService service){this.service=service;}
 void start(){
  active=true;
  connectivity=service.getSystemService(ConnectivityManager.class);
  try{connectivity.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),networks);registered=true;}catch(RuntimeException ignored){}
  IntentFilter filter=new IntentFilter("android.hardware.usb.action.USB_STATE");
  Intent initial=Build.VERSION.SDK_INT>=33?service.registerReceiver(usb,filter,Context.RECEIVER_EXPORTED):service.registerReceiver(usb,filter);
  if(initial!=null)usbConnected=initial.getBooleanExtra("connected",false);
  wake();
 }
 void changed(){handler.removeCallbacks(networkChange);handler.postDelayed(networkChange,1500);}
 void wake(){handler.removeCallbacks(tick);handler.post(tick);}
 void request(){handler.post(()->{if(!active)return;wirelessRetry.reset();usbRetry.reset();wake();});}
 boolean wirelessOn(){return Settings.Global.getInt(service.getContentResolver(),"adb_wifi_enabled",0)==1;}
 boolean usbOn(){return Settings.Global.getInt(service.getContentResolver(),Settings.Global.ADB_ENABLED,0)==1;}
 boolean hasPermission(){return service.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)==PackageManager.PERMISSION_GRANTED;}
 void recover(){
  if(!service.getSharedPreferences("link",0).getBoolean("armed",false))return;
  long now=SystemClock.elapsedRealtime();boolean writable=hasPermission();
  // Enable cable debugging only when a cable is actually attached. Android still
  // asks the user to approve a new computer's RSA key.
  if(usbConnected&&!usbOn()&&writable&&usbRetry.due(now)){
   usbRetry.attempted(now);
   try{Settings.Global.putInt(service.getContentResolver(),Settings.Global.ADB_ENABLED,1);}catch(RuntimeException ignored){}
  }
  if(!wirelessOn()){
   service.clearDiscoveredPort();
   if(writable&&wirelessRetry.due(now)){
    wirelessRetry.attempted(now);
    try{Settings.Global.putInt(service.getContentResolver(),"adb_wifi_enabled",1);}catch(RuntimeException ignored){}
   }
  }
  if(wirelessOn()&&LinkService.adbPort==0&&now>=nextDiscovery){nextDiscovery=now+30000;service.restartDiscovery();}
  if(wirelessOn()&&LinkService.adbPort>0){
   wirelessRetry.reset();state="WIRELESS ENDPOINT FOUND";detail="Paired PC can try TLS port "+LinkService.adbPort+". Authorization is checked by the desktop.";
  }else if(usbConnected&&usbOn()){
   state="USB DEBUGGING ENABLED";detail="Desktop will try USB. Unlock and approve its debugging key if prompted.";
  }else if(!writable){
   state="SETUP REQUIRED";detail="Enable debugging in Android settings, or restore the helper's WRITE_SECURE_SETTINGS grant using an authorized PC.";
  }else{
   state=wirelessOn()?"DISCOVERING DEBUG PORT":"WAITING FOR ANDROID";
   detail="Automatic recovery is active. Android may need network trust approval or a Wi-Fi uplink. If hosting a hotspot, connect USB when no wireless endpoint appears.";
  }
 }
 void stop(){active=false;handler.removeCallbacksAndMessages(null);if(registered)try{connectivity.unregisterNetworkCallback(networks);}catch(RuntimeException ignored){}try{service.unregisterReceiver(usb);}catch(RuntimeException ignored){}state="DISARMED";detail="Automatic recovery stopped.";}
}
