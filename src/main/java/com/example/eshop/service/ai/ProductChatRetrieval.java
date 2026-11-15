package com.example.eshop.service.ai;

import com.example.eshop.ai.ChatText;

import com.example.eshop.entity.Product;
import com.example.eshop.entity.Category;
import com.example.eshop.repository.CategoryRepository;
import com.example.eshop.repository.ProductRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Profile("ai-dev")
class ProductChatRetrieval {
    private static final Pattern COMPARE = Pattern.compile(
            "(?iu)^(?:(?:hãy|hay) +)?so +(?:sánh|sanh) +(.+?) +(?:và|va|với|voi|vs\\.?) +(.+?)[?.!]*$");
    private static final Pattern COMPARE_RECENT = Pattern.compile(
            "^so sanh (?:(?:2|hai|cac|nhung) )?(?:(?:san pham|mau|dien thoai|laptop|tai nghe|may tinh bang)"
                    + "(?: (?:do|nay|vua roi))?|do|nay|vua roi)$");
    private static final Pattern PRODUCT_INTENT = Pattern.compile(
            "\\b(?:tim|mua|san pham|mat hang|gia|bao nhieu|re nhat|dat nhat|thap nhat|cao nhat|"
                    + "ngan sach|con hang|co san|so sanh|man hinh|amoled|oled|chip|cau hinh|tot nhat)\\b");
    private static final Pattern WITHIN_RESULTS = Pattern.compile(
            "\\b(?:trong (?:cac|nhung) mau|mau nao|loai nao|cai nao|may nao)\\b");
    private static final Pattern ALTERNATIVE = Pattern.compile(
            "^(?:(?:co|con) )?(?:mau|loai|san pham|cai|may) (?:nao |khac |tuong tu )*"
                    + "(?:khac|tuong tu|re hon|con hang|co san)(?: khong)?$");
    private static final Comparator<Product> BY_PRICE = Comparator.comparing(Product::getPrice);
    private static final String EMPTY = "Mình chưa tìm được sản phẩm phù hợp. Bạn có thể thử mô tả nhu cầu khác.";
    private static final String NEED_REFERENCE = "Bạn đang nhắc đến mẫu nào? Hãy cho mình tên sản phẩm hoặc nhu cầu cụ thể.";

    private final ObjectProvider<ProductSemanticSearchService> semantic;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final int maxProducts;

    ProductChatRetrieval(ObjectProvider<ProductSemanticSearchService> semantic, ProductRepository products,
                         CategoryRepository categories,
                         @Value("${eshop.ai.chat.max-products:5}") int maxProducts) {
        this.semantic = semantic;
        this.products = products;
        this.categories = categories;
        this.maxProducts = Math.max(2, Math.min(5, maxProducts));
    }

    boolean isProductQuestion(String message) {
        String words = ChatText.words(message);
        if (PRODUCT_INTENT.matcher(words).find() || ALTERNATIVE.matcher(words).matches()) {
            return true;
        }
        String padded = " " + words + " ";
        return categories.findAllByOrderByNameAsc().stream()
                .map(ProductChatRetrieval::categoryAliases)
                .flatMap(List::stream)
                .anyMatch(alias -> padded.contains(" " + alias + " "));
    }

    Result retrieve(String message, Context previous) {
        var comparison = COMPARE.matcher(message);
        if (comparison.matches()) {
            return compare(comparison.group(1), comparison.group(2));
        }

        String words = ChatText.words(message);
        if (COMPARE_RECENT.matcher(words).matches()) {
            return compareRecent(previous);
        }
        if (ALTERNATIVE.matcher(words).matches() || words.matches("(?:co )?re hon(?: khong)?")) {
            return previous == null ? Result.say(NEED_REFERENCE) : alternatives(words, previous);
        }
        if (WITHIN_RESULTS.matcher(words).find() && words.matches(".*\\b(re nhat|dat nhat|phu hop hon|con hang|co san)\\b.*")) {
            return previous == null ? Result.say(NEED_REFERENCE) : withinResults(words, previous);
        }

        return search(message, null, List.of(), Kind.RECOMMEND);
    }

    private static List<String> categoryAliases(Category category) {
        String name = ChatText.words(category.getName());
        if (name.equals("ban phim chuot")) {
            return List.of(name, "ban phim", "chuot");
        }
        return List.of(name);
    }

    private Result compare(String first, String second) {
        var left = namedProducts(first);
        if (left.size() != 1) {
            return clarifyName(first, left);
        }
        var right = namedProducts(second);
        if (right.size() != 1) {
            return clarifyName(second, right);
        }
        if (left.get(0).getId().equals(right.get(0).getId())) {
            return Result.say("Hai tên cùng chỉ một sản phẩm. Bạn muốn so sánh với mẫu nào khác?");
        }
        var pair = List.of(left.get(0), right.get(0));
        return new Result(Kind.COMPARE, pair, Context.of(null, null, null, pair), null);
    }

    private Result compareRecent(Context previous) {
        if (previous == null) {
            return Result.say("Bạn hãy tìm một nhóm sản phẩm trước, rồi mình sẽ so sánh hai mẫu đầu tiên trong kết quả.");
        }
        var recent = liveProducts(previous);
        if (recent.size() < 2) {
            return Result.say("Lượt tìm gần nhất chỉ có một mẫu. Bạn cho mình tên mẫu thứ hai để so sánh nhé.");
        }
        var pair = List.copyOf(recent.subList(0, 2));
        return new Result(Kind.COMPARE, pair,
                Context.of(previous.query(), previous.explicitCategory(), previous.filters(), pair), null);
    }

    private List<Product> namedProducts(String name) {
        String term = name.strip().replaceAll("^[\"“]+|[\"”]+$", "");
        var limit = PageRequest.of(0, maxProducts + 1);
        var exact = products.findByNameIgnoreCase(term, limit);
        if (!exact.isEmpty()) {
            return exact;
        }
        // A suffix such as "128GB" is allowed, while "iPhone 150" is a different model.
        return products.findByNameStartingWithIgnoreCase(term + " ", limit);
    }

    private static Result clarifyName(String name, List<Product> matches) {
        return Result.say(matches.isEmpty()
                ? "Mình chưa tìm thấy sản phẩm mang tên “" + name.strip() + "” trong eShop. Bạn có thể kiểm tra lại tên mẫu."
                : "Có nhiều sản phẩm khớp với “" + name.strip() + "”. Bạn cho mình tên đầy đủ hoặc phiên bản cần xem nhé.");
    }

    private Result withinResults(String words, Context previous) {
        var current = liveProducts(previous).stream()
                .filter(p -> previous.filters() == null || previous.filters().matches(p))
                .filter(p -> !words.contains("con hang") && !words.matches(".*\\bco san(?! pham\\b).*") || p.getQuantity() > 0)
                .toList();
        if (current.isEmpty()) {
            return Result.say("Các mẫu vừa xem hiện không còn đáp ứng điều kiện này.");
        }
        if (words.contains("re nhat") || words.contains("dat nhat")) {
            var order = words.contains("re nhat") ? BY_PRICE : BY_PRICE.reversed();
            Product best = current.stream().min(order).orElseThrow();
            return new Result(Kind.PRICE_EXTREME, List.of(best), null,
                    words.contains("re nhat") ? "Đây là mẫu có giá thấp nhất trong các mẫu vừa xem."
                            : "Đây là mẫu có giá cao nhất trong các mẫu vừa xem.");
        }
        return new Result(words.contains("phu hop hon") ? Kind.PICK : Kind.RECOMMEND, current, null, null);
    }

    private Result alternatives(String words, Context previous) {
        var references = liveProducts(previous);
        if (references.isEmpty()) {
            return Result.say(NEED_REFERENCE);
        }
        Long category = previous.explicitCategory();
        if ((previous.filters() == null || previous.filters().categoryId() == null)
                && references.stream().map(p -> p.getCategory().getId()).distinct().count() == 1) {
            category = references.get(0).getCategory().getId();
        }
        String query = previous.query() == null ? references.get(0).getName() : previous.query();
        if (words.contains("re hon")) {
            BigDecimal price = references.stream().map(Product::getPrice).min(BigDecimal::compareTo).orElseThrow();
            query += " dưới " + price.stripTrailingZeros().toPlainString() + " đồng";
        }
        boolean stock = words.contains("con hang") || words.matches(".*\\bco san(?! pham\\b).*");
        if (stock) {
            query += " còn hàng";
        }
        return search(query, category, stock ? List.of() : previous.ids(), Kind.ALTERNATIVE);
    }

    private List<Product> liveProducts(Context previous) {
        var byId = products.loadRankedProducts(previous.ids()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return previous.ids().stream().filter(byId::containsKey).map(byId::get).toList();
    }

    private Result search(String query, Long category, List<Long> exclude, Kind kind) {
        var search = semantic.getIfAvailable();
        if (search == null) {
            throw ProductSemanticSearchService.unavailable();
        }
        if (query.length() > search.maxLength()) {
            return Result.say("Bạn rút gọn mô tả nhu cầu để mình tiếp tục tìm sản phẩm nhé.");
        }
        var result = search.search(query, category, 0, exclude);
        var matches = result.hits().stream().map(ProductSemanticSearchService.Hit::product)
                .filter(p -> !exclude.contains(p.getId()) && result.filters().matches(p))
                .limit(maxProducts).toList();
        var context = Context.of(query, category, result.filters(), matches);
        if (matches.isEmpty()) {
            return new Result(null, matches, context, EMPTY);
        }
        return new Result(kind, matches, context, null);
    }

    enum Kind { RECOMMEND, COMPARE, ALTERNATIVE, PRICE_EXTREME, PICK }

    record Context(String query, Long explicitCategory, ProductSemanticSearchService.Filters filters, List<Long> ids) {
        static Context of(String query, Long explicitCategory, ProductSemanticSearchService.Filters filters, List<Product> products) {
            return new Context(query, explicitCategory, filters, products.stream().map(Product::getId).toList());
        }
    }

    record Result(Kind kind, List<Product> products, Context context, String message) {
        static Result say(String message) {
            return new Result(null, List.of(), null, message);
        }
    }
}
