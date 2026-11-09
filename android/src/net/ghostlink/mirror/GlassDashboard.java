package net.ghostlink.mirror;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.ArrayDeque;
import java.util.Iterator;

/** Native glass UI. Pointer feedback uses actual Android hover/touch events. */
public final class GlassDashboard extends FrameLayout {
 static final int WHITE=0xffedf8ff, MUTED=0xff8c9eb5, CYAN=0xff68edff, GREEN=0xffbaff70, PURPLE=0xffaf91ff;
 final MainActivity activity;
 final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
 final ArrayDeque<float[]> trail=new ArrayDeque<>();
 final ArrayDeque<float[]> bursts=new ArrayDeque<>();
 final LinearLayout content;
 final StatusCard status;
 final MaskView mask;
 GlassButton fx, themeButton;
 long pulseStart=0;String pulseText="";boolean pulseParticles=false;
 void pulse(String text,boolean particles){if(!effects)return;pulseStart=SystemClock.uptimeMillis();pulseText=text;pulseParticles=particles;invalidate();}
 void appearance(){new android.app.AlertDialog.Builder(activity).setTitle("APPEARANCE / PROTOCOL").setSingleChoiceItems(themeNames,theme,(dialog,which)->{theme=which;activity.getSharedPreferences("link",0).edit().putInt("theme",theme).apply();themeButton.label=themeNames[theme];refreshTree(this);pulse("THEME LOADED",false);}).setNeutralButton("TEST EFFECTS",(d,w)->pulse("SYSTEM OVERDRIVE",true)).setPositiveButton("Done",null).show();}
 void wobble(){if(!effects)return;animate().cancel();setPivotX(getWidth()/2f);setPivotY(getHeight()/2f);setRotation(.35f);animate().rotation(0).setDuration(500).setInterpolator(new android.view.animation.OvershootInterpolator(3)).start();}
 void minimize(){if(!effects){activity.moveTaskToBack(true);return;}pulse("SIGNAL SUSPENDED",false);animate().alpha(.05f).translationY(dp(35)).setDuration(300).withEndAction(()->{activity.moveTaskToBack(true);setAlpha(1);setTranslationY(0);}).start();}
 int theme=2;
 final String[] themeNames={"NEON","CATHODE","HACKER GREEN","SPECTRUM RGB"};
 int dark(float value){float[] hsv=new float[3];Color.colorToHSV(themeColor(),hsv);hsv[1]=.65f;hsv[2]=value;return Color.HSVToColor(hsv);}
 int themeColor(){float hue=theme==0?166:theme==1?35:theme==2?118:effects?(SystemClock.uptimeMillis()%12000)/12000f*360:166;return Color.HSVToColor(new float[]{hue,.85f,1});}
 int themed(int color){return color==CYAN||color==GREEN||color==PURPLE?themeColor():color;}
 void refreshTree(View v){v.invalidate();if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++)refreshTree(group.getChildAt(i));}}
 void cycleTheme(){theme=(theme+1)%4;activity.getSharedPreferences("link",0).edit().putInt("theme",theme).apply();themeButton.label=themeNames[theme];themeButton.setContentDescription("Theme: "+themeNames[theme]+". Tap to change.");refreshTree(this);}

 boolean effects=true,active=false,pointer=false;
 float mx,my; long lastPointer; int hoverEvents=0;
 final Runnable frame=new Runnable(){public void run(){if(!active)return;refreshTree(GlassDashboard.this);postDelayed(this,effects?33:750);}};
 int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
 public GlassDashboard(MainActivity a){
  super(a);activity=a;setWillNotDraw(false);setClipChildren(false);
  theme=a.getSharedPreferences("link",0).getInt("theme",2);theme=Math.floorMod(theme,4);
  effects=a.getSharedPreferences("link",0).getBoolean("effects",true);
  ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
  content=new LinearLayout(a);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(22),dp(18),dp(22),dp(24));scroll.addView(content);addView(scroll,new LayoutParams(-1,-1));
  text("G H O S T  / /  L I N K",22,WHITE,true,0);
  text("MOBILE COMMAND NODE     /     v1.5",10,MUTED,false,5);
  mask=new MaskView(a);LinearLayout.LayoutParams hero=new LinearLayout.LayoutParams(-1,dp(164));hero.topMargin=dp(8);content.addView(mask,hero);
  status=new StatusCard(a);content.addView(status,new LinearLayout.LayoutParams(-1,dp(84)));
  addButton("OPEN MIRROR", "Launch your private desktop session  ↗",CYAN,64,()->activity.launchMirror(),true);
  row(new GlassButton(a,"ARM ACCESS","Automatic connection recovery",GREEN,()->activity.arm(),false),new GlassButton(a,"CONNECTION","Controller & pairing",WHITE,()->activity.configure(),false));
  row(new GlassButton(a,"TAILSCALE","Private network",CYAN,()->activity.openTailscale(),false),new GlassButton(a,"DEBUGGING","Wireless connection",WHITE,()->activity.openDebugging(),false));
  row(new GlassButton(a,"SHARE SCREEN","Hotspot / cellular fallback",CYAN,()->activity.shareScreen(),false),new GlassButton(a,"AUTO SHARE SETUP","One-time Accessibility access",WHITE,()->activity.controlSettings(),false));
  addButton("SHARE FILES WITH PC","Select files · received files: Downloads / Ghost Link",GREEN,64,()->activity.pickFiles(),false);
  fx=new GlassButton(a,"EFFECTS  "+(effects?"ON":"OFF"),"Pointer · glow · particles",PURPLE,()->toggleEffects(),false);
  row(new GlassButton(a,"HOME SHORTCUT","One-tap launch",WHITE,()->activity.pin(),false),fx);
  themeButton=new GlassButton(a,themeNames[theme],"Appearance · four DedSec themes",CYAN,()->appearance(),false);LinearLayout.LayoutParams themeLp=new LinearLayout.LayoutParams(-1,dp(54));themeLp.topMargin=dp(10);content.addView(themeButton,themeLp);
  addButton("MINIMIZE","Keep remote access running",WHITE,44,()->minimize(),false);
  addButton("DISARM REMOTE ACCESS","Stop the helper and wireless debugging",0xffffb5a5,54,()->activity.disarm(),false);
  text("PRIVATE TUNNEL  /  PAIRED DEVICE CONTROL",9,CYAN,false,18);
  text("Auto recovery while armed · tap status for connection help.",10,MUTED,false,6);
  setContentDescription("Ghost Link mobile control dashboard");
 }
 void text(String s,int size,int color,boolean bold,int margin){TextView v=new TextView(activity);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));v.setLetterSpacing(bold?.06f:.025f);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(margin);content.addView(v,lp);}
 void row(GlassButton left,GlassButton right){LinearLayout row=new LinearLayout(activity);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(64));lp.topMargin=dp(10);content.addView(row,lp);LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(0,-1,1);l.rightMargin=dp(5);row.addView(left,l);LinearLayout.LayoutParams r=new LinearLayout.LayoutParams(0,-1,1);r.leftMargin=dp(5);row.addView(right,r);}
 void addButton(String title,String sub,int color,int height,Runnable action,boolean primary){GlassButton button=new GlassButton(activity,title,sub,color,action,primary);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(height));lp.topMargin=dp(12);content.addView(button,lp);}
 void toggleEffects(){effects=!effects;activity.getSharedPreferences("link",0).edit().putBoolean("effects",effects).apply();fx.label="EFFECTS  "+(effects?"ON":"OFF");fx.setContentDescription(fx.label+", "+fx.sub);fx.invalidate();if(!effects){trail.clear();bursts.clear();}invalidate();}
 void resume(){animate().cancel();setAlpha(effects?.15f:1);setTranslationY(0);if(effects)animate().alpha(1).setDuration(300).start();pulse("SIGNAL ONLINE",true);active=true;removeCallbacks(frame);post(frame);}
 void pause(){active=false;removeCallbacks(frame);}
 @Override protected void onDraw(Canvas c){
  super.onDraw(c);float w=getWidth(),h=getHeight();p.setShader(new LinearGradient(0,0,w,h,new int[]{dark(.19f),dark(.10f),dark(.06f)},new float[]{0,.48f,1},Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,p);p.setShader(null);
  p.setShader(new RadialGradient(w*.1f,h*.08f,w*.8f,new int[]{0x2539e5ff,0x0039e5ff},null,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,p);p.setShader(null);
  p.setColor(0x09e2f7ff);Path shine=new Path();shine.moveTo(0,0);shine.lineTo(w,0);shine.lineTo(w,h*.18f);shine.lineTo(0,h*.35f);shine.close();c.drawPath(shine,p);
  p.setColor(0x063aa9d0);p.setStrokeWidth(dp(.5f));for(int x=0;x<w;x+=dp(36))c.drawLine(x,0,x,h,p);
 }
 void glass(Canvas c,RectF rect,int accent,float hover,boolean primary){
  accent=themed(accent);
  p.setStyle(Paint.Style.FILL);p.setShader(new LinearGradient(0,rect.top,0,rect.bottom,alpha(dark(primary?.30f:.16f),210),alpha(dark(.08f),225),Shader.TileMode.CLAMP));c.drawRoundRect(rect,dp(16),dp(16),p);p.setShader(null);
  if(hover>0){p.setColor(alpha(accent,(int)(25*hover)));c.drawRoundRect(rect,dp(16),dp(16),p);}
  p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));p.setColor(alpha(accent,(int)(primary?145:45+150*hover)));c.drawRoundRect(rect,dp(16),dp(16),p);
  p.setColor(alpha(WHITE,(int)(45+60*hover)));c.drawLine(rect.left+dp(16),rect.top+dp(1),rect.right-dp(16),rect.top+dp(1),p);p.setStyle(Paint.Style.FILL);
 }
 int alpha(int color,int a){color=themed(color);return (color&0x00ffffff)|(Math.max(0,Math.min(255,a))<<24);}
 void type(Canvas c,String s,float x,float y,int size,int color,boolean bold){p.setShader(null);p.setColor(themed(color));p.setStyle(Paint.Style.FILL);p.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));p.setTextSize(dp(size));c.drawText(s,x,y,p);}
 void track(MotionEvent e,boolean touch){
  long now=SystemClock.uptimeMillis();mx=e.getX();my=e.getY();lastPointer=now;
  int action=e.getActionMasked();pointer=action!=MotionEvent.ACTION_HOVER_EXIT && (!touch || e.getToolType(0)==MotionEvent.TOOL_TYPE_MOUSE);
  if(!touch)hoverEvents++;
  if(effects && (action==MotionEvent.ACTION_HOVER_MOVE||action==MotionEvent.ACTION_MOVE)){trail.addLast(new float[]{mx,my,now});while(trail.size()>20)trail.removeFirst();}
  if(effects&&action==MotionEvent.ACTION_DOWN){bursts.addLast(new float[]{mx,my,now});while(bursts.size()>6)bursts.removeFirst();}
  invalidate();
 }
 @Override public boolean dispatchHoverEvent(MotionEvent e){track(e,false);return super.dispatchHoverEvent(e);}
 @Override public boolean dispatchTouchEvent(MotionEvent e){track(e,true);return super.dispatchTouchEvent(e);}
 @Override public PointerIcon onResolvePointerIcon(MotionEvent e,int index){return PointerIcon.getSystemIcon(getContext(),PointerIcon.TYPE_NULL);}
 @Override protected void dispatchDraw(Canvas c){
  super.dispatchDraw(c);long now=SystemClock.uptimeMillis();
  if(effects){
   float elapsed=(now-pulseStart)/1000f;
   if(elapsed<1.6f){float cx=getWidth()/2f,cy=dp(160),radius=dp(20+elapsed*90);p.setColor(alpha(CYAN,(int)(160*(1-elapsed/1.6f))));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));c.drawOval(cx-radius,cy-radius*.7f,cx+radius,cy+radius*.7f,p);p.setStyle(Paint.Style.FILL);type(c,pulseText,dp(24),dp(64),11,CYAN,true);if(pulseParticles){for(int i=0;i<36;i++){double angle=i*Math.PI*2/36;float dist=dp(elapsed*(45+i%7*11));float x=cx+(float)Math.cos(angle)*dist,y=cy+(float)Math.sin(angle)*dist+dp(22*elapsed*elapsed);c.drawRect(x,y,x+dp(2),y+dp(2),p);}}}
   float scan=(now%8000)/8000f*getHeight();p.setColor(alpha(CYAN,32));p.setStrokeWidth(dp(1));c.drawLine(dp(22),scan,getWidth()-dp(22),scan,p);
   if(now%7000<160){p.setColor(alpha(CYAN,95));p.setStrokeWidth(dp(2));float glitch=dp(120+(now/80%12)*26);c.drawLine(dp(30),glitch,dp(180),glitch,p);}
   for(Iterator<float[]> it=trail.iterator();it.hasNext();){float[] t=it.next();float age=(now-t[2])/350f;if(age>=1){it.remove();continue;}p.setColor(alpha(CYAN,(int)(80*(1-age))));c.drawCircle(t[0],t[1],dp(3)*(1-age),p);}
   for(Iterator<float[]> it=bursts.iterator();it.hasNext();){float[] t=it.next();float age=(now-t[2])/650f;if(age>=1){it.remove();continue;}p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1.3f));p.setColor(alpha(CYAN,(int)(220*(1-age))));c.drawCircle(t[0],t[1],dp(8+40*age),p);p.setStyle(Paint.Style.FILL);for(int j=0;j<8;j++){double a=j*Math.PI/4;p.setColor(alpha(j%2==0?GREEN:CYAN,(int)(220*(1-age))));float dist=dp(12+40*age);c.drawCircle(t[0]+(float)Math.cos(a)*dist,t[1]+(float)Math.sin(a)*dist,dp(1.6f)*(1-age),p);}}
  }
  if(pointer&&now-lastPointer<12000){
   if(effects){p.setShader(new RadialGradient(mx,my,dp(25),new int[]{0x5568edff,0x0068edff},null,Shader.TileMode.CLAMP));c.drawCircle(mx,my,dp(25),p);p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(.8f));p.setColor(0x7768edff);c.drawCircle(mx,my,dp(13),p);p.setStyle(Paint.Style.FILL);}
   Path cursor=new Path();cursor.moveTo(mx,my);cursor.lineTo(mx+dp(3),my+dp(18));cursor.lineTo(mx+dp(7),my+dp(12));cursor.lineTo(mx+dp(14),my+dp(10));cursor.close();p.setColor(0xff091929);c.drawPath(cursor,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1.5f));p.setColor(themeColor());c.drawPath(cursor,p);p.setStyle(Paint.Style.FILL);
  }
 }
 final class GlassButton extends View {
  String label,sub;final int accent;final boolean primary;float hover=0;ValueAnimator animator;
  GlassButton(Context context,String label,String sub,int accent,Runnable action,boolean primary){super(context);this.label=label;this.sub=sub;this.accent=accent;this.primary=primary;setClickable(true);setFocusable(true);setContentDescription(label+", "+sub);setOnClickListener(v->{performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);pulse(label,false);wobble();action.run();});}
  void animateTo(float target){if(animator!=null)animator.cancel();animator=ValueAnimator.ofFloat(hover,target);animator.setDuration(effects?150:0);animator.addUpdateListener(a->{hover=(float)a.getAnimatedValue();invalidate();});animator.start();}
  @Override public boolean onHoverEvent(MotionEvent e){int a=e.getActionMasked();if(a==MotionEvent.ACTION_HOVER_ENTER)animateTo(1);if(a==MotionEvent.ACTION_HOVER_EXIT)animateTo(0);return super.onHoverEvent(e);}
  @Override public void setPressed(boolean pressed){super.setPressed(pressed);animateTo(pressed?1.35f:(isHovered()?1:0));}
  @Override protected void onFocusChanged(boolean gain,int direction,Rect r){super.onFocusChanged(gain,direction,r);animateTo(gain?1:0);}
  @Override public PointerIcon onResolvePointerIcon(MotionEvent e,int index){return PointerIcon.getSystemIcon(getContext(),PointerIcon.TYPE_NULL);}
  @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(Button.class.getName());}
  @Override protected void onDraw(Canvas c){float w=getWidth(),h=getHeight();RectF rect=new RectF(dp(1),dp(1),w-dp(1),h-dp(1));glass(c,rect,accent,hover,primary);type(c,label,dp(16),h*.43f,primary?17:12,hover>.2?WHITE:accent,true);type(c,sub,dp(16),h*.73f,10,MUTED,false);
   if(hover>.01){p.setColor(alpha(accent,(int)(160*Math.min(1,hover))));c.drawRoundRect(dp(1),dp(17),dp(3),h-dp(17),dp(1),dp(1),p);type(c,"›",w-dp(20),h*.48f,17,accent,true);}
  }
 }
 final class StatusCard extends View {
  StatusCard(Context c){super(c);setFocusable(true);setOnClickListener(v->new android.app.AlertDialog.Builder(activity).setTitle("PRIVATE LINK STATUS").setMessage(LinkService.summary(activity)+"\nMouse hover events received: "+hoverEvents).setPositiveButton("Close",null).show());}
  protected void onDraw(Canvas c){boolean armed=LinkService.running;boolean net=!LinkService.tailIp().isEmpty();boolean adb=Settings.Global.getInt(activity.getContentResolver(),"adb_wifi_enabled",0)==1;String label=ScreenService.active?"NATIVE SCREEN SHARING":armed?"FILES READY / "+LinkRecovery.state:"REMOTE ACCESS DISARMED";int color=armed&&adb&&LinkService.adbPort>0?GREEN:CYAN;glass(c,new RectF(dp(1),dp(1),getWidth()-dp(1),getHeight()-dp(1)),CYAN,0,false);type(c,"SAMSUNG S25 ULTRA",dp(17),dp(25),12,WHITE,true);p.setColor(themed(color));c.drawCircle(dp(20),dp(43),dp(2.7f),p);type(c,label,dp(31),dp(47),10,color,false);type(c,"Private tunnel · authorized controller",dp(17),dp(68),10,MUTED,false);setContentDescription("Samsung S25 Ultra. "+label);}
 }
 final class MaskView extends View {
  MaskView(Context c){super(c);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
  protected void onDraw(Canvas c){float x=getWidth()/2f,y=getHeight()/2f;float r=dp(66);float phase=effects?(SystemClock.uptimeMillis()%12000)/12000f*360:30;
   p.setShader(new RadialGradient(x,y,dp(85),new int[]{0x244fcfe5,0x004fcfe5},null,Shader.TileMode.CLAMP));c.drawCircle(x,y,dp(85),p);p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(.7f));p.setColor(0xff31536b);c.drawCircle(x,y,r,p);c.drawCircle(x,y,r-dp(7),p);p.setColor(themeColor());p.setStrokeWidth(dp(1.7f));c.drawArc(x-r,y-r,x+r,y+r,phase,65,false,p);p.setStyle(Paint.Style.FILL);
   for(int i=0;i<5;i++){double a=Math.toRadians(phase+i*72);p.setColor(themeColor());c.drawCircle(x+(float)Math.cos(a)*(r+dp(5)),y+(float)Math.sin(a)*(r+dp(5)),dp(1),p);}
   Path face=new Path();face.moveTo(x-dp(38),y-dp(27));face.lineTo(x+dp(38),y-dp(27));face.lineTo(x+dp(33),y+dp(20));face.lineTo(x,y+dp(38));face.lineTo(x-dp(33),y+dp(20));face.close();p.setColor(0xff12283b);c.drawPath(face,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));p.setColor(0xff527787);c.drawPath(face,p);
   for(int dx:new int[]{-23,14}){p.setColor(themeColor());p.setStrokeWidth(dp(3.2f));c.drawLine(x+dp(dx),y-dp(12),x+dp(dx+10),y-dp(1),p);c.drawLine(x+dp(dx+10),y-dp(12),x+dp(dx),y-dp(1),p);}
   Path grin=new Path();grin.moveTo(x-dp(22),y+dp(14));for(int i=1;i<=6;i++)grin.lineTo(x+dp(-22+i*7),y+dp(i%2==0?14:20));p.setColor(themeColor());p.setStrokeWidth(dp(2));c.drawPath(grin,p);p.setStyle(Paint.Style.FILL);
  }
 }
}
