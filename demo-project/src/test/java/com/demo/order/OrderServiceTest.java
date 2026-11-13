package com.demo.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderServiceTest {

    private OrderRepository repository;
    private OrderService service;

    @BeforeEach
    void setUp() {
        repository = new OrderRepository();
        service = new OrderService(repository, new PriceCalculator(), new PaymentGateway.FakeGateway());
    }

    @Test
    void shouldWalkThroughHappyPath() {
        Order order = service.create("c-1", List.of(new OrderLine("SKU-1", 2, new BigDecimal("59.00"))));
        service.reserve(order.id());
        service.pay(order.id(), "tok-1", PriceCalculator.MemberLevel.GOLD);

        assertEquals(OrderStatus.PAID, order.status());
        assertEquals(new BigDecimal("110.92"), service.quote(order, PriceCalculator.MemberLevel.GOLD));
    }

    @Test
    void shouldRejectPaymentWithoutToken() {
        Order order = service.create("c-2", List.of(new OrderLine("SKU-2", 1, new BigDecimal("10.00"))));
        service.reserve(order.id());

        assertThrows(OrderService.PaymentFailedException.class,
                () -> service.pay(order.id(), "", PriceCalculator.MemberLevel.NORMAL));
    }

    @Test
    void shouldNotCancelShippedOrder() {
        Order order = service.create("c-3", List.of(new OrderLine("SKU-3", 1, new BigDecimal("20.00"))));
        service.reserve(order.id());
        service.pay(order.id(), "tok-3", PriceCalculator.MemberLevel.NORMAL);
        service.ship(order.id());

        assertThrows(IllegalStateException.class, () -> service.cancel(order.id(), "changed mind"));
    }
}
