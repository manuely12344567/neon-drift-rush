# Journal des modifications

## 1.1 — 30 octobre 2026

**Correction du plantage au démarrage signalé sur téléphone** (le jeu s'ouvrait
puis se fermait tout seul après quelques secondes).

### Cause

`NeonRenderer.resize()` était appelée par la vue sur le thread UI
(`surfaceChanged`) et reconstruisait ses bitmaps en appelant `recycle()` sur les
anciens, pendant que le thread de rendu était en train de les dessiner. Android
relance une mise en page une à deux secondes après le lancement, quand le mode
immersif masque les barres système : ce `surfaceChanged` recyclait donc le ciel
sous les pieds du thread de rendu, ce qui lève
`RuntimeException: Canvas: trying to use a recycled bitmap` et tue le processus.

### Corrections

- La taille de rendu n'est plus appliquée par le thread UI : `resize()` ne fait
  qu'enregistrer une demande, le thread de rendu l'applique au début de sa frame
  suivante (`applyPendingSize()`). Plus aucun bitmap ne peut être recyclé pendant
  qu'une frame le dessine.
- Plus aucun `recycle()` sur les bitmaps en cache : l'ancien est simplement
  abandonné au ramasse-miettes.
- Même famille de bug corrigée au passage : `engine.reset()` était appelé depuis
  le thread UI à chaque appui sur JOUER / MENU, pendant que le thread de rendu
  parcourait la liste d'entités. Toutes les modifications d'état passent
  maintenant par une file de commandes consommée par le thread de rendu.
- La boucle de rendu ne peut plus tuer l'application : une exception est
  rattrapée, le rendu bascule une fois sur le canvas logiciel, et en dernier
  recours un écran de diagnostic affiche l'erreur (aussi écrite dans
  `last_error.txt`).
- Priorité du thread de rendu ramenée de `MAX_PRIORITY` à `NORM_PRIORITY + 1`
  pour ne pas affamer le thread UI sur les appareils modestes.
- Code API 30 (mode immersif) isolé dans une classe dédiée : plus de risque de
  `NoClassDefFoundError` à la vérification sur Android 5 à 10.
- Lecteur audio : une erreur du codec désactive la musique au lieu de remonter.

### Corrections d'affichage trouvées en vérifiant le rendu

- Le sol et la piste étaient dessinés semi-transparents : un `Paint` partagé
  gardait l'alpha d'un autre appel, laissant transparaître le soleil sous
  l'horizon (un arc magenta visible au point de fuite). Les Paints partagés sont
  réinitialisés à chaque frame.
- Le halo d'horizon ne déborde plus sous l'horizon.
- Le bitmap du ciel ne couvre plus que la bande du ciel : environ 7 Mo de tas
  économisés (et repli propre si l'allocation échoue).

### Tests

- `test/render/ResizeRegressionTest.java` (nouveau) reproduit le plantage :
  il vérifie qu'un `resize()` ne recycle aucun bitmap encore utilisable et que
  la nouvelle taille est bien appliquée à la frame suivante. Il échouait sur la
  1.0, il passe sur la 1.1.

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
