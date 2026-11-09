const shopSearch = document.getElementById('shop-search');

if (shopSearch) {
    const mode = document.getElementById('shop-search-mode');
    const query = document.getElementById('shop-search-query');
    const loading = document.getElementById('shop-search-loading');
    const submit = shopSearch.querySelector('button[type="submit"]');

    function updateMode() {
        if (mode.value === 'ai') {
            query.maxLength = Number(shopSearch.dataset.aiMax);
            query.placeholder = 'Ví dụ: Laptop phù hợp cho sinh viên lập trình';
        } else {
            query.removeAttribute('maxlength');
            query.placeholder = 'Tên sản phẩm';
        }
    }

    mode.addEventListener('change', updateMode);
    shopSearch.addEventListener('submit', () => {
        loading.hidden = false;
        submit.disabled = true;
        shopSearch.setAttribute('aria-busy', 'true');
    });
    window.addEventListener('pageshow', () => {
        loading.hidden = true;
        submit.disabled = false;
        shopSearch.removeAttribute('aria-busy');
        updateMode();
    });
}
