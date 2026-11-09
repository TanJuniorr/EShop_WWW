# eShop — chuẩn bị tìm kiếm sản phẩm bằng vector

Giai đoạn 1 · 09/11/2026. Compose chỉ chạy Qdrant cho môi trường phát triển trên máy cá nhân. eShop vẫn chạy trên host, dùng MariaDB và tìm kiếm theo tên như hiện tại. Chưa tạo collection, gọi Gemini hay ghi nhận hành vi người dùng.

## 1. Hiện trạng eShop

| Thành phần | Kết quả rà soát |
| --- | --- |
| Build | Maven, `com.example:eshop:0.0.1-SNAPSHOT`, Spring Boot parent và Maven plugin **3.2.12**. POM đặt Java **17**; không có Maven Wrapper. Máy có Maven **3.10.0**, JDK **17.0.12** và **21.0.6**; IntelliJ cấu hình JDK 21. Có thể chọn JDK 17 khi chạy Maven để đối chiếu đúng mức Java của POM. |
| Web | Spring MVC, Thymeleaf **3.1.2.RELEASE** và dialect Spring Security 6, CSS tĩnh. Web có danh sách/chi tiết sản phẩm, tìm theo tên/danh mục, đăng ký/đăng nhập, giỏ hàng trong session, checkout, thanh toán chuyển khoản giả lập, lịch sử đơn và trang quản trị sản phẩm, danh mục, người dùng, đơn hàng. |
| Bảo mật | Spring Security **6.2.8**, JJWT **0.12.6** (`api`, `impl`, `jackson`), BCrypt. Web dùng JWT cookie, session và CSRF; API dùng Bearer JWT và stateless. API hiện có đăng nhập và đọc/CRUD sản phẩm; quyền quản trị là `SELLER`, giỏ hàng và đơn cá nhân là `CUSTOMER`. |
| Dữ liệu | Spring Data JPA, Hibernate **6.4.10.Final**, MariaDB Connector/J **3.3.3** ở runtime, Bean Validation. SQL mô tả các bảng `category`, `product`, `users`, `orders`, `order_detail`. |
| Công cụ và kiểm thử | Lombok **1.18.36**, DevTools, Spring Boot Starter Test, Spring Security Test. Bộ test hiện tại là `OrderServiceTest` với Mockito. Chưa có Spring AI, Qdrant client, migration có phiên bản hoặc User Tracking trong mã nguồn. Các phiên bản không khai báo trực tiếp lấy từ BOM của Boot. |

Mã Java trong `src/main/java/com/example/eshop` chia thành `entity`, `repository`, `service`, `controller` (MVC và admin), `api` (REST), `cart`, `security`, `dto` và `exception`. View nằm trong `src/main/resources/templates`; cấu hình chính ở [application.properties](../src/main/resources/application.properties).

- Datasource là `jdbc:mariadb://localhost:3306/eshop`. Username đọc từ `ESHOP_DB_USER`, mặc định `root`; `ESHOP_DB_PASSWORD` hiện có giá trị dự phòng trong file cấu hình. Hãy truyền thông tin xác thực qua môi trường tiến trình hoặc Run Configuration của IntelliJ. Nên bỏ giá trị dự phòng trong một thay đổi cấu hình riêng sau này. `ESHOP_JWT_SECRET` là biến riêng cho JWT; nếu bỏ trống, app sinh khóa tạm và cookie cũ mất hiệu lực khi restart.
- `ddl-auto=none`: Hibernate không tạo hay cập nhật schema. `open-in-view=true` hỗ trợ đọc quan hệ LAZY trong view, không áp dụng cho worker chạy nền. eShop dùng cổng **8081**; MariaDB dùng **3306**.
- [database/eShop.sql](../database/eShop.sql) có `DROP DATABASE IF EXISTS eshop`; không dùng file này để cập nhật cơ sở dữ liệu đang sử dụng. Phiên rà soát không kết nối được tới cổng TCP **3306** trên loopback, nên xác nhận ở mức cấu hình/mã nguồn, chưa xác minh đăng nhập, phiên bản server hay schema thực tế. Không chạy app, SQL khởi tạo hay lệnh ghi MariaDB. Kiểm thử Maven offline chưa chạy được do cache thiếu dependency của compiler plugin.

### Luồng sản phẩm và điểm tích hợp

| Lớp | Hiện trạng và hệ quả cho tích hợp |
| --- | --- |
| [Product](../src/main/java/com/example/eshop/entity/Product.java) | `Long id` tương ứng `BIGINT AUTO_INCREMENT`; tên, mô tả, giá `BigDecimal`, tồn kho, ảnh, danh mục LAZY và `createdAt`. Không có `updatedAt`; không thể dùng `createdAt` để tìm sản phẩm vừa sửa. |
| [ProductRepository](../src/main/java/com/example/eshop/repository/ProductRepository.java) | `search` dùng `LOWER(name) LIKE …` và điều kiện danh mục tùy chọn, trả `Page<Product>`. `findByIdForUpdate` khóa dòng khi checkout. Worker nên dùng truy vấn phân lô lấy DTO có sẵn danh mục; không dùng khóa dành cho checkout. |
| [ProductService](../src/main/java/com/example/eshop/service/ProductService.java) | Chuẩn hóa từ khóa, giới hạn cỡ trang 1–100, sắp xếp `id DESC`. Web admin và API đều gọi `save`/`delete`, nên đây là điểm bắt thay đổi sản phẩm. `save` đã có transaction; `delete` hiện gọi repository và xử lý lỗi khóa ngoại từ `order_detail`. |
| [ShopController](../src/main/java/com/example/eshop/controller/ShopController.java) | `GET /` và `GET /products` nhận `keyword`, `categoryId`, `page`; trang Thymeleaf dựa vào `Page<Product>` với 12 mục/trang. Chi tiết `/products/{id}` đọc MariaDB. Kết quả vector top-K không có tổng số trang tương đương: khi thử nghiệm cần giới hạn kết quả hoặc dùng view model riêng. Giữ nguyên hợp đồng của danh sách thường, admin và API hiện tại. |

## 2. Chạy Qdrant trên Docker Desktop

Mở Docker Desktop ở chế độ Linux containers. Tại thư mục gốc eShop, chạy trong PowerShell:

```powershell
docker compose version
docker info --format '{{.ServerVersion}} {{.OSType}}'
docker compose config -q
docker compose up -d qdrant
docker compose ps qdrant
docker compose logs --tail 100 qdrant
curl.exe -fsS http://127.0.0.1:6333/readyz
curl.exe -fsS http://127.0.0.1:6333/collections
Test-NetConnection 127.0.0.1 -Port 6334
```

- REST và [dashboard](http://127.0.0.1:6333/dashboard) dùng **6333**. `/readyz` phải trả HTTP 200. Danh sách collection trống là bình thường khi chạy lần đầu.
- Spring AI kết nối **gRPC 6334**; `Test-NetConnection` chỉ kiểm tra TCP, chưa kiểm tra giao thức hay thao tác vector.
- [docker-compose.yml](../docker-compose.yml) ghim image [Qdrant v1.19.2](https://github.com/qdrant/qdrant/releases/tag/v1.19.2), bind hai cổng vào `127.0.0.1`. Named volume `qdrant_storage` gắn tại `/qdrant/storage`; Compose thêm tiền tố tên project. Chạy từ cùng thư mục với cùng tên project để dùng lại volume. Đây là cấu hình local, không đặt API key hay TLS.

Dừng/tái khởi động và giữ dữ liệu:

```powershell
docker compose stop qdrant
docker compose start qdrant
```

`docker compose down` bỏ container và network của Compose, giữ named volume. Sau đó dùng lại `docker compose up -d qdrant`. Tránh tùy chọn `--volumes` (`-v`) khi chạy `down`; tránh xóa hoặc prune volume để khắc phục lỗi.

Nếu `docker info` báo `permission denied` tại pipe `dockerDesktopLinuxEngine`, kiểm tra Docker Desktop đã mở, Linux Engine đã sẵn sàng và terminal Windows hiện tại có quyền truy cập engine; chỉ chạy `up` khi `docker info` trả phiên bản server. Nếu báo cổng đã bị chiếm, xem tiến trình đang nghe bằng `Get-NetTCPConnection -State Listen -LocalPort 6333,6334` và kiểm tra container đang dùng cổng trước khi thay đổi cấu hình. Trong lần rà soát này, Compose **v5.7.0** đã kiểm tra cấu hình tĩnh thành công nhưng truy cập Engine bị từ chối. Chưa khởi động container hoặc xác minh HTTP/gRPC; không thay đổi WSL hay BIOS.

## 3. Tương thích và quản lý cấu hình cho giai đoạn 2

Không có xung đột cổng mặc định giữa eShop, MariaDB và Qdrant. Trở ngại chính là **Spring Boot 3.2.12 không thuộc dải tương thích của Spring AI 1.1 hoặc 2.x**. Việc thêm BOM hoặc ép riêng phiên bản Spring Framework vào Boot 3.2 không giải quyết được yêu cầu tương thích.

| Phương án | Phiên bản đã đối chiếu |
| --- | --- |
| Giữ dòng Boot 3 để thử nghiệm với ít thay đổi hơn | **Spring AI 1.1.8 GA** có trên Maven Central, có starter Google GenAI Embedding và Qdrant. Bản phát hành dùng Boot **3.5.15**; có thể chọn **3.5.16** cùng dòng để thử nghiệm với Java 17. Đây là bản OSS cuối của Boot 3.5, nên chỉ dùng làm bước chuyển hoặc khi có hỗ trợ phù hợp. |
| Đích ưu tiên nếu tiếp tục phát triển lâu dài | **Spring AI 2.0.2 GA** dùng **Spring Boot 4.1.2** theo POM của bản phát hành. Tách việc nâng Boot thành một thay đổi riêng, kiểm tra tương thích Security 7, Thymeleaf security dialect, JPA và JSON/JJWT trước khi thêm AI. Giữ hành vi HTTP và phân quyền hiện tại. |

Khi đã chọn cặp phiên bản, dùng BOM `org.springframework.ai:spring-ai-bom` và hai starter `spring-ai-starter-model-google-genai-embedding`, `spring-ai-starter-vector-store-qdrant`. Kiểm tra `mvn dependency:tree` sau khi thêm để phát hiện lệch phiên bản Google SDK, gRPC, Protobuf và Jackson. Đưa cấu hình AI vào profile thử nghiệm; profile mặc định không yêu cầu key và không khởi tạo vector store.

POM Spring AI 2.0.2 dùng Qdrant Java client **1.18.0**, lệch một minor so với server **1.19.2** trong Compose, theo [khuyến nghị tương thích của Qdrant](https://qdrant.tech/documentation/faq/qdrant-fundamentals/#do-you-guarantee-compatibility-across-versions). [Spring AI 1.1.8](https://github.com/spring-projects/spring-ai/blob/v1.1.8/pom.xml) dùng client **1.13.0**; nếu chọn phương án Boot 3, kiểm tra và đồng bộ phiên bản client/server trước khi tích hợp, rồi thử lại gRPC và thao tác collection. Không hạ phiên bản server trên volume đã được dùng bởi phiên bản mới hơn.

- Lấy key Gemini từ biến môi trường riêng, ví dụ `GEMINI_API_KEY`; trong profile AI sau này tham chiếu bằng `spring.ai.google.genai.embedding.api-key=${GEMINI_API_KEY}`. Docker Compose có thể đọc `.env` để nội suy, nhưng Spring Boot chạy trên host không tự nạp file đó. Dùng biến môi trường hoặc Run Configuration, không commit key, JWT secret hoặc mật khẩu DB. Nếu bổ sung xác thực Qdrant sau này, quản lý key Qdrant riêng.
- Đặt tên model rõ ràng. Mốc thử nghiệm đơn giản là **`gemini-embedding-001`** (GA, văn bản, hỗ trợ tiếng Việt và task retrieval), chẳng hạn **768 chiều** với cosine. Mẫu Spring AI 1.1 dùng `text-embedding-004`, Google đã ngừng model đó. Với Spring AI 1.1, cấu hình model/kích thước qua `spring.ai.google.genai.embedding.text.options.model` / `…options.dimensions`; với 2.0 dùng nhóm `…embedding.text` theo tài liệu phiên bản.
- Với `gemini-embedding-001`, dùng `RETRIEVAL_DOCUMENT` khi lập chỉ mục và `RETRIEVAL_QUERY` khi tìm. Kiểm thử hai đường gọi riêng: `QdrantVectorStore` 1.1.8 gọi `EmbeddingModel.embed(query)` với tùy chọn mặc định; muốn phân biệt task cần adapter chọn theo ngữ cảnh hoặc tạo query vector riêng rồi tìm bằng Qdrant client. `gemini-embedding-2` cũng đã GA, dùng hướng dẫn trong input thay cho `task_type`; thử riêng trước khi chọn cho sản phẩm. Hai model có không gian vector khác nhau.
- Dùng Qdrant host `127.0.0.1`, gRPC **6334**, TLS tắt cho Compose local. Tạo collection có phiên bản, ví dụ `eshop_products_gemini001_768_v1`, với vector không tên, 768 chiều và cosine. Xác nhận chiều thực tế từ API trước khi tạo. Nên tạo collection qua bước setup tường minh và giữ `spring.ai.vectorstore.qdrant.initialize-schema=false` để không phụ thuộc vào Qdrant/Gemini khi khởi động. Nếu đổi model, số chiều hoặc cách tạo văn bản, lập chỉ mục vào collection mới trước khi chuyển sang dùng collection đó.

## 4. Đề xuất đồng bộ theo product ID

MariaDB là nguồn dữ liệu chính. Mỗi sản phẩm có một point trong mỗi collection, payload gồm `product_id` và `category_id` dạng số, cùng phiên bản hoặc hash của nội dung đã index. Văn bản embedding gồm tên, mô tả (bỏ qua nếu trống) và tên danh mục. Đọc giá, tồn kho và thông tin hiển thị từ MariaDB khi trả kết quả; không gửi đơn hàng, dữ liệu người dùng hoặc thông tin xác thực tới Gemini.

Qdrant hỗ trợ ID số hoặc UUID; riêng adapter Spring AI được kiểm tra ở cả 1.1.8 và 2.0.2 dùng `UUID.fromString` khi thêm/xóa. Với adapter này, ánh xạ ổn định từ ID gốc, ví dụ `UUID.nameUUIDFromBytes(("eshop:product:" + productId).getBytes(StandardCharsets.UTF_8))`. Dùng cùng công thức ở mọi worker và lưu ID gốc trong payload để truy vấn MariaDB. Không tạo UUID ngẫu nhiên mỗi lần lưu.

| Tình huống | Xử lý đề xuất |
| --- | --- |
| Backfill | Đọc DTO theo lô với `id > lastSeenId` và `ORDER BY id`, lấy sẵn tên danh mục trong cùng truy vấn/transaction đọc. Gửi theo lô, kiểm soát quota, lưu tiến độ sau khi Qdrant xác nhận. Cho worker xử lý tuần tự theo ID hoặc chạy bù các thay đổi ghi nhận trong lúc backfill trước khi bật tìm kiếm. |
| Thêm | Lấy ID do MariaDB cấp sau khi lưu. Ghi công việc index trong cùng transaction MariaDB; worker chỉ thấy việc đã commit, đọc sản phẩm mới nhất, tạo vector và upsert theo UUID ổn định. |
| Sửa | Dùng cùng UUID để ghi đè point. Hash nội dung chuẩn hóa để tránh gọi Gemini khi chỉ đổi giá/tồn kho/ảnh; nếu chỉ metadata thay đổi thì cập nhật payload tương ứng. Khi đổi tên danh mục, lên lịch index lại các sản phẩm liên quan nếu tên danh mục nằm trong embedding. |
| Xóa | Xóa point theo UUID sau khi MariaDB commit thành công. Nếu khóa ngoại từ `order_detail` ngăn xóa, giữ point. Nếu thêm transaction ở `ProductService.delete` để ghi outbox, chủ động flush bên trong vùng xử lý lỗi để duy trì thông báo khóa ngoại hiện tại. |

Để đồng bộ bền vững, dùng bảng **transactional outbox** với migration riêng trong giai đoạn sau. Worker có retry với backoff, giới hạn số lần thử và ghi lỗi; xử lý tuần tự theo product ID và đọc lại trạng thái mới nhất thay vì tin nội dung cũ trong event. Nếu sản phẩm đã biến mất, xóa point, kể cả khi event ban đầu là cập nhật. Chạy đối soát định kỳ để sửa point lệch hoặc mồ côi; không dùng `createdAt` để phát hiện sửa/xóa. Checkout và hủy/xóa đơn hiện đổi tồn kho qua JPA mà không đi qua `ProductService.save`; đọc tồn kho trực tiếp từ MariaDB giúp tránh phải chạm luồng đặt hàng.

## 5. Thứ tự thực hiện khi bắt đầu giai đoạn 2

1. Xác nhận Qdrant sẵn sàng và baseline eShop trên môi trường thử nghiệm có MariaDB riêng. Nâng phiên bản Boot trong thay đổi độc lập; kiểm thử phân quyền web/API, CSRF, giỏ hàng, đặt/hủy đơn và truy vấn sản phẩm trước khi thêm starter AI.
2. Bật profile AI và thử embedding với văn bản sản phẩm tiếng Việt; xác minh model, số chiều, gRPC và thao tác upsert/tìm/xóa trên collection thử. Sau đó thêm backfill và đồng bộ outbox. Kiểm tra các ca rollback, xóa bị khóa ngoại, retry trùng, sửa liên tiếp, đổi danh mục và worker khởi động lại.
3. Thử tìm kiếm vector trong trang shop sau cờ tính năng. Dùng top-K và lọc danh mục trong Qdrant; đọc các ID còn tồn tại từ MariaDB, kiểm tra lại danh mục nếu cần và giữ thứ tự theo điểm. Quay về truy vấn từ khóa khi profile tắt, thiếu từ khóa hoặc Gemini/Qdrant lỗi. Đánh giá câu tìm kiếm tiếng Việt và truy vấn chính xác tên/mã trước khi đổi trải nghiệm mặc định.
4. Thiết kế User Tracking riêng: sự kiện tìm kiếm, xem và nhấp sản phẩm; lưu tách khỏi chỉ mục, giới hạn trường và thời gian lưu, không gửi dữ liệu tracking sang Gemini. Chưa cần thay đổi auth, giỏ hoặc checkout để thử tìm kiếm vector.

Nguồn đối chiếu: [tương thích Spring AI/Boot](https://github.com/spring-projects/spring-ai/tree/v1.1.8#spring-boot-version-compatibility), [Spring AI 1.1.8 GA](https://spring.io/blog/2026/06/12/spring-ai-1-1-8-1-0-9-avaialble-now/), [Boot 3.5.16 và vòng đời OSS](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/), [POM Spring AI 2.0.2](https://github.com/spring-projects/spring-ai/blob/v2.0.2/pom.xml), [Google GenAI trong Spring AI 1.1](https://docs.spring.io/spring-ai/reference/1.1/api/embeddings/google-genai-embeddings-text.html), [Google GenAI trong Spring AI 2.x](https://docs.spring.io/spring-ai/reference/api/embeddings/google-genai-embeddings-text.html), [Gemini Embeddings](https://ai.google.dev/gemini-api/docs/embeddings), [vòng đời model Gemini](https://ai.google.dev/gemini-api/docs/deprecations), [Qdrant Quickstart](https://qdrant.tech/documentation/quickstart/), [Qdrant readiness](https://api.qdrant.tech/api-reference/service/readyz), [adapter Qdrant 1.1.8](https://github.com/spring-projects/spring-ai/blob/v1.1.8/vector-stores/spring-ai-qdrant-store/src/main/java/org/springframework/ai/vectorstore/qdrant/QdrantVectorStore.java), [adapter Qdrant 2.0.2](https://github.com/spring-projects/spring-ai/blob/v2.0.2/vector-stores/spring-ai-qdrant-store/src/main/java/org/springframework/ai/vectorstore/qdrant/QdrantVectorStore.java).
