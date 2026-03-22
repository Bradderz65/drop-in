import argparse
import asyncio
import glob
import json
import logging
import socket
import time
from fractions import Fraction
from typing import Optional

from aiohttp import WSMsgType, web
from aiortc import RTCConfiguration, RTCPeerConnection, RTCSessionDescription
from aiortc.contrib.media import MediaPlayer
from aiortc.sdp import candidate_from_sdp
from aiortc.mediastreams import VideoStreamTrack
from av import VideoFrame
from zeroconf import IPVersion, ServiceInfo, Zeroconf


LOG = logging.getLogger("dropin-server")
SERVICE_TYPE = "_dropin._tcp.local."
REGISTRY_TTL_SECONDS = 45
UI_HTML = """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Drop In PC Test</title>
  <style>
    :root {
      color-scheme: dark;
      --bg: #07131d;
      --panel: #10263a;
      --panel-2: #18324a;
      --text: #eef6ff;
      --muted: #9cb3c8;
      --accent: #47d68d;
      --danger: #ff7171;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      background: radial-gradient(circle at top, #12314b 0%, var(--bg) 55%);
      color: var(--text);
      font-family: "Segoe UI", system-ui, sans-serif;
      display: grid;
      place-items: center;
      padding: 24px;
    }
    .card {
      width: min(560px, 100%);
      background: rgba(16, 38, 58, 0.96);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 24px;
      padding: 24px;
      box-shadow: 0 24px 60px rgba(0, 0, 0, 0.28);
    }
    h1 { margin: 0 0 8px; font-size: 2rem; }
    p { margin: 0; color: var(--muted); }
    .status {
      margin-top: 20px;
      padding: 14px 16px;
      border-radius: 16px;
      background: var(--panel-2);
    }
    .status strong { display: block; margin-bottom: 6px; }
    .grid {
      margin-top: 20px;
      display: grid;
      gap: 12px;
    }
    .toggle {
      display: flex;
      align-items: center;
      justify-content: space-between;
      background: var(--panel);
      border-radius: 18px;
      padding: 16px;
    }
    .toggle label { font-weight: 600; }
    .toggle small { display: block; color: var(--muted); margin-top: 4px; }
    input[type="checkbox"] {
      width: 22px;
      height: 22px;
      accent-color: var(--accent);
    }
    .hint {
      margin-top: 18px;
      font-size: 0.95rem;
      color: var(--muted);
      line-height: 1.5;
    }
    code {
      background: rgba(255, 255, 255, 0.08);
      padding: 2px 6px;
      border-radius: 8px;
    }
  </style>
</head>
<body>
  <main class="card">
    <h1>Drop In PC Test</h1>
    <p>Use this page to control the local PC test peer before placing a drop-in call from the phone.</p>
    <section class="status">
      <strong id="state-line">Loading status…</strong>
      <span id="detail-line">Waiting for server state.</span>
    </section>
    <section class="grid">
      <div class="toggle">
        <div>
          <label for="camera">Send PC camera</label>
          <small>Choose which local camera device to send to the phone.</small>
        </div>
        <input id="camera" type="checkbox">
      </div>
      <div class="toggle">
        <div>
          <label for="camera-device">Camera device</label>
          <small>Available local video devices detected on this PC.</small>
        </div>
        <select id="camera-device"></select>
      </div>
      <div class="toggle">
        <div>
          <label for="mic">Send PC microphone</label>
          <small>Uses the default PulseAudio or ALSA input device when available.</small>
        </div>
        <input id="mic" type="checkbox">
      </div>
    </section>
    <p class="hint">
      Changes apply to the next call. Open the app on the phone and tap <code>PC Test</code>.
    </p>
  </main>
  <script>
    const camera = document.getElementById("camera");
    const mic = document.getElementById("mic");
    const cameraDevice = document.getElementById("camera-device");
    const stateLine = document.getElementById("state-line");
    const detailLine = document.getElementById("detail-line");

    async function loadState() {
      const response = await fetch("/api/state");
      const state = await response.json();
      camera.checked = state.enable_camera;
      mic.checked = state.enable_microphone;
      const selected = state.selected_camera_device;
      cameraDevice.innerHTML = "";
      for (const device of state.available_camera_devices) {
        const option = document.createElement("option");
        option.value = device;
        option.textContent = device;
        option.selected = device === selected;
        cameraDevice.appendChild(option);
      }
      stateLine.textContent = `Server: ${state.connection_state}`;
      detailLine.textContent = state.status_text;
    }

    async function saveState() {
      await fetch("/api/config", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          enable_camera: camera.checked,
          enable_microphone: mic.checked,
          selected_camera_device: cameraDevice.value,
        }),
      });
      await loadState();
    }

    camera.addEventListener("change", saveState);
    mic.addEventListener("change", saveState);
    cameraDevice.addEventListener("change", saveState);

    loadState();
    setInterval(loadState, 1500);
  </script>
</body>
</html>
"""


class TestPatternVideoTrack(VideoStreamTrack):
    def __init__(self) -> None:
        super().__init__()
        self._start = time.time()

    async def recv(self) -> VideoFrame:
        pts, time_base = await self.next_timestamp()
        width, height = 640, 360
        frame = VideoFrame(width=width, height=height, format="rgb24")
        elapsed = time.time() - self._start
        r = int((elapsed * 40) % 255)
        g = int((elapsed * 20 + 80) % 255)
        b = int((elapsed * 10 + 160) % 255)
        for plane in frame.planes:
            plane.update(bytes([r, g, b]) * width * height)
        frame.pts = pts
        frame.time_base = time_base if time_base else Fraction(1, 90000)
        return frame


class TailnetRegistry:
    def __init__(self) -> None:
        self._records: dict[str, dict] = {}

    def register(self, service_name: str, display_name: str, host: str, port: int, persistent: bool = False) -> None:
        self._records[service_name] = {
            "service_name": service_name,
            "display_name": display_name,
            "host": host,
            "port": port,
            "persistent": persistent,
            "last_seen": time.time(),
        }

    def peers(self, exclude: Optional[str] = None) -> list[dict]:
        now = time.time()
        stale_keys = [
            key for key, record in self._records.items()
            if not record["persistent"] and now - record["last_seen"] > REGISTRY_TTL_SECONDS
        ]
        for key in stale_keys:
            self._records.pop(key, None)
        return [
            {
                "service_name": record["service_name"],
                "display_name": record["display_name"],
                "host": record["host"],
                "port": record["port"],
            }
            for key, record in sorted(self._records.items())
            if key != exclude
        ]


class DropInPeerServer:
    def __init__(self, host: str, port: int, service_name: str, advertise: bool, registry: TailnetRegistry) -> None:
        self.host = host
        self.port = port
        self.service_name = service_name
        self.advertise = advertise
        self.registry = registry
        self.zeroconf: Optional[Zeroconf] = None
        self.service_info: Optional[ServiceInfo] = None
        self.peer_connection: Optional[RTCPeerConnection] = None
        self.web_socket: Optional[web.WebSocketResponse] = None
        self.remote_id: Optional[str] = None
        self.video_player: Optional[MediaPlayer] = None
        self.audio_player: Optional[MediaPlayer] = None
        self.enable_camera = True
        self.enable_microphone = False
        self.available_camera_devices = self._list_camera_devices()
        self.selected_camera_device = self.available_camera_devices[0] if self.available_camera_devices else "/dev/video0"
        self.connection_state = "idle"
        self.status_text = "Waiting for a phone to connect."

    async def handle_root(self, request: web.Request) -> web.StreamResponse:
        if request.headers.get("Upgrade", "").lower() != "websocket":
            return web.Response(text=UI_HTML, content_type="text/html")

        ws = web.WebSocketResponse()
        await ws.prepare(request)
        LOG.info("websocket opened from %s", request.remote)
        self.web_socket = ws
        self.connection_state = "signaling-connected"
        self.status_text = f"Signaling connected from {request.remote}."

        try:
            async for msg in ws:
                if msg.type != WSMsgType.TEXT:
                    continue
                payload = json.loads(msg.data)
                LOG.info("recv type=%s from=%s to=%s", payload.get("type"), payload.get("from"), payload.get("to"))
                await self._handle_signal(payload)
        finally:
            LOG.info("websocket closed")
            self.web_socket = None
            if self.connection_state != "idle":
                self.connection_state = "idle"
                self.status_text = "Waiting for a phone to connect."
            await self._close_peer_connection()

        return ws

    async def handle_state(self, request: web.Request) -> web.StreamResponse:
        return web.json_response(
            {
                "enable_camera": self.enable_camera,
                "enable_microphone": self.enable_microphone,
                "available_camera_devices": self.available_camera_devices,
                "selected_camera_device": self.selected_camera_device,
                "connection_state": self.connection_state,
                "status_text": self.status_text,
                "service_name": self.service_name,
                "host": self.host,
                "port": self.port,
            }
        )

    async def handle_config(self, request: web.Request) -> web.StreamResponse:
        payload = await request.json()
        self.enable_camera = bool(payload.get("enable_camera", self.enable_camera))
        self.enable_microphone = bool(payload.get("enable_microphone", self.enable_microphone))
        selected_camera_device = payload.get("selected_camera_device", self.selected_camera_device)
        if selected_camera_device in self.available_camera_devices:
            self.selected_camera_device = selected_camera_device
        self.status_text = (
            f"Ready for next call. Camera={'on' if self.enable_camera else 'off'} "
            f"({self.selected_camera_device}), "
            f"microphone={'on' if self.enable_microphone else 'off'}."
        )
        LOG.info(
            "updated config camera=%s device=%s microphone=%s",
            self.enable_camera,
            self.selected_camera_device,
            self.enable_microphone,
        )
        return await self.handle_state(request)

    async def handle_registry_register(self, request: web.Request) -> web.StreamResponse:
        payload = await request.json()
        service_name = str(payload.get("service_name", "")).strip()
        display_name = str(payload.get("display_name", "")).strip() or service_name
        port = int(payload.get("port", 0))
        host = request.remote or str(payload.get("host", "")).strip()
        if not service_name or not host or port <= 0:
            return web.json_response({"error": "service_name, host, and port are required"}, status=400)
        self.registry.register(
            service_name=service_name,
            display_name=display_name,
            host=host,
            port=port,
        )
        LOG.info("registry register service=%s host=%s port=%s", service_name, host, port)
        return web.json_response({"ok": True})

    async def handle_registry_peers(self, request: web.Request) -> web.StreamResponse:
        exclude = request.query.get("exclude")
        return web.json_response({"peers": self.registry.peers(exclude=exclude)})

    async def _handle_signal(self, payload: dict) -> None:
        signal_type = payload.get("type")
        self.remote_id = payload.get("from")

        if signal_type == "offer":
            await self._handle_offer(payload)
        elif signal_type == "ice":
            await self._handle_ice(payload)
        elif signal_type == "hangup":
            await self._close_peer_connection()

    async def _handle_offer(self, payload: dict) -> None:
        await self._close_peer_connection()
        pc = RTCPeerConnection(configuration=RTCConfiguration(iceServers=[]))
        self.peer_connection = pc

        @pc.on("icecandidate")
        async def on_icecandidate(candidate) -> None:
            if candidate is None:
                return
            await self._send(
                {
                    "type": "ice",
                    "from": self.service_name,
                    "to": self.remote_id,
                    "candidate": {
                        "sdpMid": candidate.sdpMid,
                        "sdpMLineIndex": candidate.sdpMLineIndex,
                        "sdpCandidate": candidate.to_sdp(),
                    },
                }
            )

        @pc.on("connectionstatechange")
        async def on_connectionstatechange() -> None:
            LOG.info("pc state=%s", pc.connectionState)
            self.connection_state = pc.connectionState
            self.status_text = f"Peer connection state: {pc.connectionState}."

        offer = RTCSessionDescription(sdp=payload["sdp"], type=payload.get("sdpType", "offer"))
        await pc.setRemoteDescription(offer)

        for transceiver in pc.getTransceivers():
            if transceiver.kind == "video":
                transceiver.direction = "sendonly" if self.enable_camera else "inactive"
            elif transceiver.kind == "audio":
                transceiver.direction = "sendonly" if self.enable_microphone else "inactive"

        if self.enable_camera:
            pc.addTrack(self._create_outbound_video_track())
        if self.enable_microphone:
            audio_track = self._create_outbound_audio_track()
            if audio_track is not None:
                pc.addTrack(audio_track)
            else:
                LOG.warning("microphone requested but no audio input could be opened")
        answer = await pc.createAnswer()
        await pc.setLocalDescription(answer)
        LOG.info("sending answer to %s", self.remote_id)
        self.status_text = (
            f"Answered {self.remote_id}. Camera={'on' if self.enable_camera else 'off'}, "
            f"microphone={'on' if self.enable_microphone else 'off'}."
        )
        await self._send(
            {
                "type": "answer",
                "from": self.service_name,
                "to": self.remote_id,
                "sdp": pc.localDescription.sdp,
                "sdpType": pc.localDescription.type,
            }
        )

    async def _handle_ice(self, payload: dict) -> None:
        if not self.peer_connection:
            return
        candidate = payload.get("candidate") or {}
        raw_candidate = candidate.get("sdpCandidate")
        if not raw_candidate:
            LOG.warning("ignoring ICE payload with no candidate body")
            return
        ice = candidate_from_sdp(raw_candidate)
        ice.sdpMid = candidate.get("sdpMid")
        ice.sdpMLineIndex = candidate.get("sdpMLineIndex")
        await self.peer_connection.addIceCandidate(ice)

    async def _send(self, payload: dict) -> None:
        if self.web_socket is None:
            LOG.warning("dropping outbound signal; no websocket")
            return
        LOG.info("send type=%s to=%s", payload.get("type"), payload.get("to"))
        await self.web_socket.send_str(json.dumps(payload))

    async def _close_peer_connection(self) -> None:
        if self.peer_connection is not None:
            await self.peer_connection.close()
            self.peer_connection = None
        if self.video_player is not None:
            if self.video_player.video is not None:
                self.video_player._stop(self.video_player.video)
            if self.video_player.audio is not None:
                self.video_player._stop(self.video_player.audio)
            self.video_player = None
        if self.audio_player is not None:
            if self.audio_player.video is not None:
                self.audio_player._stop(self.audio_player.video)
            if self.audio_player.audio is not None:
                self.audio_player._stop(self.audio_player.audio)
            self.audio_player = None

    def _create_outbound_video_track(self) -> VideoStreamTrack:
        try:
            player = MediaPlayer(
                self.selected_camera_device,
                format="v4l2",
                options={
                    "video_size": "1280x720",
                    "framerate": "30",
                },
            )
            if player.video is not None:
                self.video_player = player
                LOG.info("using %s webcam for outbound video", self.selected_camera_device)
                return player.video
            LOG.warning("webcam opened without a video track; falling back to test pattern")
        except Exception:
            LOG.exception("failed to open %s webcam; falling back to test pattern", self.selected_camera_device)
        self.video_player = None
        return TestPatternVideoTrack()

    def _list_camera_devices(self) -> list[str]:
        devices = sorted(glob.glob("/dev/video*"))
        return devices or ["/dev/video0"]

    def _create_outbound_audio_track(self):
        audio_sources = (
            ("pulse", "default", {"channels": "1", "sample_rate": "48000"}),
            ("alsa", "default", {"channels": "1", "sample_rate": "48000"}),
        )
        for fmt, device, options in audio_sources:
            try:
                player = MediaPlayer(device, format=fmt, options=options)
                if player.audio is not None:
                    self.audio_player = player
                    LOG.info("using %s input %s for outbound audio", fmt, device)
                    return player.audio
            except Exception:
                LOG.exception("failed to open %s input %s for outbound audio", fmt, device)
        self.audio_player = None
        return None

    def register_service(self) -> None:
        if not self.advertise:
            LOG.info("mdns disabled; not advertising service")
            return
        properties = {"device": "pc-test-peer"}
        LOG.info("registering zeroconf service %s", self.service_name)
        self.zeroconf = Zeroconf(ip_version=IPVersion.V4Only)
        self.service_info = ServiceInfo(
            type_=SERVICE_TYPE,
            name=f"{self.service_name}.{SERVICE_TYPE}",
            addresses=[socket.inet_aton(self.host)],
            port=self.port,
            properties=properties,
            server=f"{socket.gethostname()}.local.",
        )
        self.zeroconf.register_service(self.service_info)
        LOG.info("advertising %s on %s:%s", self.service_name, self.host, self.port)

    def unregister_service(self) -> None:
        if not self.advertise:
            return
        if self.zeroconf and self.service_info:
            self.zeroconf.unregister_service(self.service_info)
            self.zeroconf.close()
            self.zeroconf = None
            self.service_info = None


def default_host() -> str:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default=default_host())
    parser.add_argument("--port", type=int, default=8989)
    parser.add_argument("--name", default=f"dropin-PC-{socket.gethostname()}")
    parser.add_argument("--no-mdns", action="store_true")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    server = DropInPeerServer(
        host=args.host,
        port=args.port,
        service_name=args.name,
        advertise=not args.no_mdns,
        registry=TailnetRegistry(),
    )
    server.registry.register(
        service_name=args.name,
        display_name=args.name.removeprefix("dropin-"),
        host=args.host,
        port=args.port,
        persistent=True,
    )
    server.register_service()

    app = web.Application()
    app.router.add_get("/", server.handle_root)
    app.router.add_get("/api/state", server.handle_state)
    app.router.add_post("/api/config", server.handle_config)
    app.router.add_post("/api/registry/register", server.handle_registry_register)
    app.router.add_get("/api/registry/peers", server.handle_registry_peers)

    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, args.host, args.port)
    await site.start()
    LOG.info("listening on http://%s:%s", args.host, args.port)

    try:
        while True:
            await asyncio.sleep(3600)
    finally:
        server.unregister_service()
        await runner.cleanup()


if __name__ == "__main__":
    asyncio.run(main())
