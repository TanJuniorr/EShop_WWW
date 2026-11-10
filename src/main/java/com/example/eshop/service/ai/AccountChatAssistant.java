package com.example.eshop.service.ai;

import com.example.eshop.ai.ProductSearchQueryParser;

import com.example.eshop.cart.Cart;
import com.example.eshop.entity.Order;
import com.example.eshop.entity.OrderStatus;
import com.example.eshop.repository.OrderRepository;
import com.example.eshop.repository.UserRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
@Profile("ai-dev")
class AccountChatAssistant {
    private static final Pattern ORDER_ID = Pattern.compile("\\b(?:don(?: hang)?|ma don) +#?(\\d+)\\b");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String LOGIN_REQUIRED =
            "Bạn hãy đăng nhập eShop trước; mình chỉ tra cứu được dữ liệu của tài khoản đang đăng nhập.";

    private final OrderRepository orders;
    private final UserRepository users;
    private final Cart cart;

    AccountChatAssistant(OrderRepository orders, UserRepository users, Cart cart) {
        this.orders = orders;
        this.users = users;
        this.cart = cart;
    }

    Answer answer(String question, String username) {
        String words = ProductSearchQueryParser.words(question);
        if (words.matches(".*\\b(mat khau|password)\\b.*")) {
            return new Answer("Mình không thể xem hoặc đổi mật khẩu qua chat. Mật khẩu được lưu dưới dạng mã hóa; "
                    + "đừng gửi mật khẩu hay mã xác thực vào cuộc trò chuyện.", false);
        }

        var orderId = ORDER_ID.matcher(words);
        boolean orderQuestion = orderId.find() || words.matches(".*\\b(don hang|don cua toi|don gan day|"
                + "trang thai don|toi da dat|don moi nhat)\\b.*");
        if (orderQuestion) {
            if (!StringUtils.hasText(username)) {
                return new Answer(LOGIN_REQUIRED, false);
            }
            List<Order> accountOrders = orders.findByUserUsernameOrderByOrderDateDesc(username);
            if (orderId.reset().find()) {
                long requestedId = Long.parseLong(orderId.group(1));
                return accountOrders.stream().filter(order -> order.getId() == requestedId).findFirst()
                        .map(order -> new Answer(orderSummary(order), true))
                        .orElseGet(() -> new Answer("Mình không tìm thấy đơn hàng #" + requestedId
                                + " trong tài khoản đang đăng nhập.", false));
            }
            if (accountOrders.isEmpty()) {
                return new Answer("Tài khoản này hiện chưa có đơn hàng nào.", false);
            }
            String recent = accountOrders.stream().limit(3).map(AccountChatAssistant::orderSummary)
                    .reduce((left, right) -> left + "\n" + right).orElse("");
            return new Answer("Đơn hàng gần đây của bạn:\n" + recent, true);
        }

        if (words.matches(".*\\b(gio hang cua toi|gio hang cua minh|gio hang hien tai|"
                + "trong gio hang|gio hang cua em)\\b.*")) {
            if (!StringUtils.hasText(username)) {
                return new Answer(LOGIN_REQUIRED, false);
            }
            if (cart.isEmpty()) {
                return new Answer("Giỏ hàng hiện tại của bạn đang trống.", false);
            }
            String items = cart.getItems().stream().map(item -> item.getName() + " × " + item.getQuantity())
                    .reduce((left, right) -> left + "; " + right).orElse("");
            return new Answer("Giỏ hàng hiện có " + cart.getTotalQuantity() + " sản phẩm, tổng tạm tính "
                    + money(cart.getTotalAmount()) + ": " + items + ".", true);
        }

        boolean profileQuestion = words.matches(".*\\b(thong tin tai khoan|tai khoan cua toi|"
                + "ten tai khoan|email cua toi|so dien thoai cua toi|dia chi cua toi)\\b.*");
        if (profileQuestion) {
            if (!StringUtils.hasText(username)) {
                return new Answer(LOGIN_REQUIRED, false);
            }
            var account = users.findByUsername(username).orElse(null);
            if (account == null) {
                return new Answer("Mình chưa đọc được hồ sơ của tài khoản đang đăng nhập.", false);
            }
            if (words.contains("email")) {
                return new Answer("Email trong hồ sơ của bạn là " + value(account.getEmail()) + ".", true);
            }
            if (words.contains("so dien thoai")) {
                return new Answer("Số điện thoại trong hồ sơ của bạn là " + value(account.getPhone()) + ".", true);
            }
            if (words.contains("dia chi")) {
                return new Answer("Địa chỉ trong hồ sơ của bạn là " + value(account.getAddress()) + ".", true);
            }
            return new Answer("Tên đăng nhập: " + account.getUsername() + ". Họ tên: "
                    + account.getFullName() + ".", true);
        }
        return null;
    }

    private static String orderSummary(Order order) {
        String date = order.getOrderDate() == null ? "chưa có ngày đặt"
                : order.getOrderDate().format(DATE);
        return "Đơn #" + order.getId() + " · " + date + " · " + status(order.getStatus())
                + " · " + money(order.getTotalAmount());
    }

    private static String status(OrderStatus status) {
        if (status == null) return "chưa rõ trạng thái";
        return switch (status) {
            case NEW -> "mới đặt, đang chờ xử lý";
            case PAID -> "đã thanh toán";
            case SHIPPING -> "đang giao";
            case DONE -> "đã hoàn tất";
            case CANCELLED -> "đã hủy";
        };
    }

    private static String money(java.math.BigDecimal amount) {
        if (amount == null) return "chưa có tổng tiền";
        var format = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"));
        format.setMaximumFractionDigits(2);
        return format.format(amount) + " ₫";
    }

    private static String value(String value) {
        return StringUtils.hasText(value) ? value : "chưa cập nhật";
    }

    record Answer(String text, boolean privateData) {}
}
