# kitmux_sync — carpeta del PC → KitMux por WiFi

Vigila una carpeta del ordenador y refleja sus cambios en el teléfono, dentro de **Música/KitMux**:
lo que añades o modificas se envía, lo que renombras o mueves se mueve, y lo que borras se borra.
Solo copia archivos de audio (mp3, m4a, aac, flac, ogg, opus, wav…) y solo en un sentido (PC → teléfono).

La transferencia va siempre por la red WiFi local: el script encuentra el teléfono por mDNS
(solo responde en la misma red) y le envía los archivos por HTTP. No usa USB ni ADB.

## Instalación

```bash
cd tools/kitmux_sync
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

## Uso

1. En el teléfono: abre KitMux → menú ⋮ → **Recibir desde PC**. Aparece un aviso con la dirección y un código de 6 dígitos.
2. Empareja el PC (solo la primera vez):
   ```bash
   .venv/bin/python kitmux_sync.py pair
   ```
3. Sincroniza:
   ```bash
   .venv/bin/python kitmux_sync.py sync ~/Music/MiMusica
   ```
   El script sigue vigilando. Si el teléfono sale de la WiFi o se desactiva el receptor, espera; al volver
   envía solo las diferencias acumuladas.

Opciones de `sync`: `--once` (sincroniza y sale), `--dry-run` (muestra qué haría), `--no-delete`
(no borra en el teléfono), `--device NOMBRE` (si hay varios emparejados), `--host IP:PUERTO`
(si el router bloquea mDNS; el puerto se mantiene entre reinicios del receptor).
`devices` lista los teléfonos visibles en la red.

La configuración y las claves se guardan en `~/.config/kitmux-sync/` (o en `$KITMUX_SYNC_HOME`).

## Cómo funciona

- **Detección**: `watchdog` marca la carpeta como modificada; tras 1,5 s sin cambios (y cada 30 s por si se perdió algún evento)
  se compara todo: el PC (tamaño + fecha), el teléfono (`GET /manifest`) y lo último enviado (estado local).
  Así no importa el orden de los eventos ni si hubo cambios con el teléfono desconectado.
- **Archivos a medio copiar**: los modificados hace menos de 2 s se dejan para el siguiente ciclo.
- **Renombrados**: un archivo que desaparece y otro que aparece con el mismo tamaño, fecha y extensión se mueve en el teléfono en lugar de reenviarse.
- **Seguridad**: el emparejado usa un código de un solo uso que muestra la app (5 intentos fallidos lo renuevan) y devuelve una
  clave que se envía en cada petición. El teléfono guarda solo su hash, valida las rutas (sin `..` ni ocultos) y solo acepta audio.
  El tráfico va sin cifrar por la red local.

## Tests

```bash
.venv/bin/python -m unittest test_kitmux_sync
```

Incluyen un teléfono simulado que implementa el protocolo, así que no hace falta un dispositivo.
