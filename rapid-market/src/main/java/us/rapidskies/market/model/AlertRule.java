package us.rapidskies.market.model;

import java.util.OptionalDouble;

/**
 * Spots sales that look like alt transfers or real-money trading (PLAN.md §4.2): an item
 * sold far below its recent average price, again and again to the same buyer.
 *
 * @param belowPercent a sale's price per item under this share of the average is suspicious
 * @param minAverage   items whose average per item is under this are ignored (cheap stacks
 *                     are sold off below average all the time)
 * @param repeat       alert when this many suspicious sales went from one seller to one buyer
 * @param windowDays   ... within this many days
 */
public record AlertRule(int belowPercent, long minAverage, int repeat, int windowDays) {

    /** Whether a sale of {@code amount} items for {@code price} is suspicious against {@code average} (per item). */
    public boolean suspicious(long price, int amount, OptionalDouble average) {
        if (belowPercent <= 0 || average.isEmpty() || amount <= 0) {
            return false;
        }
        double avg = average.getAsDouble();
        if (avg < minAverage) {
            return false;
        }
        double perItem = (double) price / amount;
        return perItem * 100 < avg * belowPercent;
    }

    /**
     * Whether staff hear about the pair now that {@code flaggedSales} suspicious sales went
     * between them: at the {@code repeat}-th, and again at every further {@code repeat}, so a
     * pair that keeps going isn't announced on every sale.
     */
    public boolean alert(int flaggedSales) {
        return repeat > 0 && flaggedSales >= repeat && flaggedSales % repeat == 0;
    }
}
