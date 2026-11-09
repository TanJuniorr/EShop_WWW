package com.example.eshop.repository;

import com.example.eshop.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findAllByOrderByOrderDateDesc();

    List<Order> findByUserUsernameOrderByOrderDateDesc(String username);
}
