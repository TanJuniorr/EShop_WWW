package com.example.eshop.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "category")
@Getter @Setter @NoArgsConstructor
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Tên danh mục không được để trống")
    @Size(max = 100, message = "Tối đa 100 ký tự")
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Size(max = 255, message = "Tối đa 255 ký tự")
    private String description;
}
