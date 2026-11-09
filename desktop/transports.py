"""Select an authorized transport to one pinned Android device.

Helper credentials never leave the tailnet. LAN uses Android's paired TLS ADB.
"""
import ipaddress
import time


def lan_endpoint(value):
    try:
        host, port = value.rsplit(':', 1)
        ip = ipaddress.IPv4Address(host)
        return (any(ip in ipaddress.ip_network(n) for n in
                    ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))
                and 1 <= int(port) <= 65535)
    except (ValueError, AttributeError):
        return False


def negotiate(config, adb, run, helper, report):
    failures = []
    serial = config.get('device_serial')

    def verify(endpoint, label, connect=False, trusted=False):
        try:
            if connect:
                run([adb, 'connect', endpoint], timeout=5)
            if run([adb, '-s', endpoint, 'get-state'], timeout=4) != 'device':
                raise RuntimeError('not authorized or offline')
            identity = run([adb, '-s', endpoint, 'shell', 'getprop', 'ro.serialno'], timeout=4).strip()
            if not identity or (serial and identity != serial) or (not serial and not trusted):
                raise RuntimeError('device identity does not match; establish the trusted tailnet connection first')
            config['device_serial'] = identity
            return endpoint, label
        except Exception as error:
            failures.append(label + ': ' + str(error))
            return None

    # A cable is independent of Wi-Fi, hotspot mode and VPN availability.
    try:
        devices = run([adb, 'devices', '-l'], timeout=5).splitlines()[1:]
    except Exception as error:
        failures.append(str(error)); devices = []
    if serial:
        for line in devices:
            fields = line.split()
            if len(fields) > 1 and fields[0] == serial and fields[1] == 'device':
                result = verify(serial, 'USB')
                if result: return result

    # ADB may retain a working authorized connection even when the private
    # helper is down and the cached port has drifted (Android rotates the
    # wireless-debugging port on every toggle/reboot). Always trust the
    # device list ADB itself reports over the cached config value: scan for
    # any live connection to the pinned phone before giving up.
    for line in devices:
        fields = line.split()
        if len(fields) < 2 or fields[1] != 'device':
            continue
        endpoint = fields[0]
        if endpoint.startswith(config['phone_ip'] + ':'):
            result = verify(endpoint, 'EXISTING ADB')
            if result:
                config['adb_port'] = int(endpoint.rsplit(':', 1)[1])
                return result
        if serial and (lan_endpoint(endpoint) or endpoint == serial):
            result = verify(endpoint, 'EXISTING ADB')
            if result: return result

    # mDNS contains the live TLS connection port; never use a pairing service.
    local = []
    if serial:
        try:
            for line in run([adb, 'mdns', 'services'], timeout=5).splitlines():
                fields = line.split()
                if len(fields) >= 3 and fields[0].startswith('adb-' + serial + '-') and fields[1].rstrip('.') == '_adb-tls-connect._tcp' and lan_endpoint(fields[2]):
                    local.append(fields[2])
        except Exception as error:
            failures.append('Discovery: ' + str(error))
        for endpoint in config.get('lan_endpoints', []):
            if lan_endpoint(endpoint): local.append(endpoint)
        for endpoint in dict.fromkeys(local):
            report('NEGOTIATING LINK', 'Checking paired local Wi-Fi / hotspot transport…')
            result = verify(endpoint, 'LOCAL TLS', connect=True)
            if result: return result

    report('NEGOTIATING LINK', 'Checking Tailscale and refreshing the debugging port…')
    port = config.get('adb_port', 0)
    helper_down = False
    try:
        status = helper(config)
        if not status.get('wireless_debugging') or not status.get('adb_port'):
            helper(config, '/arm', 'POST')
            for _ in range(5):
                time.sleep(1)
                status = helper(config)
                if status.get('wireless_debugging') and status.get('adb_port'): break
        port = status.get('adb_port', 0)
        if status.get('recovery_detail'):
            report(status.get('recovery_state', 'PHONE RECOVERY'), status['recovery_detail'])
            failures.append(status['recovery_detail'])
        if not status.get('wireless_debugging'):
            failures.append('Android Wireless debugging is disabled; hotspot hosting may require USB or a Wi-Fi uplink')
    except Exception as error:
        helper_down = True
        failures.append('Private helper unavailable: ' + str(error))
    candidates = []
    for value in (port, config.get('adb_port', 0)):
        try:
            value = int(value)
        except (ValueError, TypeError):
            continue
        if 1 <= value <= 65535:
            candidates.append(config['phone_ip'] + ':' + str(value))
    for endpoint in dict.fromkeys(candidates):
        result = verify(endpoint, 'TAILSCALE TLS', connect=True, trusted=True)
        if result:
            config['adb_port'] = int(endpoint.rsplit(':', 1)[1])
            return result
    if helper_down:
        # The helper rotates the wireless-debugging port; a cached value is
        # only a hint. Ask ADB itself for any live connection to the phone.
        try:
            for line in run([adb, 'devices', '-l'], timeout=5).splitlines()[1:]:
                fields = line.split()
                if len(fields) >= 2 and fields[1] == 'device' and fields[0].startswith(config['phone_ip'] + ':'):
                    result = verify(fields[0], 'TAILSCALE TLS', trusted=True)
                    if result:
                        config['adb_port'] = int(fields[0].rsplit(':', 1)[1])
                        return result
        except Exception as error:
            failures.append('ADB rescan: ' + str(error))
    raise RuntimeError('No authorized transport is available. Connect USB with debugging approved, or enable Wireless debugging on a Wi-Fi network.\n' + '\n'.join(failures)[-1400:])
