# Journal des modifications

## 1.0 — 30 octobre 2026

Première version jouable.

### Gameplay
- Runner sans fin sur une autoroute néon à 3 voies, défilement pseudo-3D.
- Difficulté progressive : la vitesse monte de 26 à 56 unités/s, l'écart entre
  les rangées d'obstacles descend de 1,45 s à 0,62 s.
- Générateur d'obstacles garantissant toujours une voie libre **atteignable**
  depuis la voie libre précédente (jamais de configuration impossible).
- 3 types d'obstacles (barrière, portique d'énergie, pilier) + orbes d'énergie.
- Score = distance + orbes (25 pts × combo) + frôlements (12 pts × combo).
- Combo jusqu'à ×9, fenêtre de 2,6 s, avec indicateur qui pulse.
- Record persistant, compteur de parties et d'orbes collectés.

### Présentation
- Direction artistique synthwave : ciel dégradé, soleil rétro rayé, montagnes,
  skyline parallaxe avec fenêtres allumées, sol quadrillé, bords de piste néon.
- Vaisseau avec inclinaison dynamique, réacteurs animés, particules d'échappement.
- Explosions, secousses d'écran, flash, traînées de collecte, vignettage et
  scanlines CRT.
- HUD : score, record, combo, compteur d'orbes, jauge de vitesse en km/h.
- 4 écrans : menu animé (le monde continue de vivre derrière), jeu, pause,
  game over avec badge « nouveau record ».
- Interface et textes en français.

### Technique
- 100 % framework Android, aucune dépendance externe.
- Simulation `core/` sans import Android → testable sur JVM (27 tests).
- Rendu via Canvas matériel (`lockHardwareCanvas`) avec repli logiciel.
- Aucune allocation par frame (pool d'entités, particules en tableaux primitifs).
- Audio entièrement synthétisé (5 effets + boucle musicale de 12 s), généré par
  `tools/gen_audio.py`.
- Icônes adaptatives vectorielles + PNG de repli générés par `tools/gen_icons.py`.
- Compilation sans Gradle (`aapt2 → javac → d8 → zipalign → apksigner`).
- APK signé (v1+v2+v3), 724 Ko, minSdk 21, targetSdk 34.
