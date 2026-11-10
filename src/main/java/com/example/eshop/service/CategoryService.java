package com.example.eshop.service;

import com.example.eshop.entity.Category;
import com.example.eshop.exception.NotFoundException;
import com.example.eshop.repository.CategoryRepository;
import com.example.eshop.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final ApplicationEventPublisher events;

    public List<Category> findAll() {
        return categoryRepository.findAllByOrderByNameAsc();
    }

    public Category findById(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy danh mục id = " + id));
    }

    @PreAuthorize("hasRole('SELLER')")
    @Transactional
    public Category save(Category category) {
        boolean renamed = category.getId() != null && categoryRepository.findById(category.getId())
                .map(existing -> !existing.getName().equals(category.getName())).orElse(false);
        Category saved = categoryRepository.save(category);
        if (renamed) {
            events.publishEvent(new CatalogIndexChange.CategoryRenamed(saved.getId()));
        }
        return saved;
    }

    @PreAuthorize("hasRole('SELLER')")
    public void delete(Long id) {
        if (productRepository.existsByCategoryId(id)) {
            throw new IllegalStateException("Danh mục đang có sản phẩm, không thể xoá");
        }
        categoryRepository.deleteById(id);
    }
}
