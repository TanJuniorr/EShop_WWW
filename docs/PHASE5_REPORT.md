# Báo cáo giai đoạn 5 — AI Semantic Search

Ngày 09/11/2026. eShop giữ Spring Boot 4.1.2, Spring AI 2.0.2 và Java 17. Semantic Search dùng Gemini và Qdrant để xếp hạng. MariaDB cung cấp thông tin hiện tại cho sản phẩm tìm được.

## Kiểm tra và hoàn tất backfill

- MariaDB có **50 sản phẩm**. Qdrant `eshop_products_v1` đã tồn tại, dùng vector không tên **768 chiều / Cosine**, có **1 point** trước khi chạy.
- Dùng tài khoản SELLER đã có qua `POST /api/auth/login`, nhận JWT rồi gọi endpoint giai đoạn 4 `POST /api/dev/ai/products/sync`. Cả hai request trả HTTP 200. Báo cáo đồng bộ trả **49 upserted, 1 skipped, 0 failed, 0 deleted**. Sản phẩm đã lập chỉ mục và có cùng hash được bỏ qua.
- Backfill chạy đồng bộ đến hết request, nên không có việc backfill còn nằm trong hàng đợi. Không có lỗi cần retry. Tiến trình kiểm tra đọc MariaDB ở chế độ chỉ đọc và không thay đổi tài khoản hay sản phẩm. Spring Security xử lý cả đăng nhập lẫn kiểm tra JWT thật.
- Phép đếm chính xác sau đồng bộ trả **50 point**. Đã đọc payload toàn collection và đối chiếu đủ **50 product ID duy nhất** với MariaDB, không có ID lệch và không có point dùng source hoặc model khác.

## Luồng truy vấn

`ProductSemanticSearchService` chuẩn hóa Unicode NFC và khoảng trắng, giữ nguyên dấu tiếng Việt. Câu hợp lệ đi qua `ProductEmbeddingService` dùng `gemini-embedding-001` và vector **768 chiều**. `QdrantProductIndex` dùng client gRPC đã có để tìm láng giềng theo cosine, lọc đúng `source`, model embedding và danh mục được chọn. Service đọc các ID qua một truy vấn MariaDB có join danh mục, bỏ ID trùng hoặc không còn tồn tại rồi giữ thứ tự vector. Đọc giá và tồn kho từ MariaDB. Sản phẩm hết hàng vẫn hiển thị với trạng thái phù hợp và nút thêm giỏ bị vô hiệu hóa.

API truy vấn trực tiếp Qdrant để tái sử dụng vector đã tạo, lọc metadata và đặt deadline cho mỗi RPC. Spring AI 2.0.2 nhận tùy chọn `taskType` nhưng adapter Google GenAI chưa truyền nó vào `EmbedContentConfig`. Giai đoạn 4 lập chỉ mục với task type mặc định của lời gọi hiện tại, nên giai đoạn 5 giữ cùng cách tạo vector ở cả hai phía. Nếu thử `RETRIEVAL_DOCUMENT` và `RETRIEVAL_QUERY`, cần xác minh adapter thực sự gửi chúng, backfill sang collection phiên bản mới, đánh giá song song rồi mới chuyển cấu hình đọc. [Mã nguồn adapter Spring AI 2.0.2](https://github.com/spring-projects/spring-ai/blob/v2.0.2/models/spring-ai-google-genai-embedding/src/main/java/org/springframework/ai/google/genai/text/GoogleGenAiTextEmbeddingModel.java) · [Gemini Embeddings](https://ai.google.dev/gemini-api/docs/embeddings)

## API, giao diện và cách chạy

`GET /api/products/semantic-search?q=...&categoryId=...` là API công khai theo quy tắc GET sản phẩm hiện có. `categoryId` là tùy chọn. JSON là danh sách theo thứ hạng, gồm ID, tên, mô tả, giá, ảnh, danh mục, `inStock` và `score`. Không có kết quả trả danh sách rỗng. Query sai trả 400, vượt giới hạn trả 429 và AI không sẵn sàng trả 503.

Trang `/products` có lựa chọn **Tìm kiếm thông thường** hoặc **Tìm kiếm thông minh bằng AI**. Cả hai dùng lại product cards, giá, ảnh, liên kết chi tiết và form giỏ hàng hiện tại. Chế độ AI dùng thông báo tải, không có kết quả hoặc dịch vụ tạm lỗi. Liên kết sang tìm thường là thao tác của người dùng và giao diện gắn nhãn AI riêng. Biểu mẫu xếp dọc trên màn hình nhỏ và nằm cùng hàng trên màn hình lớn.

Bật profile `ai-dev` và đặt key vào môi trường của tiến trình:

```powershell
$env:GEMINI_API_KEY = Read-Host -MaskInput "Gemini API key"
mvn spring-boot:run "-Dspring-boot.run.profiles=ai-dev"
```

Mở `/products`, chọn chế độ AI rồi nhập câu tự nhiên. Có thể so sánh với chế độ thông thường bằng cùng câu. Profile mặc định vẫn chạy tìm theo tên và bỏ tùy chọn AI. Có thể tắt riêng AI Search trong `ai-dev` bằng `eshop.ai.semantic-search.enabled=false`.

| Cấu hình | Mặc định |
| --- | --- |
| `eshop.ai.semantic-search.top-k` | 5 kết quả |
| `eshop.ai.semantic-search.threshold` | 0.55 trên thang cosine từ −1 đến 1, mức khởi đầu dựa trên catalog hiện tại |
| `eshop.ai.semantic-search.max-query-length` | 300 ký tự UTF-16 |
| `eshop.ai.embedding.timeout-ms` | 10.000 ms cho Gemini, dùng chung với đồng bộ |
| `eshop.ai.semantic-search.qdrant-timeout-ms` | 3.000 ms cho Qdrant |
| `eshop.ai.semantic-search.max-concurrent`, `requests-per-minute` | 4 request đồng thời và 60 request/phút mỗi tiến trình |

## Đánh giá thực tế

Chạy 10 câu qua hai API với MariaDB thật, Gemini thật và Qdrant thật. Cả hai API trả HTTP 200 cho cả 10 câu. Bảng dưới ghi lần đo gốc ở ngưỡng **0.0** để thấy cả kết quả tốt lẫn nhiễu. Điểm trong ngoặc là cosine làm tròn ba chữ số.

| Câu truy vấn | Top 5 AI theo thứ tự và điểm | SQL LIKE | Đánh giá |
| --- | --- | --- | --- |
| Tôi cần laptop cho sinh viên lập trình. | ASUS Vivobook 15 X1504VA (0.649)<br>Lenovo ThinkPad E14 Gen 5 (0.647)<br>Dell Inspiron 15 3530 (0.634)<br>MacBook Air M3 13 inch 16GB/256GB (0.627)<br>Canon LBP2900 (0.599) | 0 | 4 laptop đứng đầu. Máy in ở hạng 5 lệch nhu cầu. |
| Điện thoại chụp ảnh đẹp. | OPPO Reno11 F 5G (0.640)<br>Xiaomi Redmi Note 13 8GB/128GB (0.617)<br>Fujifilm Instax Mini 12 (0.610)<br>iPhone 15 128GB (0.604)<br>Sony ZV-E10 Kit 16-50mm (0.596) | 0 | 3 điện thoại và 2 máy ảnh. Thu hẹp danh mục Điện thoại để bỏ kết quả khác loại. |
| Máy tính giá rẻ để học tập. | ASUS Vivobook 15 X1504VA (0.617)<br>MacBook Air M3 13 inch 16GB/256GB (0.610)<br>iPad Air M2 11 inch WiFi 128GB (0.594)<br>Dell Inspiron 15 3530 (0.590)<br>Lenovo ThinkPad E14 Gen 5 (0.588) | 0 | ASUS 12,49 triệu đứng đầu. Có cả MacBook 27,99 triệu, nên điểm vector không đảm bảo ràng buộc ngân sách. |
| Sản phẩm phù hợp làm quà tặng. | JBL Tune 520BT (0.593)<br>JBL Flip 6 (0.592)<br>Sony SRS-XB100 (0.588)<br>Pin dự phòng Xiaomi 20000mAh (0.587)<br>Akko 3087 Dragon Ball (0.587) | 0 | Trả các gợi ý điện tử đa dạng. Câu hỏi quá rộng để xác định một món quà đúng. |
| Tai nghe chống ồn để học | Sony WH-1000XM5 (0.683)<br>Soundcore Liberty 4 NC (0.680)<br>AirPods Pro 2 USB-C (0.677)<br>JBL Tune 520BT (0.659)<br>Logitech M331 Silent Plus (0.607) | 0 | 4 tai nghe đứng đầu. Ba mẫu đầu phù hợp hơn với yêu cầu chống ồn. |
| tai nghe chong on de hoc | JBL Tune 520BT (0.659)<br>AirPods Pro 2 USB-C (0.647)<br>Sony WH-1000XM5 (0.642)<br>Soundcore Liberty 4 NC (0.637)<br>Sony SRS-XB100 (0.577) | 0 | Cùng 4 mẫu tai nghe với câu có dấu, nhưng thứ hạng thay đổi và có một loa. |
| Chuột không dây cho laptop | Logitech M331 Silent Plus (0.737)<br>Logitech MX Master 3S (0.671)<br>Razer DeathAdder Essential (0.653)<br>Logitech K380 (0.640)<br>Keychron K2 V2 (0.611) | 0 | Hai chuột không dây đứng đầu. Có thêm chuột có dây và hai bàn phím. |
| Máy in laser trắng đen | Canon LBP2900 (0.752)<br>HP LaserJet M211dw (0.712)<br>Epson EcoTank L3250 (0.629)<br>LG 24MP60G 24 inch (0.587)<br>Razer DeathAdder Essential (0.584) | 0 | Hai máy in laser đúng nhu cầu đứng đầu. Epson là máy in phun và hai kết quả cuối lệch loại. |
| iPhone 15 | iPhone 15 128GB (0.718)<br>ASUS Vivobook 15 X1504VA (0.579)<br>OPPO Reno11 F 5G (0.577)<br>Cáp Ugreen USB-C to USB-C 100W (0.575)<br>iPad Air M2 11 inch WiFi 128GB (0.565) | 1 · iPhone 15 128GB | AI xếp đúng iPhone 15 đầu bảng. LIKE trả riêng đúng mẫu có cụm từ trong tên. |
| Thức ăn cho mèo trưởng thành | Logitech MX Master 3S (0.539)<br>Canon LBP2900 (0.537)<br>Logitech M331 Silent Plus (0.532)<br>Razer DeathAdder Essential (0.528)<br>MacBook Air M3 13 inch 16GB/256GB (0.525) | 0 | Catalog điện tử thiếu loại hàng này. Ở ngưỡng 0.0, cả 5 gợi ý đều không liên quan. |

LIKE hữu ích khi tên khớp chính xác. Vector tìm được sản phẩm liên quan khi nguyên câu không xuất hiện trong tên, nhưng vẫn có thể nhầm loại hàng hoặc không đáp ứng giá mong muốn. Truy vấn thức ăn cho mèo là ca ngoài catalog. Danh mục chỉ mục gồm 15 nhóm đồ điện tử và không có nhóm thức ăn thú cưng. Điểm cao nhất của truy vấn này là 0.539.

Chạy lại cả 10 câu với ngưỡng mặc định mới **0.55**. Chín câu đầu giữ nguyên top 5 theo ID, thứ tự và điểm đến ba chữ số. Câu thức ăn cho mèo trả **HTTP 200 và danh sách rỗng**. Cả hai chế độ đều hoạt động trong trường hợp thiếu loại hàng này. Mức 0.55 có thể cấu hình và cần đánh giá lại trên tập truy vấn lớn hơn.

Trang AI đã render HTTP 200 qua MockMvc với card iPhone 15 và vùng thông báo tải. Kiểm tra dùng Spring MVC và Security trong tiến trình, với ba dịch vụ dữ liệu thật. Bố cục responsive dùng lưới Bootstrap sẵn có, chưa đo bằng trình duyệt ở nhiều kích thước viewport.

Maven `verify` với Java 17 đạt **21/21 test**. Bộ test gồm hồi quy eShop, API cho khách, kết quả được hydrate từ H2, thứ hạng, ID trùng hoặc đã mất, hiển thị hết hàng, chuẩn hóa tiếng Việt, input sai, giới hạn lượt gọi và lỗi Gemini/Qdrant. JavaScript của trang cũng qua kiểm tra cú pháp.

## File thay đổi

- [ProductSemanticSearchService.java](../src/main/java/com/example/eshop/ai/ProductSemanticSearchService.java), [QdrantProductIndex.java](../src/main/java/com/example/eshop/ai/QdrantProductIndex.java) và [ProductRepository.java](../src/main/java/com/example/eshop/repository/ProductRepository.java) thực hiện truy vấn và ánh xạ thứ hạng.
- [ProductSemanticSearchController.java](../src/main/java/com/example/eshop/api/ProductSemanticSearchController.java) cung cấp API công khai.
- [ShopController.java](../src/main/java/com/example/eshop/controller/ShopController.java), [index.html](../src/main/resources/templates/shop/index.html) và [shop-search.js](../src/main/resources/static/js/shop-search.js) bổ sung chế độ tìm kiếm và phản hồi trên trang sản phẩm.
- [AiDevConfiguration.java](../src/main/java/com/example/eshop/ai/AiDevConfiguration.java), [application-ai-dev.properties](../src/main/resources/application-ai-dev.properties) và [ProductVectorSyncService.java](../src/main/java/com/example/eshop/ai/ProductVectorSyncService.java) dùng chung cấu hình Gemini và định danh nguồn Qdrant.
- [ProductSemanticSearchServiceTest.java](../src/test/java/com/example/eshop/ai/ProductSemanticSearchServiceTest.java), [ProductSemanticSearchWebTest.java](../src/test/java/com/example/eshop/ai/ProductSemanticSearchWebTest.java) và [EshopRegressionTest.java](../src/test/java/com/example/eshop/EshopRegressionTest.java) kiểm tra input, xếp hạng, quyền truy cập, lỗi dịch vụ và hành vi cũ.

## Đề xuất giai đoạn 6

Thiết kế sự kiện tìm kiếm, xem và nhấp sản phẩm ở một lớp riêng. Phân biệt chế độ thông thường và AI, ghi số kết quả, thời gian phản hồi và ID sản phẩm được nhấp. Chỉ thu thập trường cần cho đánh giá, xác định chính sách lưu và không gửi dữ liệu hành vi sang Gemini. Dùng tập truy vấn gắn nhãn để đo độ liên quan trước khi chỉnh threshold.
