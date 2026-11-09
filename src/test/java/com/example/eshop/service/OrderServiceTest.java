package com.example.eshop.service;

import com.example.eshop.cart.Cart;
import com.example.eshop.dto.CheckoutForm;
import com.example.eshop.entity.Order;
import com.example.eshop.entity.OrderDetail;
import com.example.eshop.entity.OrderStatus;
import com.example.eshop.entity.PaymentMethod;
import com.example.eshop.entity.Product;
import com.example.eshop.entity.User;
import com.example.eshop.repository.OrderRepository;
import com.example.eshop.repository.ProductRepository;
import com.example.eshop.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderServiceTest {
    private OrderRepository orders;
    private ProductRepository products;
    private UserRepository users;
    private OrderService service;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        products = mock(ProductRepository.class);
        users = mock(UserRepository.class);
        service = new OrderService(orders, products, users);
    }

    private static Product product(long id, int stock, long price) {
        Product p = new Product();
        p.setId(id);
        p.setName("Sản phẩm test");
        p.setQuantity(stock);
        p.setPrice(BigDecimal.valueOf(price));
        return p;
    }

    private static CheckoutForm checkout(PaymentMethod method) {
        CheckoutForm f = new CheckoutForm();
        f.setCustomerName("Khách thử nghiệm");
        f.setPhone("0912345678");
        f.setAddress("TP.HCM");
        f.setPaymentMethod(method);
        return f;
    }

    private static Order order(Product p, int quantity, OrderStatus status, PaymentMethod method) {
        Order order = new Order();
        order.setStatus(status);
        order.setPaymentMethod(method);
        OrderDetail d = new OrderDetail();
        d.setProduct(p);
        d.setQuantity(quantity);
        d.setUnitPrice(p.getPrice());
        order.addDetail(d);
        return order;
    }

    @Test
    void placeOrderUpdatesStockAndUsesDatabasePrice() {
        Product fromDb = product(1L, 5, 100_000);
        Product fromCart = product(1L, 5, 1); // giá trong giỏ không đáng tin
        Cart cart = new Cart();
        cart.add(fromCart, 2);

        when(products.findByIdForUpdate(1L)).thenReturn(Optional.of(fromDb));
        when(users.findByUsername("customer1")).thenReturn(Optional.of(new User()));
        when(orders.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order placed = service.placeOrder(checkout(PaymentMethod.COD), cart, "customer1");
        assertEquals(3, fromDb.getQuantity());
        assertEquals(0, placed.getTotalAmount().compareTo(new BigDecimal("200000")));
        assertEquals(1, placed.getDetails().size());
        assertEquals(OrderStatus.NEW, placed.getStatus());
    }

    @Test
    void placeOrderRejectsInsufficientStock() {
        Product p = product(1L, 1, 10_000);
        Cart cart = new Cart();
        cart.add(p, 3);
        when(products.findByIdForUpdate(1L)).thenReturn(Optional.of(p));
        assertThrows(IllegalStateException.class,
                () -> service.placeOrder(checkout(PaymentMethod.COD), cart, "customer1"));
        assertEquals(1, p.getQuantity());
        verify(orders, never()).save(any(Order.class));
    }

    @Test
    void cancelRestocksExactlyOnceAndCannotReopen() {
        Product p = product(1L, 3, 10_000);
        Order o = order(p, 2, OrderStatus.NEW, PaymentMethod.COD);
        when(orders.findById(9L)).thenReturn(Optional.of(o));

        service.updateStatus(9L, OrderStatus.CANCELLED);
        assertEquals(5, p.getQuantity());
        service.updateStatus(9L, OrderStatus.CANCELLED);
        assertEquals(5, p.getQuantity());
        assertThrows(IllegalStateException.class, () -> service.updateStatus(9L, OrderStatus.NEW));
    }

    @Test
    void deletingActiveOrderRestocksProducts() {
        Product p = product(1L, 3, 10_000);
        Order o = order(p, 2, OrderStatus.NEW, PaymentMethod.COD);
        when(orders.findById(9L)).thenReturn(Optional.of(o));

        service.delete(9L);
        assertEquals(5, p.getQuantity());
        verify(orders).delete(o);
    }

    @Test
    void paymentSimulationOnlyAcceptsNewBankTransferOrders() {
        Order cod = order(product(1L, 2, 10_000), 1, OrderStatus.NEW, PaymentMethod.COD);
        when(orders.findById(9L)).thenReturn(Optional.of(cod));
        assertThrows(IllegalStateException.class, () -> service.markPaid(9L));

        Order banking = order(product(1L, 2, 10_000), 1, OrderStatus.NEW, PaymentMethod.BANKING);
        when(orders.findById(9L)).thenReturn(Optional.of(banking));
        service.markPaid(9L);
        assertEquals(OrderStatus.PAID, banking.getStatus());
        assertThrows(IllegalStateException.class, () -> service.markPaid(9L));
    }
}
