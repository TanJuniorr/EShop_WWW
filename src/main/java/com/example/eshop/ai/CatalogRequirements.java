package com.example.eshop.ai;

import com.example.eshop.entity.Product;

import java.util.regex.Pattern;

public record CatalogRequirements(String chip, String phoneModel, String brand, String display, String remaining) {
    private static final String BRANDS = "tp link|samsung|oppo|xiaomi|redmi|realme|vivo|apple|huawei|honor|nokia|"
            + "google|sony|asus|acer|dell|hp|lenovo|lg|jbl|logitech|anker|baseus|canon";
    private static final Pattern MARKED_BRAND = Pattern.compile("\\b(?:cua )?(?:hang|thuong hieu) (" + BRANDS + ")(?= |$)");
    private static final Pattern PHONE_BRAND = Pattern.compile("^(dien thoai) (?:cua )?(" + BRANDS + ")(?= |$)");
    private static final Pattern BRAND_FOLLOW_UP = Pattern.compile(
            "^(?:(?:nhung|con) )?(?:chi )?(?:(?:cua )?(?:hang|thuong hieu) (" + BRANDS + "|[a-z][a-z0-9]*)"
                    + "|(?:cua )?(" + BRANDS + "))(?: thi sao| thoi| khong| nhe)?$");
    private static final Pattern CHIP = Pattern.compile(
            "\\b(?:(?:co|dung|su dung|trang bi|chay) )?(?:chipset|chip|vi xu ly|bo xu ly|cpu) "
                    + "(?:(?:apple|qualcomm|intel|mediatek|samsung) )?"
                    + "(a\\d+[a-z]?(?: bionic| pro)?|m\\d+(?: pro| max| ultra)?"
                    + "|snapdragon (?:[4-8]s? gen \\d+|\\d{3,4})"
                    + "|(?:dimensity|exynos) \\d{3,4}|core i[3579](?: \\d{4,5}[a-z]+)?)\\b");
    private static final Pattern IPHONE = Pattern.compile(
            "^(?:(dien thoai)(?: (?:thuoc )?dong)? )?"
                    + "(iphone(?: \\d{1,3}(?: pro max| pro| plus| mini)?| se)?)(?= |$)");
    private static final Pattern DISPLAY = Pattern.compile(
            "\\b(?:(?:man hinh|screen|display) )?(dynamic amoled(?: 2x)?|super amoled(?: plus)?|"
                    + "amoled(?: 2x)?|oled|ips(?: lcd)?|lcd)\\b");
    private static final Pattern QUESTION_FILLER = Pattern.compile(
            "(?:(?:nao|co|la|gi|the nao|vay|thi)(?: |$))*$");
    private static final Pattern ALTERNATIVE = Pattern.compile("^(?:va|hoac|hay|so voi|vs) ");

    public static CatalogRequirements parse(String subject) {
        var processor = CHIP.matcher(subject);
        String chip = processor.find() ? processor.group(1) : null;
        String remaining = chip == null ? subject : processor.replaceFirst("").replaceAll(" +", " ").strip();
        var phone = IPHONE.matcher(remaining);
        String model = null;
        if (phone.find() && !ALTERNATIVE.matcher(remaining.substring(phone.end()).stripLeading()).find()) {
            model = phone.group(2);
            remaining = phone.replaceFirst(phone.group(1) == null ? "" : phone.group(1)).strip();
        }
        var markedBrand = MARKED_BRAND.matcher(remaining);
        String brand = null;
        if (markedBrand.find() && !ALTERNATIVE.matcher(remaining.substring(markedBrand.end()).stripLeading()).find()) {
            brand = markedBrand.group(1);
            remaining = markedBrand.replaceFirst("").replaceAll(" +", " ").strip();
        } else {
            var phoneBrand = PHONE_BRAND.matcher(remaining);
            if (phoneBrand.find() && !ALTERNATIVE.matcher(remaining.substring(phoneBrand.end()).stripLeading()).find()) {
                brand = phoneBrand.group(2);
                remaining = phoneBrand.replaceFirst(phoneBrand.group(1)).strip();
            }
        }
        var displayMatcher = DISPLAY.matcher(remaining);
        String display = null;
        if (displayMatcher.find()) {
            String value = displayMatcher.group(1);
            display = value.contains("amoled") ? "amoled"
                    : value.startsWith("ips") ? "ips" : value;
            remaining = displayMatcher.replaceFirst("").replaceAll(" +", " ").strip();
        }
        remaining = QUESTION_FILLER.matcher(remaining).replaceFirst("").strip();
        return new CatalogRequirements(chip, model, brand, display, remaining);
    }

    public static String followUpBrand(String words) {
        var match = BRAND_FOLLOW_UP.matcher(words);
        return match.matches() ? (match.group(1) == null ? match.group(2) : match.group(1)) : null;
    }

    public CatalogRequirements withBrand(String requestedBrand) {
        return new CatalogRequirements(chip, phoneModel, requestedBrand, display, remaining);
    }

    public boolean present() {
        return chip != null || phoneModel != null || brand != null || display != null;
    }

    public boolean matches(Product product) {
        if (!present()) {
            return true;
        }
        String name = ProductSearchQueryParser.words(product.getName());
        if (phoneModel != null && !name.equals(phoneModel) && !name.startsWith(phoneModel + " ")) {
            return false;
        }
        if (brand != null && !matchesBrand(product, name)) {
            return false;
        }
        if (display != null && (product.getDescription() == null
                || !containsPhrase(ProductSearchQueryParser.words(product.getDescription()), display))) {
            return false;
        }
        return chip == null || matchesChip(product, name);
    }

    private boolean matchesBrand(Product product, String name) {
        String category = ProductSearchQueryParser.words(product.getCategory().getName());
        if (!category.isEmpty() && name.startsWith(category + " ")) {
            name = name.substring(category.length() + 1);
        }
        if (name.equals(brand) || name.startsWith(brand + " ")) {
            return true;
        }
        return "apple".equals(brand) && name.matches("(?:iphone|ipad|macbook|airpods)(?: .*|$)");
    }

    private boolean matchesChip(Product product, String name) {
        String description = product.getDescription() == null ? "" : ProductSearchQueryParser.words(product.getDescription());
        var described = CHIP.matcher(description);
        if (described.find()) {
            return containsPhrase(described.group(1), chip);
        }
        var named = CHIP.matcher(name);
        if (named.find()) {
            return containsPhrase(named.group(1), chip);
        }
        // A bare model code in a title can be a phone name, such as Galaxy A16.
        return chip.contains(" ") && containsPhrase(description, chip)
                || containsPhrase(description, chip + " bionic")
                || containsPhrase(description, chip + " pro")
                || containsPhrase(description, "apple " + chip);
    }

    private static boolean containsPhrase(String text, String phrase) {
        return (" " + text + " ").contains(" " + phrase + " ");
    }
}
