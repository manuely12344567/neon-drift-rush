#!/usr/bin/env python3
"""Generates every audio asset of NEON DRIFT.

Pure Python (no dependency): the sound effects and the ambient loop are
synthesised sample by sample and written as 16-bit mono WAV files into
app/src/main/res/raw/. Run from the project root:

    python3 tools/gen_audio.py
"""

import math
import os
import random
import struct
import wave

SR = 22050
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "app", "src", "main", "res", "raw")


def write_wav(name, samples):
    path = os.path.join(OUT, name)
    peak = max(1e-9, max(abs(s) for s in samples))
    gain = 0.92 / peak if peak > 0.92 else 1.0
    data = bytearray()
    for s in samples:
        v = int(max(-1.0, min(1.0, s * gain)) * 32767)
        data += struct.pack("<h", v)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(bytes(data))
    print("  %-18s %6.1f kB  %5.2f s" % (name, len(data) / 1024.0, len(samples) / SR))


def env_ad(n, attack, decay, curve=2.0):
    """Attack/decay envelope, normalised to 1.0."""
    out = []
    a = max(1, int(attack * SR))
    for i in range(n):
        if i < a:
            out.append(i / a)
        else:
            k = (i - a) / max(1, (n - a))
            out.append(max(0.0, (1.0 - k) ** curve))
    return out


def sine(freq, t):
    return math.sin(2.0 * math.pi * freq * t)


def saw(freq, t):
    x = (t * freq) % 1.0
    return 2.0 * x - 1.0


def sfx_pick():
    n = int(0.16 * SR)
    e = env_ad(n, 0.004, 0.156, 2.4)
    out = []
    for i in range(n):
        t = i / SR
        f = 780.0 * math.exp(-6.0 * t) + 1180.0
        v = sine(f, t) * 0.7 + sine(f * 2.01, t) * 0.25 + sine(f * 3.0, t) * 0.1
        out.append(v * e[i])
    return out


def sfx_crash():
    random.seed(12)
    n = int(0.62 * SR)
    out = []
    lp = 0.0
    for i in range(n):
        t = i / SR
        noise = random.uniform(-1, 1)
        lp += (noise - lp) * 0.35            # one pole low pass
        f = 210.0 * math.exp(-3.2 * t) + 34.0
        sub = sine(f, t) * (1.0 - min(1.0, t / 0.55)) ** 1.3
        bright = (noise - lp) * math.exp(-14.0 * t) * 0.9
        body = lp * math.exp(-4.5 * t) * 1.5
        v = (sub * 0.9 + body + bright) * (1.0 - min(1.0, t / 0.62)) ** 0.7
        out.append(v * 0.95)
    return out


def sfx_near_miss():
    random.seed(4)
    n = int(0.30 * SR)
    out = []
    lp = 0.0
    for i in range(n):
        t = i / SR
        k = t / 0.30
        noise = random.uniform(-1, 1)
        lp += (noise - lp) * 0.10
        swell = math.sin(math.pi * k) ** 1.6
        f = 300.0 + 1500.0 * k
        v = (noise - lp) * swell * 0.55 + sine(f, t) * swell * 0.18
        out.append(v)
    return out


def sfx_tap():
    n = int(0.07 * SR)
    e = env_ad(n, 0.002, 0.068, 3.0)
    return [(sine(1500.0, i / SR) * 0.6 + sine(760.0, i / SR) * 0.4) * e[i]
            for i in range(n)]


def sfx_record():
    notes = [523.25, 659.25, 783.99, 1046.5]
    total = int(0.55 * SR)
    out = [0.0] * total
    for k, f in enumerate(notes):
        start = int(k * 0.085 * SR)
        length = int(0.26 * SR)
        e = env_ad(length, 0.004, 0.256, 2.2)
        for i in range(length):
            idx = start + i
            if idx >= total:
                break
            t = i / SR
            out[idx] += (sine(f, t) * 0.55 + sine(f * 2.0, t) * 0.18
                         + saw(f, t) * 0.12) * e[i]
    return out


def music_loop():
    """12 seconds of slow synthwave pad; seamless because every partial
    completes a whole number of cycles over the loop length."""
    seconds = 12.0
    n = int(seconds * SR)
    # A minor 9 -> F major 7, 6 s each
    chords = [
        [110.00, 164.81, 261.63, 329.63, 493.88],   # A2 E3 C4 E4 B4
        [87.31, 130.81, 220.00, 329.63, 392.00],    # F2 C3 A3 E4 G4
    ]
    out = [0.0] * n
    for i in range(n):
        t = i / SR
        half = seconds / 2.0
        if t < half:
            chord, local = chords[0], t
        else:
            chord, local = chords[1], t - half
        # smooth crossfade between the two halves
        fade_in = min(1.0, local / 1.4)
        fade_out = min(1.0, (half - local) / 1.4)
        amp = fade_in * fade_out
        v = 0.0
        for k, f in enumerate(chord):
            det = 1.0 + 0.0016 * (1 if k % 2 else -1)
            v += math.sin(2.0 * math.pi * f * det * t) * (0.30 / (1 + 0.55 * k))
            v += saw(f * det, t) * (0.045 / (1 + 0.6 * k))
        # slow filter-ish tremolo
        v *= 0.62 + 0.38 * math.sin(2.0 * math.pi * t / seconds * 2.0)
        # soft heartbeat pulse every second
        beat = (t % 1.0)
        v += math.sin(2.0 * math.pi * 55.0 * t) * 0.16 * math.exp(-9.0 * beat)
        out[i] = v * amp * 0.85
    # make the very ends match to avoid a click on loop
    fade = int(0.05 * SR)
    for i in range(fade):
        k = i / fade
        out[i] *= k
        out[n - 1 - i] *= k
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    print("Generating audio into", OUT)
    write_wav("sfx_pick.wav", sfx_pick())
    write_wav("sfx_crash.wav", sfx_crash())
    write_wav("sfx_near.wav", sfx_near_miss())
    write_wav("sfx_tap.wav", sfx_tap())
    write_wav("sfx_record.wav", sfx_record())
    write_wav("music_loop.wav", music_loop())
    print("done.")


if __name__ == "__main__":
    main()
