#!/usr/bin/env python3
"""kitmux_sync: refleja una carpeta del PC en Música/KitMux de un teléfono con KitMux, por WiFi.

Uso rápido:
    python kitmux_sync.py devices                 # teléfonos KitMux visibles en la red
    python kitmux_sync.py pair                    # emparejar (pide el código que muestra la app)
    python kitmux_sync.py sync ~/Música           # vigilar la carpeta y sincronizar

Solo copia en un sentido (PC -> teléfono) y solo archivos de audio. Lo que se borra o renombra
en la carpeta del PC también se borra o renombra en el teléfono (salvo con --no-delete).
"""

from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import logging
import os
import socket
import sys
import threading
import time
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import quote

log = logging.getLogger("kitmux_sync")

# Mismas extensiones que acepta la app (LibraryUtils.isAudioFile).
AUDIO_EXTENSIONS = {"mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "mka", "amr", "mid", "midi"}
FORBIDDEN_CHARS = set('\\:*?"<>|')
SERVICE_TYPE = "_kitmux._tcp.local."

SETTLE_SECONDS = 2.0  # un archivo modificado hace menos de esto puede estar copiándose todavía
QUIET_SECONDS = 1.5  # espera tras el último cambio antes de sincronizar (agrupa ráfagas)
PERIODIC_SECONDS = 30.0  # comprobación completa periódica, por si se perdió algún evento
RETRY_SECONDS = 5.0  # espera antes de reintentar si se perdió la conexión
FALLBACK_TIMEOUT = 2.0  # para probar la última IP conocida si mDNS no encuentra el teléfono


def config_dir() -> Path:
    return Path(os.environ.get("KITMUX_SYNC_HOME", Path.home() / ".config" / "kitmux-sync"))


# ---------------------------------------------------------------------------
# Lógica pura: qué hay en el PC y qué hay que hacer en el teléfono
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class LocalFile:
    size: int
    mtime_ns: int
    inode: int  # no cambia al renombrar/mover dentro del mismo disco; sí al copiar
    path: Path  # ruta real en el disco del PC

    @property
    def signature(self) -> tuple[int, int, int]:
        return self.size, self.mtime_ns, self.inode


def is_syncable(rel: str) -> bool:
    """Audio, sin carpetas/archivos ocultos ni caracteres que Android no admite."""
    parts = rel.split("/")
    if any(not p or p.startswith(".") or FORBIDDEN_CHARS & set(p) for p in parts):
        return False
    name = parts[-1]
    return "." in name and name.rsplit(".", 1)[1].lower() in AUDIO_EXTENSIONS


def remote_path(rel: str) -> str:
    # macOS puede guardar los nombres en NFD; en el teléfono los queremos en NFC.
    return unicodedata.normalize("NFC", rel)


def scan_local(root: Path, now: float) -> tuple[dict[str, LocalFile], set[str]]:
    """Devuelve (archivos estables, rutas que aún se están escribiendo)."""
    stable: dict[str, LocalFile] = {}
    unstable: set[str] = set()
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if not d.startswith(".")]
        for name in filenames:
            full = Path(dirpath) / name
            rel = remote_path(full.relative_to(root).as_posix())
            if not is_syncable(rel):
                continue
            try:
                st = full.stat()
            except FileNotFoundError:
                continue
            if now - st.st_mtime < SETTLE_SECONDS:
                unstable.add(rel)
            else:
                stable[rel] = LocalFile(st.st_size, st.st_mtime_ns, st.st_ino, full)
    return stable, unstable


@dataclass
class Plan:
    uploads: list[str] = field(default_factory=list)
    deletes: list[str] = field(default_factory=list)
    moves: list[tuple[str, str]] = field(default_factory=list)
    # Ya están iguales en el teléfono pero no constaban en el estado (p. ej. estado borrado).
    adopt: list[str] = field(default_factory=list)

    @property
    def is_empty(self) -> bool:
        return not (self.uploads or self.deletes or self.moves)


def _ext(path: str) -> str:
    name = path.rsplit("/", 1)[-1]
    return name.rsplit(".", 1)[1].lower() if "." in name else ""


def compute_plan(
    local: dict[str, tuple[int, int, int]],
    unstable: set[str],
    remote: dict[str, int],
    state: dict[str, tuple],
    allow_delete: bool = True,
) -> Plan:
    """Compara PC (ruta -> (tamaño, mtime, inodo)), teléfono (ruta -> tamaño) y lo último que se envió."""
    plan = Plan()
    for path, (size, mtime, _inode) in sorted(local.items()):
        remote_size = remote.get(path)
        if remote_size is None or remote_size != size:
            plan.uploads.append(path)
        elif path in state and tuple(state[path][:2]) != (size, mtime):
            plan.uploads.append(path)  # mismo tamaño pero modificado en el PC
        elif path not in state:
            plan.adopt.append(path)

    if allow_delete:
        plan.deletes = sorted(p for p in remote if p not in local and p not in unstable)

    # Renombrados: desaparece un archivo y aparece otro con el mismo tamaño, fecha, inodo y extensión.
    # Solo se mueve si la pareja es única por ambos lados; ante cualquier duda se reenvía.
    new_by_key: dict[tuple, list[str]] = {}
    for path in plan.uploads:
        if path not in remote:
            new_by_key.setdefault((*local[path], _ext(path)), []).append(path)
    gone_by_key: dict[tuple, list[str]] = {}
    for old in plan.deletes:
        record = state.get(old)
        if record is None or len(record) < 3 or remote[old] != record[0]:
            continue
        gone_by_key.setdefault((*record[:3], _ext(old)), []).append(old)
    for key, gone in gone_by_key.items():
        new = new_by_key.get(key, [])
        if len(gone) == 1 and len(new) == 1:
            plan.moves.append((gone[0], new[0]))
    plan.moves.sort()

    moved_from = {a for a, _ in plan.moves}
    moved_to = {b for _, b in plan.moves}
    plan.uploads = [p for p in plan.uploads if p not in moved_to]
    plan.deletes = [p for p in plan.deletes if p not in moved_from]
    return plan


# ---------------------------------------------------------------------------
# Cliente HTTP del receptor de KitMux
# ---------------------------------------------------------------------------


class DeviceError(Exception):
    """No se pudo hablar con el teléfono (desconectado, fuera de la red...)."""


class AuthError(DeviceError):
    """El teléfono no reconoce la clave de este PC."""


class RequestError(Exception):
    """El teléfono rechazó una operación concreta (el resto puede seguir)."""

    def __init__(self, status: int, message: str):
        super().__init__(f"{status}: {message}")
        self.status = status


class FileChanged(Exception):
    """El archivo cambió mientras se enviaba; se reintentará."""


class Device:
    def __init__(self, host: str, port: int, token: str | None = None, timeout: float = 30.0):
        self.host, self.port, self.token, self.timeout = host, port, token, timeout

    @property
    def address(self) -> str:
        return f"{self.host}:{self.port}"

    def _request(self, method: str, path: str, *, json_body=None, file: Path | None = None, size: int = 0):
        conn = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
        try:
            conn.putrequest(method, path, skip_accept_encoding=True)
            if self.token:
                conn.putheader("Authorization", f"Bearer {self.token}")
            data = b""
            if file is not None:
                conn.putheader("Content-Type", "application/octet-stream")
                conn.putheader("Content-Length", str(size))
            elif json_body is not None:
                data = json.dumps(json_body).encode()
                conn.putheader("Content-Type", "application/json")
                conn.putheader("Content-Length", str(len(data)))
            elif method != "GET":
                conn.putheader("Content-Length", "0")
            conn.endheaders()
            if file is not None:
                self._send_file(conn, file, size)
            elif data:
                conn.send(data)
            response = conn.getresponse()
            raw = response.read()
        except (OSError, http.client.HTTPException) as e:
            raise DeviceError(f"sin conexión con {self.address}: {e}") from e
        finally:
            conn.close()
        try:
            payload = json.loads(raw) if raw else {}
        except ValueError:
            payload = {"error": raw[:200].decode(errors="replace")}
        if response.status == 401:
            raise AuthError("el teléfono no reconoce este PC; vuelve a emparejar con: pair")
        if response.status >= 400:
            raise RequestError(response.status, payload.get("error", "error"))
        return payload

    @staticmethod
    def _send_file(conn: http.client.HTTPConnection, file: Path, size: int) -> None:
        remaining = size
        with open(file, "rb") as f:
            while remaining > 0:
                chunk = f.read(min(1 << 20, remaining))
                if not chunk:
                    # El archivo encogió: cortamos la conexión y el teléfono descarta lo recibido.
                    conn.close()
                    raise FileChanged(str(file))
                conn.send(chunk)
                remaining -= len(chunk)

    # --- operaciones ---

    def info(self) -> dict:
        return self._request("GET", "/info")

    def pair(self, code: str, client: str) -> dict:
        return self._request("POST", "/pair", json_body={"code": code, "client": client})

    def manifest(self) -> dict[str, int]:
        return {f["path"]: int(f["size"]) for f in self._request("GET", "/manifest")["files"]}

    def upload(self, path: str, file: Path, size: int) -> None:
        self._request("PUT", files_url(path), file=file, size=size)

    def delete(self, path: str) -> None:
        self._request("DELETE", files_url(path))

    def move(self, src: str, dst: str) -> None:
        self._request("POST", "/move", json_body={"from": src, "to": dst})


def files_url(path: str) -> str:
    return "/files/" + quote(path, safe="/")


# ---------------------------------------------------------------------------
# Descubrimiento por mDNS (solo encuentra teléfonos en la misma red local)
# ---------------------------------------------------------------------------


@dataclass
class Found:
    id: str
    name: str
    host: str
    port: int


class Discovery:
    def __init__(self):
        from zeroconf import IPVersion, ServiceBrowser, ServiceStateChange, Zeroconf

        self._ipv4 = IPVersion.V4Only
        self._removed = ServiceStateChange.Removed
        self._lock = threading.Lock()
        self._by_name: dict[str, Found] = {}
        self.changed = threading.Event()
        self.zeroconf = Zeroconf()
        self._browser = ServiceBrowser(self.zeroconf, SERVICE_TYPE, handlers=[self._on_change])

    def _on_change(self, zeroconf, service_type, name, state_change):
        if state_change is self._removed:
            with self._lock:
                self._by_name.pop(name, None)
            return
        info = zeroconf.get_service_info(service_type, name, timeout=3000)
        if info is None:
            return
        props = {k.decode(): (v or b"").decode() for k, v in info.properties.items()}
        addresses = info.parsed_addresses(self._ipv4)
        if not addresses or "id" not in props:
            return
        found = Found(props["id"], name.removesuffix("." + SERVICE_TYPE), addresses[0], info.port)
        with self._lock:
            self._by_name[name] = found
        self.changed.set()

    def devices(self) -> list[Found]:
        with self._lock:
            return list(self._by_name.values())

    def find(self, device_id: str) -> Found | None:
        return next((d for d in self.devices() if d.id == device_id), None)

    def close(self):
        self.zeroconf.close()


# ---------------------------------------------------------------------------
# Configuración y estado
# ---------------------------------------------------------------------------


def load_json(path: Path, default):
    try:
        return json.loads(path.read_text())
    except (FileNotFoundError, ValueError):
        return default


def save_json(path: Path, data, private: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False))
    if private:
        os.chmod(tmp, 0o600)  # contiene las claves de emparejado
    os.replace(tmp, path)


def config_path() -> Path:
    return config_dir() / "config.json"


def state_path(device_id: str, root: Path) -> Path:
    digest = hashlib.sha1(str(root).encode()).hexdigest()[:12]
    return config_dir() / "state" / f"{device_id}-{digest}.json"


def parse_host(value: str) -> tuple[str, int]:
    host, sep, port = value.rpartition(":")
    if not sep or not port.isdigit():
        raise argparse.ArgumentTypeError("usa el formato IP:PUERTO, p. ej. 192.168.1.20:41235")
    return host, int(port)


# ---------------------------------------------------------------------------
# Sincronización
# ---------------------------------------------------------------------------


def reconcile(device: Device, root: Path, state: dict, *, dry_run=False, allow_delete=True) -> bool:
    """Aplica en el teléfono las diferencias con el PC. Devuelve True si hay que reintentar pronto."""
    local_files, unstable = scan_local(root, time.time())
    local = {p: f.signature for p, f in local_files.items()}
    remote = device.manifest()
    plan = compute_plan(local, unstable, remote, {k: tuple(v) for k, v in state.items()}, allow_delete)
    retry = bool(unstable)

    for path in plan.adopt:
        state[path] = list(local[path])
    if plan.is_empty:
        if unstable:
            log.info("Esperando a que terminen de copiarse %d archivo(s)…", len(unstable))
        return retry

    log.info(
        "Cambios: %d para enviar, %d para mover, %d para borrar",
        len(plan.uploads), len(plan.moves), len(plan.deletes),
    )
    if dry_run:
        for a, b in plan.moves:
            log.info("  mover   %s → %s", a, b)
        for p in plan.deletes:
            log.info("  borrar  %s", p)
        for p in plan.uploads:
            log.info("  enviar  %s (%s)", p, human_size(local[p][0]))
        return False

    for src, dst in plan.moves:
        try:
            device.move(src, dst)
            state.pop(src, None)
            state[dst] = list(local[dst])
            log.info("Movido  %s → %s", src, dst)
        except RequestError as e:
            log.warning("No se pudo mover %s (%s); se enviará de nuevo", src, e)
            plan.uploads.append(dst)
            plan.deletes.append(src)

    for path in plan.deletes:
        try:
            device.delete(path)
            log.info("Borrado %s", path)
        except RequestError as e:
            if e.status != 404:
                log.warning("No se pudo borrar %s: %s", path, e)
                continue
        state.pop(path, None)

    total = len(plan.uploads)
    for i, path in enumerate(plan.uploads, 1):
        f = local_files[path]
        started = time.monotonic()
        try:
            device.upload(path, f.path, f.size)
        except FileChanged:
            log.info("%s cambió durante el envío; se reintentará", path)
            retry = True
            continue
        except FileNotFoundError:
            retry = True
            continue
        except RequestError as e:
            log.warning("El teléfono rechazó %s: %s", path, e)
            continue
        state[path] = list(f.signature)
        elapsed = max(time.monotonic() - started, 1e-3)
        log.info("Enviado [%d/%d] %s (%s, %s/s)", i, total, path, human_size(f.size), human_size(f.size / elapsed))

    for path in list(state):
        if path not in local:
            state.pop(path)
    return retry


def human_size(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} GB"


def connect(entry: dict, device_id: str, discovery: Discovery | None, forced: tuple[str, int] | None) -> Device | None:
    """Localiza el teléfono emparejado en la red local; None si no está disponible."""
    token = entry["token"]
    if forced:
        return Device(*forced, token=token)
    found = discovery.find(device_id) if discovery else None
    if found:
        entry["host"], entry["port"] = found.host, found.port
        return Device(found.host, found.port, token=token)
    # mDNS a veces está bloqueado en el router: probamos la última dirección conocida.
    if entry.get("host"):
        probe = Device(entry["host"], entry["port"], token=token, timeout=FALLBACK_TIMEOUT)
        try:
            if probe.info().get("id") == device_id:
                return Device(entry["host"], entry["port"], token=token)
        except (DeviceError, RequestError):
            pass
    return None


def pick_device(config: dict, wanted: str | None) -> tuple[str, dict]:
    devices = config.get("devices", {})
    if not devices:
        sys.exit("No hay ningún teléfono emparejado. Ejecuta primero: python kitmux_sync.py pair")
    if wanted:
        for device_id, entry in devices.items():
            if wanted in (device_id, entry.get("name")):
                return device_id, entry
        sys.exit(f"No hay un teléfono emparejado llamado «{wanted}».")
    if len(devices) > 1:
        names = ", ".join(e.get("name", i) for i, e in devices.items())
        sys.exit(f"Hay varios teléfonos emparejados ({names}); elige uno con --device.")
    return next(iter(devices.items()))


def cmd_sync(args) -> int:
    root = Path(args.folder).expanduser().resolve()
    if not root.is_dir():
        sys.exit(f"No existe la carpeta {root}")
    config = load_json(config_path(), {"devices": {}})
    device_id, entry = pick_device(config, args.device)
    name = entry.get("name", device_id)
    spath = state_path(device_id, root)
    state = load_json(spath, {})

    discovery = None if args.host else Discovery()
    dirty = threading.Event()
    dirty.set()
    last_event = [0.0]
    observer = None
    if not args.once:
        observer = start_watcher(root, dirty, last_event)
        log.info("Vigilando %s → «%s» (Música/KitMux). Ctrl+C para salir.", root, name)

    waiting_logged = False
    next_check = 0.0
    retry_at = 0.0
    try:
        if discovery and not args.host:
            discovery.changed.wait(3)  # da tiempo a que mDNS encuentre el teléfono
        while True:
            now = time.monotonic()
            due = dirty.is_set() and now - last_event[0] >= QUIET_SECONDS and now >= retry_at
            if args.once or due or now >= next_check:
                next_check = now + PERIODIC_SECONDS
                device = connect(entry, device_id, discovery, args.host)
                if device is None:
                    if args.once:
                        log.error("No se encontró «%s» en la red. ¿Está en la misma WiFi con «Recibir desde PC» activo?", name)
                        return 2
                    if not waiting_logged:
                        log.info("Esperando a que «%s» esté en la misma red WiFi con «Recibir desde PC» activo…", name)
                        waiting_logged = True
                else:
                    dirty.clear()
                    try:
                        if waiting_logged:
                            log.info("Conectado con «%s» en %s", name, device.address)
                        retry = reconcile(device, root, state, dry_run=args.dry_run, allow_delete=not args.no_delete)
                        waiting_logged = False
                        if retry:
                            dirty.set()
                            last_event[0] = time.monotonic()
                    except AuthError as e:
                        log.error("%s", e)
                        return 1
                    except DeviceError as e:
                        if args.once:
                            log.error("Se perdió la conexión: %s", e)
                            return 2
                        if not waiting_logged:
                            log.warning("Se perdió la conexión (%s). Se reintentará cuando vuelva a estar disponible.", e)
                            waiting_logged = True
                        dirty.set()
                        retry_at = time.monotonic() + RETRY_SECONDS
                    finally:
                        if not args.dry_run:
                            save_json(spath, state)
                        save_json(config_path(), config, private=True)
                if args.once:
                    return 0
            if discovery and discovery.changed.is_set():
                discovery.changed.clear()
                dirty.set()  # el teléfono (re)apareció en la red
            time.sleep(0.5)
    except KeyboardInterrupt:
        log.info("Saliendo.")
        return 0
    finally:
        if observer:
            observer.stop()
            observer.join(timeout=2)
        if discovery:
            discovery.close()


def start_watcher(root: Path, dirty: threading.Event, last_event: list[float]):
    from watchdog.events import FileSystemEventHandler
    from watchdog.observers import Observer

    class Handler(FileSystemEventHandler):
        def on_any_event(self, event):
            if event.event_type in ("created", "modified", "deleted", "moved", "closed"):
                last_event[0] = time.monotonic()
                dirty.set()

    observer = Observer()
    observer.schedule(Handler(), str(root), recursive=True)
    observer.start()
    return observer


def cmd_devices(args) -> int:
    discovery = Discovery()
    try:
        time.sleep(args.timeout)
        found = discovery.devices()
    finally:
        discovery.close()
    paired = load_json(config_path(), {"devices": {}}).get("devices", {})
    if not found:
        print("No se encontró ningún teléfono. Abre KitMux, activa «Recibir desde PC» y comprueba que estás en la misma WiFi.")
        return 1
    for d in found:
        mark = "emparejado" if d.id in paired else "sin emparejar"
        print(f"{d.name}  {d.host}:{d.port}  ({mark})")
    return 0


def cmd_pair(args) -> int:
    if args.host:
        device = Device(*args.host)
    else:
        discovery = Discovery()
        try:
            discovery.changed.wait(args.timeout)
            time.sleep(1)
            found = discovery.devices()
        finally:
            discovery.close()
        if not found:
            print("No se encontró ningún teléfono en la red. Activa «Recibir desde PC» en KitMux o usa --host IP:PUERTO.")
            return 1
        chosen = found[0]
        if len(found) > 1:
            for i, d in enumerate(found, 1):
                print(f"  {i}) {d.name}  {d.host}:{d.port}")
            choice = input("¿Cuál? ").strip()
            if not choice.isdigit() or not 1 <= int(choice) <= len(found):
                return 1
            chosen = found[int(choice) - 1]
        device = Device(chosen.host, chosen.port)

    try:
        info = device.info()
    except DeviceError as e:
        print(f"No se pudo conectar: {e}")
        return 1
    if info.get("app") != "kitmux":
        print("Ese dispositivo no es un receptor de KitMux.")
        return 1
    code = args.code or input(f"Código que muestra KitMux en «{info['name']}»: ").strip()
    try:
        result = device.pair(code, socket.gethostname())
    except RequestError as e:
        print(f"Emparejado rechazado ({e}). Tras 5 intentos fallidos la app genera un código nuevo.")
        return 1
    config = load_json(config_path(), {"devices": {}})
    config.setdefault("devices", {})[result["id"]] = {
        "name": result["name"], "token": result["token"], "host": device.host, "port": device.port,
    }
    save_json(config_path(), config, private=True)
    print(f"Emparejado con «{result['name']}». Ahora: python kitmux_sync.py sync <carpeta>")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Sincroniza una carpeta del PC con KitMux por WiFi.")
    parser.add_argument("-v", "--verbose", action="store_true")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("devices", help="lista los teléfonos con KitMux en la red")
    p.add_argument("--timeout", type=float, default=4.0, help="segundos buscando (4)")
    p.set_defaults(func=cmd_devices)

    p = sub.add_parser("pair", help="empareja este PC con un teléfono")
    p.add_argument("--host", type=parse_host, help="IP:PUERTO si mDNS no encuentra el teléfono")
    p.add_argument("--code", help="código de 6 dígitos (si no, se pregunta)")
    p.add_argument("--timeout", type=float, default=5.0)
    p.set_defaults(func=cmd_pair)

    p = sub.add_parser("sync", help="vigila una carpeta y la refleja en el teléfono")
    p.add_argument("folder", help="carpeta del PC a reflejar")
    p.add_argument("--device", help="nombre o id del teléfono si hay varios emparejados")
    p.add_argument("--host", type=parse_host, help="IP:PUERTO fijo en lugar de buscar por mDNS")
    p.add_argument("--once", action="store_true", help="sincroniza una vez y sale")
    p.add_argument("--dry-run", action="store_true", help="muestra qué haría sin cambiar nada")
    p.add_argument("--no-delete", action="store_true", help="no borra en el teléfono lo que se borre en el PC")
    p.set_defaults(func=cmd_sync)

    args = parser.parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(message)s",
        datefmt="%H:%M:%S",
    )
    # zeroconf registra como error fallos puntuales de multicast en alguna interfaz; solo con -v.
    logging.getLogger("zeroconf").setLevel(logging.DEBUG if args.verbose else logging.CRITICAL)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
