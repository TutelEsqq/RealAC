package com.realac;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VanishManager {
    private static final Set<UUID> VANISHED = ConcurrentHashMap.newKeySet();

    private VanishManager() {
    }

    public static boolean isVanished(UUID id) {
        return VANISHED.contains(id);
    }

    public static Set<UUID> getVanished() {
        return Set.copyOf(VANISHED);
    }

    public static void setVanished(UUID id, boolean vanished) {
        if (vanished) {
            VANISHED.add(id);
        } else {
            VANISHED.remove(id);
        }
    }
}
