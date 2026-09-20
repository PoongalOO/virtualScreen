# Sous-ensemble RFB à implémenter

## Objectif

Implémenter le minimum RFB nécessaire à l'usage « deuxième écran », puis enrichir progressivement.

## Versions

Support initial : RFB 3.3 et 3.8 si possible. La version réellement utilisée est choisie après lecture de la bannière serveur.

### Négociation (SS-011)

Le serveur envoie 12 octets ASCII `RFB xxx.yyy\n` ; le client répond avec la plus haute version supportée ne dépassant pas celle du serveur :

| Version annoncée par le serveur | Réponse du client |
|---|---|
| ≥ 3.8 (dont 3.889 d'Apple, 4.x) | `RFB 003.008\n` |
| 3.3 à 3.7 | `RFB 003.003\n` (3.7 n'est pas implémenté) |
| < 3.3 | erreur `UnsupportedVersion`, connexion fermée |

Une bannière qui ne respecte pas strictement le format (préfixe, chiffres ASCII, séparateur, `\n`) produit `InvalidBanner` — cas typique : port qui n'est pas un serveur VNC. Le contenu reçu n'est jamais recopié dans les messages d'erreur.

### Négociation de sécurité (SS-012)

Le comportement diffère selon la version négociée (RFC 6143 §7.1.2) :

| | RFB 3.8 | RFB 3.3 |
|---|---|---|
| Types proposés | U8 `n` puis `n` U8 ; `n = 0` = refus + raison | un seul U32 imposé ; `0` = refus + raison |
| Choix du client | 1 octet (premier type de **sa** liste de préférence que le serveur propose) | aucun |
| `SecurityResult` (U32, 0 = OK, 1 = échec) | toujours, y compris pour `None` | sauf pour `None` |
| Raison en cas d'échec | oui (U32 longueur + texte) | non |

Types gérés : `None` (1) et VNC Authentication (2, voir ci-dessous), chacun un `SecurityHandler`. Les autres types (Tight, TLS, etc.) donnent `NoSupportedSecurityType` avec la liste proposée.

Le serveur est une entrée non fiable : le texte de raison est lu sur **256 octets au plus** quelle que soit la longueur annoncée (U32), puis assaini (ASCII imprimable, le reste devient `?`). Il est exposé dans `reason` des erreurs `ConnectionRejected` / `AuthenticationFailed`, jamais dans leur message. Un serveur qui coupe sans envoyer de raison donne une raison vide plutôt qu'une erreur réseau. Toute erreur ferme la socket.

`None` ne doit servir que sur un LAN de développement de confiance (voir SECURITY.md).

### VNC Authentication (SS-014)

Type 2. Le serveur envoie un challenge de 16 octets ; le client renvoie ce challenge chiffré en **DES-ECB** (deux blocs de 8 octets indépendants) avec pour clé le mot de passe **tronqué à 8 caractères, complété de zéros, chaque octet en miroir binaire** (bit 0 ↔ bit 7, particularité historique de VNC). Le `SecurityResult` est ensuite lu par la négociation de sécurité. DES vient de `javax.crypto` (aucune dépendance) ; sa disponibilité et son résultat ont été vérifiés sur la GT-P5110 (Android 4.2.2) contre OpenSSL.

- Le mot de passe est une suite de caractères Latin-1 (U+0000..U+00FF), 1 à 8 significatifs ; au-delà, le surplus est ignoré comme le font les serveurs. Un caractère hors Latin-1 dans les 8 premiers est refusé (`IllegalArgumentException`, sans citer le caractère).
- Mauvais mot de passe : `AuthenticationFailed` (avec la raison du serveur en 3.8, vide en 3.3).
- **Faiblesse connue** : DES, 8 caractères au plus, challenge/réponse attaquable hors ligne, et session **non chiffrée**. À réserver à un LAN de confiance ; hors LAN, passer par un VPN ou un tunnel (SECURITY.md). L'avertissement affiché à l'utilisateur relève de SS-072.

### ClientInit / ServerInit (SS-013)

`ClientInit` : 1 octet `shared-flag` (1 par défaut : les autres clients restent connectés). `ServerInit` : `U16` largeur, `U16` hauteur, `PIXEL_FORMAT` (16 octets), `U32` longueur du nom, nom du bureau.

Le serveur est une entrée non fiable ; tout est validé avant d'allouer ou de lire la suite, dans cet ordre :

| Contrôle | Limite | Erreur |
|---|---|---|
| Dimensions | 1 à 4096 par axe, **surface ≤ 1920×1200** (2 304 000 px) | `InvalidFramebufferSize` |
| `PIXEL_FORMAT` | 8/16/32 bpp, `depth` 1..bpp ; en couleurs vraies : maxima de la forme 2ⁿ−1, décalages qui tiennent dans le pixel, canaux sans chevauchement | `InvalidPixelFormat` |
| Nom du bureau | longueur annoncée ≤ 1024 octets | `InvalidDesktopName` |

Justification de la surface maximale : un buffer ARGB_8888 de 1920×1200 pèse 9,2 Mio ; avec le Bitmap de rendu, ~18 Mio sur un tas applicatif de 48 Mio mesuré sur la GT-P5110 (SS-003). Le bureau nominal 1280×800 n'en utilise que 44 %. Un serveur qui expose un écran plus grand (ex. 2560×1440) est refusé : réduire la résolution de l'écran virtuel côté PC.

Le nom est assaini (ASCII imprimable, le reste devient `?`). Contrairement aux textes de raison (256 octets lus, le reste ignoré car la connexion est fermée), un nom trop long est **refusé** et non tronqué : le flux continue après le nom, en lire moins désalignerait tous les messages suivants.

Le format de pixels du serveur est informatif : le client impose le sien avec `SetPixelFormat` (SS-021).

## Séquence

```text
TCP connect
→ ProtocolVersion
→ Security negotiation
→ SecurityResult
→ ClientInit
→ ServerInit
→ SetPixelFormat
→ SetEncodings
→ FramebufferUpdateRequest
↔ FramebufferUpdate
↔ PointerEvent / KeyEvent
```

## Messages serveur nécessaires

- FramebufferUpdate ;
- Bell : ignorer proprement ;
- ServerCutText : ignorer dans le MVP en consommant correctement le message.

Tout type inconnu doit produire une erreur protocolaire contrôlée plutôt qu'un désalignement silencieux du flux.

### Lecture des messages serveur (SS-023)

`ServerMessageReader.readMessage()` lit un message à la fois sur le thread I/O :

| Type | Message | Format | Traitement |
|---|---|---|---|
| 0 | `FramebufferUpdate` | `U8 0`, padding, `U16 n`, puis `n` rectangles : `U16 x, y, largeur, hauteur`, `S32 encodage`, données | chaque rectangle est décodé dans le framebuffer |
| 2 | `Bell` | `U8 2` | ignoré |
| 3 | `ServerCutText` | `U8 3`, 3 octets de padding, `U32 longueur`, texte | consommé et ignoré (presse-papiers non géré) |
| autre (dont 1, `SetColourMapEntries`) | — | — | `UnsupportedServerMessage`, connexion fermée |

Le serveur est une entrée non fiable :

- chaque rectangle est validé **avant** de lire ses données (dans le framebuffer, sinon `RectangleOutOfBounds`) ; un encodage sans décodeur donne `UnsupportedEncoding` (S32 signé : les pseudo-encodages sont négatifs) ;
- le texte de `ServerCutText` est sauté par blocs de 4 Kio sans être conservé, et refusé au-delà de **1 Mio** (`CutTextTooLong`) : sa longueur est un `U32` non fiable, mais il faut le consommer pour rester aligné ;
- le nombre de rectangles est un `U16` : il ne pilote aucune allocation.

**Erreurs et frontière de message** : pendant un message, toute erreur (EOF, timeout, protocole) ferme la socket, car le flux est désaligné. Un timeout **avant le premier octet** d'un message est en revanche récupérable (socket ouverte, l'appel peut être refait) ; un EOF à cet endroit est la fermeture normale par le serveur. Après une erreur au milieu d'un `FramebufferUpdate`, les rectangles déjà décodés restent appliqués et le contenu du rectangle en cours est indéfini : la reconnexion redemande un écran complet.

Un `RectangleListener` optionnel est notifié après chaque rectangle décodé (le rendu, SS-031, s'en servira pour retenir les zones modifiées).

## Messages client nécessaires

- SetPixelFormat ;
- SetEncodings ;
- FramebufferUpdateRequest ;
- KeyEvent ;
- PointerEvent.

## Encodages

### Annonce — `SetEncodings` (SS-022)

`SetEncodings` : `U8 type = 2`, 1 octet de padding, `U16 n`, puis `n` × `S32` (types d'encodage, par ordre de préférence ; les pseudo-encodages sont négatifs, d'où le S32). Message de `4 + 4n` octets, envoyé avec `SetPixelFormat`.

| Encodage | Numéro | Décodeur | Annoncé |
|---|---|---|---|
| RAW | 0 | SS-024 | **oui** |
| CopyRect | 1 | SS-025 | non (à ajouter avec son décodeur) |
| Hextile | 5 | SS-026 | non (à ajouter avec son décodeur) |

**Règle : un encodage n'est annoncé (`Encoding.ADVERTISED`) que lorsque son décodeur existe et est testé.** Annoncer un encodage non décodable ferait envoyer au serveur des rectangles que le client ne sait pas lire, ce qui coupe la connexion. Les encodages compacts (CopyRect, Hextile) se placent en tête de liste, RAW en dernier : un serveur choisit le premier qu'il sait produire. La liste ne peut être ni vide ni contenir de doublon, et est limitée à 64 entrées.

### RAW — P0

Premier encodage. Valider : coordonnées, largeur, hauteur, bytes-per-pixel et taille calculée avant lecture/allocation.

**Implémentation (SS-024)** : les données d'un rectangle sont `largeur × hauteur × bytes-per-pixel` octets, ligne par ligne, dans le format de pixels en vigueur (`SetPixelFormat`, SS-021).

- **Validation avant lecture** : un rectangle hors du framebuffer est refusé avant d'avoir lu le moindre octet de pixels. La taille des données est donc bornée par celle du framebuffer et ne peut pas déborder.
- **Ligne par ligne, sans buffer de rectangle** : chaque ligne est lue dans un petit buffer, convertie en ARGB puis copiée dans le framebuffer. Deux buffers de `maxWidth` pixels (≈ 32 Kio pour 1280 de large) sont alloués une fois : aucune allocation par rectangle ni par mise à jour, alors qu'un rectangle peut faire 9 Mio.
- **Deux chemins qui produisent les mêmes pixels** (un test l'impose) : un chemin rapide pour `XRGB_8888_LE` (`[B, G, R, X]` → `0xFF000000 | 0x00RRGGBB`), et un chemin générique via `PixelFormat.decodePixel` pour tout autre format à couleurs vraies.
- Un rectangle vide (largeur ou hauteur nulle) ne lit rien.

### CopyRect — P1

Copie locale d'une région déjà présente dans le framebuffer.

### Hextile — P1

Décodage par tuiles de 16×16. Ajouter des tests unitaires couvrant toutes les combinaisons de sous-encodage prises en charge.

### ZRLE/Tight — P3

Non nécessaires tant que les mesures ne montrent pas que RAW/Hextile limitent l'usage réel.

## Pixel format

### Format imposé par le client — `SetPixelFormat` (SS-021)

Le client impose au serveur le format **XRGB 8888 little-endian** (`PixelFormat.XRGB_8888_LE`) juste après `ServerInit` et avant la première `FramebufferUpdateRequest` :

| Champ | Valeur |
|---|---|
| bits-per-pixel / depth | 32 / 24 |
| big-endian-flag / true-colour-flag | 0 (little-endian) / 1 (couleurs vraies) |
| red-max = green-max = blue-max | 255 |
| red-shift / green-shift / blue-shift | 16 / 8 / 0 |

Sur le fil, un pixel occupe 4 octets dans l'ordre **`[bleu, vert, rouge, inutilisé]`**. Lu comme un U32 little-endian il vaut `0x00RRGGBB` ; le framebuffer stocke `0xFF000000 | pixel` (alpha toujours opaque, l'octet inutilisé est ignoré). Le message complet fait 20 octets :

```text
00 000000  20 18 00 01  00ff 00ff 00ff  10 08 00  000000
type  pad  bpp dep be tc  rmax gmax bmax  rs gs bs  padding
```

Pourquoi ce format : little-endian est l'ordre natif de l'ARM de la GT-P5110 (un pixel s'assemble en `Int` sans inversion d'octets), et c'est le format natif 24 bits de x11vnc/TigerVNC, donc le serveur n'a en général aucune conversion à faire.

`PixelFormat.decodePixel` est la conversion **de référence** vers ARGB (générique : 8/16/32 bpp, les deux endianness, composantes ramenées sur 8 bits avec arrondi). Le décodeur RAW (SS-024) aura un chemin rapide pour ce format et devra produire exactement les mêmes valeurs ; un test l'impose. Un format à palette n'est jamais demandé et n'est pas converti.

Un format invalide n'est jamais envoyé (`IllegalArgumentException` avant toute écriture).

Le format annoncé dans `ServerInit` est purement informatif : c'est celui-ci qui fait foi une fois `SetPixelFormat` envoyé.

## Robustesse

Avant toute multiplication de dimensions reçues du réseau, vérifier les bornes pour éviter overflow et allocations abusives. Aucun rectangle ne peut dépasser le framebuffer négocié.
