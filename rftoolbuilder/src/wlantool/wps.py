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


RESTART_AFTER_EXCLUDED_AP = object()


def get_hex(line):
    a = line.split(':', 3)
    return a[2].replace(' ', '').upper()


class ConnectionStatus:
    def __init__(self):
        self.status = ''
        self.last_m_message = 0
        self.essid = ''
        self.wpa_psk = ''
        self.bssid = ''
        self.wsc_done_built = False
        self.wps_success_received = False
        self.protocol_completed = False

    def isFirstHalfValid(self):
        return self.last_m_message > 5

    def clear(self):
        self.__init__()


class Companion:
    """Main application part"""
    def __init__(self, interface, save_result=False, print_debug=False, bssid=None,
                 exclude_macs=None, complete_protocol=False):
        self.interface = interface
        self.save_result = save_result
        self.print_debug = print_debug
        self.exclude_macs = exclude_macs or []
        self.complete_protocol = complete_protocol

        self.tempdir = tempfile.mkdtemp()
        with tempfile.NamedTemporaryFile(mode='w', suffix='.conf', delete=False) as temp:
            temp.write('ctrl_interface={}\nctrl_interface_group=root\nupdate_config=1\n'.format(self.tempdir))
            self.tempconf = temp.name
        self.wpas_ctrl_path = f"{self.tempdir}/{interface}"
        self.__init_wpa_supplicant()

        self.res_socket_file = f"{tempfile.gettempdir()}/{next(tempfile._get_candidate_names())}"
        self.retsock = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
        self.retsock.bind(self.res_socket_file)

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
        self.wpas = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, encoding='utf-8', errors='replace')
        # Waiting for wpa_supplicant control interface initialization
        while True:
            ret = self.wpas.poll()
            if ret is not None and ret != 0:
                raise ValueError('wpa_supplicant returned an error: ' + self.wpas.communicate()[0])
            if os.path.exists(self.wpas_ctrl_path):
                break
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
        (b, address) = self.retsock.recvfrom(4096)
        inmsg = b.decode('utf-8', errors='replace')
        return inmsg

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
        line = self.wpas.stdout.readline()
        if not line:
            self.wpas.wait()
            return False
        line = line.rstrip('\n')

        if verbose:
            sys.stderr.write(line + '\n')

        if line.startswith('WPS: '):
            if 'Building Message WSC_Done' in line:
                self.connection_status.wsc_done_built = True
                self.__print_with_indicators('*', 'Sending WPS Message WSC_Done…')
            elif 'Building Message M' in line:
                n = int(line.split('Building Message M')[1].replace('D', ''))
                self.connection_status.last_m_message = n
                self.__print_with_indicators('*', 'Sending WPS Message M{}…'.format(n))
            elif 'Received M' in line:
                n = int(line.split('Received M')[1])
                self.connection_status.last_m_message = n
                self.__print_with_indicators('*', 'Received WPS Message M{}'.format(n))
                if n == 5:
                    print('[+] The first half of the PIN is valid')
            elif 'Received WSC_NACK' in line:
                self.connection_status.status = 'WSC_NACK'
                self.__print_with_indicators('*', 'Received WSC NACK')
                print('[-] Error: wrong PIN code')
            elif 'Enrollee Nonce' in line and 'hexdump' in line:
                print('[P] E-Nonce: {}'.format(get_hex(line)))
            elif 'DH own Public Key' in line and 'hexdump' in line:
                print('[P] PKR: {}'.format(get_hex(line)))
            elif 'DH peer Public Key' in line and 'hexdump' in line:
                print('[P] PKE: {}'.format(get_hex(line)))
            elif 'AuthKey' in line and 'hexdump' in line:
                print('[P] AuthKey: {}'.format(get_hex(line)))
            elif 'E-Hash1' in line and 'hexdump' in line:
                print('[P] E-Hash1: {}'.format(get_hex(line)))
            elif 'E-Hash2' in line and 'hexdump' in line:
                print('[P] E-Hash2: {}'.format(get_hex(line)))
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
            print('[-] wpa_supplicant returned WPS-TIMEOUT')
        elif 'WPS-OVERLAP-DETECTED' in line:
            self.connection_status.status = 'WPS_FAIL'
            print('[-] wpa_supplicant detected WPS PBC overlap')
        elif ('WPS-FAIL' in line) and (self.connection_status.status != ''):
            self.connection_status.status = 'WPS_FAIL'
            print('[-] wpa_supplicant returned WPS-FAIL')
        elif pbc_mode and ('selected BSS ' in line):
            found_bssid = line.split('selected BSS ')[-1].split()[0].upper()
            if found_bssid in self.exclude_macs:
                print(f'[*] Ignored excluded AP: {found_bssid}. Aborting this WPS session...')
                self.connection_status.status = 'EXCLUDED_AP'
                return True

            self.connection_status.bssid = found_bssid
            print('[*] Selected AP: {}'.format(found_bssid))
        elif 'Trying to authenticate with' in line:
            target_bssid = line.split('Trying to authenticate with ')[-1].split()[0].upper()
            if target_bssid in self.exclude_macs:
                print(f'[*] Excluded AP {target_bssid} is authenticating. Aborting this WPS session...')
                self.connection_status.status = 'EXCLUDED_AP'
                return True

            self.connection_status.status = 'authenticating'
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
            associated_bssid = line.split()[-1].upper()
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

        return True

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
        self.wpas.stdout.read(300)   # Clean the pipe

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
            print(self._explain_wpas_not_ok_status(cmd, r))
            return False

        while True:
            target_bssid = bssid.lower() if bssid else ""
            res = self.__handle_wpas(pbc_mode=pbc_mode, verbose=verbose, bssid=target_bssid)
            if not res:
                break
            if self.connection_status.status == 'WSC_NACK':
                break
            elif self.connection_status.status == 'GOT_PSK' and not self.complete_protocol:
                break
            elif self.connection_status.status == 'WPS_SUCCESS':
                break
            elif self.connection_status.status == 'WPS_FAIL':
                break
            elif self.connection_status.status == 'EXCLUDED_AP':
                break

        if self.connection_status.status == 'EXCLUDED_AP':
            # Do not let an excluded AP reach WSC_Done. Confirm cancellation,
            # then discard the entire process and its buffered handshake output.
            self.sendAndReceive('WPS_CANCEL')
            return RESTART_AFTER_EXCLUDED_AP

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
        self.retsock.close()
        self.__stop_wpa_supplicant()
        os.remove(self.res_socket_file)
        shutil.rmtree(self.tempdir, ignore_errors=True)
        os.remove(self.tempconf)

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


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='WPS PIN & PBC Single Connection Tool')

    parser.add_argument(
        '-i', '--interface',
        type=str,
        required=True,
        help='Name of the interface to use (e.g. wlan0)'
    )
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
