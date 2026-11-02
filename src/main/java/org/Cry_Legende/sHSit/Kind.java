package org.Cry_Legende.sHSit;

/** Les six façons de se poser. id = nom de la commande et de la fonction, flag = flag de région WorldGuard. */
enum Kind {
    SIT("sit", "sit"),
    SPIN("spin", "pose"),
    LAY("lay", "pose"),
    BELLYFLOP("bellyflop", "pose"),
    CRAWL("crawl", "crawl"),
    PLAYERSIT("playersit", "playersit");

    final String id;
    final String flag;

    Kind(String id, String flag) {
        this.id = id;
        this.flag = flag;
    }

    /** Le joueur est assis sur un faux véhicule envoyé en paquets (aucune entité côté serveur). */
    boolean usesFakeSeat() {
        return this == SIT || this == SPIN;
    }

    boolean isCrawl() {
        return this == CRAWL || this == BELLYFLOP;
    }

    static Kind byId(String id) {
        for (Kind kind : values()) {
            if (kind.id.equalsIgnoreCase(id)) {
                return kind;
            }
        }
        return null;
    }
}
