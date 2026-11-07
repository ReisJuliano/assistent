# Assistent Neural Brain Server

Echo State Network (~300 neurons) that runs the robot's persistent emotional state on the PC.
The Android app queries it over the local network; it falls back to the built-in EmotionEngine when unreachable.

## Run

```
start.bat
```

Requires Python 3.10+. Installs fastapi, uvicorn and numpy automatically.

## Port

**8766** — make sure the firewall allows it on the local network.

## Configure the app

After starting, the banner prints the PC's local IP:

```
Configure no app: http://192.168.1.x:8766
```

In the app: Settings → Cérebro → "Servidor neural (PC)". Paste that URL.
Leave blank to use only the on-device emotion engine.

## State persistence

`brain_state.json` is written every 5 minutes and on every event. Delete it to reset the personality to a blank slate.

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | /health | Liveness check |
| GET | /state | Current emotion state (valence, arousal, curiosity, boredom, energy, mood_label) |
| POST | /event | Inject an event: `{"event": "user_spoke", "intensity": 1.0}` |
| GET | /thought | Suggested phrase category based on current state |
