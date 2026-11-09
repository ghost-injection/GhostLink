package net.ghostlink.mirror;
import android.content.*;
import android.provider.Settings;
public class BootReceiver extends BroadcastReceiver {
 public void onReceive(Context c,Intent i){if(!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction()))return;if(!c.getSharedPreferences("link",0).getBoolean("armed",false))return;try{c.startForegroundService(new Intent(c,LinkService.class));}catch(Exception ignored){}}
}
