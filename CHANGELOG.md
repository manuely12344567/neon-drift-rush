# Journal des modifications

## 1.2 — 30 octobre 2026

**« Le vaisseau disparaît au bout de quelques secondes et je ne peux plus jouer. »**

### Cause

Une valeur non finie (`NaN`) pouvait entrer dans la position latérale du
vaisseau. Or `NaN` n'est attrapé par **aucun** des garde-fous existants : toutes
les comparaisons avec `NaN` sont fausses, donc

```java
if (playerX < -TRACK_LIMIT) playerX = -TRACK_LIMIT;   // jamais exécuté
if (playerX >  TRACK_LIMIT) playerX =  TRACK_LIMIT;   // jamais exécuté
```

laissaient `playerX` à `NaN` **définitivement**. Comme `Canvas` ignore
silencieusement les coordonnées `NaN`, le vaisseau n'était plus jamais dessiné,
et comme la consigne du doigt passait par la même valeur, le pilotage ne
répondait plus — alors que le décor, lui, continuait de défiler normalement.
D'où l'impression exacte : plus de vaisseau, plus de jeu, mais l'application
n'a pas planté.

Le `NaN` pouvait venir du doigt (`renderer.lateralAt()` sur une projection pas
encore configurée) ou d'une valeur corrompue par un redimensionnement.

### Corrections

- `Engine.sanitize()`, appelée à chaque frame : toute valeur devenue `NaN` ou
  infinie est réparée (`playerX`, `playerVX`, `targetX`, `speed`, `travel`,
  `scoreF`, minuteries), puis la position est ramenée sur la piste.
- `Engine.setTargetX()` refuse `NaN` et les infinis : la consigne revient à 0.
- `GameView.clampTarget()` : même garde côté doigt, avant d'atteindre le moteur.
- Les entités dont la distance ou la position ne sont pas finies sont retirées.
- `NeonRenderer.drawPlayer()` : une position non finie est remplacée par 0 au
  lieu d'être passée au `Canvas`.

### Filet de sécurité (le jeu ne peut plus rester bloqué)

- **Chien de garde** sur le thread UI : si aucune image n'a été postée depuis
  4 s, la boucle de rendu est relancée (3 tentatives), puis un écran de
  diagnostic lisible s'affiche au lieu d'une image figée.
- **Génération de boucle** : deux boucles de rendu ne peuvent plus tourner en
  même temps (c'était une cause possible d'image figée après un retour
  d'arrière-plan).
- Retour d'arrière-plan : la boucle est relancée si la surface est prête et que
  le rendu ne tourne pas.
- **Overlay de diagnostic** : 5 appuis rapides dans le coin haut-gauche affichent
  l'état complet (mode, `playerX`, `targetX`, vitesse, entités, taille de
  surface, images, FPS, redémarrages, thread, GPU). Le même état est écrit dans
  `last_state.txt` toutes les 15 s.

### Intégration continue

- **GitHub Actions** construit l'APK, lance les 40 tests de simulation et les
  5 tests de rendu, publie l'APK en artefact, et attache l'APK à la release
  quand un tag `v*` est poussé. Les captures d'écran sont aussi archivées.
- `build.sh --test` échoue désormais réellement quand un test échoue (le code de
  sortie était masqué par le `| tail`).

### Tests

- 13 assertions ajoutées sur l'entrée `NaN`/infinie et sur 60 s de pilotage
  hostile. **9 d'entre elles échouent sur la 1.1** (`vaisseau toujours fini`,
  `direction de nouveau fonctionnelle`, ...) et toutes passent en 1.2.
- Total : 40 tests de simulation + 5 tests de rendu.


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
