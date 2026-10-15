# Mi IPTV

Reproductor IPTV propio para Android TV (Mi Box, Smart TV con Android), tablets y móviles.
No trae canales: se usa con tu propia lista (Xtream Codes o M3U).

## Funciones

- Menú superior: Inicio, TV en directo, Películas, Series, Buscar y Ajustes.
- Categorías a la izquierda y cuadrícula de canales o carátulas a la derecha.
- Favoritos (botón ♥ en las fichas o mantener pulsado OK) y "Últimos añadidos".
- Ficha de película: sinopsis, año, duración, géneros, Reproducir / Continuar, tráiler y títulos relacionados.
- Ficha de serie: temporadas desplegables, episodios, "Continuar SxEy" y paso automático al siguiente episodio.
- Guía EPG: ahora y después en la lista de canales y en el reproductor.
- Reproductor: ▲▼ cambia de canal, ◀ abre la lista de canales, OK marca favorito;
  en películas ◀▶ avanza o retrocede (más rápido si se mantiene pulsado) y OK pausa.
- "Continuar viendo" en Inicio.

## Cómo obtener el APK (sin instalar nada en el ordenador)

1. Sube esta carpeta a un repositorio de GitHub (rama `main`).
2. GitHub compila solo el APK (pestaña **Actions**, tarda unos 5-8 minutos).
3. El APK aparece en **Releases** como `MiIPTV.apk`.
   Si el repositorio es público, la Mi Box puede descargarlo con la app Downloader desde:
   `https://github.com/TU_USUARIO/TU_REPOSITORIO/releases/latest/download/MiIPTV.apk`

Cada versión nueva se instala encima de la anterior sin perder los datos (va firmada siempre con la misma clave `app/miiptv.keystore`).

## Compilar en el ordenador (opcional)

Abrir la carpeta con Android Studio y ejecutar `./gradlew assembleRelease`.
