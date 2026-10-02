#!/usr/bin/env python3
"""Temporarily pause Android's supplicant; recover when the owner pipe closes.

The diagnostic owns the write end of stdin. This process has a separate session,
so terminating the diagnostic terminal does not terminate its recovery guard.
All configuration/control is JSON Lines; no Android framework shell commands.
"""
import json
import os
import select
import signal
import subprocess
import sys
import time


SYSTEM_PATHS = ('/system/', '/system_ext/', '/vendor/', '/odm/', '/apex/')


def process_state(pid):
    with open('/proc/%s/stat' % pid, encoding='utf-8') as source:
        # comm may contain spaces or parentheses; fields after its last ')' are stable.
        fields = source.read().rsplit(')', 1)[1].split()
    return fields[0], fields[19]  # state, starttime (field 22)


def system_supplicants():
    for entry in os.scandir('/proc'):
        if not entry.name.isdecimal():
            continue
        pid = int(entry.name)
        try:
            with open(entry.path + '/comm', encoding='utf-8') as source:
                if source.read().strip() != 'wpa_supplicant':
                    continue
            executable = os.readlink(entry.path + '/exe')
            if not executable.startswith(SYSTEM_PATHS):
                continue
            state, identity = process_state(pid)
            if state not in ('Z', 'X'):
                yield pid, identity, state, executable
        except (FileNotFoundError, ProcessLookupError):
            continue


def guard_main(request):
    timeout = request['timeoutMillis'] / 1000
    if timeout <= 0 or not hasattr(os, 'pidfd_open') or not hasattr(signal, 'pidfd_send_signal'):
        raise RuntimeError('系统需支持 pidfd，才能安全暂停并恢复系统 wpa_supplicant')
    deadline = time.monotonic() + timeout
    stopping = False
    targets = {}
    input_buffer = b''
    success = True

    def emit(event, **fields):
        # Loss of the diagnostic log pipe must never interrupt process restoration.
        try:
            print(json.dumps(dict(event=event, **fields), ensure_ascii=False), flush=True)
        except (BrokenPipeError, OSError):
            pass

    def request_stop(signum, frame):
        nonlocal stopping
        stopping = True

    for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(sig, request_stop)

    def pause_new_processes():
        for pid, identity, state, executable in system_supplicants():
            if stopping:
                break
            if pid in targets:
                if targets[pid]['identity'] != identity:
                    os.close(targets.pop(pid)['fd'])
                else:
                    # If another component resumes our process early, pause it again.
                    if targets[pid]['resume'] and state not in ('T', 't'):
                        signal.pidfd_send_signal(targets[pid]['fd'], signal.SIGSTOP)
                        emit('supplicantPausedAgain', pid=pid)
                    continue
            try:
                descriptor = os.pidfd_open(pid)
            except ProcessLookupError:
                continue
            try:
                current_state, current_identity = process_state(pid)
                if current_identity != identity or select.select([descriptor], [], [], 0)[0]:
                    os.close(descriptor)
                    continue
            except (FileNotFoundError, ProcessLookupError):
                os.close(descriptor)
                continue
            except BaseException:
                os.close(descriptor)
                raise
            resume = current_state not in ('T', 't')
            # Register recovery ownership BEFORE submitting SIGSTOP.
            targets[pid] = dict(fd=descriptor, identity=identity, resume=resume)
            if not resume:
                emit('supplicantAlreadyPaused', pid=pid, executable=executable,
                     message='进程原本已暂停，结束时保持原状')
                continue
            signal.pidfd_send_signal(descriptor, signal.SIGSTOP)
            pause_deadline = min(deadline, time.monotonic() + 1)
            while True:
                if select.select([descriptor], [], [], 0)[0]:
                    emit('supplicantExited', pid=pid)
                    os.close(targets.pop(pid)['fd'])
                    break
                current_state, current_identity = process_state(pid)
                if current_identity != identity:
                    raise RuntimeError('暂停期间进程身份变化：pid=%s' % pid)
                if current_state in ('T', 't'):
                    emit('supplicantPaused', pid=pid, executable=executable,
                         message='已确认系统 wpa_supplicant 暂停')
                    break
                if stopping or time.monotonic() >= pause_deadline:
                    raise RuntimeError('无法确认系统 wpa_supplicant 暂停：pid=%s' % pid)
                time.sleep(.01)

    try:
        pause_new_processes()
        emit('supplicantGuardReady', pausedPids=[pid for pid, item in targets.items() if item['resume']])
        next_scan = time.monotonic() + .25
        while not stopping:
            now = time.monotonic()
            if now >= deadline:
                emit('supplicantGuardTimeout', message='守护超时，恢复系统进程')
                break
            if now >= next_scan:
                pause_new_processes()
                next_scan = time.monotonic() + .25
            if not select.select([sys.stdin.buffer], [], [], min(.1, max(0, deadline-now)))[0]:
                continue
            data = os.read(sys.stdin.fileno(), 4096)
            if not data:
                emit('supplicantOwnerExited', message='测试脚本的管道已关闭，恢复系统进程')
                break
            input_buffer += data
            while b'\n' in input_buffer:
                line, input_buffer = input_buffer.split(b'\n', 1)
                if json.loads(line).get('type') == 'release':
                    stopping = True
                    break
    except BaseException as error:
        success = False
        emit('supplicantGuardError', error=repr(error))
    finally:
        for pid, item in reversed(list(targets.items())):
            try:
                if item['resume']:
                    signal.pidfd_send_signal(item['fd'], signal.SIGCONT)
                    resume_deadline = time.monotonic() + 1
                    while not select.select([item['fd']], [], [], 0)[0]:
                        state, identity = process_state(pid)
                        if identity != item['identity']:
                            raise RuntimeError('恢复期间进程身份变化：pid=%s' % pid)
                        if state not in ('T', 't'):
                            break
                        if time.monotonic() >= resume_deadline:
                            raise RuntimeError('无法确认系统 wpa_supplicant 恢复：pid=%s' % pid)
                        time.sleep(.01)
                    emit('supplicantResumed', pid=pid, message='系统 wpa_supplicant 已恢复或已退出')
            except (ProcessLookupError, FileNotFoundError):
                emit('supplicantExited', pid=pid)
            except BaseException as error:
                success = False
                emit('supplicantResumeError', pid=pid, error=repr(error))
            finally:
                os.close(item['fd'])
        emit('supplicantGuardFinished', restored=success)
    return 0 if success else 1


class SupplicantPauseGuard:
    def __init__(self, timeout_millis, log):
        self.timeout_millis = timeout_millis
        self.log = log
        self.process = None
        self.buffer = b''
        self.ready = False
        self.restored = False

    def receive(self, timeout):
        # Drain all available records even if the child has already exited.
        while select.select([self.process.stdout], [], [], timeout)[0]:
            data = os.read(self.process.stdout.fileno(), 4096)
            if not data:
                return
            self.buffer += data
            while b'\n' in self.buffer:
                line, self.buffer = self.buffer.split(b'\n', 1)
                event = json.loads(line)
                name = event.pop('event')
                if name == 'supplicantGuardReady':
                    self.ready = True
                if name == 'supplicantGuardFinished':
                    self.restored = event.get('restored', False)
                self.log(name, **event)
            timeout = 0

    def start(self, check_running):
        self.process = subprocess.Popen(
            [sys.executable, os.path.abspath(__file__)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            start_new_session=True, close_fds=True, bufsize=0,
        )
        self.process.stdin.write((json.dumps(dict(timeoutMillis=self.timeout_millis)) + '\n').encode())
        while not self.ready:
            self.receive(.1)
            check_running()
            if self.process.poll() is not None:
                self.receive(0)
                raise RuntimeError('系统 wpa_supplicant 暂停守护进程未能启动')

    def ensure_active(self):
        if self.process and self.ready and self.process.poll() is not None:
            self.receive(0)
            raise RuntimeError('系统 wpa_supplicant 守护进程已结束，中止网卡测试')

    def release(self):
        if self.process is None:
            return True
        try:
            self.process.stdin.write(b'{"type":"release"}\n')
        except (BrokenPipeError, OSError):
            pass
        finally:
            self.process.stdin.close()
        deadline = time.monotonic() + 5
        while self.process.poll() is None and time.monotonic() < deadline:
            self.receive(.1)
        if self.process.poll() is None:
            self.process.terminate()  # Guard handles SIGTERM by restoring, never SIGKILL.
            deadline = time.monotonic() + 2
            while self.process.poll() is None and time.monotonic() < deadline:
                self.receive(.1)
        self.receive(0)
        self.process.stdout.close()
        return self.process.poll() == 0 and self.restored


if __name__ == '__main__':
    try:
        configuration = json.loads(sys.stdin.buffer.readline())
        sys.exit(guard_main(configuration))
    except Exception as error:
        try:
            print(json.dumps(dict(event='supplicantGuardError', error=repr(error))), flush=True)
        except (BrokenPipeError, OSError):
            pass
        sys.exit(1)
