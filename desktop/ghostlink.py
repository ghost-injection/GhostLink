"""GHOST//LINK: a paired-ADB controller restricted to the user's Tailscale nodes."""
import argparse
import ctypes
import hmac
import ipaddress
import json
import os
from pathlib import Path
import queue
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from transports import negotiate
import tkinter as tk
from tkinter import messagebox

ROOT = Path(sys.executable).parent if getattr(sys, 'frozen', False) else Path(__file__).resolve().parents[1]
CONFIG = ROOT / 'config.json'
BG, PANEL, LIME, CYAN, WHITE, MUTED = '#090d13', '#121a23', '#c8ff31', '#23e6d9', '#edf2f6', '#8e9eac'
NO_WINDOW = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
PC_PORTS = (47832, 51832, 52832)

def tail_address(value):
    try:
        return ipaddress.ip_address(value) in ipaddress.ip_network('100.64.0.0/10')
    except ValueError:
        return False

def load_config():
    config = json.loads(CONFIG.read_text())
    if not all(tail_address(config[k]) for k in ('phone_ip', 'pc_ip')):
        raise ValueError('Only Tailscale IPv4 addresses are supported.')
    if len(config['token']) < 32:
        raise ValueError('Controller token is missing or too short.')
    for name in ('adb','scrcpy'):
        bundled=ROOT/'tools'/'scrcpy'/(name+'.exe')
        if bundled.exists():config[name]=str(bundled)
    return config

def request(config, path='/status', method='GET', pc=False):
    host = config['pc_ip'] if pc else config['phone_ip']
    ports = PC_PORTS if pc else (47831,)
    last_error = None
    for port in ports:
        req = urllib.request.Request(f'http://{host}:{port}{path}', method=method,
            headers={'Authorization': 'Bearer ' + config['token']})
        try:
            with HTTP.open(req, timeout=2 if pc else 7) as response:
                return json.loads(response.read(8192))
        except (OSError, ValueError) as error:
            last_error = error
    raise last_error

def command(args, timeout=12):
    p = subprocess.run(args, capture_output=True, text=True, timeout=timeout,
                       creationflags=NO_WINDOW)
    if p.returncode:
        raise RuntimeError((p.stderr or p.stdout).strip()[-600:])
    return p.stdout.strip()

class Controller:
    def __init__(self, config, report):
        self.config, self.report = config, report
        self.lock = threading.Lock()
        self.cancelled = threading.Event()
        self.process = None
        self.endpoint = None
        self.logfile = None
    def connect(self, profile='balanced'):
        if not self.lock.acquire(False):
            return
        self.cancelled.clear()
        try:
            if self.endpoint == 'native' and self.process and self.process.poll() is None:
                self.report('NATIVE VIEWER OPEN', 'Use the sharing window; approve Share screen on the phone if requested.')
                return
            adb = self.config.get('adb') or shutil.which('adb')
            scrcpy = self.config.get('scrcpy') or shutil.which('scrcpy')
            if not adb or not scrcpy:
                raise RuntimeError('Install scrcpy and ADB on this PC first.')
            endpoint, transport = negotiate(self.config, adb, command, request, self.report)
            if self.cancelled.is_set(): return
            if self.process and self.process.poll() is None:
                if self.endpoint == endpoint:
                    self.report('LINK ESTABLISHED', 'The mirror is already open.'); return
                self.process.terminate()
                self.process.wait(timeout=4)
            if self.logfile:
                self.logfile.close(); self.logfile = None
            CONFIG.write_text(json.dumps(self.config, indent=2))
            size, bitrate, fps = {'balanced': (1920, '8M', 60), 'travel': (1280, '3M', 30), 'sharp': (2560, '12M', 60)}[profile]
            self.logfile = open(ROOT / 'mirror-session.log', 'a', encoding='utf-8')
            self.process = subprocess.Popen([scrcpy, '-s', endpoint, f'--max-size={size}',
                f'--video-bit-rate={bitrate}', f'--max-fps={fps}', '--window-title=Ghost Link // S25 ULTRA'],
                stdout=self.logfile, stderr=self.logfile, creationflags=NO_WINDOW)
            self.endpoint = endpoint
            time.sleep(2)
            if self.process.poll() is not None:
                raise RuntimeError('scrcpy exited. Open mirror-session.log for the connection error.')
            self.report('LINK ESTABLISHED', f'S25 Ultra  →  {endpoint}\n{transport} · {profile.upper()} profile')
        except Exception as error:
            if not self.cancelled.is_set():
                try:
                    self._start_native()
                except Exception as fallback_error:
                    self.report('ACTION NEEDED', str(error) + '\nNative fallback: ' + str(fallback_error))
        finally:
            self.lock.release()

    def _start_native(self):
        status = request(self.config)
        if not status.get('native_share'):
            raise RuntimeError('Install phone helper 1.6 or later for hotspot screen sharing and files.')
        if self.cancelled.is_set():
            return
        if self.process and self.process.poll() is None:
            if self.endpoint == 'native':
                return
            self.process.terminate()
            self.process.wait(timeout=4)
        args = [sys.executable]
        if not getattr(sys, 'frozen', False):
            args.append(str(Path(__file__).resolve()))
        self.process = subprocess.Popen(args + ['--native'], creationflags=NO_WINDOW)
        self.endpoint = 'native'
        self.report('NATIVE VIEWER OPEN', 'ADB is not needed. Approve Share screen on the phone. Files are available independently; touch control requires phone Accessibility approval.')

    def connect_native(self):
        if not self.lock.acquire(False):
            return
        self.cancelled.clear()
        try:
            self._start_native()
        except Exception as error:
            self.report('ACTION NEEDED', str(error))
        finally:
            self.lock.release()
    def disconnect(self):
        self.cancelled.set()
        if self.endpoint == 'native':
            def stop_share():
                try: request(self.config, '/share/stop', 'POST')
                except Exception: pass
            threading.Thread(target=stop_share, daemon=True).start()
        if self.process and self.process.poll() is None:
            self.process.terminate()
        if self.logfile:
            self.logfile.close(); self.logfile = None
        self.report('SESSION CLOSED', 'Phone stays armed for your next connection.')

def make_handler(config, events):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def setup(self):
            super().setup(); self.connection.settimeout(4)
        def do_POST(self):
            if self.client_address[0] not in (config['phone_ip'], config['pc_ip']):
                self.send_error(403); return
            if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + config['token']):
                self.send_error(403); return
            if self.path not in ('/launch', '/show', '/native', '/files'):
                self.send_error(404); return
            events.put((self.path[1:], ''))
            data = b'{"accepted":true}'
            self.send_response(202); self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
    return Handler

def serve(config, events, stop):
    # Outbound polling also handles phone launch actions when Windows refuses
    # inbound connections. No firewall change or public listener is required.
    threading.Thread(target=poll_phone, args=(config, events, stop), daemon=True).start()
    while not stop.is_set():
        for port in PC_PORTS:
            try:
                with ThreadingHTTPServer((config['pc_ip'], port), make_handler(config, events)) as server:
                    server.daemon_threads = True; server.timeout = 1
                    events.put(('listener', f'Phone launch receiver is online. Port {port}.'))
                    while not stop.is_set(): server.handle_request()
                return
            except OSError as error:
                last_error = error
        events.put(('listener', 'Waiting for Tailscale receiver: ' + str(last_error)))
        stop.wait(8)


def poll_phone(config, events, stop):
    delay = 2
    last_id = None
    while not stop.is_set():
        try:
            response = request(config, '/desktop/poll')
            action = response.get('action')
            if action in ('launch', 'native', 'files', 'show'):
                import uuid
                request_id = str(uuid.UUID(response['id']))
                if request_id != last_id:
                    events.put((action, ''))
                    last_id = request_id
                request(config, '/desktop/ack/' + request_id, 'POST')
            delay = 2
        except Exception:
            delay = min(15, delay * 2)
        stop.wait(delay)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--background',action='store_true');parser.add_argument('--connect',action='store_true');parser.add_argument('--native',action='store_true');args=parser.parse_args()
    config=load_config()
    if args.native:
        from native_share import ScreenWindow
        ScreenWindow(config).run()
        return
    # A named mutex prevents multiple hidden controllers and duplicate mirrors.
    mutex=None
    if os.name=='nt':
        kernel=ctypes.WinDLL('kernel32',use_last_error=True);kernel.CreateMutexW.restype=ctypes.c_void_p
        mutex=kernel.CreateMutexW(None,False,'Local\\GhostLinkController')
        if ctypes.get_last_error()==183:
            try:request(config,'/launch' if not args.background else '/show','POST',pc=True)
            except Exception:messagebox.showerror('GHOST LINK','Controller is already running. Connect Tailscale to show it again.')
            return
    from widget import App
    app=App(config,Controller,serve,ROOT,args.background)
    if not args.background:app.root.after(300,app.connect)
    app.run()

if __name__=='__main__':main()
