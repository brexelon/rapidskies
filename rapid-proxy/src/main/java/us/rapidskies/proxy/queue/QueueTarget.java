package us.rapidskies.proxy.queue;

import us.rapidskies.common.registry.ServerStatus;

/**
 * Where a player is waiting to go: any server of a group ({@code smp}), or one exact
 * server ({@code smp-1}, e.g. to follow a party leader or join a friend).
 */
public record QueueTarget(Kind kind, String name) {

    public enum Kind { GROUP, SERVER }

    public static QueueTarget group(String group) {
        return new QueueTarget(Kind.GROUP, group);
    }

    public static QueueTarget server(String id) {
        return new QueueTarget(Kind.SERVER, id);
    }

    public boolean matches(ServerStatus s) {
        return kind == Kind.GROUP ? s.group().equals(name) : s.id().equals(name);
    }
}
