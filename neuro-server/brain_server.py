"""
Echo State Network brain server for Assistent robot.
Runs on the PC; Android app queries it via REST on the local network.
State persists to brain_state.json so the robot's mood survives restarts.
"""
import asyncio
import json
import math
import socket
import time
from pathlib import Path

import numpy as np
import uvicorn
from fastapi import FastAPI
from pydantic import BaseModel

# ── ESN parameters ────────────────────────────────────────────────────────────
N_RESERVOIR = 300
N_INPUTS = 9       # one per event type
DENSITY = 0.1
SPECTRAL_RADIUS = 0.9
LEAK_RATE = 0.3
SEED = 42
STATE_FILE = Path(__file__).parent / "brain_state.json"

# ── Event index map ────────────────────────────────────────────────────────────
EVENT_IDX = {
    "sleep": 0,
    "wake": 1,
    "user_spoke": 2,
    "tool_started": 3,
    "tool_success": 4,
    "positive_interaction": 5,
    "negative_interaction": 6,
    "long_idle": 7,
    "laugh_heard": 8,
}
EVENT_MAGNITUDE = [0.8, 0.6, 0.3, 0.2, 0.3, 0.5, 0.5, 0.1, 0.4]

# ── Build reservoir matrices (deterministic from seed) ─────────────────────────
rng = np.random.default_rng(SEED)

# Sparse W with given density and rescaled spectral radius
W_raw = np.zeros((N_RESERVOIR, N_RESERVOIR))
mask = rng.random((N_RESERVOIR, N_RESERVOIR)) < DENSITY
W_raw[mask] = rng.standard_normal(mask.sum())
eigenvalues = np.linalg.eigvals(W_raw)
max_eig = np.max(np.abs(eigenvalues))
W = W_raw * (SPECTRAL_RADIUS / max_eig) if max_eig > 0 else W_raw

# Input weights: each input perturbs a subset of neurons
W_in = rng.standard_normal((N_RESERVOIR, N_INPUTS)) * 0.5

# Readout weights (5 emotion dimensions)
W_out = rng.standard_normal((5, N_RESERVOIR)) * 0.3

# ── State ─────────────────────────────────────────────────────────────────────
x = np.zeros(N_RESERVOIR)
last_save = 0.0
is_sleeping = False
dream_state: dict | None = None  # snapshot taken at sleep onset, modified during sleep


def load_state():
    global x
    try:
        data = json.loads(STATE_FILE.read_text())
        saved = np.array(data["x"], dtype=float)
        if saved.shape == (N_RESERVOIR,):
            x = saved
            print("Brain state loaded from disk.")
    except Exception:
        pass


def save_state():
    global last_save
    STATE_FILE.write_text(json.dumps({"seed": SEED, "x": x.tolist()}))
    last_save = time.time()


def step(event: str, intensity: float = 1.0):
    global x, is_sleeping, dream_state
    if event == "sleep":
        is_sleeping = True
        dream_state = read_state()  # start dream from current mood
    elif event == "wake":
        is_sleeping = False
    idx = EVENT_IDX.get(event)
    if idx is None:
        return
    u = np.zeros(N_INPUTS)
    u[idx] = EVENT_MAGNITUDE[idx] * intensity
    x = (1 - LEAK_RATE) * x + LEAK_RATE * np.tanh(W @ x + W_in @ u)


def read_state() -> dict:
    out = np.tanh(W_out @ x)
    valence = float(out[0])
    arousal = float(out[1])
    curiosity = float((out[2] + 1) / 2)
    boredom = float((out[3] + 1) / 2)
    energy = float((out[4] + 1) / 2)

    if energy > 0.6 and valence > 0.2:
        label = "animado"
    elif arousal < -0.2 and energy < 0.4:
        label = "cansado"
    elif curiosity > 0.6:
        label = "curioso"
    elif valence < -0.3 and arousal > 0.3:
        label = "estressado"
    elif abs(valence) < 0.2 and abs(arousal) < 0.2:
        label = "neutro"
    else:
        label = "calmo"

    return dict(valence=valence, arousal=arousal, curiosity=curiosity,
                boredom=boredom, energy=energy, mood_label=label)


def thought_category(s: dict) -> tuple[str | None, float]:
    boredom, arousal, curiosity, energy, valence = (
        s["boredom"], s["arousal"], s["curiosity"], s["energy"], s["valence"]
    )
    if boredom > 0.6 and arousal < 0.0:
        return "thinking_hmm", boredom
    if curiosity > 0.6 and arousal > 0.3:
        return "thinking_found", curiosity
    if energy > 0.6 and valence > 0.3:
        return "quip_animado", energy
    if valence < -0.3 and arousal > 0.3:
        return "thinking_wait_long", abs(valence)
    return None, 0.0


# ── FastAPI app ───────────────────────────────────────────────────────────────
app = FastAPI(title="Assistent Neural Brain", version="1.0")


class EventRequest(BaseModel):
    event: str
    intensity: float = 1.0


@app.get("/health")
def health():
    return {"status": "ok", "neurons": N_RESERVOIR}


@app.get("/state")
def get_state():
    return read_state()


@app.post("/event")
def post_event(req: EventRequest):
    step(req.event, req.intensity)
    if time.time() - last_save > 300:
        save_state()
    return {"ok": True, "state": read_state()}


@app.get("/thought")
def get_thought():
    s = read_state()
    category, intensity = thought_category(s)
    return {"category": category, "intensity": intensity}


@app.get("/dream")
def get_dream():
    """Returns the mood that accumulated during sleep, so Android can seed the wake state."""
    s = dream_state if dream_state is not None else read_state()
    category, intensity = thought_category(s)
    return {"category": category, "intensity": intensity, "sleeping": is_sleeping}


# ── Daily rhythm background task ───────────────────────────────────────────────
async def daily_rhythm():
    global dream_state
    tick = 0
    while True:
        await asyncio.sleep(60)
        hour = time.localtime().tm_hour
        if is_sleeping:
            # Dream drift: slow random walk through emotion space.
            # Every ~10 min of sleep the dream shifts slightly.
            if tick % 10 == 0:
                noise = rng.standard_normal(N_INPUTS) * 0.08
                global x
                x = (1 - LEAK_RATE) * x + LEAK_RATE * np.tanh(W @ x + W_in @ noise)
                dream_state = read_state()
        else:
            if 0 <= hour < 6:
                step("long_idle", 0.3)
            elif 6 <= hour < 9:
                step("wake", 0.1)
            elif 22 <= hour < 24:
                step("sleep", 0.1)
        if time.time() - last_save > 300:
            save_state()
        tick += 1


@app.on_event("startup")
async def startup():
    load_state()
    asyncio.create_task(daily_rhythm())
    # Print banner
    hostname = socket.gethostname()
    try:
        local_ip = socket.gethostbyname(hostname)
    except Exception:
        local_ip = "127.0.0.1"
    print(f"\n  Assistent Neural Brain running.")
    print(f"  Configure no app: http://{local_ip}:8766")
    print(f"  Local:            http://127.0.0.1:8766\n")


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8766)
