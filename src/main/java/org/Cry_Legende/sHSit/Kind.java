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
