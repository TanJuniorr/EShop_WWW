package com.example.eshop.cart;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class CartItem implements Serializable {
    private Long productId;
    private String name;
    private String imageUrl;
    private BigDecimal price;
    @Setter
    private int quantity;

    public BigDecimal getSubtotal() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}
