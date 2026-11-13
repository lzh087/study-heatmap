package com.demo.order;

import java.math.BigDecimal;

/**
 * 支付网关。这里只做接口与一个假的实现，方便演示失败分支。
 */
public interface PaymentGateway {

    PaymentResult charge(String orderId, BigDecimal amount, String token);

    record PaymentResult(boolean success, String paymentId, String failureReason) {

        public static PaymentResult ok(String paymentId) {
            return new PaymentResult(true, paymentId, null);
        }

        public static PaymentResult fail(String reason) {
            return new PaymentResult(false, null, reason);
        }
    }

    final class FakeGateway implements PaymentGateway {

        private int counter = 0;

        @Override
        public synchronized PaymentResult charge(String orderId, BigDecimal amount, String token) {
            if (token == null || token.isBlank()) {
                return PaymentResult.fail("missing payment token");
            }
            if (amount.signum() <= 0) {
                return PaymentResult.fail("invalid amount");
            }
            counter++;
            return PaymentResult.ok("pay_" + orderId + "_" + counter);
        }
    }
}
