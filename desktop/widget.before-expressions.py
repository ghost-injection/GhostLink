"""Compact glass control surface. Animations express connection state, not fake metrics."""
import math
import life_theme
import ctypes
import os
import queue
import threading
import time
import tkinter as tk
from tkinter import messagebox
from PIL import Image, ImageDraw, ImageFilter, ImageTk, ImageOps

W, H = 430, 554
INK, WHITE, MUTED = '#0b1222', '#edf8ff', '#8c9eb5'
CYAN, GREEN, PURPLE = '#68edff', '#baff70', '#af91ff'

def glass_image(accent=None, density=1):
    scale=3; size=(W*scale,H*scale)
    im=Image.new('RGBA',size)
    d=ImageDraw.Draw(im)
    for y in range(size[1]):
        t=y/size[1]
        d.line((0,y,size[0],y), fill=(int(23-15*t),int(35-21*t),int(56-28*t),255))
    glow=Image.new('RGBA',size)
    g=ImageDraw.Draw(glow)
    g.ellipse((-180,-220,650,500),fill=(43,170,204,45))
    g.ellipse((850,250,1500,1200),fill=(116,55,209,33))
    im=Image.alpha_composite(im,glow.filter(ImageFilter.GaussianBlur(125)))
    d=ImageDraw.Draw(im)
    def box(x0,y0,x1,y1,r,fill,outline):
        d.rounded_rectangle(tuple(int(v*scale) for v in (x0,y0,x1,y1)),radius=r*scale,fill=fill,outline=outline,width=scale)
    box(1,1,W-2,H-2,25,None,(123,191,221,110))
    # Broad specular reflection and an etched upper edge.
    shine=Image.new('RGBA',size);sd=ImageDraw.Draw(shine)
    sd.polygon([(0,0),(size[0],0),(size[0],95*scale),(0,210*scale)],fill=(210,240,255,10))
    im=Image.alpha_composite(im,shine);d=ImageDraw.Draw(im)
    d.line((30*scale,2*scale,(W-30)*scale,2*scale),fill=(210,247,255,125),width=scale)
    box(24,227,406,312,16,(5,13,26,160),(115,190,225,40))
    box(24,329,406,366,12,(6,13,25,150),(115,190,225,28))
    box(24,383,406,435,15,(48,105,127,150),(117,235,255,140))
    d.line((42*scale,385*scale,388*scale,385*scale),fill=(211,251,255,120),width=scale)
    mask=Image.new('L',size);ImageDraw.Draw(mask).rounded_rectangle((0,0,size[0]-1,size[1]-1),radius=26*scale,fill=255)
    im.putalpha(mask)
    im=im.resize((round(W*density),round(H*density)),Image.Resampling.LANCZOS)
    if accent:
        alpha=im.getchannel("A")
        im=ImageOps.colorize(ImageOps.grayscale(im),"#030706",accent).convert("RGBA");im.putalpha(alpha)
    return im

class DensityCanvas(tk.Canvas):
    def __init__(self,master,density,**kwargs):
        self.density=density
        kwargs['width']=round(kwargs['width']*density);kwargs['height']=round(kwargs['height']*density)
        super().__init__(master,**kwargs)
    def _create(self,itemType,args,kw):
        args=tk._flatten(args)
        if args and isinstance(args[-1],dict):
            opts=args[-1];args=args[:-1];kw={**opts,**kw}
        if 'width' in kw:kw['width']*=self.density
        return super()._create(itemType,tuple(v*self.density for v in args),kw)
    def coords(self,tag,*args):
        if args:return super().coords(tag,*(v*self.density for v in tk._flatten(args)))
        return [v/self.density for v in super().coords(tag)]

class App:
    def __init__(self,config,controller_factory,server,root_path,background=False):
        self.config=config;self.events=queue.Queue();self.stop=threading.Event()
        if os.name=="nt":ctypes.windll.user32.SetProcessDPIAware()
        self.root=tk.Tk();r=self.root
        self.density=max(1,r.winfo_fpixels("1i")/96)
        r.title('Ghost Link — Command glass');r.overrideredirect(True)
        r.geometry(f'{round(W*self.density)}x{round(H*self.density)}+{max(20,r.winfo_screenwidth()-round(W*self.density)-40)}+70')
        r.configure(bg='#010203');r.attributes('-transparentcolor','#010203');r.attributes('-alpha',.95)
        if os.name=='nt':
            # Keep the frameless widget in the taskbar and Alt-Tab, unlike a tool window.
            r.update_idletasks()
            user=ctypes.WinDLL('user32',use_last_error=True)
            user.GetParent.argtypes=[ctypes.c_void_p];user.GetParent.restype=ctypes.c_void_p
            user.GetWindowLongW.argtypes=[ctypes.c_void_p,ctypes.c_int]
            user.SetWindowLongW.argtypes=[ctypes.c_void_p,ctypes.c_int,ctypes.c_long]
            handle=user.GetParent(r.winfo_id())
            style=user.GetWindowLongW(handle,-20)
            user.SetWindowLongW(handle,-20,(style|0x00040000)&~0x00000080)
        if (root_path/'ghostlink.ico').exists():r.iconbitmap(str(root_path/'ghostlink.ico'))
        self.c=DensityCanvas(r,self.density,width=W,height=H,bg='#010203',highlightthickness=0)
        self.c.pack();c=self.c
        self.back=ImageTk.PhotoImage(glass_image(density=self.density));self.background_id=c.create_image(0,0,image=self.back,anchor='nw')
        self.controller=controller_factory(config,lambda a,b:self.events.put(('status',(a,b))))
        self.profile='balanced';self.phase='CONNECTING';self.connected=False;self.effects=True;self.pinned=False;self.frame=0
        self.theme,self.effects=life_theme.read();self.theme_time=0;self.last_theme_color=None
        self.drag=None;self.last_status='';self.receiver_online=False
        self.reconnect_enabled=False;self.reconnect_at=None;self.retry_delay=3
        self.text(25,28,'G H O S T   / /   L I N K',11,WHITE,'nw',True)
        self.text(25,49,'PRIVATE DEVICE CONTROL',8,MUTED,'nw')
        self.action(321,18,345,44,'◇',self.toggle_pin,CYAN)
        self.action(349,18,373,44,'−',self.minimize,MUTED)
        self.action(379,18,405,44,'×',self.quit,MUTED)
        c.tag_bind('drag','<ButtonPress-1>',self.start_drag)
        # The header's empty area is also a drag target.
        c.bind('<ButtonPress-1>',self.start_drag,add='+');c.bind('<B1-Motion>',self.move);c.bind('<ButtonRelease-1>',self.wobble)
        self.ring=c.create_oval(144,78,286,220,outline='#294e64',width=1)
        self.arc=c.create_arc(144,78,286,220,start=30,extent=75,style='arc',outline=CYAN,width=2)
        c.create_oval(153,87,277,211,fill='#101d30',outline='#365064')
        # Etched face with layered neon strokes.
        face=[174,117,256,117,250,169,215,187,180,169]
        c.create_polygon(face,fill='#122438',outline='#476879',width=1)
        for x in (187,229):
            for width,color in ((8,'#263e4c'),(4,GREEN)):
                c.create_line(x,132,x+13,145,fill=color,width=width,tags='eyes')
                c.create_line(x+13,132,x,145,fill=color,width=width,tags='eyes')
        c.create_line(190,160,198,167,206,160,214,167,222,160,230,167,238,160,fill=CYAN,width=2)
        self.particles=[]
        for i in range(5):self.particles.append(c.create_oval(0,0,3,3,fill=CYAN,outline=''))
        self.text(43,245,'SAMSUNG S25 ULTRA',10,WHITE,'nw',True)
        self.dot=c.create_oval(43,272,49,278,fill=CYAN,outline='')
        self.status=self.text(58,268,'ESTABLISHING PRIVATE LINK',9,CYAN,'nw')
        self.subtitle=self.text(43,291,'USB · Local TLS · Tailscale',8,MUTED,'nw')
        self.profile_items={}
        for key,label,x in [('travel','TRAVEL',88),('balanced','BALANCED',215),('sharp','SHARP',342)]:
            self.profile_items[key]=self.action(x-58,333,x+58,362,label,lambda k=key:self.select_profile(k),CYAN if key=='balanced' else MUTED,size=9)
        self.primary=self.action(25,384,405,434,'CONNECTING…',self.connect,WHITE,size=12,bold=True)
        self.action(28,447,140,479,'END SESSION',self.disconnect,MUTED,size=8)
        self.action(160,447,274,479,'EFFECTS  ON' if self.effects else 'EFFECTS  OFF',self.toggle_effects,CYAN,size=8,tag='fx')
        self.action(300,447,403,479,'APPEARANCE',self.appearance,MUTED,size=8)
        c.create_line(25,492,405,492,fill='#26394c')
        self.receiver=self.text(25,509,'●  PRIVATE RECEIVER STARTING',8,MUTED,'nw')
        self.theme_button=self.action(25,528,405,551,life_theme.NAMES[self.theme],self.cycle_theme,CYAN,size=8)
        self.scan=c.create_line(25,75,405,75,fill=CYAN,width=1,state='hidden')
        self.glitch=c.create_line(25,120,150,120,fill=CYAN,width=2,state='hidden')
        self.detail='Connecting automatically. Drag the header to move this widget.'
        self.mouse=None;self.hover_region=None;self.ripples=[];self.trail=[];self.pulse_born=0;self.transition=False;self.sparks=[]
        halo=Image.new('RGBA',(64,64));hd=ImageDraw.Draw(halo)
        hd.ellipse((18,18,46,46),fill=(104,237,255,70))
        self.halo_image=ImageTk.PhotoImage(halo.filter(ImageFilter.GaussianBlur(10)))
        self.hover_line=c.create_polygon(0,0,0,0,fill='',outline=CYAN,width=1,smooth=True,state='hidden')
        self.cursor_glow=c.create_image(0,0,image=self.halo_image,state='hidden')
        self.cursor_ring=c.create_oval(0,0,0,0,outline='#46788b',width=1,state='hidden')
        self.cursor_shape=c.create_polygon(0,0,0,0,fill='#0b1a2a',outline=CYAN,width=1.3,state='hidden')
        c.bind('<Motion>',self.pointer_move)
        c.bind('<Leave>',self.pointer_leave)
        self.pulse_label=self.text(215,204,'',9,CYAN)
        self.pulse_ring=c.create_oval(0,0,0,0,outline=CYAN,state='hidden')
        self.status_tooltip=None
        c.tag_bind(self.status,'<Button-1>',lambda e:messagebox.showinfo('Connection details',self.detail,parent=r))
        r.bind('<Escape>',lambda e:self.minimize())
        r.protocol('WM_DELETE_WINDOW',self.minimize)
        threading.Thread(target=server,args=(config,self.events,self.stop),daemon=True).start()
        r.after(100,self.tick)
        if background:r.withdraw()
        else:self.pulse("SYSTEM ONLINE",True)
    def text(self,x,y,value,size,color,anchor='center',bold=False):
        return self.c.create_text(x,y,text=value,font=('Segoe UI',size,'bold' if bold else 'normal'),fill=color,anchor=anchor)
    def action(self,x0,y0,x1,y1,label,fn,color,size=10,bold=False,tag=None):
        tag=tag or f'action{len(self.c.find_all())}'
        # A transparent stippled hit area keeps the artwork underneath visible.
        hit=self.c.create_rectangle(x0,y0,x1,y1,fill='',outline='',tags=tag)
        item=self.text((x0+x1)/2,(y0+y1)/2,label,size,color,bold=bold);self.c.addtag_withtag(tag,item)
        # Canvas empty rectangles do not catch their interior: route by bounded coordinates too.
        if not hasattr(self,'regions'):self.regions=[]
        self.regions.append((x0,y0,x1,y1,fn,item))
        return item
    def start_drag(self,event):
        event.x/=self.density;event.y/=self.density
        if self.effects:
            wave=self.c.create_oval(event.x-3,event.y-3,event.x+3,event.y+3,outline=CYAN,width=1.3)
            self.ripples.append((wave,event.x,event.y,time.monotonic()))
        for x0,y0,x1,y1,fn,item in self.regions:
            if x0<=event.x<=x1 and y0<=event.y<=y1:
                self.drag=None;fn();return
        if event.y<75:self.drag=(event.x_root-self.root.winfo_x(),event.y_root-self.root.winfo_y())
    def pointer_move(self,event):
        event.x/=self.density;event.y/=self.density
        self.mouse=(event.x,event.y);self.c.configure(cursor='none')
        region=next((v for v in self.regions if v[0]<=event.x<=v[2] and v[1]<=event.y<=v[3]),None)
        self.hover_region=region
        if region:
            x0,y0,x1,y1,_,_=region;r=min(12,(y1-y0)/3)
            self.c.coords(self.hover_line,x0+r,y0,x1-r,y0,x1,y0,x1,y0+r,x1,y1-r,x1,y1,x1-r,y1,x0+r,y1,x0,y1,x0,y1-r,x0,y0+r,x0,y0)
            self.c.itemconfigure(self.hover_line,state='normal')
        else:self.c.itemconfigure(self.hover_line,state='hidden')
        if self.effects:
            dot=self.c.create_oval(event.x-1,event.y-1,event.x+1,event.y+1,fill=CYAN,outline='')
            self.trail.append((dot,time.monotonic()))
    def pointer_leave(self,event):
        self.mouse=None;self.hover_region=None
        for item in (self.hover_line,self.cursor_glow,self.cursor_ring,self.cursor_shape):self.c.itemconfigure(item,state='hidden')
    def move(self,event):
        if self.drag:self.root.geometry(f'+{max(0,event.x_root-self.drag[0])}+{max(0,event.y_root-self.drag[1])}')
    def pulse(self,message,particles=False):
        if not self.effects:return
        self.pulse_born=time.monotonic()
        self.c.itemconfigure(self.pulse_label,text=message)
        for item,*_ in self.sparks:self.c.delete(item)
        self.sparks=[]
        if particles:
            for i in range(36):
                angle=i*math.tau/36;speed=45+(i%7)*11
                item=self.c.create_rectangle(213,147,217,151,fill=CYAN,outline='')
                self.sparks.append((item,math.cos(angle)*speed,math.sin(angle)*speed))
    def minimize(self):
        if self.transition:return
        if not self.effects:self.root.withdraw();return
        self.transition=True;self.pulse('SIGNAL SUSPENDED')
        def frame(n=0):
            self.root.attributes('-alpha',max(.08,.95*(1-n/12)))
            if n<12:self.root.after(25,lambda:frame(n+1))
            else:self.root.withdraw();self.root.attributes('-alpha',.95);self.transition=False
        frame()
    def restore(self):
        self.root.deiconify();self.root.lift()
        self.pulse('SIGNAL RESTORED',True)
        if not self.effects:return
        def frame(n=0):
            self.root.attributes('-alpha',min(.95,.15+n*.08))
            if n<10:self.root.after(25,lambda:frame(n+1))
        frame()
    def appearance(self):
        menu=tk.Menu(self.root,tearoff=False,bg='#0b121c',fg=WHITE,activebackground='#203b30',activeforeground=CYAN)
        for i,name in enumerate(life_theme.NAMES):
            menu.add_command(label=('● ' if i==self.theme else '   ')+name,command=lambda index=i:self.set_theme(index))
        menu.add_separator();menu.add_command(label='Visual effects: '+('ON' if self.effects else 'OFF'),command=self.toggle_effects)
        menu.add_command(label='⚡ TEST EFFECTS',command=lambda:self.pulse('SYSTEM OVERDRIVE',True))
        menu.add_separator();menu.add_command(label='Connection help',command=self.help)
        menu.tk_popup(self.root.winfo_x()+240,self.root.winfo_y()+340)
    def set_theme(self,index):
        self.theme=index;self.refresh_glass();life_theme.save(self.theme,self.effects)
        self.c.itemconfigure(self.theme_button,text=life_theme.NAMES[self.theme]);self.pulse('THEME LOADED')
    def cycle_theme(self):
        self.theme=(self.theme+1)%4
        self.refresh_glass()
        life_theme.save(self.theme,self.effects)
        self.c.itemconfigure(self.theme_button,text=life_theme.NAMES[self.theme])
    def refresh_glass(self):
        self.back=ImageTk.PhotoImage(glass_image(life_theme.color(self.theme,self.theme_time),self.density))
        self.c.itemconfigure(self.background_id,image=self.back)
    def paint_theme(self):
        global CYAN,GREEN,PURPLE
        accent=life_theme.color(self.theme,self.theme_time)
        if accent==self.last_theme_color:return
        if self.last_theme_color is None:self.refresh_glass()
        old={CYAN.lower(),GREEN.lower(),PURPLE.lower()}
        for item in self.c.find_all():
            for option in ('fill','outline'):
                try:
                    if self.c.itemcget(item,option).lower() in old:self.c.itemconfigure(item,**{option:accent})
                except tk.TclError:pass
        CYAN=GREEN=PURPLE=accent
        self.last_theme_color=accent
    def wobble(self,event=None):
        if not self.drag:return
        self.drag=None
        if not self.effects:return
        x,y=self.root.winfo_x(),self.root.winfo_y()
        def step(n=0):
            if self.drag or self.transition:return
            offset=round(4*self.density*math.exp(-n/4)*math.sin(n*1.5))
            self.root.geometry(f'+{max(0,x+offset)}+{max(0,y)}')
            if n<15:self.root.after(24,lambda:step(n+1))
        step()
    def toggle_pin(self):
        self.pinned=not self.pinned;self.root.attributes('-topmost',self.pinned)
    def toggle_effects(self):
        self.effects=not self.effects
        life_theme.save(self.theme,self.effects)
        for region in self.regions:
            if region[4]==self.toggle_effects:self.c.itemconfigure(region[5],text='EFFECTS  ON' if self.effects else 'EFFECTS  OFF')
    def select_profile(self,key):
        self.profile=key
        for k,item in self.profile_items.items():self.c.itemconfigure(item,fill=CYAN if k==key else MUTED)
        if self.connected:
            self.controller.disconnect()
            self.root.after(350,self.connect)
    def connect(self):
        self.reconnect_enabled=True;self.reconnect_at=None
        threading.Thread(target=self.controller.connect,args=(self.profile,),daemon=True).start()
    def disconnect(self):
        self.reconnect_enabled=False;self.reconnect_at=None
        self.controller.disconnect()
    def help(self):
        messagebox.showinfo('Ghost Link',self.detail+'\n\nOpen this widget to start mirroring automatically. Drag the header to move it; ◇ toggles always-on-top. Minus hides it; × quits.\n\nAuto selection: authorized USB, paired local Wi-Fi, then Tailscale. Hotspot hosting may disable Wireless debugging; connect USB in that case. Click the status line for connection details. After a reboot Android may require local approval.\n\nTravel: 1280 / 30 FPS cap. Balanced: 1920 / 60. Sharp: 2560 / 60. Effects are decorative, not traffic measurements.',parent=self.root)
    def update_status(self,title,detail):
        if title=='LINK ESTABLISHED':
            self.retry_delay=3;self.reconnect_at=None
        elif title in ('ACTION NEEDED','SESSION CLOSED') and self.reconnect_enabled:
            self.reconnect_at=time.monotonic()+self.retry_delay
            detail+=f'\nAutomatic reconnect in {self.retry_delay} seconds. END SESSION stops retries.'
            self.retry_delay=min(60,self.retry_delay*2)
        if title=='LINK ESTABLISHED' and not self.connected:self.pulse('LINK ESTABLISHED',True)
        self.last_status=title;self.detail=detail;self.connected=title=='LINK ESTABLISHED'
        color=GREEN if self.connected else '#ffb983' if title=='ACTION NEEDED' else CYAN
        label='CONNECTED  /  PAIRED DEVICE' if self.connected else title
        self.c.itemconfigure(self.status,text=label,fill=color);self.c.itemconfigure(self.dot,fill=color)
        self.c.itemconfigure(self.primary,text='MIRROR IS LIVE  ↗' if self.connected else 'RECONNECT' if title in ('ACTION NEEDED','SESSION CLOSED') else 'CONNECTING…')
        self.c.itemconfigure(self.subtitle,text='Click status for recovery steps' if title=='ACTION NEEDED' else 'USB · Local TLS · Tailscale')
    def tick(self):
        try:
            while True:
                kind,data=self.events.get_nowait()
                if kind=='status':self.update_status(*data)
                elif kind=='listener':
                    self.receiver_online='is online' in data
                    self.c.itemconfigure(self.receiver,text='●  PHONE LAUNCH RECEIVER ONLINE' if self.receiver_online else '○  WAITING FOR TAILSCALE',fill=MUTED)
                elif kind in ('show','launch'):
                    self.restore();self.connect()
        except queue.Empty:pass
        if self.controller.process and self.controller.process.poll() is not None and self.connected:
            self.update_status('SESSION CLOSED','Ready to reconnect. Your phone remains armed.')
        if self.reconnect_enabled and self.reconnect_at is not None and time.monotonic()>=self.reconnect_at:
            self.connect()
        if self.effects and self.root.state()!='withdrawn':
            self.frame+=1;phase=self.frame*.035
            self.c.itemconfigure(self.arc,start=(self.frame*1.1)%360,extent=48+20*math.sin(phase))
            for i,p in enumerate(self.particles):
                a=phase+i*math.tau/5;x=215+76*math.cos(a);y=149+76*math.sin(a)
                self.c.coords(p,x-1,y-1,x+1,y+1)
            self.c.itemconfigure(self.ring,outline='#3b687c' if math.sin(phase)>0 else '#294e64')
        now=time.monotonic()
        if self.effects:self.theme_time=now
        self.paint_theme()
        visible=self.effects and self.root.state()!='withdrawn'
        self.c.itemconfigure(self.scan,state='normal' if visible else 'hidden')
        self.c.coords(self.scan,25,76+(now%8)/8*414,405,76+(now%8)/8*414)
        self.c.itemconfigure(self.glitch,state='normal' if visible and now%7<.16 else 'hidden')
        self.c.coords(self.glitch,32,120+(int(now*12)%12)*26,180,120+(int(now*12)%12)*26)
        age=now-self.pulse_born
        pulse_on=self.effects and 0<=age<1.6
        self.c.itemconfigure(self.pulse_ring,state='normal' if pulse_on else 'hidden')
        self.c.itemconfigure(self.pulse_label,state='normal' if pulse_on else 'hidden')
        radius=20+age*70;self.c.coords(self.pulse_ring,215-radius,149-radius*.7,215+radius,149+radius*.7)
        for item,vx,vy in self.sparks:
            self.c.itemconfigure(item,state='normal' if pulse_on else 'hidden')
            self.c.coords(item,215+vx*age,149+vy*age+22*age*age,218+vx*age,152+vy*age+22*age*age)
        for dot,born in self.trail[:]:
            age=(now-born)/.24
            if age>=1 or not self.effects:self.c.delete(dot);self.trail.remove((dot,born))
            else:self.c.itemconfigure(dot,fill='#46788b' if age>.5 else CYAN)
        for item,x,y,born in self.ripples[:]:
            age=(now-born)/.5
            if age>=1 or not self.effects:self.c.delete(item);self.ripples.remove((item,x,y,born))
            else:
                radius=4+25*age;self.c.coords(item,x-radius,y-radius,x+radius,y+radius)
                self.c.itemconfigure(item,outline=CYAN if age<.4 else '#46788b')
        if self.mouse:
            x,y=self.mouse
            self.c.coords(self.cursor_glow,x,y);self.c.coords(self.cursor_ring,x-11,y-11,x+11,y+11)
            self.c.coords(self.cursor_shape,x,y,x+3,y+16,x+7,y+10,x+13,y+8)
            for item in (self.cursor_glow,self.cursor_ring):self.c.itemconfigure(item,state='normal' if self.effects else 'hidden');self.c.tag_raise(item)
            self.c.itemconfigure(self.cursor_shape,state='normal');self.c.tag_raise(self.cursor_shape)
        self.root.after(40 if self.effects and self.root.state()!='withdrawn' else 150,self.tick)
    def quit(self):
        self.stop.set();self.controller.disconnect();self.root.destroy()
    def run(self):self.root.mainloop()
