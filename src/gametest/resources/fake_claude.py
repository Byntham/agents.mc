# Stands in for the claude CLI in the menu test: speaks the same stream-json protocol and plays a
# canned build session, so screenshots don't depend on a real model. A message containing "quick"
# gets a short finished turn; anything else gets a long turn that keeps the agent working until it
# is interrupted.
#
# It appends its command line and every control request to fake-claude.jsonl in its working directory
# (the agent's folder), so the test can check what the mod sent. Agents whose name starts with "old"
# play an older Claude Code that refuses to switch models while running.
import atexit
import json
import os
import queue
import sys
import threading
import time
import uuid

inbox = queue.Queue()


def read():
    for line in sys.stdin:
        line = line.strip()
        if line:
            inbox.put(json.loads(line))
    inbox.put(None)


threading.Thread(target=read, daemon=True).start()
session = sys.argv[sys.argv.index("--resume") + 1] if "--resume" in sys.argv else str(uuid.uuid4())
pending = []
counter = [0]
settings = {
    "model": sys.argv[sys.argv.index("--model") + 1] if "--model" in sys.argv else None,
    "effortLevel": sys.argv[sys.argv.index("--effort") + 1] if "--effort" in sys.argv else None,
}
old = os.path.basename(os.getcwd()).startswith("old")


def record(obj):
    with open("fake-claude.jsonl", "a", encoding="utf-8") as f:
        f.write(json.dumps(obj) + "\n")


record({"argv": sys.argv[1:]})
atexit.register(lambda: record({"exit": True}))


def out(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


def assistant(blocks):
    out({"type": "assistant", "message": {"role": "assistant", "content": blocks}, "parent_tool_use_id": None, "session_id": session})


def control(m):
    """Answers a control request that arrives while no turn is waiting to be interrupted, or a settings change."""
    record({"control": m["request"]})
    subtype = m["request"].get("subtype")
    if subtype == "interrupt" or subtype == "apply_flag_settings" and not old:
        if subtype == "apply_flag_settings":
            settings.update(m["request"]["settings"])
        out({"type": "control_response", "response": {"subtype": "success", "request_id": m["request_id"]}})
    else:
        out({"type": "control_response", "response": {"subtype": "error", "request_id": m["request_id"], "error": "Unsupported control request"}})


def tool(name, args):
    counter[0] += 1
    tid = "toolu_%d" % counter[0]
    full = name if name[0].isupper() else "mcp__minecraft__" + name
    assistant([{"type": "tool_use", "id": tid, "name": full, "input": args}])
    time.sleep(0.25)
    return tid


def tool_result(tid, text, error=False):
    out({"type": "user", "message": {"role": "user", "content": [{"type": "tool_result", "tool_use_id": tid, "content": text, "is_error": error}]}, "parent_tool_use_id": None})


def wait(seconds):
    """Sleeps. A message that arrives meanwhile steers this turn, as with the real CLI's "next"
    priority. Returns True if interrupted."""
    end = time.time() + seconds
    while time.time() < end:
        try:
            m = inbox.get(timeout=0.2)
        except queue.Empty:
            continue
        if m is None:
            sys.exit(0)
        if m.get("type") == "control_request" and m["request"].get("subtype") != "interrupt":
            control(m)
            continue
        if m.get("type") == "control_request":
            if m["request"].get("cancel_queued"):
                pending.clear()
            out({"type": "control_response", "response": {"subtype": "success", "request_id": m["request_id"], "response": {"still_queued": []}}})
            return True
        if m.get("type") == "user":
            assistant([{"type": "text", "text": "Got it, switching the roof to spruce stairs."}])
            tool("build_layers", {"x": 12, "y": 69, "z": -8, "palette": {"s": "spruce_stairs[facing=north]"}, "layers": [[], []]})
    return False


def finish(start, ok=True):
    out({"type": "result", "subtype": "success" if ok else "error_during_execution", "is_error": not ok,
         "duration_ms": int((time.time() - start) * 1000), "session_id": session, "result": ""})


while True:
    msg = pending.pop(0) if pending else inbox.get()
    if msg is None:
        break
    if msg.get("type") == "control_request":
        control(msg)
        continue
    if msg.get("type") != "user":
        continue
    text = msg["message"]["content"]
    out({"type": "system", "subtype": "init", "session_id": session, "model": "fake-" + (settings["model"] or "default"),
         "capabilities": ["interrupt_receipt_v1", "interrupt_cancel_queued_v1"]})
    start = time.time()
    if "quick" in text:
        assistant([{"type": "text", "text": "Scouting north of spawn for a flat site big enough for a watchtower."}])
        tool_result(tool("find_blocks", {"block": "#logs", "radius": 48}), "...")
        tool_result(tool("WebSearch", {"query": "minecraft medieval watchtower proportions"}), "...")
        tool_result(tool("say", {"message": "Found a flat clearing 30 blocks north. Want the tower there?"}), "Said")
        finish(start)
        continue
    assistant([{"type": "text", "text": "I'll look around first to find flat ground near you."}])
    tool_result(tool("observe", {"radius": 16}), "...")
    tool_result(tool("find_player", {"player": "Player0"}), "...")
    tool_result(tool("say", {"message": "On it! Building a small oak cabin next to you."}), "Said")
    assistant([{"type": "text", "text": "The ground east of you is flat at y=64. I'll lay a 7 by 9 cobblestone floor, "
                                         "then oak log corners and plank walls with the door facing you."}])
    tool_result(tool("fill", {"x1": 12, "y1": 64, "z1": -8, "x2": 18, "y2": 64, "z2": 0, "block": "cobblestone"}), "...")
    tid = tool("build_layers", {"x": 12, "y": 65, "z": -8, "palette": {"o": "oak_log", "p": "oak_planks", "d": "oak_door[facing=west]"},
                                "layers": [[], [], [], []]})
    tool_result(tid, "1 block didn't end up as asked: oak_door at 12 65 -4 is air", True)
    tool_result(tool("set_blocks", {"blocks": [{}, {}]}), "...")
    tid = tool("build_layers", {"x": 12, "y": 69, "z": -8, "palette": {"s": "spruce_stairs[facing=north]"}, "layers": [[], []]})
    if wait(600):
        finish(start, ok=False)
        continue
    tool_result(tid, "...")
    finish(start)
