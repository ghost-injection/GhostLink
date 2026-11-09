"""One-shot setup verification on the user's configured phone; changes no Wi-Fi/VPN settings."""
import json
import subprocess
import time
import urllib.error
from pathlib import Path
import ghostlink as g

cfg=g.load_config()
status=g.request(cfg)
assert status['armed'] and status['port_source']=='discovered', status
print('Authenticated helper and automatic port discovery: PASS')
bad=dict(cfg,token='0'*64)
try:
    g.request(bad)
    raise AssertionError('Invalid token was accepted')
except urllib.error.HTTPError as e:
    assert e.code==403
    e.close()
print('Phone rejects invalid token: PASS')
endpoint=f"{cfg['phone_ip']}:{status['adb_port']}"
subprocess.run([cfg['adb'],'-s',endpoint,'shell','settings','put','global','adb_wifi_enabled','0'],timeout=10,check=True)
for _ in range(15):
    time.sleep(.5)
    if not g.request(cfg)['wireless_debugging']:break
else:raise AssertionError('Wireless debugging did not stop')
print('Debugging disabled while helper remains reachable: PASS')
g.request(cfg,'/arm','POST')
for _ in range(25):
    time.sleep(1)
    status=g.request(cfg)
    if status['wireless_debugging'] and status['port_source']=='discovered':
        result=g.command([cfg['adb'],'connect',f"{cfg['phone_ip']}:{status['adb_port']}"])
        if 'connected to' in result:break
else:raise AssertionError('Automatic recovery failed: '+str(status))
for file in [g.CONFIG,g.ROOT/'dist'/'GhostLink'/'config.json']:
    data=json.loads(file.read_text());data['adb_port']=status['adb_port'];file.write_text(json.dumps(data,indent=2))
print('Remote ARM, port discovery, and paired ADB reconnection: PASS',status['adb_port'])
g.request(cfg,'/launch','POST',pc=True)
print('Requested mirror through running PC receiver.')
