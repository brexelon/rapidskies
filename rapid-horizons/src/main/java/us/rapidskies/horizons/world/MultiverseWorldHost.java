package us.rapidskies.horizons.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.config.CoreConfig;
import org.mvplugins.multiverse.core.utils.result.Attempt;
import org.mvplugins.multiverse.core.world.LoadedMultiverseWorld;
import org.mvplugins.multiverse.core.world.MultiverseWorld;
import org.mvplugins.multiverse.core.world.WorldManager;
import org.mvplugins.multiverse.core.world.generators.GeneratorPlugin;
import org.mvplugins.multiverse.core.world.generators.GeneratorProvider;
import org.mvplugins.multiverse.core.world.options.CreateWorldOptions;
import org.mvplugins.multiverse.core.world.options.ImportWorldOptions;
import org.mvplugins.multiverse.core.world.options.LoadWorldOptions;
import org.mvplugins.multiverse.core.world.options.RemoveWorldOptions;
import org.mvplugins.multiverse.core.world.reasons.CreateFailureReason;
import org.slf4j.Logger;
import us.rapidskies.horizons.frontier.Modifier;
import us.rapidskies.horizons.frontier.terrain.FrontierGenerator;

/**
 * The Horizons worlds through Multiverse-Core's API (5.x). Multiverse is optional at runtime
 * (softdepend): only this class touches its types, and it's only loaded when Multiverse is
 * enabled.
 *
 * <ul>
 *   <li><b>Generators.</b> rapid-horizons registers itself as a Multiverse generator plugin, so
 *       {@code /mv create <name> normal -g rapid-horizons:<modifier>} offers and builds every
 *       Frontier terrain (handy for previewing one on the build or staging server), and
 *       Multiverse records each world's generator in {@code worlds.yml}.</li>
 *   <li><b>Frontiers</b> are created and loaded through it with {@code auto-load: false}: the
 *       rotation decides which Frontier worlds are loaded, and when. A closed one is removed
 *       from Multiverse before its folder is archived. Frontier worlds from before Multiverse
 *       (a folder Multiverse doesn't know) are imported with their generator.</li>
 *   <li><b>The Heartland</b> is imported as it is (vanilla terrain, already pre-generated), so
 *       it's listed and managed with the rest.</li>
 * </ul>
 *
 * <p>Multiverse's own spawn, respawn, game mode and flight handling would fight rapid-core and
 * rapid-essentials, so the smp profile switches them off ({@code
 * infra/profiles/smp/plugins/Multiverse-Core/config.yml.patch}); startup warns about any that
 * are still on.
 */
public final class MultiverseWorldHost implements WorldHost {

    private final Logger log;
    private final MultiverseCoreApi api;
    private final String version;

    private MultiverseWorldHost(Logger log, MultiverseCoreApi api, String version) {
        this.log = log;
        this.api = api;
        this.version = version;
    }

    /** The host, with rapid-horizons registered as a generator plugin; null if Multiverse's API isn't up. */
    public static MultiverseWorldHost create(Logger log, String version) {
        if (!MultiverseCoreApi.isLoaded()) {
            return null;
        }
        MultiverseWorldHost host = new MultiverseWorldHost(log, MultiverseCoreApi.get(), version);
        host.registerGenerators();
        host.warnConflicts();
        return host;
    }

    private WorldManager worlds() {
        return api.getWorldManager();
    }

    // ------------------------------------------------------------------ WorldHost

    @Override
    public World load(Spec spec) {
        World loaded = Bukkit.getWorld(spec.key());
        MultiverseWorld known = find(spec.key());
        if (loaded != null) {
            if (known == null) {
                known = importWorld(spec);
            }
            configure(known, spec);
            return loaded;
        }
        LoadedMultiverseWorld world;
        if (known != null) {
            world = worlds().loadWorld(LoadWorldOptions.world(known)).getOrThrow(f -> failed("load", spec.key(), f));
        } else {
            CreateWorldOptions options = CreateWorldOptions.worldKey(spec.key())
                    .environment(spec.environment())
                    .seed(spec.seed())
                    .generateStructures(true)
                    .useSpawnAdjust(false);
            spec.generator().ifPresent(m -> options.generator(FrontierGenerator.generatorString(m)));
            Attempt<LoadedMultiverseWorld, CreateFailureReason> created = worlds().createWorld(options);
            if (created.isFailure() && created.getFailureReason() == CreateFailureReason.WORLD_EXIST_FOLDER) {
                // Created before Multiverse managed the Frontiers: take the folder as it is.
                log.info("{} exists but Multiverse doesn't know it; importing it", spec.key());
                world = importWorld(spec);
            } else {
                world = created.getOrThrow(f -> failed("create", spec.key(), f));
                log.info("Multiverse created {}{}", spec.key(),
                        spec.generator().map(m -> " with " + FrontierGenerator.generatorString(m)).orElse(""));
            }
        }
        configure(world, spec);
        World bukkit = world.getBukkitWorld().getOrNull();
        if (bukkit == null) {
            throw new IllegalStateException("Multiverse loaded " + spec.key() + " but Bukkit doesn't have it");
        }
        return bukkit;
    }

    private LoadedMultiverseWorld importWorld(Spec spec) {
        ImportWorldOptions options = ImportWorldOptions.worldKey(spec.key())
                .environment(spec.environment())
                .useSpawnAdjust(false);
        spec.generator().ifPresent(m -> options.generator(FrontierGenerator.generatorString(m)));
        return worlds().importWorld(options).getOrThrow(f -> failed("import", spec.key(), f));
    }

    /** Horizons loads these itself (auto-load off); the alias and weather follow the spec. */
    private void configure(MultiverseWorld world, Spec spec) {
        boolean changed = false;
        if (world.isAutoLoad()) {
            changed |= ok(world.setAutoLoad(false), world, "auto-load");
        }
        if (!spec.alias().equals(world.getAlias())) {
            changed |= ok(world.setAlias(spec.alias()), world, "alias");
        }
        if (world.isAllowWeather() != spec.allowWeather()) {
            changed |= ok(world.setAllowWeather(spec.allowWeather()), world, "allow-weather");
        }
        if (changed) {
            worlds().saveWorldsConfig().onFailure(e -> log.warn("Couldn't save Multiverse's worlds.yml: {}",
                    e.getMessage()));
        }
    }

    private boolean ok(org.mvplugins.multiverse.external.vavr.control.Try<Void> result, MultiverseWorld world,
                       String property) {
        if (result.isFailure()) {
            log.warn("Couldn't set Multiverse's {} on {}: {}", property, world.getName(),
                    result.getCause().getMessage());
            return false;
        }
        return true;
    }

    @Override
    public boolean unload(World world) {
        LoadedMultiverseWorld mv = worlds().getLoadedWorld(world).getOrNull();
        if (mv == null) {
            return Bukkit.unloadWorld(world, true);
        }
        NamespacedKey key = world.getKey();
        var removed = worlds().removeWorld(RemoveWorldOptions.world(mv).saveBukkitWorld(true).unloadBukkitWorld(true));
        if (removed.isFailure()) {
            log.error("Multiverse couldn't unload {}: {}", key, reason(removed));
            return false;
        }
        return Bukkit.getWorld(key) == null;
    }

    @Override
    public void adopt(World world, String alias) {
        MultiverseWorld known = worlds().getWorld(world).getOrNull();
        if (known == null) {
            var imported = worlds().importWorld(ImportWorldOptions.worldKey(world.getKey())
                    .environment(world.getEnvironment()).useSpawnAdjust(false));
            if (imported.isFailure()) {
                log.warn("Multiverse couldn't import {}: {}", world.getKey(), reason(imported));
                return;
            }
            known = imported.get();
            log.info("Multiverse now manages {} (terrain unchanged)", world.getKey());
        }
        if (known.getAlias() == null || known.getAlias().isBlank()) {
            if (ok(known.setAlias(alias), known, "alias")) {
                worlds().saveWorldsConfig();
            }
        }
    }

    @Override
    public String describe() {
        return "Multiverse-Core " + version;
    }

    @Override
    public void close() {
        GeneratorProvider generators = api.getGeneratorProvider();
        if (generators.isGeneratorPluginRegistered(FrontierGenerator.PLUGIN)) {
            generators.unregisterGeneratorPlugin(FrontierGenerator.PLUGIN);
        }
    }

    // ------------------------------------------------------------------ helpers

    private MultiverseWorld find(NamespacedKey key) {
        for (MultiverseWorld w : worlds().getWorlds()) {
            if (key.equals(w.getKey())) {
                return w;
            }
        }
        return null;
    }

    private static IllegalStateException failed(String action, NamespacedKey key, Attempt.Failure<?, ?> failure) {
        return new IllegalStateException("Multiverse couldn't " + action + " " + key + ": " + reason(failure));
    }

    private static String reason(Attempt<?, ?> attempt) {
        String text = String.valueOf(attempt.getFailureReason());
        try {
            text += " (" + attempt.getFailureMessage().formatted() + ")";
        } catch (RuntimeException e) {
            // The reason alone, then.
        }
        return text;
    }

    private void registerGenerators() {
        GeneratorProvider generators = api.getGeneratorProvider();
        if (generators.isGeneratorPluginRegistered(FrontierGenerator.PLUGIN)) {
            generators.unregisterGeneratorPlugin(FrontierGenerator.PLUGIN);
        }
        generators.registerGeneratorPlugin(new Generators());
    }

    /** Multiverse settings that would fight rapid-core and rapid-essentials, if left on. */
    private void warnConflicts() {
        CoreConfig c = api.getCoreConfig();
        List<String> on = new ArrayList<>();
        if (c.getEnforceGameMode()) {
            on.add("world.enforce-gamemode");
        }
        if (c.getEnforceFlight()) {
            on.add("world.enforce-flight");
        }
        if (c.getEnforceAccess()) {
            on.add("world.enforce-access");
        }
        if (c.getAutoImport3rdPartyWorlds()) {
            on.add("world.auto-import-3rd-party-worlds");
        }
        if (c.getFirstSpawnOverride()) {
            on.add("spawn.first-spawn-override");
        }
        if (c.getEnableJoinDestination()) {
            on.add("spawn.enable-join-destination");
        }
        if (c.getDefaultRespawnInOverworld()) {
            on.add("spawn.default-respawn-in-overworld");
        }
        if (c.getDefaultRespawnWithinSameWorld()) {
            on.add("spawn.default-respawn-within-same-world");
        }
        if (c.getEnforceRespawnAtWorldSpawn()) {
            on.add("spawn.enforce-respawn-at-world-spawn");
        }
        if (c.getTeleportIntercept()) {
            on.add("teleport.teleport-intercept");
        }
        if (!on.isEmpty()) {
            log.warn("Multiverse-Core settings that override rapid-core's spawn, respawn and world rules are on: {}. "
                    + "Apply the smp profile (infra/profiles/smp/plugins/Multiverse-Core) and restart.",
                    String.join(", ", on));
        }
        for (MultiverseWorld w : worlds().getWorlds()) {
            NamespacedKey key = w.getKey();
            if (key != null && key.getNamespace().equals("rapidskies") && !key.getKey().startsWith("frontier_")) {
                log.warn("Multiverse manages {} (rapid-core's world): its pvp and other world settings now override "
                        + "rapid-core's. Run `/mv remove {}` and restart.", key, w.getName());
            }
        }
    }

    /** What {@code /mv create ... -g} suggests for rapid-horizons. */
    private static final class Generators implements GeneratorPlugin {

        @Override
        public Collection<String> suggestIds(String currentIdInput) {
            String typed = currentIdInput == null ? "" : currentIdInput.toLowerCase(Locale.ROOT);
            return Arrays.stream(Modifier.values()).map(Modifier::id).filter(id -> id.startsWith(typed)).toList();
        }

        @Override
        public Collection<String> getExampleUsages() {
            return List.of("/mv create skies_preview normal -g " + FrontierGenerator.PLUGIN + ":"
                    + Modifier.SHATTERED_SKIES.id());
        }

        @Override
        public String getInfoLink() {
            return "https://rapidskies.us";
        }

        @Override
        public String getPluginName() {
            return FrontierGenerator.PLUGIN;
        }
    }
}
