The 16,128-byte `qa-stereo-tone.mp2` fixture is self-authored test data: 440 Hz
on the left and 659 Hz on the right, one second at 32 kHz, MPEG Layer II 128 kb/s.
It contains no game or third-party recording. The generated waveform is CC0-1.0.

Generated using FFmpeg 7.1 from the pinned imageio-ffmpeg 0.6.0 Windows wheel:

```
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=32000:duration=1 \
  -f lavfi -i sine=frequency=659:sample_rate=32000:duration=1 \
  -filter_complex "[0:a][1:a]amerge=inputs=2[out]" -map "[out]" \
  -ac 2 -c:a mp2 -b:a 128k -f mp2 qa-stereo-tone.mp2
```

The encoder executable and wheel are temporary build tools, not shipped artifacts.
