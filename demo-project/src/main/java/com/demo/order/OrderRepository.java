package com.demo.order;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 演示用的内存仓储。真实项目里这里会是 JPA / MyBatis。
 */
public class OrderRepository {

    private final Map<String, Order> storage = new ConcurrentHashMap<>();

    public Order save(Order order) {
        storage.put(order.id(), order);
        return order;
    }

    public Optional<Order> findById(String id) {
        return Optional.ofNullable(storage.get(id));
    }

    public List<Order> findByCustomer(String customerId) {
        List<Order> result = new ArrayList<>();
        for (Order order : storage.values()) {
            if (order.customerId().equals(customerId)) {
                result.add(order);
            }
        }
        result.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        return result;
    }

    public List<Order> findByStatus(OrderStatus status) {
        List<Order> result = new ArrayList<>();
        for (Order order : storage.values()) {
            if (order.status() == status) {
                result.add(order);
            }
        }
        return result;
    }

    public boolean delete(String id) {
        return storage.remove(id) != null;
    }

    public int count() {
        return storage.size();
    }

    public void clear() {
        storage.clear();
    }
}
