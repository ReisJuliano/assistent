"""
Install kokoro and list all available voices with audio samples.
Run: python list_voices.py
"""
import subprocess, sys

# Install if needed
subprocess.run([sys.executable, "-m", "pip", "install", "kokoro-onnx", "soundfile", "--quiet"], check=True)

from kokoro_onnx import Kokoro
import soundfile as sf
import os

kokoro = Kokoro("kokoro-v1_0.onnx", "voices-v1_0.bin")

voices = kokoro.get_voices()
print(f"\n{'='*50}")
print(f"Kokoro — {len(voices)} vozes disponíveis")
print(f"{'='*50}")

# Group by language prefix
lang_map = {
    "a": "English (American)",
    "b": "English (British)",
    "e": "Spanish",
    "f": "French",
    "h": "Hindi",
    "i": "Italian",
    "j": "Japanese",
    "p": "Portuguese",
    "z": "Chinese",
}

by_lang: dict[str, list] = {}
for v in voices:
    lang = lang_map.get(v[0], "Other")
    by_lang.setdefault(lang, []).append(v)

for lang, vs in sorted(by_lang.items()):
    print(f"\n  {lang}:")
    for v in vs:
        gender = "♂" if v[1] == "m" else "♀"
        print(f"    {gender} {v}")

print(f"\n{'='*50}")
print("Gerando samples das vozes em português...")
os.makedirs("voice_samples", exist_ok=True)

TEST_TEXT = "Olá, estou aqui para te ajudar. É um prazer trabalhar com você."

pt_voices = [v for v in voices if v.startswith("p")]
if not pt_voices:
    print("Sem vozes PT nativas. Testando vozes EN masculinas com texto PT:")
    pt_voices = [v for v in voices if v.startswith("am_") or v.startswith("bm_")]

for voice in pt_voices:
    print(f"  Gerando {voice}...", end=" ", flush=True)
    try:
        samples, sr = kokoro.create(TEST_TEXT, voice=voice, speed=1.0, lang="pt")
        path = f"voice_samples/{voice}.wav"
        sf.write(path, samples, sr)
        print(f"-> {path}")
    except Exception as e:
        print(f"erro: {e}")

print(f"\nSamples em: neuro-server/voice_samples/")
print("Abre os .wav e escolhe a voz que mais gosta.")
