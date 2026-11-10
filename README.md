# eShop – Shopping Cart (Spring Boot, Maven, MariaDB)

Project đã được rà soát theo tài liệu **huong-dan-eshop-spring-boot-maven.pdf** (81 trang).
Bao gồm cả ba phần: CRUD với Thymeleaf, giỏ hàng/đơn hàng, Spring Security/JWT và ba vai trò.

## Yêu cầu

- JDK **17**, Maven **3.9.x** (hoặc Maven tích hợp IntelliJ), IntelliJ IDEA, MariaDB **10.6+**.
- Bật Lombok/annotation processing nếu IntelliJ yêu cầu.

## 1. Mở bằng IntelliJ

1. Giải nén, chọn **File > Open** rồi trỏ vào thư mục `eshop` (chứa `pom.xml`).
2. Chọn **Trust Project**, chờ **Reload Maven Projects** tải dependency.
3. Chọn **Project SDK = JDK 17** và Java compiler/JDK cho Maven là 17.
4. Kiểm tra `src/main/java/com/example/eshop/EshopApplication.java` có nút Run.

## 2. Database (bắt buộc)

1. Khởi động dịch vụ **MariaDB** trên máy, port mặc định 3306.
2. Dùng IntelliJ Database (Ultimate), HeidiSQL hoặc MariaDB CLI để chạy `database/eShop.sql`.
3. Kiểm tra có `category = 15`, `product = 50`, `users = 5`.

**Cảnh báo:** `eShop.sql` có `DROP DATABASE IF EXISTS eshop;` để khởi tạo dữ liệu demo mới. Chạy lại sẽ **xóa hết dữ liệu eShop hiện có**, bao gồm đơn hàng/người dùng bạn đã tạo. Hãy sao lưu trước nếu cần.

## 3. Cấu hình database và JWT

`src/main/resources/application.properties` đã tách mật khẩu và bí mật khỏi mã nguồn.

Vào **Run > Edit Configurations > EshopApplication > Environment variables**, thêm:

| Tên biến môi trường | Giá trị |
|---|---|
| `ESHOP_DB_USER` | `root` (có thể bỏ qua vì đã mặc định) |
| `ESHOP_DB_PASSWORD` | Mật khẩu tài khoản MariaDB của **máy bạn** |
| `ESHOP_JWT_SECRET` | Tùy chọn: bí mật Base64 có ít nhất 32 byte |

Nếu chưa thiết lập `ESHOP_JWT_SECRET`, ứng dụng tự tạo **khóa JWT tạm mỗi lần khởi động** để tiện phát triển local. Khi restart sẽ cần đăng nhập lại. Để giữ phiên đăng nhập qua lần restart, tạo khóa riêng và cấu hình biến này, tuyệt đối không đưa vào Git.

Ví dụ trong PowerShell, chạy trong cùng cửa sổ (chỉ có hiệu lực phiên PowerShell đó):

```powershell
$env:ESHOP_DB_PASSWORD = 'mat-khau-mariadb-cua-ban'
# Nếu đã cài Maven và thêm vào PATH:
mvn spring-boot:run
```

> File `.env.example` chỉ để tham khảo, Spring Boot **không tự tải** file `.env` nếu bạn chỉ copy/đổi tên nó.

## 4. Chạy và kiểm tra

- `mvn clean test` (nếu Maven đã có trong PATH), hoặc tab **Maven > Lifecycle > test** trong IntelliJ.
- Run class `EshopApplication` hoặc chạy `mvn spring-boot:run`.
- Truy cập `http://localhost:8080`.
- Nếu port 8080 đã được dùng, chỉnh `server.port` trong `application.properties`.

Trang `/products` dùng tìm kiếm AI khi chạy với profile `ai-dev`. Xem [cách cấu hình Gemini và Qdrant](docs/PHASE5_REPORT.md#api-giao-diện-và-cách-chạy).

Nút **Trợ lý AI** mở chatbot tư vấn sản phẩm, ngân sách và so sánh mẫu. Xem [cách bật và sử dụng chatbot](docs/PHASE6_CHATBOT_REPORT.md).

Analytics dành cho SELLER nằm ở `/admin/analytics`. Bật thu thập sau khi áp dụng migration V007. [Hướng dẫn triển khai và kiểm tra tracking](docs/PHASE7_USER_TRACKING_REPORT.md)

### Tài khoản demo (mật khẩu `123456`)

| Tên đăng nhập | Quyền |
|---|---|
| `seller1`, `seller2` | SELLER – quản trị |
| `customer1`, `customer2` | CUSTOMER – giỏ hàng/đơn hàng |
| `locked` | CUSTOMER bị khóa – không đăng nhập được |

### REST API

- `GET /api/products` – danh sách công khai, hỗ trợ `categoryId`, `page` và `size`.
- `GET /api/products/semantic-search?q=...` – tìm kiếm AI công khai, hỗ trợ `categoryId`.
- `POST /api/auth/login` JSON `{"username":"seller1","password":"123456"}` → lấy accessToken.
- `POST/PUT/DELETE /api/products` – dùng header `Authorization: Bearer <token>` của SELLER.
- Trang web Thymeleaf dùng cookie `ACCESS_TOKEN` HttpOnly; API **không dùng cookie** để xác thực.

**Lưu ý:** Thanh toán ngân hàng/QR trong bài chỉ là **giả lập theo tài liệu PDF**, chưa tích hợp cổng thanh toán thật; chưa sử dụng cho dữ liệu hoặc giao dịch thật.

## Những thay đổi so với ZIP gốc

Chi tiết xem `FIXES.md`. Thay đổi chủ yếu ở cấu hình bí mật, xác thực API, giao dịch đơn hàng và kiểm tra các trạng thái thanh toán/hủy/xóa đơn. Các package, trang HTML, dependencies và chức năng chính từ PDF được giữ lại.
