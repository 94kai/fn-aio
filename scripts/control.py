#!/usr/bin/python3
"""独立 AIO 包用户生命周期：不接管或停止业务服务。"""
import errno
import os
import pathlib
import signal
import socket
import stat
import subprocess
import sys
import time

root = pathlib.Path(os.environ['TRIM_APPDEST']).resolve()
var = pathlib.Path(os.environ['TRIM_PKGVAR']).resolve()
bridge = root / 'bridge.mjs'
sock = root / 'app.sock'
pidfile = var / 'aio.pid'

def pid():
    try:
        value = int(pidfile.read_text().strip())
        args = pathlib.Path(f'/proc/{value}/cmdline').read_bytes().split(b'\0')
        return value if str(bridge).encode() in args else None
    except (ValueError, OSError):
        return None

def clear_orphan():
    try:
        info = sock.lstat()
    except FileNotFoundError:
        return
    if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.geteuid():
        raise RuntimeError('拒绝清理非本应用拥有的 Socket')
    with socket.socket(socket.AF_UNIX) as client:
        try:
            client.connect(str(sock))
        except OSError as error:
            if error.errno not in (errno.ECONNREFUSED, errno.ENOENT):
                raise
        else:
            raise RuntimeError('Socket 仍在使用，拒绝覆盖')
    # Fixed absolute child of this resolved target; socket type and owner checked.
    sock.unlink(missing_ok=True)

command = sys.argv[1]
if command == 'status':
    sys.exit(0 if pid() else 3)
if command == 'stop':
    running = pid()
    if running:
        os.kill(running, signal.SIGTERM)
        for _ in range(50):
            if pid() is None:
                break
            time.sleep(0.1)
        if pid() is not None:
            raise RuntimeError('AIO 未正常退出，未强制终止其他进程')
    clear_orphan()
    pidfile.unlink(missing_ok=True)
elif command == 'start':
    if pid():
        sys.exit(0)
    clear_orphan()
    var.mkdir(parents=True, exist_ok=True)
    os.umask(0o077)
    with (var / 'aio.log').open('ab') as log:
        process = subprocess.Popen(['/usr/bin/node', str(bridge), str(root / 'gateway.json'), str(sock)], stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
    pidfile.write_text(str(process.pid))
    for _ in range(50):
        if process.poll() is not None:
            raise RuntimeError('AIO 启动失败，请检查应用日志')
        if sock.exists():
            sys.exit(0)
        time.sleep(0.1)
    process.terminate()
    raise RuntimeError('AIO Socket 启动超时')
else:
    raise RuntimeError('仅支持 start、stop、status')
