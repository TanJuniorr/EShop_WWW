package com.example.eshop.tracking;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class SearchKeyword {
    private static final Pattern SAFE_INPUT = Pattern.compile("[\\p{L}\\p{M}\\d ,.<>≤≥=₫-]{1,96}");
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,3}(?:[.,]\\d{1,2})?");
    private static final Set<String> MONEY_UNITS = Set.of("trieu", "tr", "nghin", "ngan", "k");
    private static final Set<String> SHOPPING_WORDS = Set.copyOf(Arrays.asList("""
            toi tui minh em cho can muon dang tim kiem mua goi y xem liet ke cac nhung tat ca
            mau san pham nao co gia o muc theo voi va hoac tu den tren duoi hon cao thap
            nho lon bang khong qua toi da thieu nhat re dat khoang tam con hang san tang giam dan
            dien thoai smartphone laptop may tinh bang ban pc chuot phim tai nghe loa man hinh
            phu kien sac cap pin du phong op lung may anh in o cung gia dung dong ho
            chup anh dep lam viec hoc tap sinh vien lap trinh gaming choi game van phong
            chong on khong day bluetooth nhe ben nhanh tot moi usb
            apple iphone samsung galaxy oppo xiaomi redmi asus dell lenovo thinkpad macbook airpods
            trieu tr nghin ngan k vnd dong d
            """.strip().split("\\s+")));

    private SearchKeyword() {}

    public static String forAnalytics(String input) {
        if (input == null) {
            return null;
        }
        String keyword = Normalizer.normalize(input.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFC)
                .replaceAll("(?U)\\s+", " ");
        if (!SAFE_INPUT.matcher(keyword).matches()) {
            return null;
        }
        String folded = Normalizer.normalize(keyword, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd');
        String[] tokens = folded.split(" ");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i].replaceAll("^[<>≤≥=]+|[,.-]+$", "");
            if (!SHOPPING_WORDS.contains(token) && !(i + 1 < tokens.length && AMOUNT.matcher(token).matches()
                    && MONEY_UNITS.contains(tokens[i + 1].replaceAll("[,.]+$", "")))) {
                return null;
            }
        }
        return keyword;
    }
}
