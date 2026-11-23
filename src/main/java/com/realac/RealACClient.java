package com.realac;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class RealACClient {
    private RealACClient() {
    }

    public static void handleRequest(RealACMod.RequestPayload payload, IPayloadContext ctx) {
        String mode = payload.mode();
        ctx.enqueueWork(() -> {
            if ("classes".equals(mode)) {
                ctx.reply(new RealACMod.ResponsePayload(collectClasses()));
                return;
            }
            StringBuilder sb = new StringBuilder();

            String mods = ModList.get().getMods().stream()
                    .map(mod -> "binaries".equals(mode)
                            ? mod.getOwningFile().getFile().getFileName().toString()
                            : mod.getDisplayName() + " (" + mod.getModId() + " " + mod.getVersion() + ")")
                    .sorted()
                    .collect(Collectors.joining("\n  "));
            sb.append("§eMods (").append(ModList.get().getMods().size()).append("):§r\n  ").append(mods);

            var packs = Minecraft.getInstance().getResourcePackRepository().getSelectedPacks();
            String packsStr = packs.stream()
                    .map(pack -> "binaries".equals(mode)
                            ? pack.getId()
                            : pack.getTitle().getString() + " [" + pack.getId() + "]")
                    .collect(Collectors.joining("\n  "));
            sb.append("\n§eResource packs (").append(packs.size()).append("):§r\n  ").append(packsStr);

            ctx.reply(new RealACMod.ResponsePayload(sb.toString()));
        });
    }

    // package prefixes of the JRE, vanilla and common libraries — everything else is worth a look
    private static final List<String> STANDARD_PACKAGES = List.of(
            "java", "javax", "jdk", "sun", "com.sun", "com.mojang", "net.minecraft", "net.neoforged",
            "cpw.mods", "org.spongepowered", "com.google", "org.apache", "io.netty", "org.slf4j",
            "it.unimi", "org.lwjgl", "org.joml", "org.objectweb", "org.ow2", "net.minecraftforge",
            "org.jetbrains", "com.electronwill", "org.antlr", "org.yaml", "com.ibm", "com.microsoft",
            "joptsimple", "com.machinezoo", "net.jodah", "ca.weblite", "org.fusesource", "com.moulberry",
            "org.openjdk", "com.github", "org.jline", "org.fusesource.jansi", "net.java.dev.jna");

    private static String collectClasses() {
        StringBuilder sb = new StringBuilder();

        Set<String> packages = new TreeSet<>();
        ClassLoader loader = RealACClient.class.getClassLoader();
        while (loader != null) {
            for (Package p : loader.getDefinedPackages()) {
                packages.add(p.getName());
            }
            loader = loader.getParent();
        }
        List<String> foreign = packages.stream()
                .filter(RealACClient::isForeignPackage)
                .toList();
        sb.append("§eLoaded foreign packages (").append(foreign.size()).append("):§r\n  ")
                .append(String.join("\n  ", foreign));

        // jars present in the mods folder but not loaded as mods (hidden/uncooperative jars)
        try {
            Set<String> knownFiles = ModList.get().getMods().stream()
                    .map(m -> m.getOwningFile().getFile().getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet());
            List<String> unknownJars = new java.util.ArrayList<>();
            try (var stream = java.nio.file.Files.list(
                    net.neoforged.fml.loading.FMLPaths.MODSDIR.get())) {
                stream.filter(p -> p.toString().endsWith(".jar"))
                        .map(p -> p.getFileName().toString())
                        .filter(name -> !knownFiles.contains(name))
                        .sorted()
                        .forEach(unknownJars::add);
            }
            sb.append("\n§eJars in mods/ not loaded as mods (").append(unknownJars.size()).append("):§r\n  ");
            sb.append(unknownJars.isEmpty() ? "(none)" : String.join("\n  ", unknownJars));
        } catch (Exception e) {
            sb.append("\n§cFailed to check mods folder: ").append(e.getMessage());
        }

        String result = sb.toString();
        // FriendlyByteBuf utf limit is 32767 chars
        if (result.length() > 30000) {
            result = result.substring(0, 30000) + "\n§c... truncated";
        }
        return result;
    }

    private static boolean isForeignPackage(String name) {
        for (String prefix : STANDARD_PACKAGES) {
            if (name.equals(prefix) || name.startsWith(prefix + ".")) {
                return false;
            }
        }
        // packages provided by loaded mods are expected
        return true;
    }
}
