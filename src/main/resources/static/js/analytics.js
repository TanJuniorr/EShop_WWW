(() => {
    const dashboard = document.getElementById('analytics');
    if (!dashboard) return;
    const get = name => document.getElementById('analytics-' + name);
    const period = get('period');
    const start = get('start');
    const end = get('end');
    const sort = get('sort');
    const status = get('status');
    const content = get('content');
    const previous = get('previous');
    const next = get('next');
    const number = new Intl.NumberFormat('vi-VN', {maximumFractionDigits: 1});
    let page = 0;
    let request;

    const format = value => value == null ? '—' : number.format(value);
    const percentage = value => value == null ? '—' : format(value) + '%';
    const text = (tag, value, className) => {
        const element = document.createElement(tag);
        element.textContent = value;
        if (className) element.className = className;
        return element;
    };

    function cards(container, pairs) {
        container.replaceChildren(...pairs.map(([label, value]) => {
            const col = text('div', '', 'col');
            const card = text('div', '', 'border rounded p-3 h-100');
            card.append(text('div', label, 'small text-muted'), text('strong', value, 'fs-5'));
            col.append(card);
            return col;
        }));
    }

    function bars(container, values, showEmpty = true) {
        if (!values.length || showEmpty && !values.some(([, value]) => value > 0)) {
            container.replaceChildren(text('p', 'Chưa có dữ liệu trong khoảng này.', 'text-muted mb-0'));
            return;
        }
        const max = Math.max(1, ...values.map(([, value]) => value));
        container.replaceChildren(...values.map(([label, value, detail]) => {
            const row = text('div', '', 'analytics-bar');
            const meter = document.createElement('progress');
            meter.max = max;
            meter.value = value;
            meter.setAttribute('aria-label', label + ' ' + format(value));
            row.append(text('span', label), meter, text('span', detail ?? format(value)));
            return row;
        }));
    }

    function products(data) {
        const tbody = get('products');
        if (!data.products.length) {
            const row = document.createElement('tr');
            const cell = text('td', 'Chưa có dữ liệu sản phẩm.', 'text-muted');
            cell.colSpan = 7;
            row.append(cell);
            tbody.replaceChildren(row);
        } else {
            tbody.replaceChildren(...data.products.map(product => {
                const row = document.createElement('tr');
                const cells = [product.name || 'Sản phẩm #' + product.id, product.category || '—',
                    format(product.views), format(product.clicks), format(product.carts),
                    format(product.searchClicks), format(product.chatClicks)];
                row.append(...cells.map(value => text('td', value)));
                return row;
            }));
        }
        previous.disabled = data.page === 0;
        next.disabled = !data.moreProducts;
        get('page').textContent = 'Trang ' + (data.page + 1);
    }

    function render(data) {
        const {counts, visitors, conversionRate} = data.summary;
        get('range').textContent = data.start + ' → ' + data.end + ' · ' + data.zone;
        cards(get('summary'), [
            ['Lượt truy cập', format(counts.PAGE_VIEW)], ['Phiên có hoạt động', format(visitors.sessions)],
            ['Tài khoản duy nhất', format(visitors.accounts)], ['Phiên khách ẩn danh', format(visitors.anonymousSessions)],
            ['Xem sản phẩm', format(counts.PRODUCT_VIEW)], ['Lượt AI Search', format(counts.AI_SEARCH)],
            ['Phiên dùng chatbot', format(data.chat.sessions)], ['Thêm vào giỏ', format(counts.ADD_TO_CART)],
            ['Đơn đặt thành công', format(counts.ORDER_PLACED)], ['Đơn hoàn tất', format(counts.ORDER_COMPLETED)],
            ['Tỷ lệ phiên đặt đơn', percentage(conversionRate)]
        ]);
        const {viewed, carted, placed, completed} = data.funnel;
        const funnelLabel = (current, prior) => format(current) + (prior ? ' · ' + percentage(100 * current / prior) : '');
        bars(get('funnel'), [
            ['Xem sản phẩm', viewed, format(viewed)], ['Thêm vào giỏ', carted, funnelLabel(carted, viewed)],
            ['Đặt đơn', placed, funnelLabel(placed, carted)], ['Hoàn tất', completed, funnelLabel(completed, placed)]
        ], false);
        cards(get('search'), [
            ['Lượt tìm', format(counts.AI_SEARCH)], ['Phiên tìm kiếm', format(data.search.sessions)],
            ['Không có kết quả', format(data.search.emptyResults)], ['Tỷ lệ kết quả rỗng', percentage(data.search.emptyRate)],
            ['Lượt tìm lỗi', format(data.search.errors)],
            ['Phản hồi trung bình', data.search.averageMs == null ? '—' : format(data.search.averageMs) + ' ms'],
            ['Click vào kết quả', format(counts.AI_SEARCH_RESULT_CLICK)], ['Tỷ lệ lượt tìm có click', percentage(data.search.clickRate)]
        ]);
        cards(get('chat'), [
            ['Mở chatbot', format(counts.CHATBOT_OPEN)], ['Gửi tin nhắn', format(counts.CHATBOT_MESSAGE_SENT)],
            ['Phiên dùng chatbot', format(data.chat.sessions)], ['Click sản phẩm', format(counts.CHATBOT_PRODUCT_CLICK)],
            ['Tỷ lệ phiên tương tác', percentage(data.chat.engagementRate)]
        ]);
        const daily = (id, key) => bars(get(id), data.daily.map(day => [day.date, day[key]]));
        daily('visits', 'visits');
        daily('search-days', 'searches');
        daily('search-clicks', 'searchClicks');
        bars(get('keywords'), data.keywords.map(keyword => [keyword.keyword, keyword.count]));
        const top = (id, items, metric) => bars(get(id), items.map(item => [item.name || '#' + item.id, item[metric]]));
        top('search-products', data.searchProducts, 'searchClicks');
        top('chat-products', data.chatProducts, 'chatClicks');
        products(data);
        content.hidden = false;
        status.textContent = data.summary.visitors.sessions ? '' : 'Chưa có phiên được ghi nhận trong khoảng này.';
    }

    async function load() {
        request?.abort();
        const current = new AbortController();
        request = current;
        previous.disabled = next.disabled = true;
        status.classList.remove('text-danger');
        status.textContent = 'Đang tải số liệu…';
        const params = new URLSearchParams({period: period.value, sort: sort.value, page: String(page)});
        if (period.value === 'custom') {
            params.set('start', start.value);
            params.set('end', end.value);
        }
        try {
            const response = await fetch(dashboard.dataset.endpoint + '?' + params, {
                credentials: 'same-origin', cache: 'no-store', signal: current.signal
            });
            const data = response.headers.get('content-type')?.includes('application/json') ? await response.json() : null;
            if (!response.ok || !data) throw new Error(data?.error || 'Chưa tải được báo cáo thống kê.');
            if (request === current) render(data);
        } catch (error) {
            if (request !== current) return;
            content.hidden = true;
            status.classList.add('text-danger');
            status.textContent = error instanceof TypeError ? 'Không kết nối được với báo cáo.' : error.message;
        }
    }

    period.addEventListener('change', () => {
        const custom = period.value === 'custom';
        dashboard.querySelectorAll('[data-analytics-date]').forEach(group => { group.hidden = !custom; });
        start.required = end.required = custom;
    });
    get('filters').addEventListener('submit', event => {
        event.preventDefault();
        page = 0;
        load();
    });
    sort.addEventListener('change', () => {
        page = 0;
        if (get('filters').reportValidity()) load();
    });
    previous.addEventListener('click', () => {
        page--;
        load();
    });
    next.addEventListener('click', () => {
        page++;
        load();
    });
    load();
})();
