import unittest
from unittest.mock import patch
from transports import negotiate, lan_endpoint


class TransportTests(unittest.TestCase):
    def setUp(self):
        self.config = dict(device_serial='PHONE', phone_ip='203.0.113.10', adb_port=40000)
        self.calls = []
        self.usb = False
        self.local = True
        self.identity = 'PHONE'

    def run_adb(self, args, timeout):
        self.calls.append(args)
        if args[1:3] == ['devices', '-l']:
            return 'List of devices attached\n' + ('PHONE device usb:1\n' if self.usb else '')
        if args[1:3] == ['mdns', 'services']:
            return 'adb-PHONE-abc _adb-tls-connect._tcp. 192.168.1.2:45678\nadb-OTHER-abc _adb-tls-connect._tcp. 192.168.1.3:45679'
        if args[1] == 'connect':
            if not self.local and args[2].startswith('192.'): raise TimeoutError('local unavailable')
            return 'connected'
        if args[-1] == 'get-state': return 'device'
        if args[-1] == 'ro.serialno': return self.identity
        raise AssertionError(args)

    def select(self, helper):
        return negotiate(self.config, 'adb', self.run_adb, helper, lambda *a: None)

    def test_usb_does_not_need_helper(self):
        self.usb = True
        self.assertEqual(self.select(lambda c: self.fail('helper called')), ('PHONE', 'USB'))

    def test_local_works_when_helper_unreachable(self):
        self.assertEqual(self.select(lambda c: self.fail('helper called')), ('192.168.1.2:45678', 'LOCAL TLS'))
        self.assertFalse(any('192.168.1.3:45679' in c for c in self.calls))

    def test_local_failure_falls_back_to_fresh_tailnet_port(self):
        self.local = False
        self.assertEqual(self.select(lambda c: dict(wireless_debugging=True, adb_port=43210)), ('203.0.113.10:43210', 'TAILSCALE TLS'))
        self.assertEqual(self.config['adb_port'], 43210)

    def test_wrong_identity_rejected_on_every_transport(self):
        self.identity = 'OTHER'
        with self.assertRaisesRegex(RuntimeError, 'identity'):
            self.select(lambda c: dict(wireless_debugging=True, adb_port=43210))

    def test_helper_failure_uses_saved_port(self):
        self.local = False
        def unavailable(c): raise TimeoutError('helper down')
        self.assertEqual(self.select(unavailable)[0], '203.0.113.10:40000')

    def test_lan_candidates_are_private_and_valid(self):
        for value in ['127.0.0.1:80','8.8.8.8:80','192.168.1.1:0','192.168.1.1:65536','host:123']:
            self.assertFalse(lan_endpoint(value))
        self.assertTrue(lan_endpoint('192.168.1.1:12345'))

    def test_existing_connection_survives_missing_discovery_and_helper(self):
        original = self.run_adb
        def run(args, timeout):
            if args[1:3] == ['devices', '-l']:
                return 'List of devices attached\n203.0.113.10:45678 device\n'
            if args[1:3] == ['mdns', 'services']:
                self.fail('working connection should not require discovery')
            return original(args, timeout)
        result = negotiate(self.config, 'adb', run,
                           lambda *a: self.fail('helper called'), lambda *a: None)
        self.assertEqual(result, ('203.0.113.10:45678', 'EXISTING ADB'))

    def test_existing_wrong_device_is_not_reused(self):
        original = self.run_adb
        def run(args, timeout):
            if args[1:3] == ['devices', '-l']:
                return 'List of devices attached\n192.168.1.50:45678 device\n'
            if args[1:3] == ['-s', '192.168.1.50:45678'] and args[-1] == 'ro.serialno':
                return 'OTHER'
            return original(args, timeout)
        result = negotiate(self.config, 'adb', run,
                           lambda *a: self.fail('helper called'), lambda *a: None)
        self.assertEqual(result[0], '192.168.1.2:45678')

    @patch('transports.time.sleep')
    def test_enabled_debugging_waits_for_port_discovery(self, sleep):
        self.local = False
        statuses = iter([dict(wireless_debugging=True, adb_port=0),
                         dict(wireless_debugging=True, adb_port=43210)])
        requests = []
        def helper(config, path='/status', method='GET'):
            requests.append((path, method))
            return {} if path == '/arm' else next(statuses)
        self.assertEqual(self.select(helper)[0], '203.0.113.10:43210')
        self.assertIn(('/arm', 'POST'), requests)


if __name__ == '__main__': unittest.main()
