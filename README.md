<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHSit

*S'asseoir sur un coussin de Minecraft 26.3, sans abus possible (remplace GSit).*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Le joueur s'assoit sur l'entité **coussin** introduite par Minecraft 26.3. Le plugin la pose sous lui, la retire dès qu'il se relève et ne fait rien d'autre : pas de pose couchée, pas de ramper, pas d'emotes.

- **Clic droit, les deux mains vides**, sur un escalier, une dalle ou un tapis (liste configurable, balises `#minecraft:...` acceptées). Seule la moitié basse des escaliers et des dalles sert de siège.
- **`/sit`** pour s'asseoir sur place, une seconde fois pour se relever.
- Se relever : accroupi (maj), ou en subissant des dégâts (réglable).
- Sur un escalier, le joueur est tourné vers le côté ouvert.

Utilisé sur : **Opale**. Nécessite Minecraft 26.3 : sur un serveur plus ancien le plugin se désactive de lui-même.

## Protections

| Abus | Réponse |
|---|---|
| Traverser un mur en s'asseyant puis en se relevant | Le clic doit viser le siège sans bloc au milieu (rayon depuis les yeux), le chemin du corps jusqu'au siège doit être libre, la portée est limitée. À la relève, le joueur est replacé sur un sol libre **visible depuis le siège**, jamais de l'autre côté d'un mur. |
| Spammer pour décaler la position | Délai entre deux assises, une seule place par joueur, une seule personne par bloc. |
| Casser ou frapper le coussin pour récupérer l'objet | Les événements de casse, de dégâts et de clic sur le coussin sont annulés ; si la foudre le détruit, l'objet lâché est supprimé. Le coussin n'est jamais sauvegardé. |
| S'asseoir sur l'île d'un autre | Membres de l'île et coop seulement (SuperiorSkyblock2), visiteurs réglables. |
| S'asseoir là où c'est interdit | Flag de région WorldGuard `sit` (le même que GSit, les régions existantes gardent leur réglage) ; sans flag il faut pouvoir construire dans la région. |
| S'asseoir sous un plafond bas, dans l'eau, en vol, en planeur, en vol créatif, au lit | Refusé. |
| Téléportation, mort ou déconnexion assis | Le coussin est retiré, le joueur n'est jamais ramené sur le siège. |
| Support cassé, déplacé ou remplacé | Le joueur est relevé tout de suite, sans objet lâché. |
| S'asseoir sur la tête d'un autre joueur | Supprimé (c'était la faille du parkour). |

## Commandes

| Commande | Description | Permission |
|---|---|---|
| `/sit` | S'asseoir sur place, ou se relever. | `shsit.command` |
| `/shsit reload` | Recharge la configuration. | `shsit.admin` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shsit.sit` | S'asseoir d'un clic droit sur un escalier, une dalle ou un tapis. | `true` |
| `shsit.command` | Utiliser `/sit` pour s'asseoir sur place. | `op` |
| `shsit.bypass` | S'asseoir malgré les protections d'île et de région. | `op` |
| `shsit.admin` | Recharger la configuration. | `op` |

## Configuration

Fichiers par défaut du plugin. Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHSit/
└── <a href="src/main/resources/config.yml">config.yml</a>
</pre>

`config.yml` contient les mondes autorisés, la couleur du coussin, le délai, la portée, la liste des blocs, les règles d'île et les messages. Les clés sont expliquées en commentaire. `settings.debug: true` écrit dans la console la raison de chaque refus.

Région WorldGuard :

```
/rg flag spawn sit deny
/rg flag mon_salon sit allow
```

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`. WorldGuard 7.0.20 et SuperiorSkyblock2 sont facultatifs.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
