# Fold Widgets

Music, agenda and weather widgets for the Galaxy Z Fold 8 Ultra's cover screen, in the finishes of
Fold Taskbar plus a new glass finish, Vetro. Ferguson Consultancy Designs, following Bellezza
Necessaria.

## The widgets

| Widget | Default size | What it shows |
|---|---|---|
| Music | Small | Whatever is playing, from whichever player is active: Spotify, Poweramp, any app with a media session |
| Music · large | Large | The same, with large artwork and the title in Italiana |
| Agenda / Agenda · small | Large / small | Today's date, then the next two weeks from your calendars |
| Weather / Weather · large | Small / large | Open-Meteo: temperature, sky, the day's range; the coming hours on the large one |
| Month / Month · large | Small / large | The month, Monday first, today a filled disc, busy days dotted |
| Storage | Small | Share used as fine arcs that sweep as far as the share, GB free beneath |

The two default sizes are the two widgets in the 31 Oct recording of the cover screen: 500 × 430 px
and 672 × 706 px (190 × 164 dp and 256 × 269 dp at 2.625 px per dp). Every widget resizes freely and
is redrawn at its exact size, so type, artwork and controls scale with it. Each has four
arrangements chosen by shape: stack, poster, wide and, when very small, a tile.

## Finishes

Cristallo (clear glass), Vetro (frosted glass), Smoked glass, Membrane, Rosso lacquer, Avorio, Carbonio and Ceramica adapt to what is
behind each widget in a 4 × 4 grid of zones, as the taskbar does along its length. Visconti and
Paper are fixed. One default for all, and any widget can wear its own (hold it, then its settings).

**How a widget knows what is behind it.** Launchers never tell a widget where it sits. Every tap on a
widget carries the bounds of the area touched, and the widget knows where that area sits inside
itself, so the first tap seats it. With its seat and the wallpaper image, it reads the exact slice
of wallpaper behind it. Before the first tap it reads the wallpaper's overall colour, and Vetro is
clear glass rather than frosted.

**Why glass needs all-files access.** Since Android 13, apps can only read the wallpaper image with
that permission. Nothing else is read.

## The glass, against Bellezza Necessaria

- **Membrane:** the glass is the wallpaper thickened, not a pane laid over it. Cristallo bends in what
  lies just beyond its rim, as a curved edge bends light. Vetro takes the wallpaper's own colour.
- **One highlight,** at the upper left, warm, and following the time of day: cooler and brighter by
  day, warmer and dimmer at night. Redrawn on the hour.
- **Left out on purpose:** the colour fringe real glass shows at its rim (it reads as glitter), and a
  second lit corner.
- **Concentrato by size:** smaller widgets shed things in a fixed order until only the vital thing is
  left. **One control** on the music widget, play and pause; any other tap opens the player.

## Permissions

- **Notification access:** the only way Android lets an app see other apps' media sessions. No
  notification content is read.
- **Calendar:** the agenda.
- **All-files access:** the wallpaper, for the glass.
- **Location (optional):** "Use my location" for the weather.

## Building

GitHub Actions builds a debug APK on every push (Actions → the latest run → FoldWidgets-apk). One
fixed debug key, so each build installs over the last.

## Known risks, to check on the phone

- Samsung keeps a separate cover-screen wallpaper. If the glass on the cover shows the main
  screen's picture, choose the cover image under Glass in settings.
- Launchers that scroll the wallpaper across pages shift what is behind a widget. The seat assumes
  a centred, still wallpaper.
- Default sizes assume 2.625 px per dp on the cover screen.
