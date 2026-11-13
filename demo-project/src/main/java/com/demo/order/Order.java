package com.demo.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class Order {

    private final String id;
    private final String customerId;
    private final List<OrderLine> lines = new ArrayList<>();
    private final Instant createdAt = Instant.now();

    private OrderStatus status = OrderStatus.CREATED;
    private String paymentId;
    private Instant paidAt;
    private String cancelReason;

    public Order(String id, String customerId) {
        this.id = id;
        this.customerId = customerId;
    }

    public void addLine(OrderLine line) {
        if (status != OrderStatus.CREATED) {
            throw new IllegalStateException("cannot modify order in status " + status);
        }
        lines.add(line);
    }

    public BigDecimal total() {
        return lines.stream()
                .map(OrderLine::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public int totalQuantity() {
        return lines.stream().mapToInt(OrderLine::quantity).sum();
    }

    public Optional<OrderLine> findLine(String sku) {
        return lines.stream().filter(l -> l.sku().equals(sku)).findFirst();
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public void markReserved() {
        requireStatus(OrderStatus.CREATED);
        this.status = OrderStatus.RESERVED;
    }

    public void markPaid(String paymentId) {
        requireStatus(OrderStatus.RESERVED);
        this.paymentId = paymentId;
        this.paidAt = Instant.now();
        this.status = OrderStatus.PAID;
    }

    public void markShipped() {
        requireStatus(OrderStatus.PAID);
        this.status = OrderStatus.SHIPPED;
    }

    public void markCompleted() {
        requireStatus(OrderStatus.SHIPPED);
        this.status = OrderStatus.COMPLETED;
    }

    public void cancel(String reason) {
        if (status == OrderStatus.SHIPPED || status == OrderStatus.COMPLETED) {
            throw new IllegalStateException("shipped order cannot be cancelled");
        }
        this.status = OrderStatus.CANCELLED;
        this.cancelReason = reason;
    }

    private void requireStatus(OrderStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("expected " + expected + " but was " + status);
        }
    }

    public String id() {
        return id;
    }

    public String customerId() {
        return customerId;
    }

    public OrderStatus status() {
        return status;
    }

    public List<OrderLine> lines() {
        return List.copyOf(lines);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<String> paymentId() {
        return Optional.ofNullable(paymentId);
    }

    public Optional<Instant> paidAt() {
        return Optional.ofNullable(paidAt);
    }

    public Optional<String> cancelReason() {
        return Optional.ofNullable(cancelReason);
    }
}
