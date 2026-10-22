"""Records a window's client area to a video file, DPI-aware, at a steady frame rate.

usage: python -I capture.py <window title> <ffmpeg.exe> <out.mkv> [fps] [max seconds]

Windows scales the GDI view of a window for programs that aren't DPI-aware (ffmpeg's gdigrab is one),
which produces a zoomed, cropped recording on a scaled display. This grabs physical pixels instead
and pipes raw frames to ffmpeg. Stop it with Ctrl+C or by killing the process; the mkv stays playable.
"""
import ctypes
import ctypes.wintypes as wt
import subprocess
import sys
import time

from PIL import ImageGrab

title, ffmpeg, out = sys.argv[1], sys.argv[2], sys.argv[3]
fps = int(sys.argv[4]) if len(sys.argv) > 4 else 30
max_seconds = float(sys.argv[5]) if len(sys.argv) > 5 else 1800

ctypes.windll.shcore.SetProcessDpiAwareness(2)
user32 = ctypes.windll.user32


def client_rect(hwnd):
    r = wt.RECT()
    user32.GetClientRect(hwnd, ctypes.byref(r))
    p = wt.POINT(0, 0)
    user32.ClientToScreen(hwnd, ctypes.byref(p))
    return p.x, p.y, p.x + r.right, p.y + r.bottom


hwnd = 0
for _ in range(600):
    hwnd = user32.FindWindowW(None, title)
    if hwnd:
        break
    time.sleep(0.5)
if not hwnd:
    sys.exit("no window titled " + title)

x0, y0, x1, y1 = client_rect(hwnd)
w, h = x1 - x0, y1 - y0
w -= w % 2
h -= h % 2
print(f"capturing {w}x{h} at {x0},{y0} from '{title}' at {fps} fps", flush=True)

proc = subprocess.Popen([
    ffmpeg, "-v", "error", "-y",
    "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", str(fps), "-i", "-",
    "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p", out,
], stdin=subprocess.PIPE)

start = time.perf_counter()
sent = 0
frame = None
try:
    while True:
        now = time.perf_counter() - start
        if now > max_seconds or not user32.IsWindow(hwnd):
            break
        due = int(now * fps)
        if due <= sent:
            time.sleep(min(0.004, (sent + 1) / fps - now))
            continue
        # Follow the window if it moves or resizes (the size is fixed once ffmpeg has started).
        cx0, cy0, _, _ = client_rect(hwnd)
        img = ImageGrab.grab(bbox=(cx0, cy0, cx0 + w, cy0 + h), all_screens=True)
        frame = img.tobytes()
        # Keep the cadence steady: if a grab took long, repeat the frame for the slots we missed.
        while sent < due:
            proc.stdin.write(frame)
            sent += 1
except (KeyboardInterrupt, BrokenPipeError):
    pass
finally:
    try:
        proc.stdin.close()
    except OSError:
        pass
    proc.wait(timeout=30)
print(f"wrote {sent} frames in {time.perf_counter() - start:.1f}s", flush=True)
