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
