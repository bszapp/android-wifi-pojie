"""Human-readable diagnostic output; no transport/state or Wi-Fi operations."""
import re


STAGE_MESSAGES = {
    'ROUTER_COMMUNICATION': 'Communicating with router',
    'WPA_HANDSHAKE_1_OF_4': 'WPA handshake M1 (1/4)',
    'WPA_HANDSHAKE_2_OF_4': 'WPA handshake M2 (2/4)',
    'WPA_HANDSHAKE_3_OF_4': 'WPA handshake M3 (3/4)',
    'WPA_HANDSHAKE_4_OF_4': 'WPA handshake M4 (4/4)',
    'IP_NEGOTIATION': 'DHCP address acquisition',
}

HEADLINES = {
    'ready': ('*', 'Managed Wi-Fi diagnostic ready (protocol={protocol})'),
    'begin': ('*', "Starting managed Wi-Fi connectivity test for SSID '{ssid}'"),
    'interface': ('i', 'Interface {interface}: managed mode, MAC {mac}'),
    'initialAddresses': ('i', 'Reading existing IPv4 addresses'),
    'subscribed': ('i', "Subscribed to nl80211 group '{group}' (id={id})"),
    'netlinkAccepted': ('i', 'nl80211: {name} accepted (command={command})'),
    'netlinkError': ('-', 'nl80211: {name} failed (command={command}, errno={errno}): {error}'),
    'netlinkEvent': ('*', 'nl80211: Received event {command}'),
    'managementResponse': ('*', '802.11: Management response subtype={subtype}, status={statusCode}: {description}'),
    'associationRejected': ('-', 'Association rejected (status={statusCode}): {description}'),
    'routerAssociated': ('+', 'Associated with {bssid}'),
    'connectionTerminated': ('-', 'Connection terminated (command={command}, reason={reasonCode}): {description}'),
    'disconnectingExistingConnection': ('*', 'Disconnecting the current interface connection'),
    'existingConnectionDisconnected': ('+', 'Previous interface connection disconnected'),
    'macSet': ('+', 'Station MAC set to {actual} (requested={requested}, original={original})'),
    'scanStarted': ('*', 'Scanning for the requested SSID'),
    'scanBusy': ('!', 'A scan is already running; waiting for results'),
    'unsupportedBss': ('!', 'Skipping unsupported WPA/RSN configuration at {bssid}'),
    'target': ('*', 'Selected AP: {bssid} (frequency={frequency} MHz)'),
    'eapolDropped': ('!', 'WPA: Dropping EAPOL packet: {reason}'),
    'otherEapol': ('i', 'WPA: Received a non-key EAPOL packet'),
    'tryingPsk': ('*', "Trying PSK '{password}'…"),
    'txEapolSubmitted': ('*', 'WPA: Sending EAPOL-Key {step}/4 to {bssid}'),
    'handshakeCount': ('i', 'M2 transmission count: {message}'),
    'm3Verified': ('+', 'WPA: Message 3 MIC verified using password line {passwordLine}'),
    'm3KeyDataDecrypted': ('+', 'WPA: Decrypted message 3 Key Data ({bytes} bytes)'),
    'm3Rsn': ('P', 'WPA: RSN information from message 3'),
    'keyInstalled': ('+', 'WPA: Installed {kind} (index={index}, key length={keyBytes} bytes)'),
    'handshakeCompletedLocally': ('+', 'WPA: Four-way handshake completed locally; keys installed and controlled port authorized'),
    'm3Retransmission': ('*', 'WPA: Retransmitted message 3; resending M4 without reinstalling keys'),
    'handshakeRestarted': ('!', 'WPA: New message 1 received during DHCP; continuing with the next password line'),
    'dhcpTxSubmitted': ('*', 'DHCP: Sending {message} (xid={xid}, client={clientMac})'),
    'dhcpRx': ('*', 'DHCP: Received {dhcpType} (xid={xid}, offered address={offeredIp})'),
    'dhcpIgnored': ('!', 'DHCP: Ignoring packet: {reason}'),
    'completed': ('+', 'Connectivity test passed: IP address {ip}/{prefix}; disconnecting immediately'),
    'failed': ('-', 'Connectivity test failed: {error}'),
    'temporaryAddressRemoved': ('i', 'Removed temporary IPv4 address {ip}'),
    'disconnected': ('i', 'Test connection disconnected'),
    'macRestored': ('+', 'Original station MAC restored: {mac}'),
    'flagsRestored': ('i', 'Original interface flags restored: {flags}'),
    'cleanupError': ('-', 'Cleanup failed ({operation}): {error}'),
    'controlRejected': ('!', 'Control request rejected: {message}'),
    'supplicantPaused': ('i', 'Paused system wpa_supplicant (pid={pid})'),
    'supplicantPausedAgain': ('!', 'System wpa_supplicant resumed externally; paused again (pid={pid})'),
    'supplicantAlreadyPaused': ('i', 'System wpa_supplicant was already paused; preserving its state (pid={pid})'),
    'supplicantGuardReady': ('+', 'System supplicant pause guard is ready'),
    'supplicantGuardTimeout': ('!', 'Supplicant guard timed out; restoring system processes'),
    'supplicantOwnerExited': ('!', 'Diagnostic owner pipe closed; restoring system processes'),
    'supplicantGuardError': ('-', 'Supplicant pause guard failed: {error}'),
    'supplicantResumeError': ('-', 'Could not restore system wpa_supplicant (pid={pid}): {error}'),
    'supplicantExited': ('i', 'System wpa_supplicant exited (pid={pid})'),
    'supplicantResumed': ('+', 'Resumed system wpa_supplicant or confirmed its exit (pid={pid})'),
    'supplicantGuardFinished': ('i', 'System supplicant guard finished (restored={restored})'),
}

HEX_LABELS = {
    'raw': 'Raw frame', 'nonce': 'Nonce', 'mic': 'MIC', 'keyData': 'Key Data',
    'data': 'RSN information', 'sequence': 'Key sequence',
    'advertisedRsn': 'AP RSN information', 'associationRsn': 'Station RSN information',
    'hostnameHex': 'DHCP hostname bytes', 'frame': '802.11 management frame', 'rsn': 'RSN information',
}


def text(value):
    if value is None:
        return 'null'
    if isinstance(value, bool):
        return 'true' if value else 'false'
    # Keep untrusted SSIDs/names on one line, separate from parseable state lines.
    return str(value).replace('\\', '\\\\').replace('\r', '\\r').replace('\n', '\\n').replace('\t', '\\t').replace('\x1b', '\\x1b')


class DisplayFields(dict):
    def __missing__(self, key):
        return 'unknown'


def hexdump(label, value):
    if value is None:
        return '[P] %s: null' % label
    try:
        data = bytes.fromhex(value)
    except (ValueError, TypeError):
        return '[P] %s: %s' % (label, text(value))
    return '[P] %s - hexdump(len=%s): %s' % (label, len(data), data.hex(' '))


def format_event(event, fields):
    display = DisplayFields({key: text(value) for key, value in fields.items()})
    if event == 'stage':
        # These exact English lines are the stage contract consumed by Kotlin.
        return ['[*] Stage: ' + STAGE_MESSAGES[fields['stage']]]
    if event == 'rxEapol':
        step = {'M1': '1', 'M3': '3'}.get(fields['stage'], '?')
        headline = '[*] WPA: RX message %s of 4-Way Handshake from %s' % (step, display['bssid'])
    elif event == 'end':
        headline = '[%s] Diagnostic finished: success=%s, cleanup completed=%s' % (
            '+' if fields.get('success') and fields.get('cleanupCompleted') else 'i',
            display['success'], display['cleanupCompleted'])
    else:
        display['step'] = {'M2': '2', 'M4': '4'}.get(fields.get('stage'), '?')
        display['dhcpType'] = {1: 'DISCOVER', 2: 'OFFER', 3: 'REQUEST', 5: 'ACK', 6: 'NAK'}.get(fields.get('type'), display['type'])
        level, template = HEADLINES.get(event, ('i', 'Diagnostic event: ' + text(event)))
        headline = '[%s] %s' % (level, template.format_map(display))
    lines = [headline]
    if event == 'tryingPsk':
        return lines
    for key, value in fields.items():
        if key == 'trace':
            lines.extend('[-] ' + part for part in value.splitlines())
        elif key in HEX_LABELS:
            label = HEX_LABELS[key]
            if key == 'nonce':
                label = 'ANonce' if event == 'rxEapol' else 'SNonce' if fields.get('stage') == 'M2' else 'EAPOL Key Nonce'
            lines.append(hexdump(label, value))
        elif key in ('attributes', 'options'):
            label = 'nl80211 attribute' if key == 'attributes' else 'DHCP option'
            for number, values in value.items():
                for item in values if isinstance(values, list) else [values]:
                    lines.append(hexdump('%s %s' % (label, number), item))
        elif isinstance(value, (list, tuple)):
            lines.append('[i] %s: %s' % (key, ', '.join(text(item) for item in value) or '(none)'))
        else:
            label = re.sub(r'(?<=[a-z0-9])([A-Z])', r' \1', key).replace('_', ' ')
            lines.append('[i] %s: %s' % (label[:1].upper() + label[1:], text(value)))
    return lines
