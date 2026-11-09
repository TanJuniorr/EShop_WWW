# Đối chiếu PDF và các sửa đổi trong dự án eShop

## Trạng thái ban đầu

- ZIP gốc đã có mã nguồn đầy đủ cho ba phần theo bố cục PDF: entities, repositories, services, controllers, Thymeleaf, JWT và SQL mẫu. Không có bằng chứng cho thấy toàn bộ dự án bị lỗi biên dịch (ZIP có các tệp `.class` đã build).
- File cấu hình gốc chứa trực tiếp mật khẩu `spring.datasource.password` của máy tạo ZIP và một khóa JWT cố định. Đây là lỗi đóng gói/bảo mật, không phải lỗi cú pháp.
- Chưa có `src/test` trong ZIP gốc; ZIP chứa `.idea/` và `target/` riêng theo máy.

## Những sửa đổi đã thực hiện

1. **`application.properties`**: dùng `ESHOP_DB_USER`, `ESHOP_DB_PASSWORD`, `ESHOP_JWT_SECRET`; giảm log Spring Security từ DEBUG xuống INFO.
2. **`JwtService`**: nếu không khai báo khóa JWT, tạo khóa ngẫu nhiên cho phiên demo hiện tại; nếu có biến môi trường, dùng khóa Base64 đó.
3. **`JwtAuthenticationFilter`**: API `/api/**` chỉ đọc Bearer header, cookie ACCESS_TOKEN chỉ dùng web; đúng hai kênh đăng nhập mà PDF mô tả.
4. **`ProductRepository` / `OrderService`**: thêm `findByIdForUpdate` với pessimistic write lock trong giao dịch đặt hàng để giảm rủi ro hai giao dịch cùng trừ vượt tồn kho. Kiểm tra số lượng phải > 0.
5. **`OrderService`**: hủy chỉ hoàn kho một lần; không mở lại đơn đã hủy; xóa đơn chưa hủy sẽ hoàn tồn kho; xác nhận chuyển khoản giả lập chỉ cho đơn BANKING ở trạng thái NEW.
6. **`OrderController` / `AdminOrderController`**: chặn truy cập màn thanh toán cho đơn không hợp lệ; đưa thông báo rõ cho thao tác trạng thái sai thay vì hiển thị lỗi máy chủ.
7. **`ProductService`**: giới hạn page size từ 1 đến 100 để tránh size 0 gây lỗi runtime.
8. **`CheckoutForm`, `RegisterForm`, `UserForm`, `ProductRequest`**: ràng buộc độ dài đầu vào đúng kích thước cột MariaDB để tránh lỗi 500 khi nhập chuỗi quá dài.
9. **`.gitignore`, `.env.example`, `README.md`**: bổ sung hướng dẫn IntelliJ, cách cấu hình DB, JWT và cảnh báo `DROP DATABASE` khi import SQL.
10. **`src/test/.../OrderServiceTest.java`**: bổ sung unit test cho tạo đơn, thiếu hàng, hoàn kho khi hủy/xóa, và thanh toán giả lập.
11. **Gói phát hành**: loại `.idea/` và `target/` để zip có thể mở trên máy khác mà không mang cấu hình cũ hoặc bytecode cũ.

## Phạm vi kiểm tra / giới hạn

- Đã kiểm tra mã nguồn, cấu trúc ZIP, file XML/properties/SQL, cấu trúc templates và hash BCrypt của tài khoản demo.
- Môi trường tạo gói không có Maven/MariaDB và không thể tải dependencies từ Maven Central, nên **chưa chạy được Maven compile/test hay kết nối MariaDB thật** ở đây. Các test JUnit đã được bổ sung cho bạn chạy trong IntelliJ.
- Script SQL đang giữ nội dung mẫu của ZIP gốc, không tự ý đổi dữ liệu mẫu PDF yêu cầu.
- Khi chạy phải khai báo đúng mật khẩu MariaDB của máy sử dụng (không thể biết từ PDF).
