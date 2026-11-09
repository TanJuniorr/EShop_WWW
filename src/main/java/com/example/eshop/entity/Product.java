package com.example.eshop.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "product")
@Getter @Setter @NoArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Tên sản phẩm không được để trống")
    @Size(max = 150, message = "Tối đa 150 ký tự")
    @Column(nullable = false, length = 150)
    private String name;

    @Size(max = 500, message = "Tối đa 500 ký tự")
    private String description;

    @NotNull(message = "Vui lòng nhập giá")
    @DecimalMin(value = "0", message = "Giá phải >= 0")
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal price;

    @NotNull(message = "Vui lòng nhập số lượng")
    @Min(value = 0, message = "Số lượng phải >= 0")
    @Column(nullable = false)
    private Integer quantity;

    @Size(max = 500)
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
