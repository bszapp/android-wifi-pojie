#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import sys
import subprocess
import os
import tempfile
import shutil
import codecs
import socket
import pathlib
import time
from datetime import datetime
import csv
import argparse
import json
import queue
import threading
import signal
import re
import errno
import struct
from contextlib import closing as close_resource


RESTART_AFTER_EXCLUDED_AP = object()
# 收到 WSC_NACK 后 wpa_supplicant 仍会打印 CONFIG_ERROR 等诊断属性；
# 在该窗口内继续补读，才能拿到具体的拒绝原因。
NACK_DIAGNOSTIC_WINDOW = 0.2
# Selected AP metadata belongs to the script session, as in managed_connect.py.
# PINs, credentials and WPS handshake state are never cached.
target_cache = {}


def get_hex(line):
    a = line.split(':', 3)
    return a[2].replace(' ', '').upper()


class ConnectionStatus:
    # WPS 握手关键数据（hex 大写），用于服务端离线计算随机数缺陷漏洞 PIN。
    HANDSHAKE_FIELDS = ('pke', 'pkr', 'e_nonce', 'authkey', 'e_hash1', 'e_hash2')

    CONFIG_ERROR_RE = re.compile(r'CONFIG_ERROR[^0-9A-Fa-fxX]*(0x[0-9A-Fa-f]+|\d+)', re.IGNORECASE)
    # WSC 规范的 Configuration Error 只登记可直接判定的几项，其余按原始数字回传。
    CONFIG_ERROR_LABELS = {
        0: 'no error',
        1: 'protocol timeout',
        2: 'device password auth failure',
    }

    def __init__(self):
        self.status = ''
        self.first_half_valid = False
        self.essid = ''
        self.wpa_psk = ''
        self.bssid = ''
        self.wsc_done_built = False
        self.wps_success_received = False
        self.protocol_completed = False
        self.pke = ''
        self.pkr = ''
        self.e_nonce = ''
        self.authkey = ''
        self.e_hash1 = ''
        self.e_hash2 = ''
        self.max_m_message = 0
        self.failure_code = ''
        self.failure_detail = ''
        self.nack_seen = False
        self.nack_refined = False
        self.nack_drain_until = 0.0

    def isFirstHalfValid(self):
        return self.first_half_valid

    def got_all(self):
        return all(getattr(self, name) for name in self.HANDSHAKE_FIELDS)

    def handshake(self):
        if not self.got_all():
            return None
        return dict(pke=self.pke, pkr=self.pkr, eNonce=self.e_nonce,
                    authKey=self.authkey, eHash1=self.e_hash1,
                    eHash2=self.e_hash2, essid=self.essid)

    def note_m_message(self, number, sent):
        try:
            number = int(number)
        except (TypeError, ValueError):
            return
        if number < 1 or number > 8:
            return
        self.max_m_message = max(self.max_m_message, number)
        # M5 是 AP 对我们 M4（含 PIN 前半段密文）的确认，收到即前半段正确。
        if number >= 5 and not sent:
            self.first_half_valid = True

    def mark_nack(self):
        """记录 WSC_NACK：先按 M 消息位置定性，补读窗口内再用 CONFIG_ERROR 细化。"""
        self.status = 'WSC_NACK'
        if self.nack_seen:
            return
        self.nack_seen = True
        self.nack_drain_until = time.monotonic() + NACK_DIAGNOSTIC_WINDOW
        self.failure_code = 'WSC_NACK_SECOND_HALF' if self.first_half_valid else 'WSC_NACK_FIRST_HALF'
        self.failure_detail = 'WPS registrar rejected this PIN'

    def fail(self, code, detail=''):
        """记录本轮终止原因；已收到 NACK 时保留更具体的拒绝结论。"""
        if self.nack_seen:
            if detail and not self.failure_detail:
                self.failure_detail = detail
            return
        self.failure_code = code
        self.failure_detail = detail

    def observe_failure_line(self, line):
        """补读 NACK 之后的诊断输出，把具体的 CONFIG_ERROR 写进失败原因。"""
        if not self.nack_seen or self.nack_refined:
            return
        match = self.CONFIG_ERROR_RE.search(line)
        if match is None:
            return
        raw = match.group(1)
        value = int(raw, 16) if raw.lower().startswith('0x') else int(raw, 10)
        label = self.CONFIG_ERROR_LABELS.get(value, 'config error %d' % value)
        self.nack_refined = True
        self.failure_code = 'WSC_NACK_CONFIG_%d' % value
        self.failure_detail = '%s (%s)' % (label, line.strip())

    def draining_nack_diagnostics(self):
        return self.nack_seen and time.monotonic() < self.nack_drain_until

    def failure(self):
        if not self.failure_code:
            return None
        return dict(code=self.failure_code, detail=self.failure_detail,
                    maxMMessage=self.max_m_message)

    def clear(self):
        self.__init__()


class Companion:
    """Main application part"""
    def __init__(self, interface, save_result=False, print_debug=False, bssid=None,
                 exclude_macs=None, complete_protocol=False, check_running=None):
        self.interface = interface
        self.save_result = save_result
        self.print_debug = print_debug
        self.exclude_macs = exclude_macs or []
        self.complete_protocol = complete_protocol
        self.check_running = check_running or (lambda: None)
        self.cleaned = False
        self.cached_target = target_cache.get((interface, (bssid or '').lower()))
        if (bssid or '').upper() in self.exclude_macs:
            self.cached_target = None
        self.cached_nl = None
        self.cached_association_started = False

        self.tempdir = tempfile.mkdtemp()
        with tempfile.NamedTemporaryFile(mode='w', suffix='.conf', delete=False) as temp:
            temp.write('ctrl_interface={}\nctrl_interface_group=root\nupdate_config=1\n'.format(self.tempdir))
            if self.cached_target is not None:
                # The script owns association; this process only handles EAP/WPS.
                temp.write('ap_scan=0\n')
            self.tempconf = temp.name
        self.wpas_ctrl_path = f"{self.tempdir}/{interface}"
        self.__init_wpa_supplicant()

        self.res_socket_file = f"{tempfile.gettempdir()}/{next(tempfile._get_candidate_names())}"
        self.retsock = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
        self.retsock.bind(self.res_socket_file)
        self.retsock.settimeout(.2)

        self.connection_status = ConnectionStatus()

        self.reports_dir = os.path.dirname(os.path.realpath(__file__)) + '/reports/'

        self.bssid = bssid or ""
        self.lastPwr = 0

    def __init_wpa_supplicant(self):
        print('[*] Running wpa_supplicant…')
        cmd = [
            'wpa_supplicant',
            '-K',
            '-d',
            '-Dnl80211,wext,hostapd,wired',
            '-i{}'.format(self.interface),
            '-c{}'.format(self.tempconf),
        ]
        if self.cached_target is not None:
            # Receive externally initiated CONNECT events and ordinary EAPOL
            # packets, matching the Netlink connection/control-port path below.
            cmd.append('-pforce_connect_cmd=1 control_port=0')
        self.wpas = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, encoding='utf-8', errors='replace')
        self.output_lines = queue.Queue()
        process = self.wpas
        output_lines = self.output_lines

        def receive_output():
            try:
                for line in process.stdout:
                    output_lines.put(line)
            finally:
                output_lines.put(None)

        self.output_thread = threading.Thread(target=receive_output, name='wps-supplicant-output', daemon=True)
        self.output_thread.start()
        # Waiting for wpa_supplicant control interface initialization
        deadline = time.monotonic() + 10
        while True:
            self.check_running()
            ret = self.wpas.poll()
            if ret is not None:
                raise ValueError('wpa_supplicant exited before control interface was ready: %s' % ret)
            if os.path.exists(self.wpas_ctrl_path):
                break
            if time.monotonic() >= deadline:
                raise TimeoutError('wpa_supplicant control interface initialization timed out')
            time.sleep(.1)

    def __stop_wpa_supplicant(self):
        if not hasattr(self, 'wpas'):
            return
        if self.wpas.poll() is None:
            self.wpas.terminate()
            try:
                self.wpas.wait(timeout=1)
            except subprocess.TimeoutExpired:
                self.wpas.kill()
                self.wpas.wait()
        if self.wpas.stdout is not None:
            self.output_thread.join(timeout=1)
            self.wpas.stdout.close()

    def __restart_wpa_supplicant(self):
        # An excluded AP may already have WPS messages buffered in the old
        # process. Never reuse that process after it was selected.
        self.__stop_wpa_supplicant()
        try:
            os.remove(self.wpas_ctrl_path)
        except FileNotFoundError:
            pass
        self.__init_wpa_supplicant()

    def sendOnly(self, command):
        """Sends command to wpa_supplicant"""
        self.retsock.sendto(command.encode(), self.wpas_ctrl_path)

    def sendAndReceive(self, command):
        """Sends command to wpa_supplicant and returns the reply"""
        self.retsock.sendto(command.encode(), self.wpas_ctrl_path)
        deadline = time.monotonic() + 5
        while True:
            self.check_running()
            try:
                b, address = self.retsock.recvfrom(4096)
                return b.decode('utf-8', errors='replace')
            except socket.timeout:
                if time.monotonic() >= deadline:
                    raise TimeoutError('wpa_supplicant control reply timed out: %s' % command)

    def __cache_selected_target(self):
        bssid = (self.connection_status.bssid or self.bssid).lower()
        key = (self.interface, bssid)
        if not bssid or key in target_cache or bssid.upper() in self.exclude_macs:
            return
        import managed_connect

        try:
            ap = bytes.fromhex(bssid.replace(':', ''))
            # GET_SCAN reads the existing kernel BSS table; it does not scan.
            with close_resource(managed_connect.Netlink(self.interface)) as nl:
                for entry in nl.read_results():
                    bss = managed_connect.scan.parse_attrs(entry)
                    if managed_connect.scan.first_attr(bss, 1) != ap:
                        continue
                    ies = (managed_connect.scan.first_attr(bss, 6) or
                           managed_connect.scan.first_attr(bss, 11) or b'')
                    ssid = next((value for eid, value in
                                 managed_connect.scan.parse_information_elements(ies)
                                 if eid == 0), b'')
                    frequency = managed_connect.scan.first_attr(bss, 2)
                    if not 1 <= len(ssid) <= 32 or not frequency:
                        continue
                    frequency = struct.unpack('=I', frequency)[0]
                    if frequency <= 0:
                        continue
                    signal_value = managed_connect.scan.first_attr(bss, 7)
                    strength = struct.unpack('=i', signal_value)[0] if signal_value else -10000
                    capability = managed_connect.scan.first_attr(bss, 5)
                    privacy = bool(struct.unpack('=H', capability)[0] & 0x10) if capability else True
                    target_cache[key] = dict(ap=ap, ssid=ssid, frequency=frequency,
                                             strength=strength, privacy=privacy)
                    print('[*] Cached WPS AP: %s frequency=%s' % (bssid, frequency))
                    return
        except (OSError, ValueError, struct.error) as error:
            print('[!] Could not cache WPS AP: %s' % error)

    def __connect_cached_target(self, pbc_mode):
        import managed_connect

        target = self.cached_target
        self.check_running()
        self.connection_status.bssid = target['ap'].hex(':').upper()
        self.connection_status.essid = target['ssid'].decode('utf-8', errors='replace')
        self.lastPwr = target['strength'] / 100
        print('[*] Reusing cached WPS AP: %s frequency=%s; skipping scan' % (
            self.connection_status.bssid, target['frequency']))
        # Match wps_build_assoc_req_ie(): Version, Request Type and WFA Version2.
        request_type = 1 if pbc_mode else 2
        wps = (b'\x00\x50\xf2\x04' +
               struct.pack('!HHB', 0x104a, 1, 0x10) +
               struct.pack('!HHB', 0x103a, 1, request_type) +
               struct.pack('!HH', 0x1049, 6) + b'\x00\x37\x2a\x00\x01\x20')
        assoc_ie = bytes([221, len(wps)]) + wps
        attrs = managed_connect.scan.pack_attr(6, target['ap'])
        attrs += managed_connect.scan.pack_u32_attr(38, target['frequency'])
        attrs += managed_connect.scan.pack_attr(52, target['ssid'])
        attrs += managed_connect.scan.pack_u32_attr(53, 0)  # Open-system authentication.
        attrs += managed_connect.scan.pack_attr(42, assoc_ie)
        attrs += managed_connect.scan.pack_attr(68, b'')  # CONTROL_PORT.
        attrs += managed_connect.scan.pack_attr(102, struct.pack('=H', 0x888e))
        attrs += managed_connect.scan.pack_attr(103, b'')  # CONTROL_PORT_NO_ENCRYPT.
        if target['privacy']:
            attrs += managed_connect.scan.pack_attr(70, b'')  # PRIVACY.
        self.cached_nl = managed_connect.Netlink(self.interface)
        self.check_running()
        self.connection_status.status = 'associating'
        self.__print_with_indicators('*', 'Associating with AP…')
        self.cached_association_started = True
        self.cached_nl.command(46, attrs, 'CONNECT WPS using cached AP without scanning')

    @staticmethod
    def _explain_wpas_not_ok_status(command: str, respond: str):
        if command.startswith(('WPS_REG', 'WPS_PBC')):
            if respond == 'UNKNOWN COMMAND':
                return ('[!] It looks like your wpa_supplicant is compiled without WPS protocol support. '
                        'Please build wpa_supplicant with WPS support ("CONFIG_WPS=y")')
        return '[!] Something went wrong — check out debug log'

    def __handle_wpas(self, pbc_mode=False, verbose=None, bssid=""):
        if not verbose:
            verbose = self.print_debug
        while True:
            self.check_running()
            try:
                line = self.output_lines.get(timeout=.1)
                break
            except queue.Empty:
                continue
        if not line:
            self.wpas.wait()
            return False

        if verbose:
            sys.stdout.write(line)
            sys.stdout.flush()

        line = line.rstrip('\n')

        if line.startswith('WPS: '):
            if 'Building Message WSC_Done' in line:
                self.connection_status.wsc_done_built = True
                self.__print_with_indicators('*', 'Sending WPS Message WSC_Done…')
            elif 'Building Message M' in line:
                n = int(line.split('Building Message M')[1].replace('D', ''))
                self.connection_status.note_m_message(n, sent=True)
                self.__print_with_indicators('*', 'Sending WPS Message M{}…'.format(n))
            elif 'Received M2D' in line:
                self.__print_with_indicators('*', 'Received WPS Message M2D')
            elif 'Received M' in line:
                n = int(line.split('Received M')[1])
                self.connection_status.note_m_message(n, sent=False)
                self.__print_with_indicators('*', 'Received WPS Message M{}'.format(n))
                if n == 5:
                    print('[+] The first half of the PIN is valid')
            elif 'Received WSC_NACK' in line:
                # 不再当场判定“wrong PIN”；继续补读一小段输出，拿到具体的拒绝原因。
                self.connection_status.mark_nack()
                self.__print_with_indicators('*', 'Received WSC NACK')
                self.__drain_nack_diagnostics(verbose)
            elif 'Enrollee Nonce' in line and 'hexdump' in line:
                self.connection_status.e_nonce = get_hex(line)
                print('[P] E-Nonce: {}'.format(self.connection_status.e_nonce))
            elif 'DH own Public Key' in line and 'hexdump' in line:
                self.connection_status.pkr = get_hex(line)
                print('[P] PKR: {}'.format(self.connection_status.pkr))
            elif 'DH peer Public Key' in line and 'hexdump' in line:
                self.connection_status.pke = get_hex(line)
                print('[P] PKE: {}'.format(self.connection_status.pke))
            elif 'AuthKey' in line and 'hexdump' in line:
                self.connection_status.authkey = get_hex(line)
                print('[P] AuthKey: {}'.format(self.connection_status.authkey))
            elif 'E-Hash1' in line and 'hexdump' in line:
                self.connection_status.e_hash1 = get_hex(line)
                print('[P] E-Hash1: {}'.format(self.connection_status.e_hash1))
            elif 'E-Hash2' in line and 'hexdump' in line:
                self.connection_status.e_hash2 = get_hex(line)
                print('[P] E-Hash2: {}'.format(self.connection_status.e_hash2))
            elif 'Network Key' in line and 'hexdump' in line:
                self.connection_status.status = 'GOT_PSK'
                self.connection_status.wpa_psk = bytes.fromhex(get_hex(line)).decode('utf-8', errors='replace')
        elif ': State: ' in line:
            if '-> SCANNING' in line:
                self.connection_status.status = 'scanning'
                self.__print_with_indicators('*', 'Scanning…')
        elif 'WPS-SUCCESS' in line:
            self.connection_status.wps_success_received = True
        elif (
            'EAPOL: SUPP_BE entering state RECEIVE' in line
            and self.connection_status.wsc_done_built
            and self.connection_status.wps_success_received
        ):
            # This state is entered after eapol_send() returns, so the WSC_Done
            # response has reached the driver instead of merely being built.
            self.connection_status.status = 'WPS_SUCCESS'
            self.connection_status.protocol_completed = True
            print('[+] WPS protocol completed')
        elif 'WPS-TIMEOUT' in line:
            self.connection_status.status = 'WPS_FAIL'
            self.connection_status.fail('WPS_TIMEOUT', line.strip())
            print('[-] wpa_supplicant returned WPS-TIMEOUT')
        elif 'WPS-OVERLAP-DETECTED' in line:
            self.connection_status.status = 'WPS_FAIL'
            self.connection_status.fail('WPS_OVERLAP', line.strip())
            print('[-] wpa_supplicant detected WPS PBC overlap')
        elif ('WPS-FAIL' in line) and (self.connection_status.status != ''):
            self.connection_status.status = 'WPS_FAIL'
            self.connection_status.fail('WPS_FAIL', line.strip())
            print('[-] wpa_supplicant returned WPS-FAIL')
        elif pbc_mode and ('selected BSS ' in line):
            found_bssid = line.split('selected BSS ')[-1].split()[0].upper()
            if found_bssid in self.exclude_macs:
                print(f'[*] Ignored excluded AP: {found_bssid}. Aborting this WPS session...')
                self.connection_status.status = 'EXCLUDED_AP'
                self.connection_status.fail('EXCLUDED_AP', 'selected AP {} is excluded'.format(found_bssid))
                return True

            self.connection_status.bssid = found_bssid
            self.__cache_selected_target()
            print('[*] Selected AP: {}'.format(found_bssid))
        elif 'Trying to authenticate with' in line:
            target_bssid = line.split('Trying to authenticate with ')[-1].split()[0].upper()
            if target_bssid in self.exclude_macs:
                print(f'[*] Excluded AP {target_bssid} is authenticating. Aborting this WPS session...')
                self.connection_status.status = 'EXCLUDED_AP'
                self.connection_status.fail('EXCLUDED_AP', 'authenticating AP {} is excluded'.format(target_bssid))
                return True

            self.connection_status.status = 'authenticating'
            self.connection_status.bssid = target_bssid
            self.__cache_selected_target()
            if 'SSID' in line:
                self.connection_status.essid = codecs.decode("'".join(line.split("'")[1:-1]), 'unicode-escape').encode('latin1').decode('utf-8', errors='replace')
            self.__print_with_indicators('*', 'Authenticating…')
        elif 'Authentication response' in line:
            self.__print_with_indicators('*', 'Authenticated')
        elif 'Trying to associate with' in line:
            self.connection_status.status = 'associating'
            if 'SSID' in line:
                self.connection_status.essid = codecs.decode("'".join(line.split("'")[1:-1]), 'unicode-escape').encode('latin1').decode('utf-8', errors='replace')
            self.__print_with_indicators('*', 'Associating with AP…')
        elif ('Associated with' in line) and (self.interface in line):
            match = re.search(r'Associated with ((?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2})', line)
            if match is None:
                return True
            associated_bssid = match.group(1).upper()
            self.connection_status.bssid = associated_bssid
            if self.connection_status.essid:
                self.__print_with_indicators('+', 'Associated with {} (ESSID: {})'.format(associated_bssid, self.connection_status.essid))
            else:
                self.__print_with_indicators('+', 'Associated with {}'.format(associated_bssid))
        elif 'EAPOL: txStart' in line:
            self.connection_status.status = 'eapol_start'
            self.__print_with_indicators('*', 'Sending EAPOL Start…')
        elif 'EAP entering state IDENTITY' in line:
            self.__print_with_indicators('*', 'Received Identity Request')
        elif 'using real identity' in line:
            self.__print_with_indicators('*', 'Sending Identity Response…')
        elif self.bssid in line and 'level=' in line:
            self.lastPwr = line.split("level=")[1].split(" ")[0]
        elif bssid in line and 'level=' in line:
            signal = line.split("level=")[1].split(" ")[0]
            if 'noise=' in line:
                noise = line.split("noise=")[1].split(" ")[0]
                print ("[i] Current signal: {}, noise: {}".format(signal, noise))
            else:
                print ("[i] Current signal: {}".format(signal))

        # NACK 补读窗口内解析 CONFIG_ERROR，把具体的拒绝原因写进失败原因。
        self.connection_status.observe_failure_line(line)

        return True

    def __drain_nack_diagnostics(self, verbose):
        """NACK 之后 wpa_supplicant 仍会打印 CONFIG_ERROR；在短窗口内有界地补读这些诊断行。

        窗口超时或读到进程结束就收尾，取消指令照常经 check_running 生效。
        """
        deadline = self.connection_status.nack_drain_until
        while self.connection_status.draining_nack_diagnostics():
            try:
                line = self.output_lines.get(timeout=max(.01, min(.1, deadline - time.monotonic())))
            except queue.Empty:
                continue
            self.check_running()
            if not line:
                break
            if verbose:
                sys.stdout.write(line)
                sys.stdout.flush()
            self.connection_status.observe_failure_line(line.rstrip('\n'))

    def __credentialPrint(self, wps_pin=None, wpa_psk=None, essid=None):
        print(f"[+] WPS PIN: '{wps_pin}'")
        print(f"[+] WPA PSK: '{wpa_psk}'")
        print(f"[+] AP SSID: '{essid}'")

    def __saveResult(self, bssid, essid, wps_pin, wpa_psk):
        if not os.path.exists(self.reports_dir):
            os.makedirs(self.reports_dir)
        filename = self.reports_dir + 'stored'
        dateStr = datetime.now().strftime("%d.%m.%Y %H:%M")
        with open(filename + '.txt', 'a', encoding='utf-8') as file:
            file.write('{}\nBSSID: {}\nESSID: {}\nWPS PIN: {}\nWPA PSK: {}\n\n'.format(
                        dateStr, bssid, essid, wps_pin, wpa_psk
                    )
            )
        writeTableHeader = not os.path.isfile(filename + '.csv')
        with open(filename + '.csv', 'a', newline='', encoding='utf-8') as file:
            csvWriter = csv.writer(file, delimiter=';', quoting=csv.QUOTE_ALL)
            if writeTableHeader:
                csvWriter.writerow(['Date', 'BSSID', 'ESSID', 'WPS PIN', 'WPA PSK'])
            csvWriter.writerow([dateStr, bssid, essid, wps_pin, wpa_psk])
        print(f'[i] Credentials saved to {filename}.txt, {filename}.csv')

    def single_connection(self, bssid=None, pin=None, pbc_mode=False, verbose=None):
        while True:
            result = self.__single_connection_attempt(
                bssid=bssid,
                pin=pin,
                pbc_mode=pbc_mode,
                verbose=verbose,
            )
            if result is not RESTART_AFTER_EXCLUDED_AP:
                return result
            print('[*] Recreating wpa_supplicant before retrying PBC selection…')
            self.__restart_wpa_supplicant()
            time.sleep(0.5)

    def __single_connection_attempt(self, bssid=None, pin=None, pbc_mode=False, verbose=None):
        self.connection_status.clear()

        if pbc_mode:
            # Consume each reply before WPS_PBC so a stale blacklist reply
            # cannot be mistaken for the WPS_PBC response.
            for mac in self.exclude_macs:
                self.sendAndReceive(f'BLACKLIST {mac}')
                self.sendAndReceive(f'BSSID_IGNORE {mac}')

            if bssid:
                print(f"[*] Starting WPS push button connection to {bssid}…")
                cmd = f'WPS_PBC {bssid}'
            else:
                if self.exclude_macs:
                    print(f"[*] Starting WPS push button connection (excluding {len(self.exclude_macs)} MACs)…")
                else:
                    print("[*] Starting WPS push button connection…")
                cmd = 'WPS_PBC'
        else:
            print(f"[*] Trying PIN '{pin}'…")
            cmd = f'WPS_REG {bssid} {pin}'

        r = self.sendAndReceive(cmd)
        if 'OK' not in r:
            self.connection_status.status = 'WPS_FAIL'
            self.connection_status.fail('SUPPPLICANT_REJECT', r.strip())
            print(self._explain_wpas_not_ok_status(cmd, r))
            return False

        if self.cached_target is not None:
            # WPS_REG/WPS_PBC first initializes the normal WPS state machine.
            # ap_scan=0 keeps its scheduled scan from triggering a driver scan.
            self.__connect_cached_target(pbc_mode)

        while True:
            target_bssid = bssid.lower() if bssid else ""
            res = self.__handle_wpas(pbc_mode=pbc_mode, verbose=verbose, bssid=target_bssid)
            if not res:
                break
            status = self.connection_status.status
            if status == 'WSC_NACK':
                break
            elif status == 'GOT_PSK' and not self.complete_protocol:
                break
            elif status == 'WPS_SUCCESS':
                break
            elif status == 'WPS_FAIL':
                break
            elif status == 'EXCLUDED_AP':
                break

        if self.connection_status.status == 'WSC_NACK':
            print('[-] WPS NACK: {} ({})'.format(
                self.connection_status.failure_code, self.connection_status.failure_detail))

        if self.connection_status.status == 'EXCLUDED_AP':
            # Do not let an excluded AP reach WSC_Done. Confirm cancellation,
            # then discard the entire process and its buffered handshake output.
            self.sendAndReceive('WPS_CANCEL')
            return RESTART_AFTER_EXCLUDED_AP

        self.__cache_selected_target()

        # In the historical/incomplete mode, stop immediately after M8 exposes
        # the Network Key. In complete mode wpa_supplicant must be allowed to
        # build and transmit WSC_Done; WPS-SUCCESS confirms that protocol end.
        if not self.connection_status.protocol_completed:
            # The process can be cleaned up immediately after this method
            # returns, so do not merely queue WPS_CANCEL. Waiting for its reply
            # guarantees wpa_supplicant handled the incomplete termination.
            self.sendAndReceive('WPS_CANCEL')

        if self.connection_status.wpa_psk:
            final_bssid = bssid
            if pbc_mode and self.connection_status.bssid:
                final_bssid = self.connection_status.bssid

            display_pin = pin if not pbc_mode else '<PBC mode>'

            self.__credentialPrint(display_pin, self.connection_status.wpa_psk, self.connection_status.essid)
            if self.save_result:
                self.__saveResult(final_bssid, self.connection_status.essid, display_pin, self.connection_status.wpa_psk)
            return not self.complete_protocol or self.connection_status.protocol_completed
        return False

    def __print_with_indicators(self, level, msg):
        print('[{}] [{}] {}'.format(level, self.lastPwr, msg))

    def cleanup(self):
        if self.cleaned:
            return
        self.cleaned = True
        if hasattr(self, 'retsock'):
            self.retsock.close()
        try:
            self.__stop_wpa_supplicant()
        finally:
            if self.cached_nl is not None:
                try:
                    if self.cached_association_started:
                        try:
                            import managed_connect
                            attrs = managed_connect.scan.pack_attr(54, struct.pack('=H', 3))
                            self.cached_nl.command(48, attrs, 'disconnect cached WPS connection')
                        except OSError as error:
                            if error.errno != errno.ENOTCONN:
                                raise
                finally:
                    self.cached_nl.close()
                    self.cached_nl = None
        for name in ('res_socket_file', 'tempconf'):
            path = getattr(self, name, None)
            if path and os.path.exists(path):
                os.remove(path)
        if hasattr(self, 'tempdir'):
            shutil.rmtree(self.tempdir, ignore_errors=True)

    def __del__(self):
        try:
            self.cleanup()
        except (ImportError, AttributeError, TypeError):
            pass


def ifaceUp(iface, down=False):
    if down:
        action = 'down'
    else:
        action = 'up'
    cmd = 'ip link set {} {}'.format(iface, action)
    res = subprocess.run(cmd, shell=True, stdout=sys.stdout, stderr=sys.stdout)
    if res.returncode == 0:
        return True
    else:
        return False


def die(msg):
    sys.stderr.write(msg + '\n')
    sys.exit(1)


def persistent_fifo_main(interface, command_pipe, event_pipe):
    """One owner-controlled session; only caller-supplied PIN or explicit PBC commands."""
    import managed_connect
    from managed_supplicant_guard import SupplicantPauseGuard

    pending = queue.Queue()
    closing = threading.Event()
    cancel = threading.Event()
    lock = threading.Lock()
    active_request = [None]
    cancelled = set()
    guard = None
    writer = None
    restored = False
    emit_lock = threading.RLock()
    original_stdout, original_stderr = sys.stdout, sys.stderr

    def emit(kind, **fields):
        with emit_lock:
            writer.write(json.dumps(dict(type=kind, **fields), ensure_ascii=False) + '\n')
            writer.flush()

    def forward_log(content):
        if not content.strip():
            return
        request_id = active_request[0]
        emit('log', requestId=request_id, content=content)
        if request_id is None:
            return
        packet = re.search(r'(Sending|Received) WPS Message (M[1-8]D?|WSC_Done)', content)
        if packet:
            emit('progress', requestId=request_id, stage='HANDSHAKE', packet=packet[2],
                 direction='SENDING' if packet[1] == 'Sending' else 'RECEIVED')
        elif 'Received WSC NACK' in content:
            emit('progress', requestId=request_id, stage='HANDSHAKE', packet='WSC_NACK', direction='RECEIVED')
        else:
            for text, stage in (
                ('Scanning…', 'SCANNING'), ('Authenticating…', 'AUTHENTICATING'),
                ('Associating with AP…', 'ASSOCIATING'), ('Associated with ', 'ASSOCIATED'),
                ('Sending EAPOL Start…', 'EAPOL_START'), ('Received Identity Request', 'IDENTITY_REQUEST'),
                ('Sending Identity Response…', 'IDENTITY_RESPONSE'), ('WPS protocol completed', 'PROTOCOL_COMPLETED'),
            ):
                if text in content:
                    emit('progress', requestId=request_id, stage=stage)
                    break

    class FifoConsole:
        """Preserve console output and forward complete lines without changing WPS behavior."""
        def __init__(self, stream):
            self.stream = stream
            self.pending = ''
            self.lock = threading.RLock()

        def write(self, text):
            with self.lock:
                self.pending += text
                while '\n' in self.pending or len(self.pending) >= 8192:
                    newline = self.pending.find('\n')
                    end = newline if 0 <= newline < 8192 else 8192
                    line = self.pending[:end]
                    self.pending = self.pending[end + (1 if newline == end else 0):]
                    forward_log(line)
                self.stream.write(text)
            return len(text)

        def flush(self):
            self.stream.flush()

        def finish(self):
            with self.lock:
                if self.pending:
                    forward_log(self.pending)
                    self.pending = ''
                self.stream.flush()

        def __getattr__(self, name):
            return getattr(self.stream, name)

    def check_running():
        if closing.is_set() or cancel.is_set():
            raise InterruptedError('WPS request cancelled')
        if guard is not None:
            guard.ensure_active()

    def shutdown(*_):
        closing.set()
        cancel.set()
        managed_connect.stop_requested.set()
        pending.put(None)

    def read_commands():
        try:
            with open(command_pipe, encoding='utf-8') as source:
                for raw in source:
                    try:
                        request = json.loads(raw)
                        if not isinstance(request, dict):
                            raise ValueError('Command must be an object')
                        kind = request.get('type')
                        if kind == 'shutdown':
                            shutdown()
                            return
                        if kind == 'cancel':
                            request_id = request.get('requestId')
                            with lock:
                                if request_id == active_request[0]:
                                    cancel.set()
                                else:
                                    cancelled.add(request_id)
                            continue
                        if kind != 'run' or not isinstance(request.get('requestId'), str) or not request['requestId']:
                            raise ValueError('run requires requestId')
                        pending.put(request)
                    except (ValueError, TypeError) as error:
                        print('[-] Invalid FIFO command: %s' % error, flush=True)
        except BaseException as error:
            print('[-] Command FIFO failed: %r' % error, flush=True)
        finally:
            shutdown()

    for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(sig, shutdown)

    def guard_log(name, **fields):
        print('[*] %s: %s' % (name, json.dumps(fields, ensure_ascii=False)), flush=True)

    try:
        writer = open(event_pipe, 'w', encoding='utf-8', buffering=1)
        sys.stdout = FifoConsole(original_stdout)
        sys.stderr = FifoConsole(original_stderr)
        threading.Thread(target=read_commands, name='wps-command-fifo', daemon=True).start()
        guard = SupplicantPauseGuard(None, guard_log)
        guard.start(check_running)
        # Reuse the exact managed diagnostic disconnect path, including socket ownership handling.
        managed_connect.supplicant_guard = guard
        managed_connect.overall_deadline = time.monotonic() + 10
        with close_resource(managed_connect.Netlink(interface)) as nl:
            info = nl.command(5, name='read WPS interface mode', reply=True)[0]
            mode = managed_connect.struct.unpack('=I', managed_connect.scan.first_attr(info, 5))[0]
            if mode != 2:
                raise RuntimeError('WPS requires managed interface mode')
            before_flags = managed_connect.flags(interface)
            if not before_flags & 1:
                managed_connect.flags(interface, before_flags | 1)
            connected = lambda: any(
                managed_connect.scan.first_attr(managed_connect.scan.parse_attrs(bss), 9) ==
                managed_connect.struct.pack('=I', 1) for bss in nl.read_results())
            if connected():
                print('[*] Disconnecting current system connection…', flush=True)
                nl.disconnect_existing()
                while connected():
                    check_running()
                    managed_connect.ensure_running()
                    time.sleep(.05)
        managed_connect.overall_deadline = None
        emit('ready', protocol=3)
        print('[*] WPS FIFO session ready (protocol=3)', flush=True)
        while not closing.is_set():
            try:
                request = pending.get(timeout=.25)
            except queue.Empty:
                guard.ensure_active()
                continue
            if request is None:
                break
            request_id = request['requestId']
            with lock:
                cancel.clear()
                active_request[0] = request_id
                if request_id in cancelled:
                    cancel.set()
                    cancelled.discard(request_id)
            companion = None
            success = False
            network = None
            message = ''
            state = None
            request_nl = None
            original_flags = None
            original_mac = None
            restore_mac = False
            actual_mac = None
            try:
                check_running()
                emit('progress', requestId=request_id, stage='PREPARING')
                mode = request.get('mode')
                bssid = request.get('mac')
                pin = request.get('pin')
                excluded = request.get('excludeMacs', [])
                valid_mac = lambda value: isinstance(value, str) and re.fullmatch(r'(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}', value)
                if mode not in ('pin', 'pbc') or (bssid is not None and not valid_mac(bssid)):
                    raise ValueError('Invalid WPS mode or target MAC')
                if mode == 'pin' and (not bssid or not isinstance(pin, str) or not re.fullmatch(r'(?:[0-9]{4}|[0-9]{8})', pin)):
                    raise ValueError('PIN mode requires MAC and one 4/8-digit PIN')
                if not isinstance(excluded, list) or any(not valid_mac(mac) for mac in excluded):
                    raise ValueError('Invalid excludeMacs')
                local_mac = request.get('localMac')
                if local_mac is not None and not isinstance(local_mac, str):
                    raise ValueError('localMac must be a MAC string or null')
                local_mac = local_mac.strip() if local_mac else None
                requested_mac = None
                if local_mac:
                    if not valid_mac(local_mac):
                        raise ValueError('Invalid localMac format')
                    requested_mac = bytes.fromhex(local_mac.replace(':', ''))
                    if requested_mac[0] & 1 or requested_mac == bytes(6):
                        raise ValueError('localMac must be a valid unicast address')
                request_nl = managed_connect.Netlink(interface)
                original_flags = managed_connect.flags(interface)
                original_mac = managed_connect.read_mac(request_nl)
                actual_mac = original_mac
                if requested_mac is not None:
                    # Same ioctl/down-up/readback sequence as managed_connect.run().
                    restore_mac = True
                    managed_connect.flags(interface, original_flags & ~1)
                    managed_connect.set_mac(interface, requested_mac)
                    actual_mac = managed_connect.read_mac(request_nl)
                    print('[*] Local MAC set: requested=%s actual=%s original=%s' % (
                        requested_mac.hex(':'), actual_mac.hex(':'), original_mac.hex(':')), flush=True)
                    if actual_mac != requested_mac:
                        raise RuntimeError('Driver did not retain the requested local MAC')
                managed_connect.flags(interface, original_flags | 1)
                if managed_connect.read_mac(request_nl) != actual_mac:
                    raise RuntimeError('Local MAC changed after bringing the interface up')
                print('[*] WPS local MAC: %s' % actual_mac.hex(':'), flush=True)
                check_running()
                companion = Companion(interface, bssid=bssid, exclude_macs=[mac.upper() for mac in excluded],
                                      complete_protocol=request.get('completeProtocol', True), check_running=check_running)
                # 立即绑定状态对象，即使后续抛异常也能回传已捕获的握手数据。
                state = companion.connection_status
                if managed_connect.read_mac(request_nl) != actual_mac:
                    raise RuntimeError('Private supplicant changed the selected local MAC')
                success = companion.single_connection(bssid=bssid, pin=pin, pbc_mode=mode == 'pbc')
                if managed_connect.read_mac(request_nl) != actual_mac:
                    raise RuntimeError('Local MAC changed during WPS exchange')
                state = companion.connection_status
                final_mac = (state.bssid or bssid or '').lower()
                if state.wpa_psk and state.essid and valid_mac(final_mac):
                    network = dict(ssid=state.essid, mac=final_mac, password=state.wpa_psk)
                message = state.status
            except Exception as error:
                message = str(error)
                if state is not None:
                    state.fail('EXCEPTION', message)
                print('[-] WPS request failed: %s' % message, flush=True)
            finally:
                cleanup_completed = True
                if companion is not None:
                    try:
                        companion.cleanup()
                    except Exception as error:
                        cleanup_completed = False
                        print('[-] WPS request cleanup failed: %r' % error, flush=True)
                # The private supplicant must exit before restoring the original interface identity.
                if restore_mac and original_mac is not None:
                    try:
                        managed_connect.flags(interface, managed_connect.flags(interface) & ~1)
                        managed_connect.set_mac(interface, original_mac)
                        if managed_connect.read_mac(request_nl) != original_mac:
                            raise RuntimeError('Original local MAC could not be confirmed by reading it back')
                        print('[*] Local MAC restored: %s' % original_mac.hex(':'), flush=True)
                    except Exception as error:
                        cleanup_completed = False
                        print('[-] Restore local MAC failed: %r' % error, flush=True)
                if original_flags is not None:
                    try:
                        managed_connect.flags(interface, original_flags)
                        if request_nl is not None and original_mac is not None and managed_connect.read_mac(request_nl) != original_mac:
                            raise RuntimeError('Local MAC changed after restoring interface flags')
                    except Exception as error:
                        cleanup_completed = False
                        print('[-] Restore interface flags failed: %r' % error, flush=True)
                if request_nl is not None:
                    request_nl.close()
                with lock:
                    active_request[0] = None
            # 实际收到 M5 的事实与握手数据随结果回传；实时阶段通过 progress 事件发送。
            emit('result', requestId=request_id, success=bool(success), network=network,
                 message=message, cleanupCompleted=cleanup_completed,
                 localMac=actual_mac.hex(':') if actual_mac is not None else None,
                 firstHalfValid=bool(state.isFirstHalfValid()) if state is not None else False,
                 maxMMessage=(state.max_m_message if state is not None else 0),
                 failure=(state.failure() if state is not None else None),
                 handshake=(state.handshake() if state is not None else None))
            if not cleanup_completed:
                break
    except Exception as error:
        print('[-] WPS FIFO session failed: %r' % error, flush=True)
        if writer is not None:
            emit('error', message=str(error))
    finally:
        if guard is not None:
            try:
                restored = guard.release()
            except Exception as error:
                print('[-] System supplicant recovery failed: %r' % error, flush=True)
        managed_connect.supplicant_guard = None
        if writer is not None:
            try:
                sys.stdout.finish()
                sys.stderr.finish()
                emit('stopped', supplicantRestored=restored)
            finally:
                sys.stdout, sys.stderr = original_stdout, original_stderr
                writer.close()
    return 0 if restored else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='WPS PIN & PBC Single Connection Tool')

    parser.add_argument(
        '-i', '--interface',
        type=str,
        required=True,
        help='Name of the interface to use (e.g. wlan0)'
    )
    parser.add_argument('--command-pipe')
    parser.add_argument('--event-pipe')
    parser.add_argument(
        '-mac',
        dest='bssid',
        type=str,
        help='BSSID of the target AP (e.g. xx:xx:xx:xx:xx:xx)'
    )
    parser.add_argument(
        '-pin',
        type=str,
        help='The known WPS PIN to connect with (e.g. xxxxxxxx)'
    )
    parser.add_argument(
        '--pbc',
        action='store_true',
        help='Run WPS push button connection (PBC) mode'
    )
    parser.add_argument(
        '-exclude',
        type=str,
        help='Comma-separated list of MAC addresses to exclude in PBC any mode (e.g. xx:xx:xx:xx:xx:xx,yy:yy:yy:yy:yy:yy)'
    )
    protocol_group = parser.add_mutually_exclusive_group()
    protocol_group.add_argument(
        '--incomplete-protocol',
        dest='complete_protocol',
        action='store_false',
        help='Stop after receiving credentials without waiting for WSC_Done (default)'
    )
    protocol_group.add_argument(
        '--complete-protocol',
        dest='complete_protocol',
        action='store_true',
        help='Wait for wpa_supplicant to send WSC_Done and report WPS-SUCCESS'
    )
    parser.set_defaults(complete_protocol=False)
    parser.add_argument(
        '-w', '--write',
        action='store_true',
        help='Write credentials to the file on success'
    )
    parser.add_argument(
        '-v', '--verbose',
        action='store_true',
        help='Verbose output'
    )

    args = parser.parse_args()

    if bool(args.command_pipe) != bool(args.event_pipe):
        die('Both --command-pipe and --event-pipe are required')
    if args.command_pipe:
        if os.getuid() != 0:
            die('Run it as root')
        sys.stdout.reconfigure(line_buffering=True)
        sys.exit(persistent_fifo_main(args.interface, args.command_pipe, args.event_pipe))

    # 验证参数合理性
    if not args.pin and not args.pbc:
        die("Error: You must specify either -pin or --pbc")

    if args.pin and args.pbc:
        die("Error: -pin and --pbc cannot be used simultaneously")

    if args.pin and not args.bssid:
        die("Error: -mac is required when using -pin")

    # 处理排除名单格式
    exclude_macs = []
    if args.exclude:
        exclude_macs = [mac.strip().upper() for mac in args.exclude.split(',')]

    if sys.hexversion < 0x03060F0:
        die("The program requires Python 3.6 and above")
    if os.getuid() != 0:
        die("Run it as root")

    if not ifaceUp(args.interface):
        die('Unable to up interface "{}"'.format(args.interface))

    try:
        companion = Companion(
            args.interface,
            args.write,
            print_debug=args.verbose,
            bssid=args.bssid,
            exclude_macs=exclude_macs,
            complete_protocol=args.complete_protocol,
        )
        companion.single_connection(bssid=args.bssid, pin=args.pin, pbc_mode=args.pbc)
    except KeyboardInterrupt:
        print("\nAborting…")
