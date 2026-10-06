#!/usr/bin/env python3
"""
G13 System Monitor: shows live system stats on the Logitech G13 LCD.

    19:12:03     up 3d 04:12
    CPU  42% [XXXX------] 65C
    RAM  23% 3.6G/15.5G
    NET in  1.2M/s out   85K/s
    DSK rd   12M/s wr   1.3M/s

Runs until stopped (Ctrl+C or the g13-monitor systemd user service). While no
G13 is connected or the driver is not running, it waits and starts showing
stats as soon as the driver's LCD pipe appears. Requires python psutil.
"""
import errno
import glob
import os
import signal
import sys
import time
from datetime import datetime

import psutil

# Path to the Named Pipe (Must match the path in the C++ driver, ConfigPath::getFifoPath)
PIPE_PATH = os.path.join(os.environ.get("XDG_RUNTIME_DIR") or "/tmp", "g13-lcd")

REFRESH_SECONDS = 1.0

# Common sensor names for the CPU temperature (Intel, AMD, Raspberry Pi).
CPU_SENSORS = ("coretemp", "k10temp", "zenpower", "cpu_thermal")

# Path of the CPU temperature input, looked up once (False = not looked up yet).
_cpu_temp_path = False


def create_bar(percent, length=10):
    """Creates a simple ASCII loading bar."""
    filled_length = int(length * percent // 100)
    return "[" + "X" * filled_length + "-" * (length - filled_length) + "]"


def format_size(size):
    """Formats a byte count compactly, e.g. 512B, 85K, 1.2M, 15.5G."""
    for unit in ("B", "K", "M", "G"):
        if size < 1024 or unit == "G":
            return f"{size:.1f}{unit}" if unit != "B" and size < 10 else f"{size:.0f}{unit}"
        size /= 1024


def format_uptime(seconds):
    """Formats the uptime as "3d 04:12" or "04:12"."""
    days, rest = divmod(int(seconds), 86400)
    hours, minutes = rest // 3600, rest % 3600 // 60
    return f"{days}d {hours:02d}:{minutes:02d}" if days else f"{hours:02d}:{minutes:02d}"


def find_cpu_temp_path():
    """Returns the temperature input of the CPU sensor in /sys/class/hwmon, or None."""
    names = {}
    for hwmon in glob.glob("/sys/class/hwmon/hwmon*"):
        try:
            with open(os.path.join(hwmon, "name")) as f:
                names[f.read().strip()] = hwmon
        except OSError:
            continue
    for name in CPU_SENSORS:
        path = os.path.join(names.get(name, ""), "temp1_input")
        if name in names and os.path.exists(path):
            return path
    return None


def cpu_temperature():
    """
    Returns the CPU temperature in degrees Celsius, or None if not available.
    Only the CPU sensor is read: reading all sensors every second (psutil.sensors_temperatures)
    also polls e.g. embedded controllers, which can cause kernel warnings like
    "Concurrent access to the ACPI EC detected" (asus-ec-sensors).
    """
    global _cpu_temp_path
    if _cpu_temp_path is False:
        _cpu_temp_path = find_cpu_temp_path()
    if _cpu_temp_path is None:
        return None
    try:
        with open(_cpu_temp_path) as f:
            return int(f.read()) / 1000
    except (OSError, ValueError):
        _cpu_temp_path = False  # Look it up again next time.
        return None


def net_bytes():
    """Returns (received, sent) bytes over all network interfaces except loopback."""
    counters = psutil.net_io_counters(pernic=True)
    recv = sum(c.bytes_recv for nic, c in counters.items() if nic != "lo")
    sent = sum(c.bytes_sent for nic, c in counters.items() if nic != "lo")
    return recv, sent


def disk_bytes():
    """Returns (read, written) bytes over all disks."""
    counters = psutil.disk_io_counters()
    return (counters.read_bytes, counters.write_bytes) if counters else (0, 0)


class Rate:
    """Calculates a per-second rate from an increasing counter."""

    def __init__(self, value):
        self.value, self.time = value, time.monotonic()

    def update(self, value):
        now = time.monotonic()
        rate = max(0, value - self.value) / max(now - self.time, 1e-6)
        self.value, self.time = value, now
        return rate


def start_measuring():
    """Starts the rate measurements (network, disk, CPU usage) from now on."""
    psutil.cpu_percent(interval=None)  # The first call always returns 0.
    return {
        "net": [Rate(v) for v in net_bytes()],
        "disk": [Rate(v) for v in disk_bytes()],
    }


def build_screen(rates):
    """Collects the stats and builds the 5 lines (max 26 characters each)."""
    now = datetime.now().strftime("%H:%M:%S")
    uptime = format_uptime(time.time() - psutil.boot_time())

    cpu = psutil.cpu_percent(interval=None)
    temp = cpu_temperature()
    temp_str = f" {temp:.0f}C" if temp is not None else ""

    ram = psutil.virtual_memory()

    net_in, net_out = (r.update(v) for r, v in zip(rates["net"], net_bytes()))
    disk_rd, disk_wr = (r.update(v) for r, v in zip(rates["disk"], disk_bytes()))

    return "\n".join([
        f"{now}  up {uptime:>10}",
        f"CPU {cpu:3.0f}% {create_bar(cpu)}{temp_str}",
        f"RAM {ram.percent:3.0f}% {format_size(ram.used)}/{format_size(ram.total)}",
        # Fixed-width values (format_size returns at most 5 characters), so the fields don't move.
        f"NET in {format_size(net_in):>5}/s out {format_size(net_out):>5}/s",
        f"DSK rd {format_size(disk_rd):>5}/s wr  {format_size(disk_wr):>5}/s",
    ])


def write_to_pipe(message):
    """
    Writes the message to the driver's LCD pipe.
    Returns False if the pipe is not available (driver not running or no G13 connected).
    """
    try:
        # Non-blocking, so this fails instead of hanging if the pipe has no reader.
        fd = os.open(PIPE_PATH, os.O_WRONLY | os.O_NONBLOCK)
    except OSError as e:
        if e.errno in (errno.ENOENT, errno.ENXIO):
            return False
        raise
    try:
        os.write(fd, message.encode("ascii", "replace"))
    except BrokenPipeError:
        return False
    finally:
        os.close(fd)
    return True


def main():
    # Stop cleanly on "systemctl stop" (SIGTERM) as on Ctrl+C.
    signal.signal(signal.SIGTERM, lambda signum, frame: sys.exit(0))

    print(f"G13 Monitor started, writing to {PIPE_PATH}. Press Ctrl+C to exit.", flush=True)
    rates = None  # Only measured while a G13 is connected.
    connected = None

    try:
        while True:
            if not os.path.exists(PIPE_PATH):
                # No G13 connected or driver not running: don't collect any stats.
                rates = None
                now_connected = False
            elif rates is None:
                # (Re)connected: start measuring, the first stats are shown in the next round.
                rates = start_measuring()
                now_connected = connected
            else:
                now_connected = write_to_pipe(build_screen(rates))

            if now_connected != connected and now_connected is not None:
                connected = now_connected
                print("G13 found, showing stats." if connected
                      else "Waiting for the G13 driver and device...", flush=True)
            time.sleep(REFRESH_SECONDS)
    except KeyboardInterrupt:
        pass
    finally:
        # Don't leave stale stats on the display.
        if connected:
            write_to_pipe("\n")
        print("G13 Monitor stopped.", flush=True)


if __name__ == "__main__":
    main()
