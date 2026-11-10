package us.rapidskies.horizons.trade;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import us.rapidskies.horizons.data.HorizonsRepository.TradeRow;

/**
 * Trade writes still unsaved when the server stops with the database unreachable, kept in
 * {@code pending-trades.json} in the plugin folder and queued again on the next start
 * ({@link TradeOutbox}). Payouts keep their transaction ids, so one that did reach the
 * ledger after all is still paid once.
 */
final class TradeJournal {

    static final String FILE = "pending-trades.json";

    private TradeJournal() {
    }

    /** Writes {@code batches} (atomically, through a temporary file); an empty list deletes the file. */
    static void save(Path file, Collection<TradeOutbox.Batch> batches) throws IOException {
        List<TradeOutbox.Batch> kept = batches.stream().filter(b -> !b.isEmpty()).toList();
        if (kept.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        JsonArray all = new JsonArray();
        for (TradeOutbox.Batch b : kept) {
            JsonObject o = new JsonObject();
            o.addProperty("player", b.player().toString());
            JsonObject rows = new JsonObject();
            b.rows().forEach((t, r) -> {
                JsonObject row = new JsonObject();
                row.addProperty("xp", r.xp());
                row.addProperty("level", r.level());
                row.addProperty("active", r.active());
                rows.add(t.id(), row);
            });
            o.add("rows", rows);
            JsonObject points = new JsonObject();
            b.points().forEach((day, n) -> points.addProperty(day.toString(), n));
            o.add("points", points);
            JsonArray payouts = new JsonArray();
            for (TradeOutbox.Payout p : b.payouts()) {
                JsonObject payout = new JsonObject();
                payout.addProperty("trade", p.trade().id());
                payout.addProperty("crowns", p.crowns());
                payout.addProperty("txn", p.txnId());
                payouts.add(payout);
            }
            o.add("payouts", payouts);
            all.add(o);
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(all), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** What {@link #save} wrote; empty if there's no file. Entries it can't read are skipped. */
    static List<TradeOutbox.Batch> load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (JsonParseException e) {
            throw new IOException(file + " isn't valid JSON: " + e.getMessage(), e);
        }
        List<TradeOutbox.Batch> out = new ArrayList<>();
        if (!root.isJsonArray()) {
            return out;
        }
        for (JsonElement e : root.getAsJsonArray()) {
            try {
                JsonObject o = e.getAsJsonObject();
                UUID player = UUID.fromString(o.get("player").getAsString());
                Map<Trade, TradeRow> rows = new EnumMap<>(Trade.class);
                for (Map.Entry<String, JsonElement> r : o.getAsJsonObject("rows").entrySet()) {
                    Optional<Trade> t = Trade.parse(r.getKey());
                    JsonObject row = r.getValue().getAsJsonObject();
                    t.ifPresent(trade -> rows.put(trade, new TradeRow(row.get("xp").getAsLong(),
                            row.get("level").getAsInt(), row.get("active").getAsBoolean())));
                }
                Map<LocalDate, Long> points = new HashMap<>();
                for (Map.Entry<String, JsonElement> p : o.getAsJsonObject("points").entrySet()) {
                    points.put(LocalDate.parse(p.getKey()), p.getValue().getAsLong());
                }
                List<TradeOutbox.Payout> payouts = new ArrayList<>();
                for (JsonElement p : o.getAsJsonArray("payouts")) {
                    JsonObject payout = p.getAsJsonObject();
                    Optional<Trade> t = Trade.parse(payout.get("trade").getAsString());
                    if (t.isPresent()) {
                        payouts.add(new TradeOutbox.Payout(t.get(), payout.get("crowns").getAsLong(),
                                payout.get("txn").getAsString()));
                    }
                }
                out.add(new TradeOutbox.Batch(player, rows, points, payouts));
            } catch (RuntimeException bad) {
                // A hand-edited or truncated entry; the rest still count.
            }
        }
        return out;
    }
}
