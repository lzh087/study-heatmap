package com.demo.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 价格计算：先把满减折到行上，再按会员等级算最终应付。
 */
public class PriceCalculator {

    private static final BigDecimal FREE_SHIPPING_THRESHOLD = new BigDecimal("199");
    private static final BigDecimal SHIPPING_FEE = new BigDecimal("12");

    public BigDecimal payable(Order order, MemberLevel level) {
        BigDecimal goods = order.total();
        BigDecimal shipping = shippingFee(goods);
        BigDecimal discount = level.discount(goods);
        BigDecimal total = goods.add(shipping).subtract(discount);
        return total.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal shippingFee(BigDecimal goodsAmount) {
        if (goodsAmount.compareTo(FREE_SHIPPING_THRESHOLD) >= 0) {
            return BigDecimal.ZERO;
        }
        return SHIPPING_FEE;
    }

    public void applyPromotion(Order order, List<Promotion> promotions) {
        for (Promotion promotion : promotions) {
            if (!promotion.matches(order)) {
                continue;
            }
            BigDecimal budget = promotion.amount();
            for (OrderLine line : order.lines()) {
                if (budget.signum() <= 0) {
                    break;
                }
                BigDecimal room = line.subtotal();
                BigDecimal cut = budget.min(room);
                line.applyDiscount(cut);
                budget = budget.subtract(cut);
            }
        }
    }

    public interface Promotion {
        boolean matches(Order order);

        BigDecimal amount();
    }

    public enum MemberLevel {
        NORMAL(BigDecimal.ZERO),
        SILVER(new BigDecimal("0.03")),
        GOLD(new BigDecimal("0.06")),
        PLATINUM(new BigDecimal("0.10"));

        private final BigDecimal rate;

        MemberLevel(BigDecimal rate) {
            this.rate = rate;
        }

        public BigDecimal discount(BigDecimal amount) {
            return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        }
    }
}
