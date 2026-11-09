package com.example.eshop.repository;

import com.example.eshop.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.List;
import java.util.Collection;
import java.math.BigDecimal;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsByCategoryId(Long categoryId);

    String CATALOG_FILTERS = """
            (:categoryId is null or p.category.id = :categoryId)
            and (:min is null or p.price > :min or (:minInclusive = true and p.price = :min))
            and (:max is null or p.price < :max or (:maxInclusive = true and p.price = :max))
            and (:inStock = false or p.quantity > 0)
            """;

    @Query("select p.id from Product p where " + CATALOG_FILTERS)
    List<Long> hybridCandidateIds(@Param("categoryId") Long categoryId,
                                  @Param("min") BigDecimal min, @Param("minInclusive") boolean minInclusive,
                                  @Param("max") BigDecimal max, @Param("maxInclusive") boolean maxInclusive,
                                  @Param("inStock") boolean inStock);

    @Query(value = "select p from Product p join fetch p.category where " + CATALOG_FILTERS,
           countQuery = "select count(p) from Product p where " + CATALOG_FILTERS)
    Page<Product> searchCatalog(@Param("categoryId") Long categoryId,
                                @Param("min") BigDecimal min, @Param("minInclusive") boolean minInclusive,
                                @Param("max") BigDecimal max, @Param("maxInclusive") boolean maxInclusive,
                                @Param("inStock") boolean inStock, Pageable pageable);

    @Query("""
            select new com.example.eshop.repository.ProductIndexSnapshot(p.id, p.name, p.description, c.id, c.name)
            from Product p left join p.category c where p.id = :id
            """)
    Optional<ProductIndexSnapshot> indexSnapshot(@Param("id") long id);

    @Query("""
            select p.id from Product p where p.id > :afterId
              and (:categoryId is null or p.category.id = :categoryId)
            order by p.id
            """)
    List<Long> indexIds(@Param("afterId") long afterId, @Param("categoryId") Long categoryId, Pageable limit);

    @Query("select coalesce(max(p.id), 0) from Product p")
    long maxIndexId();

    @Query("select p from Product p join fetch p.category where p.id in :ids")
    List<Product> loadRankedProducts(@Param("ids") Collection<Long> ids);

    /** Khóa dòng sản phẩm trong transaction thanh toán để tránh bán vượt tồn kho. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    /** Tìm theo tên (không phân biệt hoa thường) và/hoặc theo danh mục; tham số null = bỏ qua điều kiện. */
    @Query("""
            SELECT p FROM Product p
            WHERE (:keyword IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:categoryId IS NULL OR p.category.id = :categoryId)
            """)
    Page<Product> search(@Param("keyword") String keyword,
                         @Param("categoryId") Long categoryId,
                         Pageable pageable);
}
