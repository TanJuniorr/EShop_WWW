# Sửa lỗi 403 khi CUSTOMER thêm sản phẩm vào giỏ

## Nguyên nhân xác định từ mã nguồn được tải lên
- `SecurityConfig.java` của web vẫn dùng `SessionCreationPolicy.STATELESS`, dù ứng dụng cần HttpSession để lưu giỏ hàng và CSRF token.
- `shop/detail.html` có `th:value="${p.id}"` sai biến (controller cung cấp `product`), khiến không truyền đúng `productId`.
- `shop/index.html` chưa khai báo rõ trường CSRF. Thymeleaf thường tự chèn, bản sửa có thêm tường minh để dễ kiểm tra.

## Đã sửa
1. Web filter chain chuyển sang `SessionCreationPolicy.IF_REQUIRED`, giữ mặc định CSRF bật và JWT-cookie authentication. API filter chain sử dụng `STATELESS`.
2. Form trong `shop/detail.html` dùng `${product.id}`.
3. Form trong `shop/index.html` có trường CSRF tường minh.
4. Dọn thư mục build `target/` và cấu hình cục bộ `.idea/` khỏi gói ZIP.

## Chạy lại
1. Mở project trong IntelliJ, Reload Maven.
2. Chạy `mvn clean test` rồi khởi động `EshopApplication`.
3. Trong Chrome, xóa site data cho `localhost` (cookie/JSESSIONID/ACCESS_TOKEN) rồi đăng nhập lại `customer1 / 123456`.
4. Thử thêm sản phẩm ở cả trang chủ lẫn trang chi tiết.
5. Nếu còn 403, mở DevTools Network, kiểm tra `POST /cart/add`, trường `_csrf`, cookie `JSESSIONID`, và log Spring Security DEBUG.

Không cần thay đổi cột role của database. Không tắt CSRF toàn bộ website.

## Giới hạn kiểm thử
Chưa chạy integration test với MariaDB/IntelliJ trong môi trường đóng gói hiện tại.
