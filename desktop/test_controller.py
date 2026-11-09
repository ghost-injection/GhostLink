import importlib.util
import json
from pathlib import Path
import queue
import threading
import unittest
from unittest.mock import patch, Mock
import urllib.request
import urllib.error
from http.server import ThreadingHTTPServer

spec=importlib.util.spec_from_file_location('ghostlink',Path(__file__).with_name('ghostlink.py'))
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)

class BoundaryTests(unittest.TestCase):
    def test_receiver_uses_alternate_port_when_default_is_unavailable(self):
        events=queue.Queue();stop=threading.Event();ports=[]
        class FakeServer:
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def handle_request(self):stop.set()
        def factory(address,handler):
            ports.append(address[1])
            if address[1]==47832:raise OSError('port unavailable')
            return FakeServer()
        with patch.object(g,'ThreadingHTTPServer',side_effect=factory):
            g.serve({'pc_ip':'100.64.0.1'},events,stop)
        self.assertEqual(ports,[47832,51832])
        self.assertIn('51832',events.get()[1])

    def test_adb_failure_opens_native_fallback(self):
        reports=[];controller=g.Controller({'adb':'adb','scrcpy':'scrcpy'},lambda *args:reports.append(args))
        process=Mock();process.poll.return_value=None
        with patch.object(g,'negotiate',side_effect=RuntimeError('debugging off')), patch.object(g,'request',return_value={'native_share':True}), patch.object(g.subprocess,'Popen',return_value=process) as launch:
            controller.connect()
        self.assertEqual(controller.endpoint,'native')
        self.assertIn('--native',launch.call_args.args[0])
        self.assertEqual(reports[-1][0],'NATIVE VIEWER OPEN')

    def test_only_tailnet_addresses(self):
        for ip in ['100.64.0.1','100.127.255.254']:self.assertTrue(g.tail_address(ip))
        for ip in ['127.0.0.1','192.168.1.2','100.63.255.255','100.128.0.1','example.com','']:self.assertFalse(g.tail_address(ip))
    def test_receiver_requires_token_and_known_action(self):
        config={'pc_ip':'127.0.0.1','phone_ip':'127.0.0.2','token':'a'*48}
        events=queue.Queue()
        server=ThreadingHTTPServer(('127.0.0.1',0),g.make_handler(config,events))
        threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            base=f'http://127.0.0.1:{server.server_port}'
            for path,token,code in [('/launch','bad',403),('/execute','a'*48,404)]:
                req=urllib.request.Request(base+path,method='POST',headers={'Authorization':'Bearer '+token})
                with self.assertRaises(urllib.error.HTTPError) as ctx:g.HTTP.open(req)
                self.assertEqual(ctx.exception.code,code)
                ctx.exception.close()
            self.assertTrue(events.empty())
            req=urllib.request.Request(base+'/launch',method='POST',headers={'Authorization':'Bearer '+'a'*48})
            with g.HTTP.open(req) as response:self.assertEqual(response.status,202)
            self.assertEqual(events.get_nowait(),('launch',''))
        finally:server.shutdown();server.server_close()

if __name__=='__main__':unittest.main()
