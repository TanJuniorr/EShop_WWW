# Báo cáo giai đoạn 4 — đồng bộ MariaDB sang Qdrant

Ngày 09/11/2026. eShop giữ Spring Boot 4.1.2, Spring AI 2.0.2 và Java 17. MariaDB là nguồn dữ liệu chính. Qdrant là chỉ mục có thể đồng bộ lại.

## Kiến trúc

1. Trang quản trị và REST API đều ghi sản phẩm qua `ProductService`. Service phát sự kiện trong transaction. Khi xóa, Hibernate flush trong cùng transaction để tiếp tục báo lỗi khóa ngoại nếu sản phẩm thuộc đơn hàng. Đổi tên danh mục qua `CategoryService` phát sự kiện cho các sản phẩm liên quan.
2. Listener chỉ nhận sự kiện **sau commit** và đưa công việc vào hàng đợi giới hạn 128 mục. Worker đọc trạng thái mới nhất bằng truy vấn projection gồm ID, tên, mô tả và danh mục. Worker và tác vụ thủ công được tuần tự hóa theo ID trong cùng tiến trình.
3. Nếu sản phẩm tồn tại, service so SHA-256 của model, số chiều, ID danh mục và văn bản chuẩn hóa. Hash trùng thì bỏ qua trước khi gọi Gemini. Khi hash khác, service dùng `ProductEmbeddingService` để tạo và kiểm tra vector 768 chiều, rồi upsert theo UUID cố định.
4. Nếu sản phẩm đã vắng mặt trong MariaDB, service xóa đúng point có UUID và metadata sở hữu tương ứng. Nếu point hoặc collection chưa có, thao tác trả `SKIPPED`. Nếu UUID trùng một point có metadata khác, service trả 409.

## Collection và metadata

Collection `eshop_products_v1` dùng **một vector không tên**, **768 chiều**, khoảng cách **Cosine**. Service kiểm tra tồn tại trước khi tạo và xác nhận cấu hình cả với collection mới lẫn collection đã có. Cấu hình sai trả 409 và yêu cầu quản trị xử lý. Client **1.19.0** dùng cùng kết nối gRPC OkHttp ở giai đoạn 3 và nằm cách server hiện tại 1.20.0 một minor. Tích hợp dùng API Qdrant Java để kiểm tra schema tường minh, đọc hash và đặt deadline cho từng RPC. Adapter Spring AI của Gemini 2.0.2 có thể báo chiều mặc định 3072 qua `dimensions()`, nên không dùng nó để tự tạo collection. [Qdrant Java client](https://github.com/qdrant/java-client/releases/tag/v1.19.0) · [Tương thích Qdrant](https://qdrant.tech/documentation/faq/qdrant-fundamentals/#do-you-guarantee-compatibility-across-versions)

Mỗi point dùng UUID sinh từ UTF-8 `eshop:product:<product_id>`. Payload chứa `source`, `product_id`, `product_name`, `category_id` nếu có, `category_name`, `doc_content`, `content_hash` và `embedding_model`. Tên, mô tả hoặc tên danh mục null được chuẩn hóa thành chuỗi trống. Nội dung gửi Gemini gồm tên, danh mục và mô tả công khai. Giá, tồn kho và dữ liệu khách hàng không nằm trong nội dung hoặc payload.

## Vận hành và đồng bộ lại

Bật profile `ai-dev` và cung cấp `GEMINI_API_KEY` trong môi trường của tiến trình. Dùng Bearer token của SELLER. Hai endpoint ghi vào chỉ mục:

| Endpoint | Kết quả |
| --- | --- |
| `POST /api/dev/ai/products/sync` | Backfill thủ công theo trang ID tăng dần. Trả `succeeded`, `upserted`, `deleted`, `skipped`, `failed` và tối đa 100 chi tiết lỗi. Mỗi tiến trình chỉ chạy một backfill toàn bộ tại một thời điểm. |
| `POST /api/dev/ai/products/{id}/sync` | Đọc lại một ID và trả `UPSERTED`, `DELETED` hoặc `SKIPPED`. Dùng endpoint này để thử lại ID đã lỗi, kể cả sản phẩm đã xóa. |

`eshop.ai.product-sync.batch-size` mặc định 20, giới hạn 1–100 ID mỗi trang. Các trang giới hạn bộ nhớ khi đọc DB. Trong mỗi trang, Gemini được gọi tuần tự cho từng nội dung mới. Backfill chỉ quét tới ID lớn nhất tại lúc bắt đầu và chạy khi SELLER yêu cầu. `eshop.ai.product-sync.auto-enabled=false` tắt listener nếu chỉ muốn đồng bộ thủ công trong `ai-dev`.

`eshop.ai.product-sync.max-attempts` mặc định 3, giới hạn 1–3. Lỗi tạm thời như Gemini 429/5xx và Qdrant unavailable/deadline được thử lại với khoảng nghỉ hữu hạn. Thiếu key, lỗi xác thực và schema không tương thích cần sửa cấu hình trước khi thử lại. Log có ID và lý do đã rút gọn. Công việc đồng bộ chạy ngoài transaction ghi MariaDB.

Hàng đợi trong bộ nhớ có thể mất công việc khi tiến trình dừng đột ngột hoặc từ chối khi đầy. Chạy lại backfill để sửa các sản phẩm còn tồn tại. Backfill không quét xóa point mồ côi. Với ID đã xóa, chạy lại endpoint một sản phẩm. Nếu cần phục hồi tự động qua restart hoặc nhiều instance, bước tiếp theo là outbox bền vững và worker có điều phối.

## Kết quả xác minh

| Kiểm tra với dịch vụ thật | Kết quả |
| --- | --- |
| MariaDB | **50 sản phẩm**. Kết nối ở chế độ chỉ đọc, `ddl-auto=none`. |
| Qdrant trước kiểm tra | Server **1.20.0**. Collection đích chưa tồn tại. |
| Đồng bộ một sản phẩm hiện có | Lần đầu **HTTP 200 / UPSERTED** qua Spring MVC, Gemini thật và Qdrant gRPC. |
| Đồng bộ lại cùng ID | **HTTP 200 / SKIPPED**. |
| Qdrant sau kiểm tra | `eshop_products_v1` có **1 point** theo phép đếm chính xác, **768 chiều / Cosine**. |

Smoke test dùng Spring MVC trong tiến trình kiểm thử, với dịch vụ thật ở cả ba đầu. Đã kiểm chứng một sản phẩm và khả năng chạy lại không trùng. Backfill toàn bộ 50 sản phẩm là thao tác thủ công, chưa được kích hoạt trong smoke test. Kiểm thử tạo, sửa, xóa và rollback dùng H2 hoặc mock. API key cục bộ được chuyển ra khỏi file cấu hình nguồn và được dùng trong môi trường của tiến trình smoke test.

Maven `compile` và `verify` thành công với Java 17. **17/17 test đạt**, gồm hồi quy eShop, tạo point, sửa tên/mô tả/danh mục, bỏ qua khi nội dung lập chỉ mục không đổi, xóa point thuộc eShop, chạy lại không trùng, thử lại khi Gemini 429 hoặc Qdrant gián đoạn, phục hồi sau lỗi và từ chối collection sai chiều. Test H2 xác nhận cả tạo và xóa bị rollback đều không kích hoạt công việc đồng bộ.

## File thay đổi

- [pom.xml](../pom.xml) khóa phiên bản Qdrant Java client tương thích. [application-ai-dev.properties](../src/main/resources/application-ai-dev.properties) đọc Gemini key từ biến môi trường.
- [ProductService.java](../src/main/java/com/example/eshop/service/ProductService.java), [CategoryService.java](../src/main/java/com/example/eshop/service/CategoryService.java) và [CatalogIndexChange.java](../src/main/java/com/example/eshop/service/CatalogIndexChange.java) phát sự kiện trong transaction.
- [ProductRepository.java](../src/main/java/com/example/eshop/repository/ProductRepository.java) và [ProductIndexSnapshot.java](../src/main/java/com/example/eshop/repository/ProductIndexSnapshot.java) cung cấp truy vấn chỉ mục.
- [QdrantProductIndex.java](../src/main/java/com/example/eshop/ai/QdrantProductIndex.java), [ProductVectorSyncService.java](../src/main/java/com/example/eshop/ai/ProductVectorSyncService.java), [ProductVectorSyncListener.java](../src/main/java/com/example/eshop/ai/ProductVectorSyncListener.java) và [ProductVectorSyncController.java](../src/main/java/com/example/eshop/ai/ProductVectorSyncController.java) triển khai đồng bộ, retry và endpoint.
- [ProductEmbeddingService.java](../src/main/java/com/example/eshop/ai/ProductEmbeddingService.java) phân loại lỗi Gemini để retry phù hợp.
- [ProductVectorSyncServiceTest.java](../src/test/java/com/example/eshop/ai/ProductVectorSyncServiceTest.java) và [AiDiagnosticsTest.java](../src/test/java/com/example/eshop/ai/AiDiagnosticsTest.java) kiểm tra vector, lỗi dịch vụ và ranh giới commit/rollback.

## Đề xuất giai đoạn 5

Tạo vector truy vấn bằng cùng model và số chiều, đánh giá cấu hình truy vấn và tài liệu bằng tập câu hỏi tiếng Việt. Dùng Qdrant trả top-K ID và điểm, sau đó đọc danh mục, giá, tồn kho và thông tin hiển thị từ MariaDB. Bỏ kết quả có ID đã mất hoặc không còn khớp bộ lọc. Giữ tìm SQL theo tên làm đường dự phòng khi chỉ mục chưa đầy hoặc dịch vụ AI lỗi. Tách cấu hình tìm kiếm bằng cờ tính năng và kiểm tra thứ tự, độ trễ, tỷ lệ phủ chỉ mục trước khi mở trên giao diện.
