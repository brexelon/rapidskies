package us.rapidskies.core.economy;

import java.util.OptionalLong;

/**
 * Converts Vault's {@code double} amounts to whole Coins or Crowns. Rounding always goes
 * against whoever is receiving money (deposits round down, withdrawals round up), so
 * fractional prices can only ever remove currency from the economy, never create it.
 */
final class Amounts {

    /** Far above any real balance, and safely inside the range a double holds exactly. */
    static final long MAX = 1_000_000_000_000L;

    private Amounts() {
    }

    /** Whole units to credit, or empty for a negative, NaN, infinite or absurd amount. */
    static OptionalLong forDeposit(double amount) {
        return valid(amount) ? OptionalLong.of((long) Math.floor(amount)) : OptionalLong.empty();
    }

    /** Whole units to debit, or empty for a negative, NaN, infinite or absurd amount. */
    static OptionalLong forWithdrawal(double amount) {
        return valid(amount) ? OptionalLong.of((long) Math.ceil(amount)) : OptionalLong.empty();
    }

    private static boolean valid(double amount) {
        return Double.isFinite(amount) && amount >= 0 && amount <= MAX;
    }
}
