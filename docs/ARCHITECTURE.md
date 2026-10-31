# whereAreYou — Analyse des exigences et architecture

## 1. Compatibilité entre les exigences

| # | Exigence | Statut | Commentaire |
|---|----------|--------|-------------|
| 1 | Géolocalisation entre utilisateurs | ✅ | `LocationManager` natif d'Android (GPS, réseau, « fused » AOSP sur Android 12+). |
| 2 | Partager sa position / voir ceux qui partagent avec moi | ✅ | Modélisé comme un **cercle à deux**. Le partage est asymétrique : interrupteur « Partager ma position » par cercle. |
| 3 | Groupes : on voit automatiquement tous les membres | ✅ | Un cercle = un secret partagé. Tous ceux qui ont le secret voient tous les autres, sans liste de membres à gérer. |
| 4 | 100 % open source, services open source ou publics | ✅ | Aucun Google Play Services / Firebase / Google Maps. Carte MapLibre + OpenFreeMap (données OpenStreetMap), relais Nostr publics. |
| 5 | Pas de serveur, P2P sécurisé, sinon décentralisé et résilient | ⚠️ → ✅ | **Le P2P pur n'est pas réaliste sur mobile** (voir §2). Solution retenue : **relais Nostr publics**, plusieurs en parallèle, chiffrement de bout en bout. Aucun serveur à nous. |
| 6 | GitHub + F-Droid | ✅ | Licence GPL-3.0+, dépendances 100 % libres, pas de binaire précompilé, `dependenciesInfo` désactivé, métadonnées fastlane fournies. |
| 7 | Natif Android, briques de design natives | ✅ | Kotlin + Jetpack Compose + Material 3 (couleurs dynamiques « Material You » sur Android 12+). |
| 8 | Android 10+ | ✅ | `minSdk = 29`. Les règles de localisation en arrière-plan d'Android 10+ sont respectées via un service de premier plan de type `location`. |

### Tensions entre exigences

- **5 ↔ 1/2/3 (pas de serveur ↔ voir la position des autres à distance)** : c'est la seule vraie tension. Voir §2.
- **4/6 ↔ fiabilité de la localisation** : sans Google Play Services, pas de `FusedLocationProviderClient` ni de push Firebase. On utilise l'API Android de base (un peu moins économe en batterie) et une connexion WebSocket maintenue par un service de premier plan (une notification permanente est visible pendant le partage, c'est obligatoire et c'est aussi plus honnête pour l'utilisateur).
- **6 ↔ cartes** : Google Maps est interdit sur F-Droid → carte vectorielle **MapLibre** (BSD) avec les styles et tuiles libres **OpenFreeMap** (données OpenStreetMap, sans clé ni compte). Le rendu vectoriel reste net sur les écrans haute densité, contrairement aux tuiles image 256 px d'OpenStreetMap (version 0.1, osmdroid) qui devaient être agrandies. Évolution possible : laisser l'utilisateur choisir sa source de carte.

## 2. Pourquoi pas du P2P pur ?

| Option | Problème |
|--------|----------|
| Connexion directe (WebRTC, TCP/UDP) | Les mobiles sont derrière du NAT opérateur (CGNAT), sans adresse fixe. Il faut des serveurs STUN/TURN et de signalisation → c'est un serveur. |
| Tor / services onion (modèle Briar) | Réellement sans serveur, mais les deux appareils doivent être en ligne **en même temps**, lourd pour la batterie, binaire Tor natif, et N² connexions pour un groupe. Pas de « dernière position connue » si l'autre est hors ligne. |
| libp2p / IPFS pubsub | Nécessite quand même des nœuds d'amorçage et des relais ; peu fiable sur mobile (mise en veille, changement de réseau). |
| Bluetooth / Wi-Fi Direct / mesh | Portée de quelques dizaines de mètres : inutile pour savoir où sont ses proches. |
| Matrix | Fédéré (serveurs), protocole lourd, nécessite un compte. |
| **Nostr (retenu)** | Protocole ouvert et simple : des centaines de **relais publics** indépendants, logiciels libres, sans compte. Les messages sont signés et l'app les envoie à **plusieurs relais à la fois**. Si un relais disparaît, les autres prennent le relais ; chacun peut ajouter le sien (y compris auto-hébergé). Les relais ne voient que des données chiffrées. |

Nostr apporte aussi le **stockage asynchrone** : la dernière position reste disponible sur les relais même si l'émetteur est hors ligne (pendant 24 h maximum, voir NIP-40).

Une évolution possible : ajouter en plus un canal local (Bluetooth LE) pour les cas sans réseau. L'architecture (`RelayPool` = un transport) le permet.

## 3. Protocole

### Cercle
Un cercle est un **secret aléatoire de 32 octets** + un nom. Il est transmis par QR code ou par lien `whereareyou://join/<base64url(json)>`.

À partir du secret (HKDF-SHA256, sel `whereareyou-v1`) on dérive :
- `tag` (16 octets, hex) : identifiant public du cercle, utilisé comme tag `d` Nostr. Il ne révèle ni le nom ni les membres.
- `key` (32 octets) : clé AES-256-GCM.

### Identité
Chaque appareil génère une graine aléatoire de 32 octets qui ne quitte jamais l'appareil (exclue des sauvegardes). Pour **chaque cercle**, une clé de signature Nostr différente est dérivée (`HKDF(graine, "author:" + tag)`) : un relais ne peut pas relier vos différents cercles entre eux.

Une **clé d'identité** personnelle est aussi dérivée de la graine (`HKDF(graine, "member-identity")`). Sa clé publique sert d'**identifiant de membre** ; elle n'apparaît que dans le contenu chiffré, donc seuls les membres des cercles la voient.

### Publication d'une position
Événement Nostr, kind **30078** (NIP-78, « données d'application », remplaçable) :

```json
{
  "kind": 30078,
  "pubkey": "<clé dérivée pour ce cercle>",
  "tags": [["d", "<tag du cercle>"], ["expiration", "<maintenant + 24 h>"]],
  "content": "base64( 0x01 || nonce(12) || AES-256-GCM(json, aad = tag) )"
}
```

Contenu chiffré : une enveloppe **signée par le membre** avec sa clé d'identité (Schnorr BIP-340) :
```json
{"v":2, "p":"<JSON interne>", "s":"<signature>"}
```
avec `signature = Schnorr(clé d'identité, taggedHash("whereareyou/v2/message", tag || JSON interne))` et le JSON interne :
```json
{"k":"<clé publique d'identité>", "n":"Prénom", "lat":48.8566, "lon":2.3522, "acc":12, "ts":1730000000000, "gap":1800000}
```
`{"k":"...", "off":true, "ts":...}` signale l'arrêt du partage (le marqueur disparaît chez les autres).

Les relais ne gardent que le **dernier** événement par (auteur, `d`) : c'est exactement la « dernière position connue ».

### Réception
Abonnement `{"kinds":[30078], "#d":[tags de mes cercles], "since": maintenant - 24 h}` sur tous les relais. Chaque événement est déchiffré avec la clé du cercle ; s'il ne se déchiffre pas, il est ignoré. Ensuite l'enveloppe est vérifiée : le message est rejeté si la signature ne correspond pas à la clé `k` annoncée (un membre ne peut pas se faire passer pour un autre), si `ts` a plus de 24 h, ou s'il est plus ancien que le dernier message accepté de ce membre dans ce cercle (pas de rejeu).

### Ce que voit un relais
- des clés publiques aléatoires (une par membre et par cercle), un tag aléatoire, une date, un contenu chiffré ;
- l'adresse IP de l'appareil (comme tout service internet ; Orbot/Tor ou un VPN peuvent la masquer).

### Limites connues (v0.1)
- Tout porteur du lien d'invitation peut rejoindre le cercle : il faut le transmettre par un canal de confiance (idéalement le QR code en personne).
- On ne peut pas exclure quelqu'un d'un cercle : il faut en recréer un.
- Les secrets sont stockés dans les préférences privées de l'app (non sauvegardées). Amélioration prévue : chiffrement via Android Keystore.
- Le partage s'arrête si le système tue l'app (pas de redémarrage automatique : nécessiterait l'autorisation « localisation en arrière-plan »).

### Consommation d'énergie
Le GPS et la radio sont les deux postes coûteux. Le service adapte son fonctionnement (`MotionPolicy`, testée unitairement) selon deux réglages :

| Mode | Réglage (défaut) | Sources de position | Envois |
|---|---|---|---|
| **En mouvement** | 30 s → 3 h (5 min) | GPS précis (fournisseur « fused » si disponible, sinon GPS + réseau), échantillonné à la moitié du réglage, borné entre 15 s et 2 min | au rythme réglé, **seulement si on a bougé** (> 25 m ou au-delà de la précision) ; au **départ** après un arrêt, la première position à plus de 100 m est envoyée tout de suite |
| **Immobile** (resté dans un rayon de 50 m pendant 3 min) | 5 min → 6 h (30 min) | **GPS coupé** ; attente du **capteur matériel de mouvement significatif** (quasi gratuit, sans services Google) ; positions Wi-Fi/antennes en secours toutes les 15 min (5 min sans capteur) + positions gratuites des autres apps | la position est seulement **confirmée** au rythme réglé (même position, horodatée « maintenant ») |

Règles de cohérence entre les deux réglages :
- la confirmation à l'arrêt n'est jamais plus fréquente que les envois en mouvement (les deux curseurs se poussent l'un l'autre) ;
- l'échantillonnage GPS est plafonné à 2 min même si l'envoi est réglé à 3 h, sinon l'app ne remarquerait jamais l'arrêt et garderait le GPS allumé ;
- chaque position annonce le **silence maximal** prévu avant la suivante (champ `gap`, = réglage « immobile ») : chez les autres, le marqueur ne s'estompe qu'en cas de retard sur cette annonce (+ 50 % et 2 min), et non au bout d'une durée fixe.

Côté réseau, le signal de maintien des connexions WebSocket passe de 30 s à 2 min, pour réveiller moins souvent la radio.

Évolution prévue : un « mode direct » à la demande (mises à jour rapides seulement quand quelqu'un regarde la carte).

## 4. Organisation du code

```
app/src/main/java/io/github/whereareyou/
├── WhereApp.kt              Application : init. stockage, dépôt, MapLibre, canal de notification
├── crypto/
│   ├── Crypto.kt            SHA-256, HMAC, HKDF, AES-GCM (API Java standard)
│   └── Secp256k1.kt         secp256k1 + signatures Schnorr BIP-340 en Kotlin pur
├── nostr/
│   ├── Nostr.kt             Construction/signature d'événements (NIP-01)
│   └── RelayPool.kt         WebSockets vers N relais, reconnexion automatique, renvoi des messages
├── data/
│   ├── Models.kt            Cercle, position, membre, invitation
│   ├── Store.kt             Persistance locale
│   └── LocationRepository.kt Logique centrale : publier, recevoir, déchiffrer, fusionner
├── service/
│   └── LocationService.kt   Service de premier plan (type location)
└── ui/                      Compose + Material 3 : carte, cercles, réglages
```

### Dépendances (toutes libres)

| Bibliothèque | Licence | Rôle |
|---|---|---|
| AndroidX / Jetpack Compose / Material 3 | Apache 2.0 | UI native |
| OkHttp | Apache 2.0 | WebSocket |
| MapLibre Android (+ turf) | BSD-2-Clause | Carte vectorielle (styles OpenFreeMap, données OpenStreetMap) |
| ZXing core + zxing-android-embedded | Apache 2.0 | QR codes |
| kotlinx.coroutines | Apache 2.0 | Concurrence |

La cryptographie n'ajoute aucune dépendance : AES/HMAC viennent de la plateforme, secp256k1 est implémenté dans le projet (testé avec les vecteurs officiels BIP-340).
