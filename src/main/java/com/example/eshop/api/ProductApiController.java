package com.example.eshop.api;

import com.example.eshop.entity.Product;
import com.example.eshop.service.ProductService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductApiController {

    private final ProductService productService;

    /** DTO trả ra JSON – không trả entity trực tiếp (tránh lỗi proxy LAZY, vòng lặp JSON). */
    public record ProductDto(Long id, String name, String description, BigDecimal price,
                             Integer quantity, String imageUrl, Long categoryId, String categoryName) {
        static ProductDto from(Product p) {
            return new ProductDto(p.getId(), p.getName(), p.getDescription(), p.getPrice(),
                    p.getQuantity(), p.getImageUrl(), p.getCategory().getId(), p.getCategory().getName());
        }
    }

    public record ProductRequest(@NotBlank @Size(max = 150) String name,
                                 @Size(max = 500) String description,
                                 @NotNull @DecimalMin("0") BigDecimal price,
                                 @NotNull @Min(0) Integer quantity,
                                 @Size(max = 500) String imageUrl, @NotNull Long categoryId) {}

    @GetMapping                    // GUEST, CUSTOMER, SELLER
    public Page<ProductDto> list(@RequestParam(required = false) Long categoryId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "10") int size) {
        return productService.list(categoryId, page, size).map(ProductDto::from);
    }

    @GetMapping("/{id}")
    public ProductDto get(@PathVariable Long id) {
        return ProductDto.from(productService.findById(id));
    }

    @PostMapping                   // SELLER
    @ResponseStatus(HttpStatus.CREATED)
    public ProductDto create(@Valid @RequestBody ProductRequest req) {
        Product p = new Product();
        apply(req, p);
        return ProductDto.from(productService.save(p, req.categoryId()));
    }

    @PutMapping("/{id}")           // SELLER
    public ProductDto update(@PathVariable Long id, @Valid @RequestBody ProductRequest req) {
        Product p = productService.findById(id);
        apply(req, p);
        return ProductDto.from(productService.save(p, req.categoryId()));
    }

    @DeleteMapping("/{id}")        // SELLER
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        productService.delete(id);
    }

    private void apply(ProductRequest r, Product p) {
        p.setName(r.name());
        p.setDescription(r.description());
        p.setPrice(r.price());
        p.setQuantity(r.quantity());
        p.setImageUrl(r.imageUrl());
    }
}
