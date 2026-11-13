package com.demo.order;

import java.math.BigDecimal;
import java.util.Objects;

public final class OrderLine {

    private final String sku;
    private final int quantity;
    private final BigDecimal unitPrice;
    private BigDecimal discount = BigDecimal.ZERO;

    public OrderLine(String sku, int quantity, BigDecimal unitPrice) {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        this.sku = sku;
        this.quantity = quantity;
        this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice");
    }

    public BigDecimal subtotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).subtract(discount);
    }

    public void applyDiscount(BigDecimal amount) {
        BigDecimal next = discount.add(amount);
        if (next.compareTo(unitPrice.multiply(BigDecimal.valueOf(quantity))) > 0) {
            throw new IllegalStateException("discount exceeds subtotal for " + sku);
        }
        this.discount = next;
    }

    public String sku() {
        return sku;
    }

    public int quantity() {
        return quantity;
    }

    public BigDecimal unitPrice() {
        return unitPrice;
    }

    public BigDecimal discount() {
        return discount;
    }
}
