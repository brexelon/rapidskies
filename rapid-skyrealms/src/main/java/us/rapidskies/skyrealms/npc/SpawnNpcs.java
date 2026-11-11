package us.rapidskies.skyrealms.npc;

/**
 * The NPCs round the Trading Post's compass rose. Kept behind an interface so Citizens' classes
 * are only loaded when Citizens is installed ({@link CitizensSpawnNpcs}).
 */
public interface SpawnNpcs {

    /** Spawns the NPCs and their labels from the current settings. */
    void start();

    /** Removes the NPCs and their labels. */
    void stop();
}
