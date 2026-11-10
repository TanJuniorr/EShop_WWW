# Báo cáo giai đoạn 2 — nâng cấp nền tảng eShop

Ngày 09/11/2026. Bản nâng cấp biên dịch và đóng gói thành công bằng **Spring Boot 4.1.2**, **Java 17** và **Maven 3.10.0**. Cả **8 test** đều đạt. Lần chạy với MariaDB thật tới được máy chủ JDBC nhưng chưa xác thực thành công vì phiên chạy thiếu mật khẩu.

## 1. Phiên bản lựa chọn

- **Spring Boot 4.1.2** thay cho 3.2.12. Maven dùng JDK **17.0.12**, biên dịch với `release 17`. Spring Boot 4.1.2 hỗ trợ Java 17. [Yêu cầu hệ thống Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html)
- **Spring AI 2.0.2** được nhập qua `spring-ai-bom` trong `dependencyManagement`. POM của Spring AI 2.0.2 dùng Spring Boot 4.1.2. Bản JAR hiện tại chỉ có các thư viện của eShop, chưa có starter AI. [POM Spring AI 2.0.2](https://github.com/spring-projects/spring-ai/blob/v2.0.2/pom.xml)
- **Boot 3.5.x + Spring AI 1.1.8** là phương án đã đánh giá ở giai đoạn 1. Kết quả build và test trên Boot 4.1.2 chưa cho thấy cần quay về Boot 3.5, dòng đã kết thúc hỗ trợ OSS. [Thông báo vòng đời Boot 3.5](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)

## 2. Dependencies

| Thành phần | Phiên bản hoặc thay đổi được Maven giải quyết |
| --- | --- |
| Web MVC | Thay `spring-boot-starter-web` bằng `spring-boot-starter-webmvc`. Spring Framework **7.0.10**, Tomcat **11.0.27**. |
| Security | Spring Security và `spring-security-test` **7.1.2** theo BOM của Boot. |
| Thymeleaf | **3.1.5.RELEASE**, bao gồm `thymeleaf-extras-springsecurity6` cùng phiên bản. Các trang có `sec:authorize` và thông tin người đăng nhập đã qua test render. |
| JPA và MariaDB | Spring Data JPA **4.1.2**, Hibernate ORM **7.4.13.Final**, MariaDB Connector/J **3.5.10**. |
| JSON và JWT | MVC dùng Jackson **3.1.7** (`tools.jackson.core`). JJWT giữ **0.12.6** và dùng Jackson **2.21.7** (`com.fasterxml.jackson.core`) qua `jjwt-jackson`. Test đã đi qua cả JSON của API và ký/đọc JWT. |
| Công cụ và test | Lombok **1.18.48**, JUnit Jupiter **6.0.3**, H2 **2.4.240** với scope `test`. |

Đã kiểm tra cây dependency ở chế độ verbose. Maven không ghi nhận mục `omitted for conflict`. Kiểm tra nội dung JAR xác nhận H2, Spring AI và Qdrant client nằm ngoài gói runtime. [Hướng dẫn nâng cấp Spring Boot 4](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)

## 3. File thay đổi

| File | Nội dung |
| --- | --- |
| [pom.xml](../pom.xml) | Nâng Boot parent lên 4.1.2, chuyển starter MVC, nhập Spring AI BOM và thêm H2 cho kiểm thử. |
| [application.properties](../src/main/resources/application.properties) | Bỏ giá trị mật khẩu MariaDB dự phòng. Datasource lấy mật khẩu từ `ESHOP_DB_PASSWORD` và giữ `ddl-auto=none`. |
| [EshopRegressionTest.java](../src/test/java/com/example/eshop/EshopRegressionTest.java) | Thêm ba kịch bản hồi quy chạy qua MVC, Security, Thymeleaf và JPA với H2 trong bộ nhớ ở chế độ tương thích MariaDB. Dữ liệu kiểm thử được tạo trong H2. |
| [PHASE2_REPORT.md](PHASE2_REPORT.md) | Ghi lại phiên bản, kết quả kiểm thử và điều kiện chạy với MariaDB thật. |

`git status --short --branch` đã được chạy trước khi sửa và trả về `not a git repository`. Đối chiếu hash với bản rà soát trước cho thấy các file nghiệp vụ Java, template, SQL và Docker Compose giữ nguyên.

## 4. Lỗi gặp phải và cách xử lý

| Vấn đề | Kết quả |
| --- | --- |
| Sandbox không phân giải được Maven Central và hạn chế ghi vào cache Maven của người dùng | Chạy Maven có mạng, dùng thư mục cache ghi riêng trong thư mục tạm và đọc thêm cache đã có. Maven tải được dependencies cần thiết. Có cảnh báo timeout metadata ở Apache Snapshots, còn build qua Maven Central hoàn tất. |
| Assertion kiểm tra chuyển hướng đăng nhập thất bại ở lần đầu | Security 7 trả `/login` dạng tương đối cho khách truy cập `/cart`. Sửa assertion để kiểm tra đích chuyển hướng thực tế. Lần chạy cuối đạt. |
| Windows khóa log trong lần khởi chạy thử đầu tiên | Đổi cách đọc log để chia sẻ file với tiến trình Java đang ghi và chạy lại bản JAR. |
| MariaDB `1045`, SQLSTATE `28000` | Máy chủ ở cổng 3306 đã nhận kết nối nhưng từ chối xác thực vì phiên thử chưa có `ESHOP_DB_PASSWORD`. Thông báo Hibernate thiếu JDBC metadata xuất hiện sau lỗi xác thực. Cần cung cấp thông tin kết nối hiện có trong môi trường chạy để kiểm tra tiếp. |

## 5. Maven và kiểm thử chức năng

| Kiểm tra | Kết quả |
| --- | --- |
| `mvn clean compile` | Thành công. Biên dịch 41 file Java với JDK 17. |
| `mvn clean package` | Thành công. Tổng cộng 8 test, 0 lỗi, 0 thất bại, 0 bỏ qua. |
| `dependency:tree -Dverbose=true` | Thành công. Cây dependency ở `target/phase2-dependencies.txt`. |
| Chạy JAR với MariaDB thật | Connector/J đã tới được server. Khởi động dừng ở lỗi xác thực 1045. Chưa có kiểm tra HTTP thành công với MariaDB thật. |

| Chức năng | Kết quả kiểm thử |
| --- | --- |
| Đăng ký, đăng nhập web và API, đăng xuất | Đạt trên H2. Kiểm tra tạo CUSTOMER, JWT cookie HttpOnly, Bearer JWT, xóa cookie và hủy session khi đăng xuất. |
| CUSTOMER và SELLER | Đạt. CUSTOMER bị từ chối truy cập trang admin, SELLER bị từ chối truy cập giỏ. API ghi sản phẩm yêu cầu Bearer token và từ chối JWT cookie của web. |
| Danh sách, tìm theo tên/danh mục, chi tiết sản phẩm | Đạt. Thymeleaf render dữ liệu và truy vấn JPA thực thi `LIKE` trên H2. API vẫn trả nội dung trang và `totalElements` trong test. |
| Thêm, sửa, xóa sản phẩm | Đạt qua REST với SELLER. Trang danh sách quản trị render thành công. Các thao tác ghi sử dụng sản phẩm mẫu trên H2. |
| Giỏ hàng và đặt hàng | Đạt. POST thiếu CSRF bị chặn, giỏ duy trì qua session và checkout COD trừ đúng tồn kho. Test đơn vị kiểm tra giá lấy từ DB và từ chối khi thiếu hàng. |
| Quản lý đơn hàng | Đạt. CUSTOMER xem đơn của mình, SELLER xem danh sách/chi tiết và hủy đơn. Test xác nhận hoàn tồn kho, xóa đơn đang hoạt động và điều kiện thanh toán chuyển khoản giả lập. |
| MariaDB | Cổng mở và nhận được phản hồi xác thực từ MariaDB Connector/J. Chưa xác minh truy vấn hay ghi dữ liệu trên server thật. |

## 6. Việc còn lại và mức độ sẵn sàng cho AI

- Đặt `ESHOP_DB_USER` và `ESHOP_DB_PASSWORD` cho tài khoản MariaDB hiện có trong Run Configuration của IntelliJ hoặc môi trường terminal, rồi chạy `mvn spring-boot:run`. eShop mặc định dùng **8081**. Nếu cổng đã có dịch vụ, chạy thử bằng cổng riêng, ví dụ `mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=18081"`. Giữ `ddl-auto=none` và dùng các GET công khai để xác nhận catalog, tìm theo tên và chi tiết sản phẩm. [Hướng dẫn Qdrant giai đoạn 1](AI_SETUP.md)
- Spring Data cảnh báo việc tuần tự hóa trực tiếp `PageImpl` không cam kết cấu trúc JSON lâu dài. Test đã kiểm tra các trường hiện dùng. Việc đổi kiểu response cần đánh giá riêng để giữ tương thích với client.
- BOM AI đã giải quyết được trên Maven. Mức sẵn sàng hiện tại là **nền tảng build đã kiểm thử**, còn xác nhận khởi động với MariaDB thật và thử profile chứa starter Gemini Embedding/Qdrant là các điều kiện trước khi tích hợp. Khi bổ sung starter, cần xác minh profile mặc định khởi động được khi không có Gemini key hoặc Qdrant. Semantic Search và User Tracking nằm ngoài phạm vi bản nâng cấp này.
