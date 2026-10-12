# GTA Car Lab

App Android nativa (Kotlin + Jetpack Compose + OpenGL ES 3.0), **GTA San Andreas**, con la arquitectura lista para otros juegos (`FormatModule`).
Dos módulos independientes que comparten el mismo motor (`:core`, Kotlin puro y testeado):

| Entrada del menú | Módulo | Qué hace |
|---|---|---|
| **VISOR Y CONVERSOR** | A | Importa ZIP, empareja DFF/TXD, 3D, Outliner, exporta GLB/OBJ/FBX (individual y masivo). *Todo lo anterior se conserva.* |
| **GENERADOR DE AUTOS** | B | Genera alternativas desde el catálogo (tipo, estilo, nivel de modificación, pintura, ruedas, interior, emblemas), con vista previa renderizada y revisión de solapes sobre las mallas. Se pueden bloquear piezas y regenerar. |
| **CATÁLOGO** | B | Biblioteca **permanente** (sobrevive a reinicios y a actualizaciones): vehículos y piezas con id, categoría, medidas, origen, materiales, texturas y compatibilidad. Búsqueda, filtros, orden, miniaturas progresivas, favoritos, categorías propias, renombrar, eliminar (con confirmación), detección de duplicados por huella (re-importar no duplica), copia de seguridad exportable/importable. |
| **EDITOR DE TEXTURAS** | B | Pincel, borrador, restaurar original, línea/rectángulo/elipse, relleno, cuentagotas, sello/logo (PNG/JPG), texto, «quitar logo» (rellena solo la zona), capas con opacidad, deshacer/rehacer por teselas, mapa UV superpuesto y edición básica de islas. Los cambios se guardan **en los píxeles** de una copia de la textura del proyecto. |
| **PROYECTOS** | B | Taller de ensamblaje con guardado automático (reabre tras reiniciar y avisa de recursos que falten). |
| **CONFIGURACIÓN** | — | Almacenamiento, limpieza, copia de seguridad, perfil de exportación por defecto, límites conocidos. |

## Módulo B en detalle

* **Taller**: la carrocería base se carga **bloqueada** (es la referencia y nunca se deforma sola). Cada pieza tiene posición/rotación/escala (campos numéricos, botones ±, o arrastrando con el dedo en el visor), restablecer, duplicar, copia en espejo, sustituir.
  Estados **independientes**: 👁 visible ≠ EXP exportar ≠ eliminada; además bloqueo, selección, renombrar, enfocar cámara, deshacer/recuperar y un resumen de lo incluido/omitido en la exportación.
* **Compatibilidad** (Compatible / con ajustes / dudosa / incompatible): se calcula por razón de escalas, residuos de posición, zona de la pieza y familia de vehículo, con los motivos a la vista; nunca solo por el nombre. Siempre se puede probar una pieza (con aviso). «Guardar ajuste» enseña al motor para próximos proyectos con la misma base.
* **Ruedas**: la orientación de cada rueda sale de la geometría (cara exterior hacia fuera, sin espejos).
* **Clasificación de tipo** (multi-etiqueta con confianza): medidas, ruedas/ejes, puertas, nombres y similitud con vehículos ya corregidos. Sus correcciones se guardan y se aprenden.
* **Separación de piezas**: por objetos del DFF, sub-meshes/materiales, componentes conexos y regiones de caras (selección manual de caras si hay dudas).
* **Logos/marcas**: ocultar/excluir, borrar caras (con vista previa en rojo y deshacer), limpiar la textura solo en la zona, reemplazar. La búsqueda por palabras clave ayuda a localizar, **no promete detección automática**.
* **AJUSTAR Y FUSIONAR**: ajustar a la base · combinar mallas · fusionar superficies (soldadura por posición+UV y normales unificadas) · coser bordes · analizar (caras duplicadas/degeneradas/invertidas, aristas abiertas, agujeros) · reparar **validado** (solo se aplica si re-analizar confirma la mejora). **No es una unión booleana de volúmenes.** Todo se hace sobre copias: lo original se conserva.
* **Materiales**: color, metálico, rugosidad, opacidad, vidrio independiente de la pintura, material «ninguno» (solo color), biblioteca de materiales (cromo, aluminio, goma…), variantes de pintura. Se exportan como PBR en el GLB.
* **Exportación Godot**: alcance (completo / carrocería / sin ruedas / sin interior / selección / componentes separados), perfiles (Godot 4: frente −Z, origen en suelo/centro, ruedas como nodos independientes; o glTF estándar) editables y guardables, y **re-importación y verificación del GLB** tras exportar.

## Dónde está el código

`core/src/main/kotlin/com/webmaletin/gtaviewer/`: `library/` (catálogo en disco, deduplicado por SHA-1) · `classify/` · `mesh/` (análisis, reparación, fusión) · `texture/` (PaintDocument, PNG) · `uv/` · `fit/` (compatibilidad y generador) · `project/` (formato de proyecto editable) · `export/ExportProfile.kt` · `glb/GlbImporter.kt` (verificación).
`app/src/main/java/com/webmaletin/gtaviewer/`: `LabViewModel.kt` (Módulo B) · `ui/{Menu,Library,Generator,Workshop*,Texture,Projects,Settings}Screen.kt`.

## Pruebas

```
./gradlew :core:test                 # núcleo: parsers, wheel orientation, catálogo, mallas, texturas, proyectos, generador, GLB
./gradlew :app:testDebugUnitTest     # interfaz Compose sobre Robolectric (JVM): cada pantalla compone, gestos táctiles pintan, el taller opera
```
`RealPacksTest` recorre el flujo completo con packs reales si existe `GTA_REAL_ZIPS` (o `/tmp/claude-0/zips`): importar → reabrir → generar → proyecto → GLB Godot → verificar.
Robolectric descarga su jar de Android desde Maven; sin red se puede precargar y apuntar `ROBOLECTRIC_DIR` a esa carpeta.
**Límite honesto:** no hay emulador en CI/entorno de desarrollo, así que OpenGL y el aspecto real en un teléfono no están probados automáticamente.

## Descarga

Cada push a `main` publica el APK firmado en la release **latest**: `https://github.com/webmaletin-art/App-Cars-Dream-Racing/releases/latest/download/GTA-Car-Lab.apk`
(no hay actualizador automático dentro de la app: se descarga ese APK y se instala encima).

---

# Módulo A · Visor y conversor (GTA Vehicle Viewer)

Aplicación Android nativa (Kotlin + Jetpack Compose + OpenGL ES 3.0) para **importar ZIP de vehículos de GTA San Andreas,
emparejar DFF/TXD automáticamente, verlos en 3D, editar piezas (ruedas, puertas, capó…) y exportar a GLB, OBJ+MTL+texturas y FBX+texturas**.
Los archivos originales (ZIP/DFF/TXD) nunca se modifican: toda edición ocurre sobre una representación interna.

> Primera versión: **solo GTA San Andreas** (también lee DFF/TXD de GTA III/VC, mismo RenderWare 3.x). Need for Speed **no** está implementado;
> la arquitectura deja el punto de enganche listo (ver «Añadir otro juego»).

## Instalar / actualizar sin desinstalar

* `app/build/outputs/apk/debug/app-debug.apk` y `app/build/outputs/apk/release/app-release.apk` (tras `./gradlew :app:assembleDebug :app:assembleRelease`).
* **Ambos se firman con la misma clave estable** (`signing/gtaviewer.keystore`), y el `versionCode` sube solo con cada commit
  (`max(APP_VERSION_CODE, nº de commits)`). Resultado: cualquier APK nuevo se instala **encima** del anterior sin desinstalar la app
  (mientras no cambies de clave). Instalar un APK `debug` sobre un `release` (o al revés) también funciona porque comparten firma.
* La clave está en el repositorio con contraseña conocida para que cualquier equipo/CI firme igual. **Si el repositorio es público, cualquiera
  podría firmar «actualizaciones» de esta app**: para uso serio reemplaza `signing/gtaviewer.keystore` por tu clave privada (o define
  `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` como variables de entorno/secrets) y no la publiques.
  Cambiar de clave obliga a desinstalar una vez.
* CI: `.github/workflows/build.yml` ejecuta los tests, compila y publica los APK (artefactos + release `latest`) en cada push a `main`.

## Compilar y probar

```
./gradlew :core:test                      # tests JVM (parsers, matching, edición, exportación…)
./gradlew :app:assembleDebug :app:assembleRelease
```
Requisitos: JDK 17+, Android SDK 34 (`local.properties` con `sdk.dir`).

## Uso

1. **IMPORTAR ZIP** (uno o varios). La app escanea recursivamente (async, con progreso), empareja y muestra el catálogo
   («48 vehículos detectados») con estado ✓ Completo / ⚠ TXD faltante / ⚠ DFF sospechoso / ⚠ Piezas separadas / ✕ Error de lectura.
2. **VER** carga *solo* ese vehículo (el anterior se libera por completo: VBO, IBO, texturas, geometría).
3. Visor: 1 dedo rota · 2 dedos pellizco = zoom y desplazamiento = pan · doble toque = auto encuadrar · botones Frontal/Trasera/Lateral/Superior/3-4/Reset/Auto.
4. **EDITAR PIEZAS**: tocar para seleccionar (Pieza / Sub-mesh / Material / Textura), Ocultar, Eliminar, Aislar, Solo esta, Seleccionar parecidas,
   Deshacer/Rehacer, Mostrar todo, restaurar eliminadas. Pestañas *Árbol* (por categoría, con visibilidad por pieza) y *Materiales* (ocultar/eliminar/seleccionar todos los meshes de un material).
   Botones rápidos: ruedas FL/FR/RL/RR, «ELIMINAR RUEDAS», y selección por tipo (puertas, capó, baúl, espejos, spoiler, paragolpes, interior, motor, escape, suspensión…) cuando el DFF las trae separadas.
5. **EXPORTAR**: vehículo (escena actual visible o completo) o **selección** (p. ej. `ruedas.glb`, `puertas.glb`) en GLB / OBJ+MTL+texturas (`Nombre_OBJ.zip`) / FBX+texturas (`Nombre_FBX.zip`). Si hubo ediciones: `Nombre_editado.glb`. Se guarda con el selector del sistema o se comparte.
6. **MODO MANUAL** (siempre disponible): elige cualquier DFF y cualquier TXD (del ZIP navegando carpetas, o del dispositivo), o «Cargar DFF sin TXD».
7. **Diagnóstico**: contadores del ZIP, DFF/TXD válidos, materiales/texturas encontradas y faltantes, errores de chunks, parser usado, log copiable.

## Flujo «preparar para mi juego» (Outliner + exportación masiva)

1. **Outliner** (botón OUTLINER del visor, panel lateral estilo Blender): lista *todas* las partes del DFF (10, 35… las que haya; sin nombre → `Mesh 001…`).
   Nombres propuestos por frame/textura (rueda, puerta, capó, baúl, cristales, luces, motor, escape, paragolpes, spoiler, interior, frenos, suspensión…); el usuario puede
   **reclasificar cualquier parte** (menú ⋮) y la propuesta nunca se aplica sin que se vea («propuesto»/«manual»).
2. **Casilla = visibilidad = exportación.** Hay una única fuente de verdad (`EditSession`): lo visible en el visor es exactamente lo que se exporta (GLB/OBJ/FBX, individual o masivo). No existe una segunda lista.
   Desmarcar no destruye nada (se puede volver a marcar). Casillas tri-estado en grupos y partes con varios materiales; contadores permanentes «Partes visibles: 10 / 14 · Meshes visibles: 27 / 32».
3. **Árbol real o lista plana**: solo se agrupa si hay información (al menos dos categorías distintas); si no, lista plana. Las partes sin clasificar quedan sueltas, sin grupos inventados.
4. **Tocar una pieza en el visor** la resalta, selecciona su fila en el Outliner (se desplaza hasta ella) y muestra malla/material/textura/vértices/triángulos.
5. Botones: Mostrar todo · Ocultar todo · Invertir · Restaurar original · Seleccionar todos · Deseleccionar todos · Solo selección · **PREPARAR PARA MI JUEGO** · **MARCAR COMO LISTO** · EXPORTAR ESTE VEHÍCULO · RESTABLECER VEHÍCULO.
   *Preparar* parte de la visibilidad original y desmarca las ruedas (opcionalmente suspensión, frenos, otras piezas; ver «Preparación para mi juego» en el catálogo). No toca DFF/TXD/ZIP.
6. **Estados por vehículo**: ○ SIN REVISAR · ◐ EDITADO · ✓ LISTO PARA EXPORTAR · ✕ ERROR. Cada vehículo guarda **su propia configuración** en memoria (mientras el ZIP siga abierto) y la recupera al reabrirlo.
   Editar un vehículo «listo» lo devuelve a EDITADO.
7. **Catálogo masivo**: casillas, buscar, filtros (Todos/Compatibles/Con TXD/Sin TXD/Con errores/Seleccionados/Listos), seleccionar todos/ninguno/invertir/compatibles, contador.
   **EXPORTAR SELECCIONADOS** y **EXPORTAR TODOS LOS COMPATIBLES** procesan los vehículos **uno por uno** (cargar DFF+TXD → configuración propia → visibilidad → exportar → liberar) y escriben en streaming
   `VEHICULOS_GLB.zip` (o `_OBJ`/`_FBX`, una carpeta por vehículo) con solo los archivos correctos y nombres humanos (`Audi_A3.glb`, `BMW_M4_GTS.glb`…). Un fallo no cancela el resto y se informa (`✕ Golf GTI — TXD incompatible`).
   Los vehículos SIN REVISAR se exportan con todas sus partes (la app lo avisa antes de empezar). La configuración global nunca se aplica por encima de la individual.
8. **Modo manual**: el vehículo cargado entra al catálogo como un elemento más (mismo visor, Outliner, estados, exportación individual y masiva).

## Arquitectura

```
core/  (Kotlin/JVM puro, sin Android → testeable)        app/  (Android)
  zip/        ZipArchive (commons-compress, acceso aleatorio)   ui/        pantallas Compose
  renderware/ lector genérico de chunks RW (tolerante)           gl/        GLSurfaceView + renderer ES 3.0
  dff/        GtaSaDffParser  → DffDocument                      io/        SAF, caché, exportación a archivo
  txd/        GtaSaTxdParser + TextureDecoder (DXT1/3/5, 8888, 888, 565, 1555, 4444, LUM8, PAL4/8)
  gta/        GtaSaScanner, TxdMatcher, DffClassifier, VehicleNamer, Dedupe, GtaSaVehicleBuilder
  catalog/    FormatModule, VehicleFormatParser, FormatRegistry, modelos de catálogo
  model/      VehicleModel (partes → sub-meshes → geometría compartida), Mat4, validación
  selection/  SelectionTools, RayPicker      editing/  EditSession (undo/redo)      camera/ OrbitCamera
  export/ (+ glb/ obj/ fbx/)  ExportScene, PngEncoder, GLB / OBJ+MTL / FBX
```

* **Parser RenderWare propio** (`RenderWareParser` + `GtaSaDffParser`/`GtaSaTxdParser`): consultadas rw-parser-ng, rwfury y DFF Loader solo como referencia de formato;
  **no se copió código** (evita problemas de licencia/compatibilidad; sin dependencias nativas). Soporta Clump, FrameList (nombres, HAnim), GeometryList, Geometry
  (posiciones, normales, UV, colores de vértice, triángulos o BinMesh/tri-strip), MaterialList, Material, Texture, Atomic, Extension, Skin (se detecta, no se usa).
* **Matching** por niveles: nombre exacto → nombre normalizado (sin mayúsculas/espacios/guiones/símbolos) → misma carpeta → carpeta más cercana → (último recurso, avisando) único DFF+TXD de la carpeta.
  Un TXD sin DFF nunca crea un vehículo; un DFF sin TXD sí («TXD faltante», se visualiza igualmente). Un TXD no se comparte entre dos DFF homónimos de carpetas distintas.
* **Vehículo vs pieza**: puntuación por nombre de archivo/carpeta/frames (`chassis`, `wheel_*_dummy`, …). Las piezas (`wheels.dff`, `tuning.dff`…) **no se descartan**:
  se listan en «PIEZAS RELACIONADAS» del vehículo (y se pueden añadir a la escena), o aparecen como «DFF sospechoso» si no se puede decidir a quién pertenecen.
* **Duplicados lógicos**: misma ruta/nombre normalizado + tamaño + CRC → una sola tarjeta (se listan los duplicados). Si difieren, se conservan como «Nombre» y «Nombre (2)».
* **Nombres**: carpeta específica del vehículo → nombre del DFF → nombre del TXD; «audi_a3»/«audi-a3»/«AudiA3» → «Audi A3»; los `.fxt/.txt` solo mejoran el nombre (`DeclasseYosemite.fxt` → «Declasse Yosemite»).
* **Ruedas** (`model/WheelOrientation.kt`): GTA SA puede traer una plantilla `wheel` + dummies `wheel_*_dummy`, o (como en el DFF real que sirvió de referencia) un atomic de rueda por esquina con copias idénticas y matriz identidad.
  Con la matriz del frame sola, las ruedas de un lado quedan mirando hacia dentro. La app **detecta en la geometría qué lado del eje es el exterior** (3 señales independientes: materiales llanta vs. freno/sombra, media axial del interior, plano dominante
  perpendicular al eje) y, con la matriz real del punto de montaje, **gira 180° sobre el eje vertical (rotación propia, sin espejo ni escala negativa) solo la instancia cuya cara exterior apuntaría hacia el centro del vehículo**. No depende del lado
  nombrado (si la plantilla se modela al revés, se giran las otras) ni duplica geometría; los hijos (logos) acompañan a su rueda. Si las señales no bastan, se respeta la matriz del DFF y se avisa en el diagnóstico.
  Variantes `_dam`/`_vlo` se cargan **ocultas por defecto** (no eliminadas; se activan en el Outliner).
* **Texturas por nombre** (Material → Texture → TXD, sin distinguir mayúsculas ni extensión `.png`), nunca por orden. Si falta: se mantiene el material (color) y se avisa.
* **Pintura**: los materiales marcador de SA (verde 60,255,0 = primario; magenta 255,0,175 = secundario) se sustituyen por un color elegible en el visor y en la exportación.

### Conversión de coordenadas (única, igual para todos los vehículos)
GTA SA: +X derecha, +Y delante, +Z arriba (diestro). Visor y exportaciones (GLB/OBJ/FBX): +Y arriba, frente del vehículo hacia +Z, +X = lado izquierdo,
metros. Transformación `(x, y, z) → (-x, z, y)`: rotación propia (det = +1), no refleja ni invierte el winding. Definida una sola vez en `Mat4.GTA_TO_VIEW`.
V de las UV: GLB conserva el origen arriba-izquierda de RenderWare; OBJ y FBX usan `1 − v`.

### Validación de modelos
Tras cargar, `ModelValidator` comprueba caja envolvente, escala, piezas muy alejadas, transformaciones no finitas, 0 triángulos y UV absurdas. Si falla: «Modelo requiere revisión» con
**Encuadre por geometría** (ignora piezas alejadas), **Normalizar escala** (solo escena/exportación; el original no cambia) y **Mostrar estructura**.

### Añadir otro juego (p. ej. Need for Speed) sin tocar el resto
1. Añadir el valor a `FormatModule` (`catalog/FormatModule.kt`).
2. Implementar `VehicleFormatParser` (`scan` / `load` / `loadManual` / `addRelatedPart`) en su propio paquete, produciendo un `VehicleModel`.
3. Registrarlo en `FormatRegistry.parsers`.
UI, edición, selección, cámara y exportadores trabajan sobre `VehicleModel` y no cambian. **No hay código de NFS en este repositorio.**

## Limitaciones conocidas (honestas)
* **FBX**: ASCII FBX 7.4 escrito a mano (sin SDK de Autodesk): sin esqueleto/animación, sin colores de vértice, mallas planas sin jerarquía. La app lo indica tras exportar («FBX exportado con limitaciones»). Verificado con assimp; **no** con el SDK oficial/Blender.
* Solo DFF/TXD de PC (D3D8/D3D9). Geometrías/texturas nativas de PS2/Xbox se detectan y se informan como no soportadas. Texturas con paleta: se asume RGBA (poco frecuente en vehículos).
* Skin/HAnim se detectan pero no se aplican. Colores de vértice se leen pero no se usan en visor/exportación. Efectos MatFX/reflejos no se renderizan.
* Sin miniaturas 3D en el catálogo (se omiten a propósito para no gastar RAM); la tarjeta muestra la inicial del vehículo.
* ZIP anidados y RAR/7z no se abren. Los ZIP con cifrado no se soportan.
* El catálogo vive en memoria: si Android mata la app hay que volver a importar el ZIP.
