# whereAreYou

Application Android libre de partage de position entre proches. **Sans compte, sans serveur à nous, sans Google.**

- Créez un **cercle** (famille, amis, sortie…) et invitez des personnes par QR code ou par lien.
- Tous les membres d'un cercle se voient automatiquement sur une carte vectorielle nette (OpenStreetMap, via OpenFreeMap).
- Pour partager avec une seule personne : un cercle à deux.
- Les positions sont **chiffrées de bout en bout** (AES-256-GCM) et transitent par plusieurs **relais Nostr publics** en parallèle : si l'un disparaît, les autres prennent le relais. Vous pouvez ajouter les vôtres.

Les choix techniques, l'analyse des exigences et le protocole sont décrits dans [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Compiler

Prérequis : JDK 17+ et le SDK Android (API 35).

```sh
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

Tests :

```sh
./gradlew testDebugUnitTest                      # tests unitaires (crypto, invitations)
WAY_NETWORK_TEST=1 ./gradlew testDebugUnitTest   # + test réel via des relais publics
```

## Tester entre deux appareils

1. Installer l'APK sur les deux téléphones (`adb install app-debug.apk`, ou copier le fichier).
2. Sur chacun : onglet **Réglages** → saisir un nom.
3. Téléphone A : **Cercles** → **Nouveau cercle** → icône de partage → un QR code s'affiche.
4. Téléphone B : **Cercles** → **Rejoindre** → **Scanner un QR code**.
5. Sur les deux : onglet **Carte** → **Partager ma position** (accepter les autorisations).
6. Chacun voit l'autre apparaître sur la carte en quelques secondes (puis mise à jour selon la fréquence choisie).

## Compatibilité

Android 10 (API 29) et plus. Aucune dépendance aux services Google.

## Licence

[GPL-3.0-or-later](LICENSE). Carte : [OpenFreeMap](https://openfreemap.org), © [OpenMapTiles](https://www.openmaptiles.org), données © contributeurs [OpenStreetMap](https://www.openstreetmap.org/copyright).
