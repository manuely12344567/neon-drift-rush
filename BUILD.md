# Construire et installer NEON DRIFT

Ce projet se compile **sans Gradle ni Android Studio**, directement avec les
outils en ligne de commande du SDK Android. Le script `build.sh` fait tout :
ressources, compilation Java, conversion en DEX, alignement, signature.

## Prérequis

| Outil | Version | Remarque |
|---|---|---|
| JDK | 17 (11 minimum) | `javac`, `keytool` |
| Android SDK Build-Tools | 34.0.0 | fournit `aapt2`, `d8`, `zipalign`, `apksigner` |
| Android SDK Platform | android-34 | fournit `android.jar` |

Installation des composants SDK si besoin :

```bash
sdkmanager --install "platforms;android-34" "build-tools;34.0.0"
```

Le script trouve le SDK tout seul via `ANDROID_SDK_ROOT`, `ANDROID_HOME` ou les
emplacements habituels (`~/Android/Sdk`, `~/android-sdk`, `/tmp/android-sdk`,
`/usr/local/lib/android/sdk`).

## Compilation

```bash
export ANDROID_SDK_ROOT=/chemin/vers/Android/Sdk
export JAVA_HOME=/chemin/vers/jdk-17

./build.sh            # produit dist/NeonDrift-1.0.apk
./build.sh --test     # lance d'abord les 27 tests de la simulation
```

Sortie typique :

```
== NEON DRIFT 1.0 ==
   sdk      : .../Android/Sdk
   tools    : .../build-tools/34.0.0
   platform : .../platforms/android-34/android.jar
-- aapt2 compile
-- aapt2 link
-- javac
-- d8
-- package
-- zipalign
-- apksigner
Signer #1 certificate DN: CN=NEON DRIFT, OU=Games, O=Neon Drift, L=Lome, C=TG
== OK ==
   dist/NeonDrift-1.0.apk  (724K)
```

Le pipeline exécuté par le script :

1. `aapt2 compile` — compile chaque ressource en `.flat`.
2. `aapt2 link` — lie les ressources, génère `R.java` et un APK non signé.
3. `javac` — compile les sources Java + `R.java` en bytecode Java 8.
4. `d8` — convertit en `classes.dex` (avec désucrage des lambdas).
5. `zip` — insère `classes.dex` dans l'APK.
6. `zipalign` — aligne les entrées non compressées sur 4 octets.
7. `apksigner` — signe en v1 + v2 + v3.

## Signature

La clé de signature se trouve dans `release/neondrift.keystore` (créée
automatiquement au premier build si elle est absente) :

| Champ | Valeur |
|---|---|
| Fichier | `release/neondrift.keystore` (JKS) |
| Alias | `neondrift` |
| Mot de passe (store et clé) | `neondrift` |
| Validité | 30 ans |

> Cette clé est volontairement simple et versionnée : elle permet de **mettre à
> jour** l'application sur un téléphone sans la désinstaller, et de reconstruire
> une version identique plus tard. Elle n'est évidemment pas destinée à une
> publication sur le Play Store, où il faut une clé privée gardée hors du dépôt.

Pour signer avec votre propre clé :

```bash
keytool -genkeypair -v -keystore ma-cle.jks -storetype JKS -alias monalias \
  -keyalg RSA -keysize 2048 -validity 10950

apksigner sign --ks ma-cle.jks --out dist/NeonDrift-perso.apk build/aligned.apk
```

## Installer sur le téléphone

### Méthode 1 — depuis le téléphone (la plus simple)

1. Copiez `dist/NeonDrift-1.0.apk` sur le téléphone (câble USB, Google Drive,
   WhatsApp, e-mail…).
2. Ouvrez le fichier depuis l'explorateur de fichiers.
3. Android demandera d'autoriser l'installation depuis cette source
   (« Installer des applications inconnues ») : acceptez, puis « Installer ».
4. Lancez **NEON DRIFT** depuis le tiroir d'applications.

### Méthode 2 — par ADB

```bash
adb install -r dist/NeonDrift-1.0.apk
adb shell am start -n com.neondrift.rush/.MainActivity
```

Si l'installation est refusée pour cause de signature différente d'une version
déjà présente :

```bash
adb uninstall com.neondrift.rush
adb install dist/NeonDrift-1.0.apk
```

## Vérifier l'APK

```bash
aapt2 dump badging dist/NeonDrift-1.0.apk | head -5
apksigner verify --print-certs dist/NeonDrift-1.0.apk
```

Attendu : `package: name='com.neondrift.rush' versionCode='1' versionName='1.0'`,
`sdkVersion:'21'`, `targetSdkVersion:'34'`, et une vérification de signature OK.

## Régénérer les ressources générées

```bash
python3 tools/gen_audio.py    # réécrit app/src/main/res/raw/*.wav
python3 tools/gen_icons.py    # réécrit les mipmap-*/ic_launcher*.png
```

Ces scripts n'ont besoin que de Python 3 (et de Pillow pour les icônes).
