package com.example.eshop.cart;

import com.example.eshop.entity.Product;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Mỗi HttpSession (mỗi trình duyệt) có một đối tượng Cart riêng. */
@Component
@SessionScope
public class Cart implements Serializable {

    private final Map<Long, CartItem> items = new LinkedHashMap<>();

    public void add(Product p, int quantity) {
        CartItem item = items.get(p.getId());
        if (item == null) {
            items.put(p.getId(), new CartItem(p.getId(), p.getName(), p.getImageUrl(), p.getPrice(), quantity));
        } else {
            item.setQuantity(item.getQuantity() + quantity);
        }
    }

    public void update(Long productId, int quantity) {
        if (quantity <= 0) {
            items.remove(productId);
        } else if (items.containsKey(productId)) {
            items.get(productId).setQuantity(quantity);
        }
    }

    public void remove(Long productId) { items.remove(productId); }

    public void clear() { items.clear(); }

    public Collection<CartItem> getItems() { return items.values(); }

    public boolean isEmpty() { return items.isEmpty(); }

    public int getQuantityOf(Long productId) {
        CartItem i = items.get(productId);
        return i == null ? 0 : i.getQuantity();
    }

    public int getTotalQuantity() {
        return items.values().stream().mapToInt(CartItem::getQuantity).sum();
    }

    public BigDecimal getTotalAmount() {
        return items.values().stream().map(CartItem::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
