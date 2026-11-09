package us.rapidskies.proxy.party;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import us.rapidskies.common.lang.Lang;

/** {@code /pc <message>}: party chat, short for {@code /party chat}. */
public final class PartyChatCommand implements SimpleCommand {

    private final Lang lang;
    private final PartyService parties;

    public PartyChatCommand(Lang lang, PartyService parties) {
        this.lang = lang;
        this.parties = parties;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            lang.send(invocation.source(), "errors.players-only");
            return;
        }
        if (invocation.arguments().length == 0) {
            lang.send(player, "party.chat-usage");
            return;
        }
        parties.chat(player, String.join(" ", invocation.arguments()));
    }
}
