# Giai đoạn 7 · User Tracking và Analytics

Ngày 10/11/2026. eShop ghi sự kiện từ những phiên đã đồng ý thống kê và tổng hợp dữ liệu cho SELLER ở `/admin/analytics`. Dashboard có KPI, biểu đồ theo ngày, bảng sản phẩm có phân trang, thống kê AI Search và chatbot, cùng phễu mua hàng.

## Luồng thu thập và bảo mật

1. Thymeleaf hiển thị **Đồng ý**, **Từ chối** và nút **Tùy chọn thống kê**. Mặc định chưa có sự đồng ý. JavaScript gửi lựa chọn đến server bằng phiên web và header CSRF. Lựa chọn được kiểm tra lại khi trở về trang từ bộ nhớ của trình duyệt hoặc khi tab khác thay đổi lựa chọn.
2. Server tạo UUID analytics riêng khi đồng ý. Khách có `user_id = NULL`. Với tài khoản đã xác thực, server tra ID bằng danh tính Spring Security. Chuyển từ khách sang tài khoản, đổi tài khoản hoặc đăng xuất kết thúc phiên cũ. Sau 30 phút không có sự kiện, lần truy cập tiếp theo yêu cầu đồng ý lại.
3. Backend ghi nhận GET công khai thành công, lượt tìm AI, lượt gửi chat và các thao tác nghiệp vụ thành công. JavaScript gửi mở chat và click sản phẩm qua `fetch` bất đồng bộ. Server chỉ chấp nhận click khi ID sản phẩm có trong lượt gợi ý do server cấp cho chính phiên đó. Vị trí thẻ lấy từ thứ tự backend đã trả.
4. Sự kiện vào hàng đợi giới hạn 512 tác vụ với một worker ghi MariaDB. Ghi sự kiện thông thường tách khỏi request chính. Rút lại đồng ý vô hiệu hóa phiên trong bộ nhớ ngay và đóng, đánh dấu thu hồi trong MariaDB. Worker bỏ qua các ghi nhận còn chờ của phiên bị thu hồi. Dữ liệu đã lưu trước đó hết hạn theo chính sách lưu trữ.
5. Dashboard gọi API có kiểm tra `ROLE_SELLER` qua security chain web hiện có. Các phép đếm, nhóm ngày, xếp hạng, phân trang và phễu thực hiện bằng SQL tổng hợp. Giao diện dùng Thymeleaf, Bootstrap, JavaScript và thanh biểu đồ HTML.

Endpoint và phản hồi chính:

| Endpoint | Công dụng |
| --- | --- |
| `GET /tracking/consent` | Đọc trạng thái của phiên, trả `UNSET`, `ACCEPTED` hoặc `DECLINED`. |
| `POST /tracking/consent` | Nhận JSON `{"allowed": true}` hoặc `{"allowed": false}` với CSRF. |
| `POST /tracking/events` | Nhận `eventId` UUID và `eventType`. `PRODUCT_CLICK` cần thêm `interactionId` UUID và `productId`. `CHATBOT_OPEN` dùng hai trường đầu. Phản hồi 202 khi sự kiện đã qua bước tiếp nhận. |
| `GET /admin/analytics` | Trang dashboard cho SELLER. |
| `GET /admin/analytics/data` | Dữ liệu dashboard theo `period=today\|7\|30\|custom`, `start`, `end`, `sort` và `page`. |

Tracking API giới hạn body 1 KiB. Thiếu consent hoặc CSRF trả 403, payload sai trả 400, body lớn trả 413, vượt 60 sự kiện UI/phút/phiên trả 429. Tạo phiên sau khi đồng ý giới hạn 10 lần/phút trong cùng phiên web. Lỗi cập nhật lựa chọn trả 503. Mọi mốc thời gian do server đặt. UUID phía trình duyệt được ánh xạ theo phiên và chống ghi trùng bằng khóa duy nhất trong MariaDB.

## Sự kiện

| Loại | Điểm ghi |
| --- | --- |
| `PAGE_VIEW` | Sau GET HTTP 200 tại trang chủ, danh sách, chi tiết, đăng nhập hoặc đăng ký. Đường dẫn chỉ gồm route và ID sản phẩm khi có. Một lần tải lại là một lượt truy cập mới. |
| `PRODUCT_VIEW` | Sau GET HTTP 200 trang chi tiết. Một lượt mở chi tiết cũng tạo `PAGE_VIEW`. |
| `PRODUCT_CLICK` | Khách bấm thẻ đã được cấp cho phiên từ danh sách, AI Search hoặc chatbot. |
| `AI_SEARCH` | Tìm có truy vấn qua giao diện. Ghi kết quả hiển thị, trạng thái, độ trễ xử lý và bộ lọc backend trả về. `FILTERS`, `VECTOR` và `HYBRID` là cách xử lý bên trong cùng một tính năng. |
| `AI_SEARCH_RESULT_CLICK` | Cú bấm thẻ AI Search, có ID lượt tìm và vị trí. Cùng hành động còn được ghi một `PRODUCT_CLICK` tổng quát. |
| `ADD_TO_CART` | Sau khi giỏ thêm thành công, với ID sản phẩm và số lượng được chấp nhận. |
| `ORDER_PLACED` | Sau khi transaction tạo đơn commit. ID đơn do nghiệp vụ cung cấp. |
| `ORDER_COMPLETED` | Sau khi đổi trạng thái thành `DONE` và transaction commit. Gắn vào phiên đã đặt đơn, kể cả khi phiên đã đóng. Cùng đơn chỉ có một lần ghi nhận. Phiên bị thu hồi hoặc lượt đặt đã hết hạn sẽ không có sự kiện hoàn tất bổ sung. |
| `CHATBOT_OPEN` | Widget được mở. |
| `CHATBOT_MESSAGE_SENT` | Tin hợp lệ được gửi đến chatbot. Chỉ lưu sự kiện, kể cả khi dịch vụ trả lỗi sau đó. |
| `CHATBOT_PRODUCT_CLICK` | Cú bấm thẻ trong câu trả lời chatbot. Liên kết với lượt gửi tin, cùng một `PRODUCT_CLICK` tổng quát. |

Từ khóa AI Search chỉ được lưu khi tối đa 96 ký tự và toàn bộ từ thuộc danh sách từ vựng mua sắm an toàn, kèm các khoản tiền ngắn có đơn vị. Truy vấn ngoài danh sách vẫn góp số lượt, số kết quả và thời gian phản hồi. Bảng tracking chỉ chứa trường cần cho thống kê, nên nội dung chatbot, prompt, lịch sử hội thoại, dữ liệu thanh toán, vector, địa chỉ IP, token và khóa Gemini nằm ngoài dữ liệu thu thập.

## Migration và vận hành

`database/migrations/V007__user_tracking.sql` tạo `tracking_sessions` và `tracking_events` trong schema đang chọn. Sự kiện tham chiếu phiên bằng khóa ngoại. ID tài khoản nằm ở bảng phiên. ID sản phẩm và đơn lưu dưới dạng tham chiếu lịch sử để thống kê vẫn đọc được sau khi bản ghi nghiệp vụ bị xóa. Có index theo thời gian, loại sự kiện, phiên, tài khoản, sản phẩm, đơn và lượt kết quả. Migration dùng `CREATE TABLE IF NOT EXISTS` và có thể chạy lại trên cùng schema.

1. Trong công cụ MariaDB, chọn schema eShop hiện có và chạy riêng [V007__user_tracking.sql](../database/migrations/V007__user_tracking.sql). File `database/eShop.sql` dành cho khởi tạo mới và chứa lệnh xóa schema demo.
2. Bật cấu hình sau, giữ cấu hình MariaDB và AI của dự án.

```powershell
$env:ESHOP_TRACKING_ENABLED = 'true'
$env:ESHOP_TRACKING_RETENTION_DAYS = '30'
$env:ESHOP_TRACKING_ZONE = 'Asia/Ho_Chi_Minh'
mvn spring-boot:run "-Dspring-boot.run.profiles=ai-dev"
```

3. Mở `/products`, chọn **Đồng ý**, rồi tải một trang, tìm sản phẩm hoặc mở chatbot. Chọn **Tùy chọn thống kê** để đổi quyết định. Việc chọn đồng ý có hiệu lực cho các thao tác tiếp theo.
4. Đăng nhập SELLER, vào **Quản trị → Thống kê hành vi**, hoặc mở `/admin/analytics`. Lọc Hôm nay, 7 ngày, 30 ngày hoặc tự chọn tối đa 93 ngày. Dữ liệu lưu UTC và chia ngày theo `ESHOP_TRACKING_ZONE`.
5. Có thể kiểm tra các dòng đã lưu bằng truy vấn chỉ đọc.

```sql
SELECT event_type, COUNT(*) AS total
FROM tracking_events
GROUP BY event_type
ORDER BY total DESC;

SELECT event_type, product_id, interaction_id, result_position, created_at
FROM tracking_events
ORDER BY id DESC
LIMIT 20;
```

Tác vụ hằng ngày chạy lúc 03:15 UTC, đóng các phiên không hoạt động và dọn sự kiện cũ theo `ESHOP_TRACKING_RETENTION_DAYS`. Phiên cũ hết thời hạn và đã rỗng được dọn cùng đợt. Thiết lập mặc định là 30 ngày và tắt thu thập để có thể áp dụng migration trước.

## Công thức

| Chỉ số | Định nghĩa trong khoảng ngày đã chọn |
| --- | --- |
| Lượt truy cập | Số `PAGE_VIEW`. |
| Phiên có hoạt động | Số UUID analytics khác nhau có ít nhất một sự kiện. Tài khoản duy nhất đếm ID tài khoản khác nhau. Phiên khách đếm UUID phiên có ID tài khoản trống. |
| Tỷ lệ phiên đặt đơn | Số phiên có `ORDER_PLACED` / số phiên có hoạt động. |
| Tỷ lệ kết quả rỗng | Lượt `AI_SEARCH` thành công trả 0 sản phẩm / lượt tìm thành công. |
| Tỷ lệ lượt tìm có click | Lượt tìm có sản phẩm và có ít nhất một `AI_SEARCH_RESULT_CLICK` / lượt tìm có sản phẩm. Lượt tìm và cú bấm đều nằm trong khoảng đã chọn. |
| Phản hồi AI Search trung bình | Trung bình thời gian xử lý đã ghi cho các lượt tìm trong khoảng lọc. |
| Tỷ lệ phiên tương tác chatbot | Số phiên mở chat rồi gửi tin hoặc bấm sản phẩm / số phiên mở chat. |
| Phễu | Đếm phiên theo thứ tự xem sản phẩm → thêm giỏ → đặt đơn → hoàn tất. Mỗi bước đếm một lần mỗi phiên. Bước hoàn tất khớp ID đơn đã đặt trong phễu. Tỷ lệ mỗi bước dùng số phiên ở bước trước làm mẫu số. |

Tỷ lệ có mẫu số bằng 0 hiển thị dấu gạch. Các bước dùng thứ tự sự kiện đã ghi trong khoảng ngày đã chọn. Một phiên khách là một định danh truy cập, có thể thuộc cùng người với phiên khác. Phễu chỉ dùng các hoạt động đã có consent và đã ghi thành công.

## Kiểm thử

Kiểm thử dùng catalog và đơn hàng riêng. MockMvc chạy Thymeleaf, Spring Security và CSRF thật của ứng dụng. Gemini được giả lập. Các ca tracking dùng truy vấn cấu trúc hoặc mô phỏng lỗi trước khi gọi Qdrant. Bộ hồi quy Semantic Search kiểm tra riêng luồng vector với Qdrant giả lập. Các ca kiểm tra consent, thu hồi, trang và sản phẩm đã xem, click trùng hoặc sai phiên, bộ lọc AI Search và từ khóa nhạy cảm, click chatbot, cart thành công, chuyển trạng thái đơn, giới hạn gửi, phân quyền SELLER, khoảng thời gian UTC, thứ tự phễu, hết hạn và việc tìm/chat tiếp tục hoạt động khi tracking ghi lỗi.

- **Maven verify** đạt **46/46**, với 0 thất bại, 0 lỗi và 0 bỏ qua. Giai đoạn 7 bổ sung 2 unit test và 10 integration test. Chạy trên JDK 21, biên dịch với `--release 17`.
- **MariaDB 12.2.2 thật** đạt **10/10 integration test** trên instance tạm với cổng cao và schema `eshop_tracking_it`. Bộ test áp dụng đúng migration V007 rồi ghi sự kiện, truy vấn KPI và kiểm tra phễu trên MariaDB Connector/J. Instance dùng thư mục dữ liệu riêng và được dọn sau khi chạy.
- JavaScript qua `node --check`. Giao diện được kiểm tra ở mức render Thymeleaf qua MockMvc, còn thao tác và bố cục trên trình duyệt thật là bước kiểm tra khi chạy demo.

## File giai đoạn 7

Thêm 17 file và sửa 15 file trong phạm vi giai đoạn 7.

| Nhóm | Thêm mới | Sửa |
| --- | --- | --- |
| Database | `database/migrations/V007__user_tracking.sql` | |
| Backend | `tracking/TrackingEventType.java`, `TrackingEvent.java`, `SearchKeyword.java`, `TrackingRepository.java`, `TrackingService.java`, `TrackingController.java`, `TrackingWebConfiguration.java`, `AnalyticsService.java`, `controller/admin/AnalyticsController.java` | `ShopController.java`, `CartController.java`, `CheckoutController.java`, `AdminOrderController.java`, `OrderService.java`, `AiChatController.java`, `GlobalModelAttributes.java`, `SecurityConfig.java` |
| Giao diện | `templates/admin/analytics.html`, `templates/fragments/tracking.html`, `static/js/tracking.js`, `static/js/analytics.js` | `templates/shop/index.html`, `templates/fragments/layout.html`, `static/js/shopping-chat.js`, `static/css/style.css` |
| Kiểm thử | `tracking/TrackingWebTest.java`, `tracking/SearchKeywordTest.java` | |
| Cấu hình và tài liệu | `docs/PHASE7_USER_TRACKING_REPORT.md` | `application.properties`, `.env.example`, `README.md` |

## Giới hạn và hướng cải thiện

- Hàng đợi trong bộ nhớ ưu tiên luồng mua sắm. Số liệu có thể thiếu nếu ứng dụng dừng đột ngột, MariaDB gián đoạn hoặc hàng đợi đầy. Đơn hoàn tất được ghi khi lượt đặt ban đầu vẫn còn trong thời hạn lưu.
- Lượt click UI phụ thuộc khả năng gửi request khi rời trang. Thẻ từ một trang được mở trước khi đồng ý sẽ được cấp mã tracking ở lần tải tiếp theo.
- Danh sách từ khóa ưu tiên bảo mật nên có thể bỏ qua tên mẫu hoặc cách diễn đạt ngoài từ vựng an toàn. Có thể bổ sung từ vựng công khai sau khi rà soát.
- Khi cần theo dõi quy mô lớn hơn, có thể bổ sung tổng hợp theo ngày và cảnh báo tỷ lệ ghi thất bại. Nếu cần theo dõi doanh thu, tách định nghĩa đo lường và chính sách dữ liệu trước khi mở rộng.
