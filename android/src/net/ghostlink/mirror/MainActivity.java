package net.ghostlink.mirror;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.net.*;

public class MainActivity extends Activity {
 static final int BG=Color.rgb(9,13,19), PANEL=Color.rgb(18,26,35), GREEN=Color.rgb(200,255,49), CYAN=Color.rgb(35,230,217), WHITE=Color.rgb(237,242,246), MUTED=Color.rgb(142,158,172);
 static final int CAPTURE=41, FILES=42;
 GlassDashboard dashboard; TextView status; Handler handler=new Handler();
 Runnable refresh=new Runnable(){ public void run(){ if(status!=null)status.setText(LinkService.summary(MainActivity.this)); handler.postDelayed(this,1500); }};
 int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
 public void onCreate(Bundle b){super.onCreate(b);if(!getSharedPreferences("link",0).contains("token")){try{java.io.InputStream in=getAssets().open("controller.json");java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buf=new byte[1024];int n;while((n=in.read(buf))>0)out.write(buf,0,n);in.close();org.json.JSONObject cfg=new org.json.JSONObject(out.toString("UTF-8"));getSharedPreferences("link",0).edit().putString("pc",cfg.getString("pc_ip")).putString("token",cfg.getString("token")).putString("port",String.valueOf(cfg.optInt("adb_port",0))).apply();}catch(Exception ignored){}}getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
  getWindow().setDecorFitsSystemWindows(false);
  dashboard=new GlassDashboard(this);setContentView(dashboard);
  if(b==null&&getIntent().getBooleanExtra("native_share",false))handler.post(()->shareScreen());
  dashboard.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return WindowInsets.CONSUMED;});
  if(Build.VERSION.SDK_INT>=33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=0)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},1);
  if(b==null && getIntent().getBooleanExtra("mirror",false)){arm();new Thread(()->{String result=LinkService.requestPc(this);runOnUiThread(()->Toast.makeText(this,result,Toast.LENGTH_LONG).show());}).start();}
 }
 void pin(){android.content.pm.ShortcutManager sm=getSystemService(android.content.pm.ShortcutManager.class);if(sm.isRequestPinShortcutSupported()){Intent i=new Intent(this,MainActivity.class).setAction(Intent.ACTION_VIEW).putExtra("mirror",true);android.content.pm.ShortcutInfo shortcut=new android.content.pm.ShortcutInfo.Builder(this,"mirror").setShortLabel("Ghost Mirror").setLongLabel("Open mirror on PC").setIcon(android.graphics.drawable.Icon.createWithResource(this,R.drawable.icon)).setIntent(i).build();sm.requestPinShortcut(shortcut,null);}else Toast.makeText(this,"Long-press Ghost Link in your app drawer and add it to Home",1).show();}
 TextView add(LinearLayout root,String text,int size,int color){TextView v=new TextView(this);v.setText(text);v.setTextColor(color);v.setTextSize(size);v.setTypeface(Typeface.MONOSPACE);v.setPadding(0,dp(7),0,dp(7));root.addView(v);return v;}
 void button(LinearLayout root,String text,int color,Runnable fn){Button v=new Button(this);v.setText(text);v.setTextColor(color);v.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);v.setTextSize(14);GradientDrawable bg=new GradientDrawable();bg.setColor(PANEL);bg.setCornerRadius(dp(8));bg.setStroke(dp(1),color);v.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(54));lp.topMargin=dp(10);root.addView(v,lp);v.setOnClickListener(x->fn.run());}
 void arm(){if(getSharedPreferences("link",0).getString("token","").length()<32){configure();return;}getSharedPreferences("link",0).edit().putBoolean("armed",true).apply();startForegroundService(new Intent(this,LinkService.class));}
 void configure(){LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(20),dp(8),dp(20),dp(8));android.content.SharedPreferences prefs=getSharedPreferences("link",0);EditText host=new EditText(this);host.setHint("PC Tailscale IPv4 address");host.setText(prefs.getString("pc",""));box.addView(host);EditText token=new EditText(this);token.setHint("Controller token (from PC settings)");token.setInputType(129);token.setText(prefs.getString("token",""));box.addView(token);EditText port=new EditText(this);port.setHint("ADB port (optional fallback)");port.setInputType(2);port.setText(prefs.getString("port",""));box.addView(port);new AlertDialog.Builder(this).setTitle("PAIR YOUR CONTROLLER").setView(box).setPositiveButton("Save",(d,w)->{String h=host.getText().toString().trim(),t=token.getText().toString().trim(),p=port.getText().toString().trim();if(!LinkService.isTail(h)||t.length()<32||(!p.isEmpty()&&!p.matches("[0-9]{1,5}"))){Toast.makeText(this,"Use a Tailscale IPv4 and a token of 32+ characters",1).show();return;}prefs.edit().putString("pc",h).putString("token",t).putString("port",p).apply();}).setNegativeButton("Cancel",null).show();}
 protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);if(i.getBooleanExtra("native_share",false))shareScreen();if(i.getBooleanExtra("mirror",false)){arm();new Thread(()->{String result=LinkService.requestPc(this);runOnUiThread(()->Toast.makeText(this,result,Toast.LENGTH_LONG).show());}).start();}}
 protected void onResume(){super.onResume();if(getSharedPreferences("link",0).getBoolean("armed",false))startForegroundService(new Intent(this,LinkService.class));if(dashboard!=null)dashboard.resume();}protected void onPause(){if(dashboard!=null)dashboard.pause();super.onPause();}
 void launchMirror(){arm();new Thread(()->{String result=LinkService.requestPc(this);runOnUiThread(()->Toast.makeText(this,result,Toast.LENGTH_LONG).show());}).start();}
 void openTailscale(){Intent i=getPackageManager().getLaunchIntentForPackage("com.tailscale.ipn");if(i!=null)startActivity(i);}
 void openDebugging(){try{startActivity(new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"));}catch(Exception e){startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));}}
 void disarm(){ControlService.stopSharing();stopService(new Intent(this,ScreenService.class));getSharedPreferences("link",0).edit().putBoolean("armed",false).apply();stopService(new Intent(this,LinkService.class));try{Settings.Global.putInt(getContentResolver(),"adb_wifi_enabled",0);}catch(Exception ignored){}Toast.makeText(this,"Remote access disarmed",Toast.LENGTH_LONG).show();}
 void shareScreen(){
  arm();
  if(ControlService.instance!=null){new Thread(()->LinkService.requestPc(this,"native")).start();return;}
  controlSettings();
 }
 void shareScreenOnce(){
  arm();
  if(ScreenService.active){new Thread(()->LinkService.requestPc(this,"native")).start();return;}
  android.media.projection.MediaProjectionManager manager=getSystemService(android.media.projection.MediaProjectionManager.class);
  Intent consent=Build.VERSION.SDK_INT>=34?manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()):manager.createScreenCaptureIntent();
  startActivityForResult(consent,CAPTURE);
 }
 void controlSettings(){
  new AlertDialog.Builder(this).setTitle("Enable automatic screen sharing")
   .setMessage("Enable Ghost Link automatic sharing under Installed apps in Accessibility settings once. While armed, your paired PC can then automatically view your screen and use taps and swipes. Disarm stops access; locked and protected screens are not shared.")
   .setPositiveButton("Open settings",(d,w)->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).setNegativeButton("Cancel",null).show();
 }
 void pickFiles(){arm();Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(pick,FILES);}
 protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);
  if(result!=RESULT_OK||data==null)return;
  if(request==CAPTURE){startForegroundService(new Intent(this,ScreenService.class).putExtra("permission",data));new Thread(()->LinkService.requestPc(this,"native")).start();}
  if(request==FILES){
   Toast.makeText(this,"Preparing selected files…",Toast.LENGTH_SHORT).show();
   new Thread(()->{try{
    if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)SharedFiles.select(this,data.getClipData().getItemAt(i).getUri());}
    else if(data.getData()!=null)SharedFiles.select(this,data.getData());
    String reply=LinkService.requestPc(this,"files");runOnUiThread(()->Toast.makeText(this,"Files ready for your PC. "+reply,Toast.LENGTH_LONG).show());
   }catch(Exception e){runOnUiThread(()->Toast.makeText(this,"Could not share file: "+e.getMessage(),Toast.LENGTH_LONG).show());}}).start();
  }
 }

}
