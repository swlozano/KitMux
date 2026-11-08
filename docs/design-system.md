# Sistema de diseño KitMux

Basado en Material 3 y en los colores del logo. Todo vive en `app/src/main/res/` y funciona en modo claro y oscuro.

## Marca

| Token | Valor | Uso |
|---|---|---|
| `brand_orange` | `#F0360A` | Naranja del logo. Botón de reproducir, acentos grandes, color de las notificaciones. |
| `brand_gray` | `#646669` | Gris del logo. Rol `secondary` en tema claro. |
| `brand_orange_dark` | `#C22D07` | Naranja para **texto y controles** sobre fondo claro (5,6:1). |
| `brand_orange_light` | `#FF8A65` | Naranja para texto y controles sobre fondo oscuro (8:1). |

`brand_orange` con texto blanco da 4,0:1: sirve para iconos y elementos grandes (mínimo 3:1), pero **no** para texto pequeño. Para texto usa `?attr/colorPrimary`.

Logotipo: `@drawable/logo_wordmark` (barra superior) y `@drawable/splash_logo` (splash). Ambos tienen versión `-night` con el gris aclarado. El icono de la app es siempre blanco.

## Color (roles)

En layouts usa siempre los atributos del tema (`?attr/colorPrimary`, `?attr/colorSurface`…). No uses colores fijos. Los valores están en `values/colors.xml` (claro) y `values-night/colors.xml` (oscuro).

| Rol | Claro | Oscuro | Para qué |
|---|---|---|---|
| `colorPrimary` | `#C22D07` | `#FF8A65` | Elemento activo: canción actual, controles activos, progreso |
| `colorPrimaryContainer` / `colorOnPrimaryContainer` | `#FFDBD1` / `#3C0800` | `#8C2000` / `#FFDBD1` | Destacados suaves: filtro seleccionado, avisos |
| `colorSecondary` | `#646669` | `#C6C6CA` | Acentos neutros |
| `colorSurface` / `colorOnSurface` | `#FFFBFA` / `#1D1B1A` | `#141313` / `#E8E1DF` | Fondo y texto principal |
| `colorOnSurfaceVariant` | `#55575A` | `#C4C6CA` | Texto secundario, iconos inactivos |
| `colorSurfaceContainer*` | `#FFFFFF` → `#EDE7E5` | `#0F0E0E` → `#353333` | Paneles y tarjetas (reproductor = `High`, carátula = `Highest`) |
| `colorOutline` / `colorOutlineVariant` | `#85878A` / `#D8D2D0` | `#8E9094` / `#45474A` | Bordes y divisores |
| `color_brand` | `#F0360A` | `#F0360A` | Botón principal (`bg_play_button`) |

## Espaciado, forma y tamaños (`values/dimens.xml`)

- Espaciado (escala de 4dp): `space_xxs` 2 · `space_xs` 4 · `space_s` 8 · `space_m` 12 · `space_l` 16 · `space_xl` 24 · `space_xxl` 32.
- Radios: `radius_xs` 4 · `radius_s` 8 · `radius_m` 12 · `radius_l` 16 · `radius_xl` 28. Están conectados a las formas de Material (`shapeAppearanceCorner*`). El panel inferior usa `ShapeAppearance.KitMux.BottomPanel`, con solo las esquinas superiores redondeadas.
- Tamaños: `size_touch` 48 (mínimo táctil) · `size_icon` 24 · `size_icon_l` 48 · `size_artwork_s` 44 (canción) · `size_artwork_m` 48 (carpeta en lista) · `size_folder_play` 44 · `size_play_button` 64 · `size_toolbar_logo` 22 · `grid_cell_min_width` 160 (ancho mínimo de una celda de la cuadrícula; mínimo 2 columnas) · `swipe_reveal_width` 88 (acciones reveladas al deslizar).

## Tipografía (`values/type.xml`)

Escala de Material 3 con la fuente del sistema. Estilos con su color ya incluido:

- `TextAppearance.KitMux.ListTitle` / `ListSubtitle`: filas de la lista.
- `TextAppearance.KitMux.PlayerTitle` / `PlayerSubtitle`: reproductor.
- `TextAppearance.KitMux.Caption`: tiempos, contador de canciones.

## Componentes (`values/styles.xml`)

- `Widget.KitMux.PlayerButton`: botón secundario del reproductor. Inactivo en `colorOnSurfaceVariant`; activo (aleatorio, repetir) en `colorPrimary`.
- `Widget.KitMux.PlayerButton.Play`: círculo naranja de marca de 64dp con icono blanco.
- `Widget.KitMux.FolderPlayButton`: círculo de 44dp que reproduce una carpeta. En la cuadrícula es gris en reposo (fondo `secondaryContainer`, icono `colorSecondary`) y pasa a `color_brand` (naranja intenso, icono blanco) en la carpeta seleccionada. En la lista, donde solo aparece al deslizar, siempre es `color_brand`. En la cuadrícula va visible abajo a la derecha de la portada. En la lista está oculto y aparece al deslizar la fila de derecha a izquierda (`SwipeRevealLayout`, panel de `swipe_reveal_width` 88dp sobre `surfaceContainerHigh`). Para TalkBack también se ofrece como acción de accesibilidad. Si esa carpeta está sonando muestra ⏸.
- Carpeta (`item_folder_list` / `item_folder_grid`) y canción (`item_song`): icono en un contenedor `surfaceContainerHighest`. El elemento que suena pasa a `primaryContainer`, con el título en `colorPrimary` y en negrita.
- `Widget.KitMux.Banner.Button`: botón de texto dentro de un aviso `primaryContainer`.
- `bg_rounded_m` / `bg_rounded_l`: fondos redondeados; el color se pone con `backgroundTint`. `bg_selectable_rounded`: ripple redondeado para las celdas de la cuadrícula.

## Pantallas

- **Inicio**: carpetas en lista o cuadrícula. En la lista, deslizar una carpeta a la izquierda muestra ▶; solo una fila abierta a la vez, y se cierra al tocarla o al desplazar la lista. El botón de la barra cambia de vista y la elección se guarda. La barra muestra el logo y «N carpetas · M canciones».
- **Carpeta**: al tocar una carpeta se ven sus canciones. La barra muestra la flecha de volver, el nombre y el número de canciones. «Atrás» vuelve al inicio.
- **Reproductor**: panel inferior común a ambas pantallas.

## Reglas

1. **Color**: nada de colores fijos en layouts ni en código. Usa roles (`?attr/...` o `MaterialColors.getColor`).
2. **Naranja**: el naranja de marca puro es para gráficos grandes. Para texto, `colorPrimary`.
3. **Medidas**: usa los tokens de espaciado y radio, no valores sueltos en dp.
4. **Contraste**: si añades un color nuevo, comprueba un contraste mínimo de 4,5:1 para texto y 3:1 para iconos y controles.
