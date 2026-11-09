package net.ghostlink.mirror;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import java.io.ByteArrayOutputStream;

/** User-approved screen sharing, independent of ADB and the Wi-Fi role. */
public class ScreenService extends Service {
 static volatile byte[] frame;
 static volatile boolean active;
 static volatile long lastRead;
 static volatile String error="";
 MediaProjection projection;
 VirtualDisplay display;
 ImageReader reader;
 HandlerThread thread;
 Handler capture;
 long lastFrame;
 final Handler main=new Handler(Looper.getMainLooper());
 final Runnable idle=new Runnable(){public void run(){
  if(SystemClock.elapsedRealtime()-lastRead>90000){stopSelf();return;}
  main.postDelayed(this,5000);
 }};
 public IBinder onBind(Intent i){return null;}
 public int onStartCommand(Intent intent,int flags,int id){
  if(intent==null||"stop".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
  if(active)return START_NOT_STICKY;
  error="";
  try{
   NotificationManager nm=getSystemService(NotificationManager.class);
   nm.createNotificationChannel(new NotificationChannel("screen","Screen sharing",NotificationManager.IMPORTANCE_LOW));
   PendingIntent stop=PendingIntent.getService(this,51,new Intent(this,ScreenService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
   startForeground(3,new Notification.Builder(this,"screen").setSmallIcon(R.drawable.icon)
    .setContentTitle("Ghost Link is sharing your screen").setContentText("Your paired PC can view and control this session.")
    .addAction(new Notification.Action.Builder(null,"Stop sharing",stop).build()).setOngoing(true).build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
   Intent permission=intent.getParcelableExtra("permission");
   if(permission==null)throw new IllegalArgumentException("Screen approval is required.");
   projection=getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK,permission);
   thread=new HandlerThread("ghost-screen");thread.start();capture=new Handler(thread.getLooper());
   projection.registerCallback(new MediaProjection.Callback(){
    public void onStop(){active=false;frame=null;stopSelf();}
    public void onCapturedContentResize(int w,int h){if(active)resize(w,h);}
   },main);
   android.util.DisplayMetrics metrics=getResources().getDisplayMetrics();
   Rect bounds=getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
   makeReader(bounds.width(),bounds.height());
   display=projection.createVirtualDisplay("Ghost Link",reader.getWidth(),reader.getHeight(),metrics.densityDpi,
    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,capture);
   active=true;lastRead=SystemClock.elapsedRealtime();main.post(idle);
  }catch(Exception e){error="Screen sharing could not start: "+e.getClass().getSimpleName();stopSelf();}
  return START_NOT_STICKY;
 }
 void makeReader(int width,int height){
  float scale=Math.min(1f,960f/Math.max(width,height));
  reader=ImageReader.newInstance(Math.max(2,Math.round(width*scale)),Math.max(2,Math.round(height*scale)),PixelFormat.RGBA_8888,2);
  reader.setOnImageAvailableListener(source->{
   try(Image image=source.acquireLatestImage()){
    if(image==null||SystemClock.elapsedRealtime()-lastFrame<180)return;
    lastFrame=SystemClock.elapsedRealtime();
    Image.Plane plane=image.getPlanes()[0];int stride=plane.getRowStride()/plane.getPixelStride();
    Bitmap padded=Bitmap.createBitmap(stride,image.getHeight(),Bitmap.Config.ARGB_8888);
    Bitmap cropped=null;
    try{
     padded.copyPixelsFromBuffer(plane.getBuffer());
     cropped=Bitmap.createBitmap(padded,0,0,image.getWidth(),image.getHeight());
     ByteArrayOutputStream bytes=new ByteArrayOutputStream();cropped.compress(Bitmap.CompressFormat.JPEG,65,bytes);
     frame=bytes.toByteArray();
    }finally{if(cropped!=null&&cropped!=padded)cropped.recycle();padded.recycle();}
   }catch(Exception ignored){}
  },capture);
 }
 void resize(int w,int h){
  if(display==null||w<=0||h<=0)return;
  // Serialize reader replacement with frame conversion on the capture thread.
  capture.post(()->{if(!active)return;display.setSurface(null);reader.close();makeReader(w,h);
   display.resize(reader.getWidth(),reader.getHeight(),getResources().getDisplayMetrics().densityDpi);
   display.setSurface(reader.getSurface());});
 }
 public void onDestroy(){
  active=false;frame=null;main.removeCallbacksAndMessages(null);
  if(capture!=null)capture.post(()->{if(display!=null)display.release();if(reader!=null)reader.close();if(projection!=null)projection.stop();thread.quitSafely();});
  else if(projection!=null)projection.stop();
  super.onDestroy();
 }
}
