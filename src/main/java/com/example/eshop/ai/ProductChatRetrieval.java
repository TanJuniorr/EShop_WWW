package com.example.eshop.ai;

import com.example.eshop.entity.Product;
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
    private static final Pattern BUDGET = Pattern.compile(
            "(?iu)^(?:(?:tôi|toi|mình|minh|em) +)?(?:có|co|ngân sách(?: là)?|ngan sach(?: la)?) +"
                    + "(\\d+(?:[.,]\\d+)*(?: *(?:triệu|trieu|tr|nghìn|nghin|ngàn|ngan|k|vnd|đồng|dong|₫|đ))?)"
                    + "(?=[ ,?!]|$)");
    private static final Pattern COMPARE = Pattern.compile(
            "(?iu)^(?:(?:hãy|hay) +)?so +(?:sánh|sanh) +(.+?) +(?:và|va|với|voi|vs\\.?) +(.+?)[?.!]*$");
    private static final Pattern LOOKUP = Pattern.compile(
            "(?iu)^(?:(?:shop|eshop|cửa hàng) +)?(?:có +(.+?) +không|giá (?:của +)?(.+?))[?.!]*$");
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
    private final ProductSearchQueryParser parser = new ProductSearchQueryParser();
    private final int maxProducts;

    ProductChatRetrieval(ObjectProvider<ProductSemanticSearchService> semantic, ProductRepository products,
                         @Value("${eshop.ai.chat.max-products:5}") int maxProducts) {
        this.semantic = semantic;
        this.products = products;
        this.maxProducts = Math.max(2, Math.min(5, maxProducts));
    }

    Result retrieve(String message, Context previous) {
        var comparison = COMPARE.matcher(message);
        if (comparison.matches()) {
            return compare(comparison.group(1), comparison.group(2));
        }

        String words = ProductSearchQueryParser.words(message);
        if (ALTERNATIVE.matcher(words).matches() || words.matches("(?:co )?re hon(?: khong)?")) {
            return previous == null ? Result.say(NEED_REFERENCE) : alternatives(words, previous);
        }
        if (WITHIN_RESULTS.matcher(words).find() && words.matches(".*\\b(re nhat|dat nhat|phu hop hon|con hang|co san)\\b.*")) {
            return previous == null ? Result.say(NEED_REFERENCE) : withinResults(words, previous);
        }

        var lookup = LOOKUP.matcher(message);
        if (lookup.matches()) {
            String name = lookup.group(1) == null ? lookup.group(2) : lookup.group(1);
            if (parser.parse(name).hasConstraints()) {
                return search(name, null, List.of(), Kind.RECOMMEND);
            }
            var matches = namedProducts(name);
            if (matches.size() != 1) {
                return clarifyName(name, matches);
            }
            return new Result(Kind.RECOMMEND, matches, Context.of(matches.get(0).getName(), null, null, matches), null);
        }
        String query = withBudget(message);
        return query == null ? Result.say("Bạn cho mình biết ngân sách tính bằng đồng, nghìn hay triệu nhé.")
                : search(query, null, List.of(), Kind.RECOMMEND);
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
                .filter(p -> !words.contains("con hang") && !words.contains("co san") || p.getQuantity() > 0)
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
        // Let semantic search infer the category again if it came from the user's query.
        // That keeps category-and-price-only follow-ups on the structured catalog path.
        if ((previous.filters() == null || previous.filters().categoryId() == null)
                && references.stream().map(p -> p.getCategory().getId()).distinct().count() == 1) {
            category = references.get(0).getCategory().getId();
        }
        String query = previous.query() == null ? references.get(0).getName() : previous.query();
        if (words.contains("re hon")) {
            BigDecimal price = references.stream().map(Product::getPrice).min(BigDecimal::compareTo).orElseThrow();
            if (previous.filters() != null && previous.filters().min() != null
                    && price.compareTo(previous.filters().min()) <= 0) {
                return Result.say("Mình chưa tìm được mẫu rẻ hơn trong khoảng giá bạn đã chọn.");
            }
            query += " dưới " + price.stripTrailingZeros().toPlainString() + " đồng";
        }
        boolean stock = words.contains("con hang") || words.contains("co san");
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
        if (matches.isEmpty()) {
            return Result.say(EMPTY);
        }
        return new Result(kind, matches, Context.of(query, category, result.filters(), matches), null);
    }

    private static String withBudget(String message) {
        var match = BUDGET.matcher(message);
        if (!match.find()) {
            return message;
        }
        String amount = match.group(1);
        if (amount.matches("[\\d.,]+") && !amount.matches("\\d{4,}|\\d{1,3}(?:[.]\\d{3})+")) {
            return null;
        }
        String rest = message.substring(match.end()).replaceFirst("^[ ,]+", "");
        if (ProductSearchQueryParser.words(rest).matches("(?:mua (?:duoc )?gi)?")) {
            rest = "";
        }
        return (rest + " không quá " + amount).strip();
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
