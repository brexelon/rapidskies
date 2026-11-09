package us.rapidskies.hub.npc;

/**
 * The mode NPCs on the stage. Kept behind an interface so Citizens' classes are only
 * loaded when Citizens is installed ({@link CitizensNpcs}).
 */
public interface HubNpcs {

    /** Spawns the NPCs and their labels from the current settings. */
    void start();

    /** Redraws the labels (player counts changed). */
    void updateLabels();

    /** Removes the NPCs; their labels go with {@code Labels#removeAll}. */
    void stop();
}
