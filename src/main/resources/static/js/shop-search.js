const shopSearch = document.getElementById('shop-search');

if (shopSearch) {
    const loading = document.getElementById('shop-search-loading');
    const submit = shopSearch.querySelector('button[type="submit"]');

    shopSearch.addEventListener('submit', () => {
        loading.hidden = false;
        submit.disabled = true;
        shopSearch.setAttribute('aria-busy', 'true');
    });
    window.addEventListener('pageshow', () => {
        loading.hidden = true;
        submit.disabled = false;
        shopSearch.removeAttribute('aria-busy');
    });
}
