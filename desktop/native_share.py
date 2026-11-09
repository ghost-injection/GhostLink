"""ADB-independent sharing over the existing authenticated Tailscale helper."""
import base64
import hashlib
import http.client
import io
import ipaddress
import json
import os
from pathlib import Path
import queue
import tempfile
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox
from PIL import Image, ImageTk

MAX_FILE = 256 * 1024 * 1024


class Client:
    def __init__(self, config):
        self.host = config['phone_ip']
        if ipaddress.ip_address(self.host) not in ipaddress.ip_network('100.64.0.0/10'):
            raise ValueError('Native sharing requires the paired Tailscale address')
        self.token = config['token']

    def connection(self):
        return http.client.HTTPConnection(self.host, 47831, timeout=15)

    def headers(self):
        return {'Authorization': 'Bearer ' + self.token}

    @staticmethod
    def check(response):
        if response.status >= 300:
            body = response.read(8192)
            try:
                detail = json.loads(body).get('error', str(response.status))
            except (ValueError, AttributeError):
                detail = f'Phone returned HTTP {response.status}'
            raise RuntimeError(detail)

    def request(self, path, method='GET', data=None, binary=False):
        conn = self.connection()
        try:
            body = None if data is None else json.dumps(data).encode()
            conn.request(method, path, body=body, headers=self.headers())
            response = conn.getresponse()
            self.check(response)
            body = response.read(4 * 1024 * 1024 + 1)
            if len(body) > 4 * 1024 * 1024:
                raise RuntimeError('Phone response exceeds the size limit')
            return body if binary else json.loads(body)
        finally:
            conn.close()

    def upload(self, path, progress=lambda n, total: None):
        path = Path(path)
        conn = self.connection()
        try:
            with path.open('rb') as source:
                size = os.fstat(source.fileno()).st_size
                if size > MAX_FILE:
                    raise ValueError('File limit is 256 MiB')
                sha = hashlib.file_digest(source, 'sha256').hexdigest()
                source.seek(0)
                headers = self.headers() | {'Content-Length': str(size),
                    'X-File-Name': base64.b64encode(path.name.encode()).decode(),
                    'X-File-SHA256': sha}
                conn.putrequest('POST', '/files')
                for key, value in headers.items():
                    conn.putheader(key, value)
                conn.endheaders()
                sent = 0
                while sent < size:
                    block = source.read(min(65536, size - sent))
                    if not block:
                        raise RuntimeError('File changed during transfer; retry it')
                    conn.send(block)
                    sent += len(block)
                    progress(sent, size)
                response = conn.getresponse()
                self.check(response)
                result = json.loads(response.read(8192))
                if result.get('sha256') != sha or result.get('size') != size:
                    raise RuntimeError('Phone did not confirm file integrity')
                return result
        finally:
            conn.close()

    def download(self, item, destination, progress=lambda n, total: None):
        size = int(item['size'])
        if not 0 <= size <= MAX_FILE:
            raise ValueError('Invalid remote file size')
        # IDs are opaque UUIDs, never paths supplied by the remote peer.
        import uuid
        file_id = str(uuid.UUID(item['id']))
        destination = Path(destination)
        conn = self.connection()
        temp = None
        try:
            conn.request('GET', '/files/' + file_id, headers=self.headers())
            response = conn.getresponse()
            self.check(response)
            if int(response.getheader('Content-Length', '-1')) != size:
                raise RuntimeError('Remote file changed; refresh the file list')
            digest = hashlib.sha256()
            count = 0
            with tempfile.NamedTemporaryFile(dir=destination.parent, prefix='.ghost-', delete=False) as out:
                temp = Path(out.name)
                while count < size:
                    block = response.read(min(65536, size - count))
                    if not block:
                        raise RuntimeError('Transfer interrupted; retry the file')
                    out.write(block)
                    digest.update(block)
                    count += len(block)
                    progress(count, size)
            if digest.hexdigest() != item['sha256']:
                raise RuntimeError('File checksum mismatch; destination was not changed')
            os.replace(temp, destination)
            temp = None
        finally:
            conn.close()
            if temp is not None:
                temp.unlink(missing_ok=True)


class FilesWindow:
    def __init__(self, parent, config):
        self.client = Client(config)
        self.root = tk.Toplevel(parent)
        self.root.title('Ghost Link — Files')
        self.root.geometry('650x400')
        self.events = queue.Queue()
        self.closed = False
        self.busy = False
        self.items = []
        tk.Label(self.root, text='Phone → PC: select files in Ghost Link on the phone.\nPC → phone: received files appear in Downloads / Ghost Link.', justify='left').pack(padx=15, pady=12, anchor='w')
        self.list = tk.Listbox(self.root, exportselection=False)
        self.list.pack(fill='both', expand=True, padx=15)
        actions = tk.Frame(self.root)
        actions.pack(pady=10)
        self.buttons = []
        for title, action in [('Send to phone', self.upload), ('Save selected', self.download),
                              ('Refresh', self.refresh), ('Remove shared copy', self.remove)]:
            button = tk.Button(actions, text=title, command=action)
            button.pack(side='left', padx=4)
            self.buttons.append(button)
        self.status = tk.StringVar(value='Connecting…')
        tk.Label(self.root, textvariable=self.status, wraplength=620).pack(padx=15, pady=8)
        self.root.protocol('WM_DELETE_WINDOW', self.close)
        self.root.after(100, self.tick)
        self.refresh()

    def close(self):
        self.closed = True
        self.root.destroy()

    def work(self, function):
        if self.busy:
            return
        self.busy = True
        self.status.set('Working…')
        for button in self.buttons:
            button.config(state='disabled')
        def run():
            try:
                function()
            except Exception as error:
                self.events.put(('status', f'{error}. You can retry after reconnecting.'))
            finally:
                self.events.put(('done', None))
        threading.Thread(target=run, daemon=True).start()

    def progress(self, n, total):
        # Keep UI updates bounded, even on fast LAN transfers.
        now = time.monotonic()
        if now - getattr(self, '_progress_at', 0) > .15 or n == total:
            self._progress_at = now
            self.events.put(('status', f'Transferring {n / 1048576:.1f} / {total / 1048576:.1f} MiB'))

    def refresh(self):
        def run():
            self.events.put(('files', self.client.request('/files')))
            self.events.put(('status', 'Ready · 256 MiB per file · transfers are checksum verified'))
        self.work(run)

    def upload(self):
        names = filedialog.askopenfilenames(parent=self.root, title='Send files to S25 Ultra')
        if not names:
            return
        def run():
            for name in names:
                self.client.upload(name, self.progress)
            self.events.put(('files', self.client.request('/files')))
            self.events.put(('status', f'Sent {len(names)} file(s) to Downloads / Ghost Link'))
        self.work(run)

    def selected(self):
        selection = self.list.curselection()
        return self.items[selection[0]] if selection else None

    def download(self):
        item = self.selected()
        if item is None:
            return
        # The picker, not a remote filename, determines the destination.
        name = Path(item['name'].replace('\\', '/')).name
        dest = filedialog.asksaveasfilename(parent=self.root, initialfile=name, title='Save phone file')
        if not dest:
            return
        def run():
            self.client.download(item, dest, self.progress)
            self.events.put(('status', 'Saved and checksum verified: ' + dest))
        self.work(run)

    def remove(self):
        item = self.selected()
        if item is None:
            return
        if not messagebox.askyesno('Remove shared copy', 'Remove this copy from Ghost Link? The original file and any copy in Downloads remain.', parent=self.root):
            return
        def run():
            import uuid
            self.client.request('/files/' + str(uuid.UUID(item['id'])), 'DELETE')
            self.events.put(('files', self.client.request('/files')))
            self.events.put(('status', 'Shared copy removed'))
        self.work(run)

    def tick(self):
        if self.closed:
            return
        try:
            while True:
                kind, value = self.events.get_nowait()
                if kind == 'status':
                    self.status.set(value)
                elif kind == 'files':
                    self.items = value
                    self.list.delete(0, 'end')
                    for item in value:
                        self.list.insert('end', f"{item['name']}   ·   {item['size'] / 1048576:.2f} MiB")
                elif kind == 'done':
                    self.busy = False
                    for button in self.buttons:
                        button.config(state='normal')
        except queue.Empty:
            pass
        self.root.after(100, self.tick)


class ScreenWindow:
    def __init__(self, config):
        self.config = config
        self.client = Client(config)
        self.root = tk.Tk()
        self.root.title('Ghost Link — Native screen sharing')
        self.root.geometry('560x850')
        self.stop = threading.Event()
        self.frames = queue.Queue(maxsize=1)
        self.messages = queue.Queue()
        self.commands = queue.Queue(maxsize=8)
        self.status = tk.StringVar(value='Connecting to the phone…')
        tk.Label(self.root, textvariable=self.status, wraplength=530).pack(padx=10, pady=8)
        toolbar = tk.Frame(self.root)
        toolbar.pack()
        for title, action in [('Back', 'back'), ('Home', 'home'), ('Recents', 'recents')]:
            tk.Button(toolbar, text=title, command=lambda a=action: self.control({'action': a})).pack(side='left', padx=4)
        tk.Button(toolbar, text='Files', command=lambda: FilesWindow(self.root, config)).pack(side='left', padx=4)
        tk.Button(toolbar, text='Request approval', command=self.request_approval).pack(side='left', padx=4)
        self.canvas = tk.Canvas(self.root, bg='#090d13', highlightthickness=0)
        self.canvas.pack(fill='both', expand=True, padx=8, pady=8)
        self.canvas.bind('<ButtonPress-1>', self.press)
        self.canvas.bind('<ButtonRelease-1>', self.release)
        self.box = None
        self.pressed = None
        self.root.protocol('WM_DELETE_WINDOW', self.close)
        threading.Thread(target=self.receive, daemon=True).start()
        threading.Thread(target=self.send_controls, daemon=True).start()
        self.root.after(50, self.tick)

    def request_approval(self):
        def request():
            try:
                self.client.request('/share/request', 'POST')
                self.messages.put('On the phone, tap the Ghost Link notification or open Share screen to approve.')
            except Exception as error:
                self.messages.put(str(error))
        threading.Thread(target=request, daemon=True).start()

    def receive(self):
        delay = 1
        prompted = False
        while not self.stop.is_set():
            try:
                status = self.client.request('/status')
                if not status.get('screen_active'):
                    self.frames_put(None)
                    if not prompted:
                        self.client.request('/share/request', 'POST')
                        prompted = True
                    self.messages.put(status.get('screen_error') or 'Approve Share screen in Ghost Link on the phone. Files are available now.')
                    self.stop.wait(2)
                    continue
                prompted = False
                data = self.client.request('/screen', binary=True)
                image = Image.open(io.BytesIO(data))
                image.load()
                self.frames_put(image)
                control = 'Tap / drag to control' if status.get('control_enabled') else 'View only · enable Ghost Link control in phone Accessibility settings'
                self.messages.put('Private fallback · ' + control)
                delay = 1
                self.stop.wait(.18)
            except Exception as error:
                self.frames_put(None)
                self.messages.put(f'Reconnecting: {error}')
                self.stop.wait(delay)
                delay = min(10, delay * 2)

    def frames_put(self, value):
        try:
            self.frames.get_nowait()
        except queue.Empty:
            pass
        self.frames.put_nowait(value)

    def control(self, data):
        try:
            self.commands.put_nowait((time.monotonic(), data))
        except queue.Full:
            self.status.set('Control busy; try again')

    def send_controls(self):
        while not self.stop.is_set():
            try:
                created, data = self.commands.get(timeout=.5)
            except queue.Empty:
                continue
            # Never replay stale clicks after reconnecting.
            if time.monotonic() - created > 1:
                continue
            try:
                self.client.request('/control', 'POST', data)
            except Exception as error:
                self.messages.put(str(error))

    def point(self, event):
        if self.box is None:
            return None
        x, y, w, h = self.box
        if x <= event.x <= x+w and y <= event.y <= y+h:
            return ((event.x-x)/w, (event.y-y)/h)
        return None

    def press(self, event):
        point = self.point(event)
        self.pressed = (point, time.monotonic()) if point else None

    def release(self, event):
        point = self.point(event)
        if self.pressed and point:
            (x, y), started = self.pressed
            self.control({'action': 'gesture', 'x': x, 'y': y, 'x2': point[0], 'y2': point[1],
                          'duration': max(50, min(1500, int((time.monotonic()-started)*1000)))})
        self.pressed = None

    def tick(self):
        if self.stop.is_set():
            return
        try:
            while True:
                self.status.set(self.messages.get_nowait())
        except queue.Empty:
            pass
        try:
            image = self.frames.get_nowait()
            self.canvas.delete('all')
            self.box = None
            if image is not None:
                image.thumbnail((max(1, self.canvas.winfo_width()), max(1, self.canvas.winfo_height())))
                self.photo = ImageTk.PhotoImage(image)
                x = (self.canvas.winfo_width()-image.width)//2
                y = (self.canvas.winfo_height()-image.height)//2
                self.canvas.create_image(x, y, image=self.photo, anchor='nw')
                self.box = (x, y, image.width, image.height)
        except queue.Empty:
            pass
        self.root.after(50, self.tick)

    def close(self):
        self.stop.set()
        self.root.destroy()

    def run(self):
        try:
            self.root.mainloop()
        finally:
            self.stop.set()
            try:
                self.client.request('/share/stop', 'POST')
            except Exception:
                pass  # Phone also stops capture after 90 seconds without a viewer.
