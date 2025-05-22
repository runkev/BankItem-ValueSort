package com.BankSorter;

import lombok.Getter;

/**
 * Represents a bank item with its value information for sorting purposes
 */
@Getter
public class BankItemValue {
    private final int originalPosition;
    private final int itemId;
    private final long totalValue;
    private final int quantity;

    public BankItemValue(int originalPosition, int itemId, long totalValue, int quantity) {
        this.originalPosition = originalPosition;
        this.itemId = itemId;
        this.totalValue = totalValue;
        this.quantity = quantity;
    }

    @Override
    public String toString() {
        return String.format("BankItemValue{pos=%d, id=%d, qty=%d, value=%d}",
                originalPosition, itemId, quantity, totalValue);
    }
}