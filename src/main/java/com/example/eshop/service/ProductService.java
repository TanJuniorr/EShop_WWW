package com.example.eshop.service;

import com.example.eshop.entity.Category;
import com.example.eshop.entity.Product;
import com.example.eshop.exception.NotFoundException;
import com.example.eshop.repository.CategoryRepository;
import com.example.eshop.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public Page<Product> search(String keyword, Long categoryId, int page, int size) {
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        // Chặn size <= 0 (làm PageRequest ném lỗi) và size quá lớn từ API công khai.
        int safeSize = Math.max(1, Math.min(size, 100));
        return productRepository.search(kw, categoryId,
                PageRequest.of(Math.max(page, 0), safeSize, Sort.by("id").descending()));
    }

    public Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy sản phẩm id = " + id));
    }

    @PreAuthorize("hasRole('SELLER')")
    @Transactional
    public Product save(Product product, Long categoryId) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy danh mục id = " + categoryId));
        product.setCategory(category);
        return productRepository.save(product);   // id == null → INSERT, id != null → UPDATE
    }

    @PreAuthorize("hasRole('SELLER')")
    public void delete(Long id) {
        try {
            productRepository.deleteById(id);
        } catch (DataIntegrityViolationException e) {
            // vướng khoá ngoại order_detail.product_id
            throw new IllegalStateException("Sản phẩm đã có trong đơn hàng, không thể xoá");
        }
    }
}
