package com.example.eshop.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ProductSearchQueryParser {
    private static final String START = "(?<![\\p{L}\\p{N}.,])";
    private static final String END = "(?![\\p{L}\\p{N}]|[.,]\\d)";
    private static final String NUMBER = "(\\d{1,3}(?:[.]\\d{3})+|\\d+(?:[.,]\\d+)?)";
    private static final String UNIT = "(trieu|tr|nghin|ngan|k|vnd|dong|d|₫)";
    private static final String MONEY = NUMBER + "(?: *" + UNIT + ")?";
    private static final String PRICE_PREFIX = "(?:co +)?gia +";
    private static final String OPERATORS = "khong nho hon|khong lon hon|khong thap hon|khong cao hon|"
            + "nho hon hoac bang|lon hon hoac bang|khong vuot qua|khong qua|khong duoi|"
            + "toi da|toi thieu|it nhat|tro len|duoi|nho hon|thap hon|it hon|tren|lon hon|cao hon|hon|tu|<=|>=|≤|≥|<|>|=";
    private static final Pattern RANGE = Pattern.compile(START + "(?:" + PRICE_PREFIX + ")?tu +" + MONEY + " *(?:den|toi|[-–]) *" + MONEY + END);
    private static final Pattern BOUND = Pattern.compile(START + "(?:" + PRICE_PREFIX + ")?(" + OPERATORS + ") *" + MONEY + END);
    private static final Pattern EXACT = Pattern.compile(START + "(" + PRICE_PREFIX + ")?" + NUMBER + " *" + UNIT + END);
    private static final Pattern GROUPED = Pattern.compile("\\d{1,3}(?:[.]\\d{3})+");
    private static final Pattern STOCK = Pattern.compile("(?<!khong )(?<!het )\\b(?:dang +)?(?:con hang|co san)\\b");
    private static final Pattern SORT = Pattern.compile("\\b(?:sap xep (?:theo )?|co (?=gia ))?"
            + "(gia tu thap den cao|gia tu cao den thap|tu thap den cao|tu cao den thap|"
            + "gia tang dan|gia giam dan|gia thap nhat|gia cao nhat|gia re nhat|gia dat nhat|re nhat|dat nhat|moi nhat)\\b");
    private static final Pattern GENERIC = Pattern.compile(
            "(?:(?:toi|minh|em|dang|can|muon|tim|mua|xem|goi|y|cho|cac|nhung|san|pham|co|gia|va|voi|o|muc|hang)(?: |$))*");
    private static final BigDecimal MAX_PRICE = new BigDecimal("9999999999999.99");

    Query parse(String original) {
        String folded = fold(original);
        char[] remaining = original.toCharArray();
        Bounds bounds = new Bounds();
        var ranges = RANGE.matcher(folded);
        while (ranges.find()) {
            String leftUnit = ranges.group(2);
            String rightUnit = ranges.group(4);
            if (leftUnit == null && rightUnit == null
                    && (!isLiteralDong(ranges.group(1)) || !isLiteralDong(ranges.group(3)))) {
                continue;
            }
            var lower = money(ranges.group(1), inheritedUnit(ranges.group(1), leftUnit, rightUnit));
            var upper = money(ranges.group(3), inheritedUnit(ranges.group(3), rightUnit, leftUnit));
            bounds.lower(lower, true);
            bounds.upper(upper, true);
            clear(remaining, ranges);
        }
        var comparisons = BOUND.matcher(fold(new String(remaining)));
        while (comparisons.find()) {
            String amount = comparisons.group(2);
            String unit = comparisons.group(3);
            if (unit == null && !isLiteralDong(amount)) {
                continue;
            }
            BigDecimal price = money(amount, unit);
            switch (comparisons.group(1)) {
                case "duoi", "nho hon", "thap hon", "it hon", "<" -> bounds.upper(price, false);
                case "khong lon hon", "khong cao hon", "nho hon hoac bang", "khong vuot qua", "khong qua",
                     "toi da", "<=", "≤" -> bounds.upper(price, true);
                case "tren", "lon hon", "cao hon", "hon", ">" -> bounds.lower(price, false);
                case "=" -> bounds.exact(price);
                default -> bounds.lower(price, true);
            }
            clear(remaining, comparisons);
        }
        var prices = EXACT.matcher(fold(new String(remaining)));
        while (prices.find()) {
            if (prices.group(1) == null && "k".equals(prices.group(3)) && prices.group(2).length() == 1) {
                continue; // 4K commonly describes screen resolution.
            }
            bounds.exact(money(prices.group(2), prices.group(3)));
            clear(remaining, prices);
        }
        var stock = STOCK.matcher(fold(new String(remaining)));
        boolean inStock = false;
        while (stock.find()) {
            inStock = true;
            clear(remaining, stock);
        }
        var sorts = SORT.matcher(fold(new String(remaining)));
        Order order = Order.RELEVANCE;
        while (sorts.find()) {
            Order requested = switch (sorts.group(1)) {
                case "gia tu cao den thap", "tu cao den thap", "gia giam dan", "gia cao nhat", "gia dat nhat",
                     "dat nhat" -> Order.PRICE_DESC;
                case "moi nhat" -> Order.NEWEST;
                default -> Order.PRICE_ASC;
            };
            if (order != Order.RELEVANCE && order != requested) {
                throw invalid("Chỉ chọn một thứ tự sắp xếp.");
            }
            order = requested;
            clear(remaining, sorts);
        }
        bounds.validate();
        String semantic = new String(remaining).strip().replaceAll(" +", " ");
        if (bounds.min != null || bounds.max != null || inStock || order != Order.RELEVANCE) {
            semantic = semantic.replaceFirst("^[,;:–-]+ *", "").replaceFirst("[,;:–-]+$", "").strip();
            if (GENERIC.matcher(words(semantic)).matches()) {
                semantic = "";
            }
        }
        return new Query(semantic, bounds.min, bounds.max, inStock, order);
    }

    private static String inheritedUnit(String number, String ownUnit, String otherUnit) {
        return ownUnit != null || isLiteralDong(number) ? ownUnit : otherUnit;
    }

    private static boolean isLiteralDong(String number) {
        return GROUPED.matcher(number).matches() || number.matches("\\d{4,}");
    }

    private static BigDecimal money(String number, String unit) {
        try {
            String decimal = GROUPED.matcher(number).matches() ? number.replace(".", "") : number.replace(',', '.');
            BigDecimal amount = new BigDecimal(decimal).multiply(switch (unit == null ? "" : unit) {
                case "trieu", "tr" -> BigDecimal.valueOf(1_000_000);
                case "nghin", "ngan", "k" -> BigDecimal.valueOf(1_000);
                default -> BigDecimal.ONE;
            }).stripTrailingZeros();
            if (amount.scale() > 2 || amount.compareTo(MAX_PRICE) > 0) {
                throw invalid("Số tiền nằm ngoài phạm vi hỗ trợ.");
            }
            return amount;
        } catch (NumberFormatException e) {
            throw invalid("Số tiền không hợp lệ.");
        }
    }

    // Keep offsets aligned with the original so removing a filter preserves Vietnamese text.
    private static String fold(String original) {
        var folded = new StringBuilder(original.length());
        for (char ch : original.toCharArray()) {
            char base = Normalizer.normalize(String.valueOf(Character.toLowerCase(ch)), Normalizer.Form.NFD).charAt(0);
            folded.append(base == 'đ' ? 'd' : base);
        }
        return folded.toString();
    }

    static String words(String text) {
        return fold(text).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static void clear(char[] remaining, Matcher match) {
        Arrays.fill(remaining, match.start(), match.end(), ' ');
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record Bound(BigDecimal amount, boolean inclusive) {}
    enum Order { RELEVANCE, PRICE_ASC, PRICE_DESC, NEWEST }

    record Query(String semantic, Bound min, Bound max, boolean inStock, Order order) {
        boolean hasConstraints() {
            return min != null || max != null || inStock || order != Order.RELEVANCE;
        }
    }

    private static final class Bounds {
        private Bound min;
        private Bound max;

        void lower(BigDecimal price, boolean inclusive) {
            int comparison = min == null ? 1 : price.compareTo(min.amount());
            if (comparison > 0 || (comparison == 0 && !inclusive)) {
                min = new Bound(price, inclusive);
            }
        }

        void upper(BigDecimal price, boolean inclusive) {
            int comparison = max == null ? -1 : price.compareTo(max.amount());
            if (comparison < 0 || (comparison == 0 && !inclusive)) {
                max = new Bound(price, inclusive);
            }
        }

        void exact(BigDecimal price) {
            lower(price, true);
            upper(price, true);
        }

        void validate() {
            if (min != null && max != null) {
                int comparison = min.amount().compareTo(max.amount());
                if (comparison > 0 || (comparison == 0 && (!min.inclusive() || !max.inclusive()))) {
                    throw invalid("Khoảng giá không hợp lệ.");
                }
            }
        }
    }
}
