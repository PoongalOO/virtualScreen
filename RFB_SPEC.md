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

## Messages client nécessaires

- SetPixelFormat ;
- SetEncodings ;
- FramebufferUpdateRequest ;
- KeyEvent ;
- PointerEvent.

## Encodages

### RAW — P0

Premier encodage. Valider : coordonnées, largeur, hauteur, bytes-per-pixel et taille calculée avant lecture/allocation.

### CopyRect — P1

Copie locale d'une région déjà présente dans le framebuffer.

### Hextile — P1

Décodage par tuiles de 16×16. Ajouter des tests unitaires couvrant toutes les combinaisons de sous-encodage prises en charge.

### ZRLE/Tight — P3

Non nécessaires tant que les mesures ne montrent pas que RAW/Hextile limitent l'usage réel.

## Pixel format

Format client préféré initial : true color 32 bits. Les conversions doivent être isolées dans une classe/fonction testable. Attention à l'endianness et aux shifts/max RGB.

## Robustesse

Avant toute multiplication de dimensions reçues du réseau, vérifier les bornes pour éviter overflow et allocations abusives. Aucun rectangle ne peut dépasser le framebuffer négocié.
