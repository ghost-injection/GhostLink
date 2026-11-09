package net.ghostlink.mirror;

import android.accessibilityservice.*;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import org.json.JSONObject;
import java.io.*;
import java.util.concurrent.*;

/** Controls only an active, locally-approved Ghost Link screen session. */
public class ControlService extends AccessibilityService {
 static volatile ControlService instance;
 static volatile byte[] screenshot;
 static volatile long lastRead;
 final Handler main=new Handler(Looper.getMainLooper());
 final ExecutorService screenshots=Executors.newSingleThreadExecutor();
 final Runnable idle=()->{if(SystemClock.elapsedRealtime()-lastRead>=9000)stopSharing();};
 protected void onServiceConnected(){instance=this;}
 public void onAccessibilityEvent(AccessibilityEvent e){}
 public void onInterrupt(){}
 public void onDestroy(){stopSharing();instance=null;screenshots.shutdown();super.onDestroy();}
 static boolean sharing(){return instance!=null&&screenshot!=null&&SystemClock.elapsedRealtime()-lastRead<10000;}
 static boolean allowed(){return (ScreenService.active||sharing())&&instance!=null&&instance.getSharedPreferences("link",0).getBoolean("armed",false);}
 static void stopSharing(){screenshot=null;lastRead=0;ControlService service=instance;if(service!=null){service.main.removeCallbacks(service.idle);service.getSystemService(NotificationManager.class).cancel(5);}}
 static synchronized byte[] capture()throws Exception{
  ControlService service=instance;
  if(service==null)throw new IOException("Enable Ghost Link automatic sharing once in phone Accessibility settings");
  if(!service.getSharedPreferences("link",0).getBoolean("armed",false))throw new IOException("Ghost Link is disarmed");
  if(service.getSystemService(KeyguardManager.class).isKeyguardLocked()){stopSharing();throw new IOException("Unlock the phone to share its screen");}
  long now=SystemClock.elapsedRealtime();
  if(screenshot!=null&&now-lastRead<400)return screenshot;
  CountDownLatch ready=new CountDownLatch(1);byte[][] result=new byte[1][];String[] failure=new String[1];
  service.main.post(()->{
   try{service.takeScreenshot(Display.DEFAULT_DISPLAY,service.screenshots,new TakeScreenshotCallback(){
    public void onSuccess(ScreenshotResult shot){
     Bitmap hardware=null,software=null,scaled=null;
     try{
      hardware=Bitmap.wrapHardwareBuffer(shot.getHardwareBuffer(),shot.getColorSpace());
      if(hardware==null)throw new IOException("Screenshot unavailable");
      software=hardware.copy(Bitmap.Config.ARGB_8888,false);
      float scale=Math.min(1f,960f/Math.max(software.getWidth(),software.getHeight()));
      scaled=Bitmap.createScaledBitmap(software,Math.max(1,Math.round(software.getWidth()*scale)),Math.max(1,Math.round(software.getHeight()*scale)),true);
      ByteArrayOutputStream bytes=new ByteArrayOutputStream();scaled.compress(Bitmap.CompressFormat.JPEG,70,bytes);result[0]=bytes.toByteArray();
     }catch(Exception e){failure[0]="Screenshot unavailable: "+e.getClass().getSimpleName();}
     finally{if(scaled!=null&&scaled!=software)scaled.recycle();if(software!=null)software.recycle();if(hardware!=null)hardware.recycle();shot.getHardwareBuffer().close();ready.countDown();}
    }
    public void onFailure(int error){failure[0]=error==6?"This app protects its screen from capture":"Android screenshot unavailable ("+error+")";ready.countDown();}
   });}catch(Exception e){failure[0]="Enable Ghost Link automatic sharing in Accessibility settings";ready.countDown();}
  });
  if(!ready.await(3,TimeUnit.SECONDS))throw new IOException("Android screenshot timed out");
  if(result[0]==null){stopSharing();throw new IOException(failure[0]);}
  if(instance!=service||!service.getSharedPreferences("link",0).getBoolean("armed",false))throw new IOException("Sharing stopped");
  boolean notify=!sharing();screenshot=result[0];lastRead=SystemClock.elapsedRealtime();
  service.main.removeCallbacks(service.idle);service.main.postDelayed(service.idle,10000);
  if(notify){
   NotificationManager nm=service.getSystemService(NotificationManager.class);
   nm.createNotificationChannel(new NotificationChannel("auto-screen","Automatic screen sharing",NotificationManager.IMPORTANCE_LOW));
   PendingIntent stop=PendingIntent.getService(service,53,new Intent(service,LinkService.class).setAction("DISARM"),PendingIntent.FLAG_IMMUTABLE);
   nm.notify(5,new Notification.Builder(service,"auto-screen").setSmallIcon(R.drawable.icon).setContentTitle("Ghost Link is sharing your screen")
    .setContentText("Your paired PC can view, tap, and swipe. Disarm to stop.").setOngoing(true)
    .addAction(new Notification.Action.Builder(null,"Disarm",stop).build()).build());
  }
  return screenshot;
 }
 static void perform(JSONObject data){
  ControlService service=instance;if(service==null)return;
  new Handler(Looper.getMainLooper()).post(()->{if(!allowed())return;
   String action=data.optString("action");
   if(action.equals("back")){service.performGlobalAction(GLOBAL_ACTION_BACK);return;}
   if(action.equals("home")){service.performGlobalAction(GLOBAL_ACTION_HOME);return;}
   if(action.equals("recents")){service.performGlobalAction(GLOBAL_ACTION_RECENTS);return;}
   if(!action.equals("gesture"))return;
   Rect bounds=service.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
   float x=(float)data.optDouble("x",-1),y=(float)data.optDouble("y",-1);
   float x2=(float)data.optDouble("x2",x),y2=(float)data.optDouble("y2",y);
   if(!valid(x)||!valid(y)||!valid(x2)||!valid(y2))return;
   Path path=new Path();path.moveTo(x*(bounds.width()-1),y*(bounds.height()-1));
   if(x!=x2||y!=y2)path.lineTo(x2*(bounds.width()-1),y2*(bounds.height()-1));
   long duration=Math.max(50,Math.min(1500,data.optLong("duration",80)));
   service.dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,duration)).build(),null,null);
  });
 }
 static boolean valid(float n){return !Float.isNaN(n)&&n>=0&&n<=1;}
}
