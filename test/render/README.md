# Harnais de captures d'écran (hors appareil)

Ce dossier permet d'exécuter **le vrai `NeonRenderer` du jeu** sur une JVM, sans
émulateur, grâce à [Robolectric](https://robolectric.org) en mode graphique
natif (`robolectric.graphicsMode=NATIVE`). C'est ce qui a produit les images du
dossier `screenshots/` : ce ne sont pas des maquettes, c'est le code qui tourne
dans l'APK.

Le harnais sert aussi de test de robustesse : 7 tailles d'écran (de 320×480 à
1440×3200), les 4 états d'écran, un monde vide et un monde de 200 entités, sans
qu'aucune exception ne soit levée.

## Utilisation

Prérequis : JDK 17, Maven, et un `android.jar` (platform 34).

```bash
cd test/render

# 1. récupérer la classpath Robolectric + JUnit
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt

ANDROID_JAR=$ANDROID_SDK_ROOT/platforms/android-34/android.jar
SRC=../../app/src/main/java/com/neondrift/rush

# 2. compiler le jeu + le harnais
javac -nowarn -encoding UTF-8 -cp "$ANDROID_JAR:$(cat cp.txt)" -d out \
  $SRC/core/*.java $SRC/Palette.java $SRC/NeonRenderer.java RenderHarness.java

# 3. exécuter : écrit les PNG dans ../../screenshots/
java -cp "out:$(cat cp.txt):$ANDROID_JAR" \
  -Drobolectric.graphicsMode=NATIVE \
  org.junit.runner.JUnitCore RenderHarness
```

Selon la configuration, Robolectric télécharge au premier lancement le jar
`android-all-instrumented` de l'API 33 (quelques dizaines de Mo, mis en cache
dans `~/.m2`).
