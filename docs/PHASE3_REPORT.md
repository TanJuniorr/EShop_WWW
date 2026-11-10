# Báo cáo giai đoạn 3 — Gemini Embedding và kiểm tra Qdrant

Ngày 09/11/2026. eShop dùng Spring Boot **4.1.2**, Spring AI **2.0.2** và Java **17**. Tính năng chẩn đoán chạy theo profile `ai-dev` và dùng quyền SELLER sẵn có.

## Dependencies

| Dependency | Vai trò |
| --- | --- |
| `org.springframework.ai:spring-ai-starter-model-google-genai-embedding` | Starter chính thức, lấy phiên bản **2.0.2** từ Spring AI BOM. Kéo Google GenAI SDK **1.65.0**. |
| `org.springframework.ai:spring-ai-starter-vector-store-qdrant` | Starter chính thức, lấy phiên bản **2.0.2** từ BOM. Kéo Qdrant Java client **1.18.0**. |
| `io.grpc:grpc-okhttp` | Transport gRPC **1.84.1** do Spring Boot quản lý. Thay transport Netty của starter Qdrant vì Netty không mở được selector trên Windows trong phiên kiểm tra. |

## Cấu hình và cách kiểm tra

Đặt `GEMINI_API_KEY` trong biến môi trường của tiến trình hoặc Run Configuration của IntelliJ. Có thể nhập ẩn trên PowerShell 7 rồi chạy profile phát triển:

```powershell
$env:GEMINI_API_KEY = Read-Host -MaskInput "Gemini API key"
mvn spring-boot:run "-Dspring-boot.run.profiles=ai-dev"
```

Dùng token của SELLER từ `POST /api/auth/login` trong header `Authorization: Bearer <token>`.

| Endpoint | Hành vi |
| --- | --- |
| `POST /api/dev/ai/embedding` | Nhận JSON `{"text":"Laptop phù hợp cho sinh viên lập trình"}`. Trả tên model và số chiều sau khi kiểm tra vector. Văn bản rỗng hoặc dài hơn 1.000 ký tự trả 400. Thiếu key trả 503. Lỗi từ Gemini hoặc vector không hợp lệ trả 502 với thông báo đã loại chi tiết nhạy cảm. |
| `GET /api/dev/ai/qdrant` | Gọi gRPC `HealthCheck` tới `localhost:6334`, deadline 3 giây. Trả trạng thái và phiên bản server hoặc 503 nếu không kết nối được. |

Profile mặc định không đăng ký endpoint hay client AI. Ngay cả trong `ai-dev`, hai client được tạo khi cần và kiểm tra Qdrant độc lập với Gemini. Cấu hình giữ tắt khởi tạo collection. Chức năng hiện tại tiếp tục dùng MariaDB, kể cả khi thiếu key hoặc Qdrant không truy cập được.

## Kết quả Gemini

Spring AI 2.0.2 khai báo trực tiếp `GEMINI_EMBEDDING_001`. Adapter chuyển `dimensions=768` thành `outputDimensionality` khi gọi Google SDK. Service kiểm tra chiều dài thực tế của mảng thay vì dựa vào `EmbeddingModel.dimensions()`, vì adapter 2.0.2 trả kích thước mặc định của model từ bảng tra cứu. Model đã ngừng `text-embedding-004` không được cấu hình. [Mã nguồn model Spring AI 2.0.2](https://github.com/spring-projects/spring-ai/blob/v2.0.2/models/spring-ai-google-genai-embedding/src/main/java/org/springframework/ai/google/genai/text/GoogleGenAiTextEmbeddingModel.java) · [Gemini Embeddings](https://ai.google.dev/gemini-api/docs/embeddings)

| Văn bản kiểm thử thực tế | Kết quả |
| --- | --- |
| Laptop phù hợp cho sinh viên lập trình | HTTP **200**, `gemini-embedding-001`, **768** chiều |
| Bàn phím gọn nhẹ để mang đi học | HTTP **200**, `gemini-embedding-001`, **768** chiều |

Cả hai vector đều có giá trị hữu hạn và ít nhất một phần tử khác 0. Request đi qua Spring MVC, xác thực SELLER và `EmbeddingModel` thật. Key được nhập ẩn vào môi trường của tiến trình thử, không lưu vào project hay phản hồi chẩn đoán.

## Qdrant, MariaDB và hồi quy

- Container Qdrant đang chạy báo phiên bản **1.20.0**. REST `/readyz` trả HTTP 200. Kiểm tra gRPC ban đầu trả 503 vì Netty gặp lỗi selector loopback trên Windows. Sau khi chuyển sang gRPC OkHttp, endpoint với SELLER trả **HTTP 200**, `status=UP`, `version=1.20.0`. Request không xác thực trả **401**. Smoke test chỉ gọi health check.
- eShop kết nối thành công tới **MariaDB 12.2.2**. `GET /api/products?size=1` trả HTTP 200 và có sản phẩm trong phiên kiểm tra chỉ đọc, với `ddl-auto=none`. Test hồi quy thực hiện thao tác ghi trong H2.
- Maven `compile` và `verify` thành công trên JDK **17.0.12**. **11/11 test đạt**, gồm các kịch bản eShop hiện có, 404 với endpoint AI ở profile mặc định, 401/403 với tài khoản không có quyền, lỗi thiếu key, Qdrant lỗi và kiểm tra hình dạng vector. Maven lưu cây dependency trong `target/phase3-dependencies.txt`. Cây verbose có các phiên bản bắc cầu bị Maven loại, gồm Guava, Okio và ANTLR. Build và smoke test đều chạy thành công với các phiên bản được chọn.

## File thay đổi

- [pom.xml](../pom.xml) thêm hai starter và transport gRPC.
- [application.properties](../src/main/resources/application.properties) tắt auto-configuration AI cần thiết để khởi động độc lập.
- [application-ai-dev.properties](../src/main/resources/application-ai-dev.properties) nhận key từ môi trường và cấu hình host, cổng gRPC của Qdrant.
- [AiDevConfiguration.java](../src/main/java/com/example/eshop/ai/AiDevConfiguration.java) cấu hình client theo profile và model.
- [ProductEmbeddingService.java](../src/main/java/com/example/eshop/ai/ProductEmbeddingService.java) tạo và xác thực vector qua Spring AI.
- [AiDiagnosticsController.java](../src/main/java/com/example/eshop/ai/AiDiagnosticsController.java) cung cấp endpoint giới hạn cho SELLER.
- [AiDiagnosticsTest.java](../src/test/java/com/example/eshop/ai/AiDiagnosticsTest.java) và [EshopRegressionTest.java](../src/test/java/com/example/eshop/EshopRegressionTest.java) kiểm tra profile, phân quyền, kết quả vector và hành vi khi thiếu dịch vụ.
- [PHASE3_REPORT.md](PHASE3_REPORT.md) ghi lại cách chạy và kết quả.

## Giới hạn và đề xuất giai đoạn 4

Tomcat trong tiến trình thử gặp lỗi mở loopback trên Windows. Smoke test sử dụng MockMvc để chạy DispatcherServlet và bộ lọc Security trong cùng tiến trình, với các kết nối Gemini, MariaDB và Qdrant thật. Kiểm tra này không xác nhận việc mở cổng HTTP của một tiến trình Tomcat mới trong cùng môi trường.

Starter Gemini ban đầu cố khởi tạo kết nối Vertex AI khi thiếu key. Đã loại auto-configuration của kết nối đó và kiểm tra lại cả profile mặc định lẫn `ai-dev` không có key.

Qdrant client **1.18.0** và container đang chạy **1.20.0** lệch hai minor. Trước khi ghi dữ liệu ở giai đoạn 4, xác nhận cặp phiên bản nằm trong phạm vi tương thích Qdrant. File Compose hiện ghim **1.19.2**. Chọn phiên bản phù hợp với volume hiện có, vì hạ phiên bản server trên volume đã được phiên bản mới dùng không được hỗ trợ. [Hướng dẫn tương thích Qdrant](https://qdrant.tech/documentation/faq/qdrant-fundamentals/#do-you-guarantee-compatibility-across-versions)

Giai đoạn 4 nên tạo collection có phiên bản với **768** chiều và cosine. Lấy sản phẩm theo lô từ MariaDB, dùng UUID ổn định sinh từ `product_id`, rồi upsert sau khi transaction MariaDB commit. Xử lý xóa và thử lại qua outbox. Đọc giá và tồn kho từ MariaDB khi hiển thị. Trước khi cố định chiến lược embedding, xác minh hỗ trợ `task_type` của adapter, vì mã nguồn 2.0.2 hiện không đưa tùy chọn đó vào cấu hình gọi Gemini. [Thiết kế đồng bộ giai đoạn 1](AI_SETUP.md)
