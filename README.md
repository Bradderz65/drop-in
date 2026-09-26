# Drop In

[![Verify](https://github.com/Bradderz65/drop-in/actions/workflows/verify.yml/badge.svg)](https://github.com/Bradderz65/drop-in/actions/workflows/verify.yml)

Drop In is an Android app for direct audio/video sessions between devices on a trusted LAN or Tailscale network. It uses Android NSD for local discovery, an optional shared registry for tailnet discovery, WebSocket signaling, and WebRTC media.

## Requirements

- JDK 17
- Android SDK Platform 37.0 and Build Tools 37.0.0
- Android 9 (API 28) or newer on the device

The Gradle wrapper is the supported build entry point. Newer system JDKs may not be compatible with the Android build toolchain, so select JDK 17 explicitly when necessary:

```bash
export JAVA_HOME=/path/to/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## How discovery works

- LAN peers advertise `_dropin._tcp.` with Android NSD/mDNS.
- Tailscale peers can be found by scanning active VPN host routes.
- A saved Tailscale address provides a direct fallback.
- Any Drop In peer can act as the shared registry when every device is configured with the same `http://<tailnet-ip>:8989` registry URL.

Signaling defaults to port `8989`. The service falls back to an available ephemeral port for LAN discovery if that port is already occupied.

## PC test peer

The Python utility exposes a browser control page, signaling peer, and optional discovery registry. On Linux it tries V4L2 video and PulseAudio/ALSA input, then falls back to a generated video pattern when a camera is unavailable.

```bash
python3 -m venv .venv-test-server
source .venv-test-server/bin/activate
python -m pip install -r requirements-test-server.txt
python tools/local_dropin_server.py
```

Open `http://localhost:8989`. Useful options include:

```bash
python tools/local_dropin_server.py --host 100.x.y.z --name dropin-PC-office
python tools/local_dropin_server.py --no-mdns
```

Run its checks with:

```bash
python -m compileall -q tools
PYTHONPATH=tools python -m unittest discover -s tools -p 'test_*.py'
python -m pip check
```

## Security

WebRTC encrypts media in transit, but peer discovery, registry traffic, and signaling use unauthenticated cleartext HTTP/WebSocket connections. Use Drop In only on networks you trust, such as a private LAN or tailnet; do not expose port `8989` directly to the public internet.

Camera, microphone, nearby-device, and notification permissions are requested independently. Calls can still receive media when camera or microphone access is unavailable.
