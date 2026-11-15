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

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsByCategoryId(Long categoryId);

    List<Product> findByNameIgnoreCase(String name, Pageable limit);

    List<Product> findByNameStartingWithIgnoreCase(String name, Pageable limit);

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

    @Query("select p from Product p where (:categoryId is null or p.category.id = :categoryId)")
    Page<Product> listByCategory(@Param("categoryId") Long categoryId, Pageable pageable);
}
