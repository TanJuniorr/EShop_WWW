# Giai đoạn 6 · Trợ lý mua sắm AI của eShop

Ngày 10/11/2026. Khách có thể mở chat từ giao diện eShop để tìm sản phẩm theo nhu cầu và ngân sách, so sánh hai mẫu, hỏi về giá hoặc tồn kho, rồi mở trang chi tiết từ thẻ sản phẩm.

## Luồng RAG

1. `AiChatController` nhận tin nhắn JSON qua Spring MVC và Spring Security. `AiChatService` xác thực độ dài, giới hạn lượt gọi và đọc ngữ cảnh theo phiên.
2. `ProductChatRetrieval` nhận diện câu so sánh, tra cứu tên, ngân sách và câu hỏi nối tiếp. Lời chào và hướng dẫn thao tác trên eShop dùng nội dung cố định.
3. Các truy vấn khám phá đi qua `ProductSemanticSearchService` và Hybrid Search hiện có. Gemini Embedding `gemini-embedding-001` tạo vector 768 chiều. Qdrant `eshop_products_v1` xếp hạng ID. MariaDB lọc điều kiện giá, danh mục và tồn kho, rồi cung cấp tên, mô tả, giá và tồn kho hiện tại. Truy vấn chỉ có điều kiện cấu trúc đi thẳng vào bộ lọc MariaDB.
4. Khi so sánh, chatbot tra tên chính xác trong MariaDB. Tiền tố có ranh giới từ chỉ được chấp nhận khi khớp một mẫu duy nhất. Chatbot yêu cầu tên đầy đủ khi có nhiều biến thể. Khi hỏi mẫu rẻ nhất trong kết quả trước, chatbot đọc lại các ID đã trả và so giá hiện tại. Khi hỏi mẫu rẻ hơn, chatbot giữ điều kiện ban đầu và áp thêm ngưỡng nhỏ hơn giá mẫu tham chiếu.
5. Spring AI `ChatModel` gửi câu hỏi, lịch sử gần đây và dữ kiện của tối đa năm sản phẩm đến Gemini Chat. Gemini chọn một sản phẩm và khía cạnh nên nhấn mạnh. Backend soạn câu tiếng Việt từ lựa chọn hợp lệ, tính chênh lệch giá và dùng nguyên mô tả trong MariaDB khi cần nêu thông số. Định dạng chọn có giới hạn giúp chặn văn bản tùy ý từ model, kể cả khi tin nhắn hoặc mô tả chứa chỉ dẫn. Thẻ sản phẩm lấy URL và trạng thái từ backend.

Danh sách, chi tiết, giỏ hàng và đặt hàng tiếp tục dùng các luồng eShop hiện có. Chatbot chỉ đọc catalog. Các lượt hỏi thuần chào hỏi, kết quả rỗng và so giá trong tập đã trả có thể được xử lý tại chỗ.

## Cấu hình và sử dụng

- Maven dùng `spring-ai-starter-model-google-genai` qua Spring AI BOM **2.0.2**, với Spring Boot **4.1.2** và mã nguồn biên dịch cho Java **17**.
- Model chat mặc định là `gemini-3.5-flash`, model Flash ổn định trong danh sách Google. `GEMINI_CHAT_MODEL` cho phép đổi tên model. Chat và embedding dùng chung `GEMINI_API_KEY` qua client Developer API đã có, mặc định timeout 10 giây, một lần gọi mỗi thao tác. [Spring AI Google GenAI Chat](https://docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html) · [Vòng đời model Gemini](https://ai.google.dev/gemini-api/docs/deprecations)
- Profile `ai-dev` bật widget. `eshop.ai.chat.enabled=false` ẩn widget và làm endpoint trả 503. `eshop.ai.chat.max-products` mặc định là 5, giới hạn từ 2 đến 5 để vẫn so sánh được hai mẫu. Tìm theo ngữ nghĩa cần Semantic Search được bật và Qdrant có chỉ mục hiện tại.

```powershell
docker compose up -d qdrant
$env:GEMINI_API_KEY = Read-Host -MaskInput "Gemini API key"
# Tùy chọn: $env:GEMINI_CHAT_MODEL = 'gemini-3.5-flash'
mvn spring-boot:run "-Dspring-boot.run.profiles=ai-dev"
```

Khởi động với cấu hình MariaDB sẵn có. Nếu chỉ mục trên Qdrant còn trống, dùng [luồng đồng bộ của giai đoạn 4](PHASE4_REPORT.md#vận-hành-và-đồng-bộ-lại). Mở `http://localhost:8081/products` theo cổng mặc định của ứng dụng, rồi chọn **Trợ lý AI** ở góc phải dưới. Enter gửi tin, Shift + Enter xuống dòng. **Cuộc trò chuyện mới** xóa phần hội thoại đang hiển thị và dùng ID mới ở lần gửi tiếp theo. Khi API gặp lỗi, lịch sử trên màn hình vẫn còn để người dùng đọc lại.

## API và bộ nhớ hội thoại

`POST /ai/chat` nhận `Content-Type: application/json`. Endpoint nằm trong security chain của website để khách và người đã đăng nhập dùng cùng cơ chế phiên và CSRF. Widget gửi cookie phiên và header CSRF lấy từ Thymeleaf. API Bearer hiện có tiếp tục dùng security chain riêng.

```json
{
  "conversationId": null,
  "message": "Điện thoại dưới 5 triệu"
}
```

Phản hồi 200 gồm `conversationId` dạng UUID, `reply` dạng văn bản và `products` dạng mảng. Mỗi mục có `id`, `name`, `price`, `imageUrl`, `productUrl` và `inStock`. Dùng UUID trả về cho lượt sau. Khi bắt đầu cuộc trò chuyện mới, gửi `null` hoặc bỏ trường này.

| HTTP | Kết quả |
| --- | --- |
| 200 | Trả lời thành công. Kết quả rỗng hoặc câu hỏi cần làm rõ có `products: []`. Nếu Gemini Chat gián đoạn sau khi đã tìm được sản phẩm, trả câu trả lời dự phòng và các thẻ đã xác minh. |
| 400 | JSON hoặc UUID sai, tin rỗng, tin dài hơn 300 ký tự, hoặc khoảng giá không hợp lệ. |
| 403 | Thiếu hoặc sai CSRF. |
| 404 | ID cuộc trò chuyện đã hết hạn, đã bị loại khỏi bộ nhớ hoặc thuộc phiên/tài khoản khác. |
| 429 | Đạt giới hạn 30 lượt/phút hoặc 4 yêu cầu đồng thời mỗi tiến trình, hoặc phiên đang xử lý một lượt chat. |
| 503 | Chat tắt hoặc quá trình tìm sản phẩm lỗi do embedding, Qdrant hay truy cập catalog. Thông báo ngắn bằng tiếng Việt. |

Mỗi HTTP session giữ tối đa ba UUID, tách theo tài khoản đang xác thực. Spring AI `MessageWindowChatMemory` giữ tám tin gần nhất cho mỗi UUID. Mỗi cuộc trò chuyện giữ thêm truy vấn, bộ lọc và ID sản phẩm của tập kết quả gần nhất để đối chiếu dữ liệu mới. Các cuộc trò chuyện quá 30 phút không hoạt động được dọn khi phiên gửi lượt chat tiếp theo. Hết hạn HTTP session hoặc khởi động lại ứng dụng cũng giải phóng bộ nhớ. `MessageWindowChatMemory` vẫn dùng được trong 2.0.2 và đã được Spring AI đánh dấu deprecated. [Thông báo Spring AI 2.0.2](https://spring.io/blog/2026/10/23/spring-ai-2-1-0-RC1-available-now/)

## Kiểm thử và kết quả

MockMvc kiểm tra tuyến HTTP, CSRF, Thymeleaf và truy vấn catalog trên H2 chế độ MariaDB. Gemini Chat, Gemini Embedding và Qdrant được giả lập. Các sản phẩm trong bảng kiểm thử là fixture H2.

| Câu hỏi hoặc tình huống | Kết quả kiểm tra |
| --- | --- |
| Xin chào | Khách nhận lời chào và UUID với CSRF hợp lệ. |
| Tôi cần laptop cho sinh viên lập trình | Thẻ laptop giữ đúng ID, giá, tồn kho và route. Kết quả sai danh mục và ID không tồn tại bị loại. |
| Điện thoại dưới 5 triệu / không quá 5 triệu | Mẫu đúng 5 triệu bị loại ở điều kiện nhỏ hơn và xuất hiện ở điều kiện nhỏ hơn hoặc bằng. |
| Sản phẩm từ 1 đến 3 triệu / Tôi có 3 triệu, mua được gì? | Trả đúng sản phẩm trong khoảng giá và giữ mẫu đúng ngưỡng 3 triệu. |
| So sánh iPhone 15 và Samsung Galaxy S24 | Tìm đúng hai tên, tính chênh lệch giá, dùng mô tả catalog. Nếu iPhone có nhiều biến thể, hỏi lại. |
| Mẫu nào rẻ nhất? / Có sản phẩm nào rẻ hơn không? | Đọc giá đã cập nhật rồi tìm lựa chọn rẻ hơn trong phạm vi cũ. |
| Có mẫu khác tương tự không? / Có sản phẩm nào còn hàng không? | Trả mẫu tiếp theo ngoài nhóm vừa xem hoặc lọc tồn kho trong phạm vi truy vấn trước. |
| Có iPhone 99 không? / Yêu cầu tiết lộ API key | Trả thông báo phù hợp và danh sách trống. Văn bản model ngoài định dạng bị loại. |
| Phiên, tài khoản và UUID khác nhau | Ngữ cảnh tách riêng, UUID mượn từ phiên hoặc tài khoản khác trả 404. |
| Model, Qdrant, MariaDB lỗi | Nếu chỉ Gemini Chat lỗi, giữ thẻ đã xác minh. Lỗi truy xuất trả 503 với nội dung dành cho khách. |
| Tin quá dài, JSON sai kiểu, sai CSRF, vượt số lượt | Từ chối bằng mã 400, 403 hoặc 429 tương ứng. |

`mvn verify` thành công với JDK 21 và `--release 17`. **33 bài kiểm thử đạt, 0 thất bại, 0 lỗi, 0 bỏ qua**. Trong đó có 2 unit test và 8 integration test mới cho chat. Trong sandbox Windows, Maven dùng khóa cục bộ trong tiến trình và Surefire nạp sẵn agent Byte Buddy để Mockito chạy. `node --check` cho `shopping-chat.js` và `git diff --check` đều đạt.

Smoke test trên tài khoản Gemini thật đang chờ môi trường có `GEMINI_API_KEY`. Phiên làm việc hiện tại thiếu biến này. Lệnh đọc trạng thái Docker cũng bị giới hạn quyền truy cập Docker Desktop. Kết quả truy cập model theo tài khoản, chất lượng trả lời trên catalog 50 sản phẩm và thao tác trên trình duyệt thật cần xác nhận khi chạy tại máy đã cấu hình.

## Giới hạn

- So sánh hỗ trợ hai tên sản phẩm trong một câu. Các thông số ngoài mô tả catalog cần nguồn dữ liệu có cấu trúc để đối chiếu.
- Ngân sách dùng cú pháp giá của Hybrid Search và các cách nói như “tôi có 3 triệu”. Chatbot hỏi lại khi ngân sách khai báo thiếu đơn vị. Câu hỏi nối tiếp hỗ trợ các mẫu ngôn ngữ về giá, hàng sẵn có và lựa chọn tương tự.
- Giá và tồn kho phản ánh thời điểm đọc catalog. Trang chi tiết và quy trình mua hàng kiểm tra lại khi người dùng thao tác.
- Hội thoại backend nằm trong bộ nhớ của tiến trình. Khi điều hướng hoặc tải lại trang, widget mở với phần hiển thị mới.

## Danh mục file giai đoạn 6

Giai đoạn này thêm 8 file và sửa 13 file. Bảng chỉ tính các thay đổi của chatbot.

| Nhóm | Thêm mới | Sửa |
| --- | --- | --- |
| Backend | `AiChatController.java`, `AiChatService.java`, `ProductChatRetrieval.java` | `AiDevConfiguration.java`, `ProductSemanticSearchService.java`, `ProductRepository.java`, `GlobalModelAttributes.java`, `SecurityConfig.java` |
| Giao diện | `fragments/chat.html`, `shopping-chat.js` | `fragments/layout.html`, `style.css` |
| Kiểm thử | `AiChatServiceTest.java`, `AiChatWebTest.java` | `EshopRegressionTest.java` |
| Cấu hình và tài liệu | `docs/PHASE6_CHATBOT_REPORT.md` | `pom.xml`, `application.properties`, `application-ai-dev.properties`, `.env.example`, `README.md` |

## Đề xuất giai đoạn 7

Thiết kế User Tracking như một lớp sự kiện riêng cho tìm kiếm, xem sản phẩm và nhấp thẻ gợi ý. Định nghĩa mục đích đo, thời gian lưu và cách ẩn danh trước. Bộ sự kiện có thể dùng mã phiên đã băm, loại thao tác, thời điểm và ID sản phẩm. Phần hội thoại thô nên tách khỏi dữ liệu đo hành vi.
