<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHSit

*S'asseoir, s'allonger, ramper, tournoyer et s'asseoir sur la tête des autres, sans entité ni abus (remplace GSit).*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Rien n'est posé dans le monde : le siège est un **faux véhicule invisible envoyé en paquets** (packetevents) aux seuls joueurs qui voient la scène, les fesses exactement au niveau du bloc. Pas d'entité serveur, pas d'événement de monture, donc rien à compter, à nettoyer ou à voler, et les flags WorldGuard `ride` et `interact` ne gênent pas.

| Fonction | Comment | Permission |
|---|---|---|
| **Assis** | clic droit les deux mains vides sur un escalier, une dalle ou un tapis (liste configurable), ou `/sit` | clic : `shsit.sit` (tous), `/sit` : `shsit.use.sit` |
| **Couché** | `/lay` | `shsit.use.lay` |
| **Spin** | `/spin` (pose de trident) | `shsit.use.spin` |
| **Ventre** | `/bellyflop` (à plat ventre, immobile) | `shsit.use.bellyflop` |
| **Ramper** | `/crawl` | `shsit.use.crawl` |
| **Sur la tête d'un joueur** | clic droit les deux mains vides sur lui, `/sit playertoggle` pour refuser | `shsit.playersit` (tous) |

Se relever : accroupi (maj) ou retaper la commande. Un tapis posé sur une dalle haute (banc) se vise aussi en cliquant la dalle qui le porte. Sur un escalier, le joueur est tourné vers le côté ouvert.

Utilisé sur : **Opale**. Nécessite **packetevents** et Minecraft 26.3.

## Comment c'est fait

- **Assis, spin** : un `item_display` fantôme (sans modèle) est envoyé avec le joueur comme passager. Le serveur place le joueur 0,6 bloc sous la surface du siège, ce qui met les fesses au niveau du bloc, et ne le voit plus bouger.
- **Couché** : les spectateurs reçoivent la pose de sommeil du joueur, avec un lit fantôme enterré en bas du monde qui donne le sens du corps (la pose part avant le lit, sinon le client poserait le joueur sur ce lit). Le joueur lui-même reçoit une copie couchée de lui (même skin, même équipement, hors de la liste des joueurs) et sa caméra s'y installe comme celle d'un spectateur : vue du lit en première personne, tout son corps allongé en F5. Tant que la caméra n'est pas lui, son client ne dessine plus son propre joueur et n'envoie plus sa position, le serveur le garde donc exactement où il est posé. Une barre d'action rappelle de s'accroupir pour se relever.
- **Ramper, bellyflop** : vraie nage côté serveur, plus un plafond fantôme (barrière ou dalle haute) visible du joueur seul qui force le client à se baisser. Le bellyflop gèle en plus la vitesse.
- **Sur la tête** : vraie monture sur le joueur porteur, pour que WorldGuard (`ride`) et le parkour la voient. Jusqu'à trois joueurs empilés (réglable).
- Les spectateurs sont recalculés en continu (arrivée dans la zone, départ, invisibilité).

## Protections

| Abus | Réponse |
|---|---|
| Traverser un mur en s'asseyant puis en se relevant | Le clic doit viser le siège sans bloc au milieu (rayon depuis les yeux), le chemin du corps jusqu'au siège doit être libre, la portée est limitée. À la relève, le joueur est replacé sur un sol libre **visible depuis le siège**, jamais de l'autre côté d'un mur : son point de départ, sinon le sol sous ce point (clic en sautant), sinon le sol réel le plus proche autour du siège (tapis, dalle, escalier), et faute de mieux le dessus du siège lui-même. Il n'est jamais laissé dans le bloc du siège. |
| Serveur arrêté brutalement pendant une pose | Le point où relever le joueur est noté sur lui pendant la pose : à sa reconnexion, il est relevé au lieu de traverser le sol. |
| Bouger alors qu'on est posé (client modifié) | Le serveur ne voit plus bouger un joueur posé : une dérive est recalée, un vrai déplacement met fin à la pose. Le bellyflop ramène celui qui avance. |
| Spammer pour décaler la position | Délai entre deux poses, une pose par joueur, une personne par bloc. |
| Se faire porter hors d'une zone | Le joueur assis sur une tête est descendu dès que la région ou l'île où il arrive l'interdit, et quand le porteur s'accroupit. |
| S'asseoir sur l'île d'un autre | Membres de l'île et coop seulement (SuperiorSkyblock2), visiteurs réglables. L'île du spawn est publique. |
| Se poser là où c'est interdit | Un flag WorldGuard par fonction (voir plus bas). |
| Téléportation, mort ou déconnexion posé | La pose s'arrête avant, le joueur n'est jamais ramené sur le siège. |
| Support cassé, déplacé ou remplacé | Le joueur est relevé tout de suite. |
| Se poser sous un plafond bas, dans l'eau, en vol, en planeur, au lit | Refusé. |

## WorldGuard

Un flag d'état (`allow` / `deny`) par fonction, les mêmes noms que GSit, donc les régions existantes gardent leur réglage :

| Flag | Fonction |
|---|---|
| `sit` | s'asseoir (clic et `/sit`) |
| `pose` | `/lay`, `/bellyflop`, `/spin` |
| `crawl` | `/crawl` |
| `playersit` | s'asseoir sur un joueur |

```
/rg flag spawn sit deny
/rg flag mon_salon sit allow
/rg flag -w spawn __global__ sit allow
```

Seul un flag réellement posé sur une région (ou sur la région `__global__` du monde) décide, avec la priorité WorldGuard habituelle (à égalité, `deny` l'emporte). Le flag de `__global__` ne compte que là où aucune région ne pose le sien : sur Opale, `sit` et `pose` sont en `allow` sur `__global__` du monde `spawn` (bancs et poses partout au spawn), et les régions `spawn-nosit` et `minikoth` les gardent en `deny`. Sans flag, il faut pouvoir construire dans la région ; le contournement WorldGuard compte, mais pas contre un `deny` explicite (seul `shsit.bypass` passe outre).

Les poses ne créent aucune monture : les flags `ride` et `interact` n'ont donc aucun effet sur elles. Un flag `allow` rend même possible de s'asseoir dans une région protégée par `interact: deny` (le clic annulé par WorldGuard est traité quand la région dit `allow`).

## Commandes

| Commande | Description | Permission |
|---|---|---|
| `/sit`, `/lay`, `/spin`, `/bellyflop`, `/crawl` | Prendre la pose à l'endroit où l'on se tient, ou se relever en la retapant. | `shsit.use.<nom>` |
| `/sit playertoggle` | Interdire ou autoriser qu'on s'asseye sur soi. | aucune |
| `/shsit reload` | Recharge `config.yml` et `messages.yml` ; si un fichier est illisible, l'ancienne configuration reste active. | `shsit.admin.reload` |
| `/shsit list` | Liste les joueurs posés, avec leur pose et leur monde. | `shsit.admin.list` |
| `/shsit stand <joueur>` | Relève un joueur posé. | `shsit.admin.stand` |

Les permissions `shsit.use.*` sont `op` par défaut ; la commande vérifie elle-même la permission, y compris sous la forme `/shsit:sit`. Les sous-commandes de `/shsit` fonctionnent aussi depuis la console.

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shsit.sit` | S'asseoir d'un clic droit sur un bloc. | `true` |
| `shsit.playersit` | S'asseoir d'un clic droit sur un joueur. | `true` |
| `shsit.use` | Les cinq commandes de pose (parent de `shsit.use.<nom>`). | `op` |
| `shsit.use.sit` `.lay` `.spin` `.bellyflop` `.crawl` | Utiliser la commande. | `op` |
| `shsit.bypass` | Se poser malgré les protections d'île et de région. | `op` |
| `shsit.admin` | Toutes les sous-commandes de `/shsit` (parent de `shsit.admin.<sous-commande>`). | `op` |
| `shsit.admin.reload` `.list` `.stand` | Une sous-commande de `/shsit`. | `op` |

## Configuration

Fichiers par défaut du plugin. Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHSit/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

`config.yml` contient les mondes autorisés, le délai, la portée, les fonctions actives, l'empilement des joueurs, la liste des blocs et les règles d'île ; `messages.yml` tous les textes. Les clés sont expliquées en commentaire. `settings.debug: true` écrit dans la console la raison de chaque refus et la décision de WorldGuard.

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`. packetevents est requis ; WorldGuard 7.0.20 et SuperiorSkyblock2 sont facultatifs.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
