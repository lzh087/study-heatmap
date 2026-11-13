package com.demo.order;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 订单主流程：下单 -> 预占 -> 支付 -> 发货 -> 完成。
 * 这里刻意保留了业务校验和异常分支，用来演示热力图在长方法上的分布。
 */
public class OrderService {

    private final OrderRepository repository;
    private final PriceCalculator calculator;
    private final PaymentGateway gateway;

    public OrderService(OrderRepository repository, PriceCalculator calculator, PaymentGateway gateway) {
        this.repository = repository;
        this.calculator = calculator;
        this.gateway = gateway;
    }

    public Order create(String customerId, List<OrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("order must contain at least one line");
        }
        Order order = new Order(UUID.randomUUID().toString(), customerId);
        for (OrderLine line : lines) {
            order.addLine(line);
        }
        repository.save(order);
        return order;
    }

    public Order reserve(String orderId) {
        Order order = require(orderId);
        if (order.isEmpty()) {
            throw new IllegalStateException("cannot reserve empty order");
        }
        order.markReserved();
        return repository.save(order);
    }

    public Order pay(String orderId, String token, PriceCalculator.MemberLevel level) {
        Order order = require(orderId);
        if (order.status() != OrderStatus.RESERVED) {
            throw new IllegalStateException("order is not reserved: " + order.status());
        }
        BigDecimal amount = calculator.payable(order, level);
        PaymentGateway.PaymentResult result = gateway.charge(orderId, amount, token);
        if (!result.success()) {
            throw new PaymentFailedException(result.failureReason());
        }
        order.markPaid(result.paymentId());
        return repository.save(order);
    }

    public Order ship(String orderId) {
        Order order = require(orderId);
        order.markShipped();
        return repository.save(order);
    }

    public Order complete(String orderId) {
        Order order = require(orderId);
        order.markCompleted();
        return repository.save(order);
    }

    public Order cancel(String orderId, String reason) {
        Order order = require(orderId);
        order.cancel(reason);
        return repository.save(order);
    }

    public Optional<Order> lookup(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(orderId);
    }

    public List<Order> historyOf(String customerId) {
        return repository.findByCustomer(customerId);
    }

    public BigDecimal quote(Order order, PriceCalculator.MemberLevel level) {
        return calculator.payable(order, level);
    }

    private Order require(String orderId) {
        return repository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(String orderId) {
            super("order not found: " + orderId);
        }
    }

    public static class PaymentFailedException extends RuntimeException {
        public PaymentFailedException(String reason) {
            super("payment failed: " + reason);
        }
    }
}
