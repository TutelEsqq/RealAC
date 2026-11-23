package com.realac;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforgespi.language.IModInfo;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.IoSupplier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class AnticheatClient {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final long SCAN_CACHE_MILLIS = 300_000;
    private static final AtomicBoolean WATCHER_STARTED = new AtomicBoolean();

    private static long lastScanTime;
    private static List<String> lastIds = List.of();
    private static List<String> lastPkgs = List.of();
    private static String lastResult = "";

    private AnticheatClient() {
    }

    public static void handleHandshake(RealACMod.HandshakePayload payload, IPayloadContext ctx) {
        startPackWatcher();
        String hash = AnticheatCrypto.challengeHash(payload.nonce());
        // scan off the main thread to avoid frame stutter; packet replies are thread-safe
        Thread scanThread = new Thread(() -> {
            String violations = scanCached(payload.blacklistedModIds(), payload.blacklistedPackages())
                    + scanPacks();
            if (!violations.isEmpty()) {
                ctx.reply(new RealACMod.CheatReportPayload(violations));
            }
            String version = ModList.get().getModContainerById(RealACMod.MOD_ID)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("unknown");
            ctx.reply(new RealACMod.HandshakeResponsePayload(payload.nonce(), hash, version));
        }, "clientcheck-scan");
        scanThread.setDaemon(true);
        scanThread.setPriority(Thread.MIN_PRIORITY);
        scanThread.start();
    }

    // lightweight watcher: every 5s checks whether the selected pack set changed,
    // and only then runs the full x-ray analysis
    private static void startPackWatcher() {
        if (!WATCHER_STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread watcher = new Thread(() -> {
            List<String> lastPackIds = null;
            while (true) {
                try {
                    Thread.sleep(5000);
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft.level == null) {
                        continue; // not in a world right now
                    }
                    List<String> ids = minecraft.getResourcePackRepository().getSelectedPacks()
                            .stream().map(Pack::getId).toList();
                    if (lastPackIds == null) {
                        lastPackIds = ids; // initial snapshot, handshake scan already covered this state
                        continue;
                    }
                    if (!ids.equals(lastPackIds)) {
                        LOGGER.info("[ClientCheck] resource pack set changed, rescanning");
                        lastPackIds = ids;
                        String violations = scanPacks();
                        if (!violations.isEmpty()) {
                            PacketDistributor.sendToServer(new RealACMod.CheatReportPayload(violations));
                        }
                    }
                } catch (InterruptedException ignored) {
                    return;
                } catch (Exception e) {
                    LOGGER.warn("[ClientCheck] pack watcher error", e);
                }
            }
        }, "clientcheck-pack-watch");
        watcher.setDaemon(true);
        watcher.setPriority(Thread.MIN_PRIORITY);
        watcher.start();
    }

    private static synchronized String scanCached(List<String> ids, List<String> pkgs) {
        long now = System.currentTimeMillis();
        if (now - lastScanTime < SCAN_CACHE_MILLIS && ids.equals(lastIds) && pkgs.equals(lastPkgs)) {
            return lastResult;
        }
        lastResult = scan(ids, pkgs);
        lastIds = ids;
        lastPkgs = pkgs;
        lastScanTime = now;
        return lastResult;
    }

    private static String scan(List<String> ids, List<String> pkgs) {
        StringBuilder sb = new StringBuilder();

        Set<String> blacklistedIds = new HashSet<>();
        for (String id : ids) {
            blacklistedIds.add(id.toLowerCase(Locale.ROOT));
        }
        for (IModInfo mod : ModList.get().getMods()) {
            String id = mod.getModId().toLowerCase(Locale.ROOT);
            if (blacklistedIds.contains(id)) {
                sb.append("mod:").append(id).append(";");
            }
        }

        for (Path jar : jarCandidates()) {
            String match = findBlacklistedPackage(jar, pkgs);
            if (match != null) {
                sb.append("jar:").append(jar.getFileName()).append("(").append(match).append(");");
            }
        }
        return sb.toString();
    }

    // Opaque blocks that legit resource packs almost never turn transparent
    private static final Set<String> OPAQUE_BLOCKS = Set.of(
            "stone", "deepslate", "netherrack", "dirt", "gravel", "sand", "grass_block",
            "sandstone", "red_sandstone", "andesite", "diorite", "granite", "tuff", "calcite",
            "blackstone", "basalt", "end_stone", "obsidian", "clay", "snow_block", "soul_sand", "soul_soil");

    private static String scanPacks() {
        StringBuilder sb = new StringBuilder();
        for (Pack pack : Minecraft.getInstance().getResourcePackRepository().getSelectedPacks()) {
            String id = pack.getId();
            if ("vanilla".equals(id)) {
                continue;
            }
            LOGGER.info("[ClientCheck] scanning pack: {}", id);
            try (PackResources resources = pack.open()) {
                if (resources == null) {
                    LOGGER.info("[ClientCheck] pack {} could not be opened", id);
                    continue;
                }
                for (String block : OPAQUE_BLOCKS) {
                    String reason = checkBlockOverride(resources, block);
                    if (reason != null) {
                        LOGGER.info("[ClientCheck] pack {} flagged: {}", id, reason);
                        sb.append("pack:").append(id).append('(').append(reason).append(");");
                        break;
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[ClientCheck] failed to scan pack {}", id, e);
            }
        }
        return sb.toString();
    }

    private record ModelAnalysis(double volume, String flagReason) {
    }

    private static String checkBlockOverride(PackResources resources, String block) {
        // x-ray packs commonly redirect the blockstate to their own degenerate models
        ResourceLocation blockstateLoc = ResourceLocation.withDefaultNamespace("blockstates/" + block + ".json");
        IoSupplier<InputStream> blockstateSupplier = resources.getResource(PackType.CLIENT_RESOURCES, blockstateLoc);
        if (blockstateSupplier != null) {
            try (InputStream in = blockstateSupplier.get()) {
                JsonObject blockstate = JsonParser.parseString(
                        new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                String result = analyzeBlockstateModels(resources, blockstate, block);
                if (result != null) {
                    return result;
                }
            } catch (Exception ignored) {
            }
            // blockstate override alone proves nothing (legit packs do this too) — only analysis results flag
            return null;
        }

        ResourceLocation modelLoc = ResourceLocation.withDefaultNamespace("models/block/" + block + ".json");
        ModelAnalysis analysis = analyzeModel(resources, modelLoc, new HashMap<>(), 0);
        if (analysis != null) {
            if (analysis.flagReason() != null) {
                return block + " " + analysis.flagReason();
            }
            if (analysis.volume() < 4096.0 * 0.15) {
                return block + " degenerate model (volume " + (int) analysis.volume() + "/4096)";
            }
        }
        // direct texture override without a model override
        if (isTransparentTexture(resources, "block/" + block)) {
            return block + " transparent texture";
        }
        return null;
    }

    private static String analyzeBlockstateModels(PackResources resources, JsonObject blockstate, String block) {
        Set<String> modelRefs = new HashSet<>();
        var variants = blockstate.get("variants");
        if (variants != null && variants.isJsonObject()) {
            for (var entry : variants.getAsJsonObject().entrySet()) {
                collectModelRefs(entry.getValue(), modelRefs);
            }
        }
        for (String ref : modelRefs) {
            ResourceLocation loc = ResourceLocation.parse(ref);
            if (!loc.getNamespace().equals("minecraft")) {
                continue;
            }
            loc = loc.withPath(path -> "models/" + path + ".json");
            ModelAnalysis analysis = analyzeModel(resources, loc, new HashMap<>(), 0);
            if (analysis == null) {
                continue;
            }
            if (analysis.flagReason() != null) {
                return block + " " + analysis.flagReason();
            }
            if (analysis.volume() < 4096.0 * 0.15) {
                return block + " degenerate model (volume " + (int) analysis.volume() + "/4096)";
            }
        }
        return null;
    }

    private static void collectModelRefs(JsonElement element, Set<String> out) {
        if (element.isJsonObject()) {
            var model = element.getAsJsonObject().get("model");
            if (model != null && model.isJsonPrimitive()) {
                out.add(model.getAsString());
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                collectModelRefs(item, out);
            }
        }
    }

    private static ModelAnalysis analyzeModel(PackResources resources, ResourceLocation loc,
                                              Map<String, String> inheritedTextures, int depth) {
        if (depth > 8) {
            return null;
        }
        IoSupplier<InputStream> supplier = resources.getResource(PackType.CLIENT_RESOURCES, loc);
        if (supplier == null) {
            return null; // defined in vanilla or a lower pack — nothing suspicious in this pack
        }
        try (InputStream in = supplier.get()) {
            JsonObject json = JsonParser.parseString(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();

            if (json.has("render_type")) {
                String renderType = json.get("render_type").getAsString();
                if (!renderType.endsWith(":solid") && !"solid".equals(renderType)) {
                    return new ModelAnalysis(4096.0, "model render_type=" + renderType);
                }
            }

            Map<String, String> textures = new HashMap<>(inheritedTextures);
            if (json.has("textures")) {
                for (var entry : json.getAsJsonObject("textures").entrySet()) {
                    textures.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            for (String texture : textures.values()) {
                String resolved = texture;
                int guard = 0;
                while (resolved.startsWith("#") && guard++ < 8) {
                    resolved = textures.getOrDefault(resolved.substring(1), resolved);
                }
                if (!resolved.startsWith("#") && isTransparentTexture(resources, resolved)) {
                    return new ModelAnalysis(4096.0, "transparent texture " + resolved);
                }
            }

            if (json.has("elements")) {
                double volume = 0;
                for (JsonElement el : json.getAsJsonArray("elements")) {
                    JsonObject element = el.getAsJsonObject();
                    var from = element.getAsJsonArray("from");
                    var to = element.getAsJsonArray("to");
                    if (from == null || to == null) {
                        continue;
                    }
                    volume += Math.max(0, to.get(0).getAsDouble() - from.get(0).getAsDouble())
                            * Math.max(0, to.get(1).getAsDouble() - from.get(1).getAsDouble())
                            * Math.max(0, to.get(2).getAsDouble() - from.get(2).getAsDouble());
                }
                return new ModelAnalysis(volume, null);
            }

            if (json.has("parent")) {
                ResourceLocation parent = ResourceLocation.parse(json.get("parent").getAsString());
                if (!parent.getNamespace().equals("minecraft")) {
                    return new ModelAnalysis(4096.0, null);
                }
                ModelAnalysis parentAnalysis = analyzeModel(resources,
                        parent.withPath(path -> "models/" + path + ".json"), textures, depth + 1);
                return parentAnalysis != null ? parentAnalysis : new ModelAnalysis(4096.0, null);
            }
            return new ModelAnalysis(4096.0, null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isTransparentTexture(PackResources resources, String texture) {
        ResourceLocation loc = texture.contains(":")
                ? ResourceLocation.parse(texture)
                : ResourceLocation.withDefaultNamespace(texture);
        loc = loc.withPath(path -> "textures/" + path + ".png");
        IoSupplier<InputStream> supplier = resources.getResource(PackType.CLIENT_RESOURCES, loc);
        if (supplier == null) {
            return false;
        }
        try (InputStream in = supplier.get()) {
            BufferedImage image = ImageIO.read(in);
            if (image == null || !image.getColorModel().hasAlpha()) {
                return false;
            }
            int total = image.getWidth() * image.getHeight();
            if (total == 0) {
                return false;
            }
            int transparent = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) >>> 24) < 16) {
                        transparent++;
                    }
                }
            }
            return transparent > total * 0.75;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Set<Path> jarCandidates() {
        Set<Path> jars = new HashSet<>();
        try (Stream<Path> stream = Files.list(FMLPaths.MODSDIR.get())) {
            stream.filter(p -> p.toString().endsWith(".jar")).forEach(jars::add);
        } catch (IOException ignored) {
        }
        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(Pattern.quote(File.pathSeparator))) {
            if (entry.endsWith(".jar")) {
                jars.add(Path.of(entry));
            }
        }
        return jars;
    }

    private static String findBlacklistedPackage(Path jar, List<String> pkgs) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                String dotted = name.replace('/', '.');
                for (String pkg : pkgs) {
                    if (dotted.startsWith(pkg)) {
                        return pkg;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }
}
