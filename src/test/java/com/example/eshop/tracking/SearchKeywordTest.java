package com.example.eshop.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SearchKeywordTest {
    @Test
    void retainsRecognizedShoppingPhrasesAndShortCurrencyAmounts() {
        assertEquals("tìm các điện thoại có giá trên 5 triệu",
                SearchKeyword.forAnalytics("  Tìm các điện thoại có giá trên 5 triệu  "));
        assertEquals("laptop dưới 12,5 triệu", SearchKeyword.forAnalytics("laptop dưới 12,5 triệu"));
    }

    @Test
    void discardsTheEntireQueryWhenItMayContainSensitiveInput() {
        assertNull(SearchKeyword.forAnalytics("điện thoại me@example.test"));
        assertNull(SearchKeyword.forAnalytics("điện thoại 0900 000 000"));
        assertNull(SearchKeyword.forAnalytics("laptop password"));
        assertNull(SearchKeyword.forAnalytics("laptop https://example.test/token"));
        assertNull(SearchKeyword.forAnalytics("laptop ".repeat(25)));
    }
}
