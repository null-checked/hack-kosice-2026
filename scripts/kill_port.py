#!/usr/bin/env python3
"""Cross-platform TCP port utility — kill or check listeners on 127.0.0.1.

Usage:
    python scripts/kill_port.py 8765 3000          # kill all processes on these ports
    python scripts/kill_port.py --check 8765       # exit 0 if port is bound, 1 if free

Replaces the Unix `lsof -ti:PORT | xargs kill -9` idiom (and its detection-only
counterpart `lsof -ti:PORT >/dev/null`) so the Makefile and scripts/*.sh work
identically on Windows, macOS, and Linux. Stdlib only.
"""
from __future__ import annotations

import platform
import socket
import subprocess
import sys


def is_bound(port: int, host: str = "127.0.0.1", timeout: float = 0.25) -> bool:
    """True if *port* has a listener on *host* right now."""
    if not 0 <= port <= 65535:
        return False
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout)
        try:
            return sock.connect_ex((host, port)) == 0
        except OSError:
            return False


def kill_port(port: int) -> None:
    """Kill every process listening on *port*. Silent if nothing is bound."""
    if platform.system() == "Windows":
        proc = subprocess.run(
            ["netstat", "-ano"],
            capture_output=True,
            text=True,
            check=False,
        )
        pids: set[str] = set()
        for line in proc.stdout.splitlines():
            parts = line.split()
            # Typical line: "  TCP    127.0.0.1:8765    0.0.0.0:0    LISTENING    12345"
            if (
                len(parts) >= 5
                and parts[1].endswith(f":{port}")
                and parts[3].upper() == "LISTENING"
            ):
                pids.add(parts[-1])
        for pid in pids:
            subprocess.run(
                ["taskkill", "/PID", pid, "/F"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
    else:
        proc = subprocess.run(
            ["lsof", "-ti", f":{port}"],
            capture_output=True,
            text=True,
            check=False,
        )
        for pid in proc.stdout.strip().split():
            subprocess.run(
                ["kill", "-9", pid],
                stderr=subprocess.DEVNULL,
                check=False,
            )


def main(argv: list[str]) -> int:
    if not argv:
        print(
            "usage: kill_port.py [--check] PORT [PORT ...]",
            file=sys.stderr,
        )
        return 2

    check_mode = False
    if argv[0] == "--check":
        check_mode = True
        argv = argv[1:]

    if not argv:
        print("kill_port.py: at least one PORT required", file=sys.stderr)
        return 2

    try:
        ports = [int(raw) for raw in argv]
    except ValueError as exc:
        print(f"kill_port.py: invalid port ({exc})", file=sys.stderr)
        return 2

    if check_mode:
        # Mirror `lsof -ti:PORT`'s semantics: success when ANY port is bound.
        return 0 if any(is_bound(p) for p in ports) else 1

    for port in ports:
        kill_port(port)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
