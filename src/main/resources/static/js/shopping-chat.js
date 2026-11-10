(() => {
    const widget = document.getElementById('shopping-chat');
    if (!widget) return;

    const toggle = document.getElementById('shopping-chat-toggle');
    const panel = document.getElementById('shopping-chat-panel');
    const close = document.getElementById('shopping-chat-close');
    const restart = document.getElementById('shopping-chat-new');
    const log = document.getElementById('shopping-chat-log');
    const welcome = document.getElementById('shopping-chat-welcome');
    const form = document.getElementById('shopping-chat-form');
    const input = document.getElementById('shopping-chat-message');
    const send = form.querySelector('button[type="submit"]');
    const status = document.getElementById('shopping-chat-status');
    const currency = new Intl.NumberFormat('vi-VN', {maximumFractionDigits: 2});
    let conversationId = null;
    let pending = false;

    function scrollToLatest() {
        log.scrollTop = log.scrollHeight;
    }

    function show(open) {
        panel.hidden = !open;
        toggle.setAttribute('aria-expanded', String(open));
        (open ? input : toggle).focus();
        if (open) {
            scrollToLatest();
            widget.dispatchEvent(new CustomEvent('eshop:chat-open', {bubbles: true}));
        }
    }

    function bubble(text, fromUser) {
        const message = document.createElement('p');
        message.className = fromUser ? 'shopping-chat-bubble shopping-chat-bubble-user' : 'shopping-chat-bubble';
        message.textContent = text;
        log.append(message);
    }

    function webUrl(value) {
        if (!value) return null;
        try {
            const url = new URL(value, document.baseURI);
            return ['http:', 'https:'].includes(url.protocol) ? url : null;
        } catch {
            return null;
        }
    }

    function productCard(product, trackingId) {
        const card = document.createElement('article');
        card.className = 'shopping-chat-product';
        const imageUrl = webUrl(product.imageUrl);
        if (imageUrl) {
            const img = document.createElement('img');
            img.src = imageUrl.href;
            img.alt = '';
            img.loading = 'lazy';
            card.append(img);
        }
        const details = document.createElement('div');
        const name = document.createElement('strong');
        name.textContent = product.name;
        const facts = document.createElement('div');
        facts.textContent = currency.format(product.price) + ' ₫ · ' + (product.inStock ? 'Còn hàng' : 'Hết hàng');
        details.append(name, facts);
        const productUrl = webUrl(product.productUrl);
        if (productUrl?.origin === window.location.origin) {
            const link = document.createElement('a');
            link.className = 'btn btn-sm btn-outline-primary mt-1';
            link.href = productUrl.href;
            link.textContent = 'Xem chi tiết';
            link.setAttribute('aria-label', 'Xem chi tiết ' + product.name);
            if (trackingId) {
                link.dataset.trackingOffer = trackingId;
                link.dataset.trackingProduct = product.id;
            }
            details.append(link);
        }
        card.append(details);
        log.append(card);
    }

    toggle.addEventListener('click', () => show(panel.hidden));
    close.addEventListener('click', () => show(false));
    panel.addEventListener('keydown', event => {
        if (event.key === 'Escape') show(false);
    });
    restart.addEventListener('click', () => {
        conversationId = null;
        log.replaceChildren(welcome.cloneNode(true));
        input.value = '';
        status.textContent = '';
        input.focus();
    });
    input.addEventListener('keydown', event => {
        if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
            event.preventDefault();
            if (!pending) form.requestSubmit();
        }
    });
    form.addEventListener('submit', async event => {
        event.preventDefault();
        const message = input.value.trim();
        if (pending || !message) return;
        pending = send.disabled = restart.disabled = true;
        input.value = '';
        bubble(message, true);
        status.classList.remove('text-danger');
        status.textContent = 'Trợ lý đang trả lời…';
        scrollToLatest();
        try {
            const response = await fetch(widget.dataset.endpoint, {
                method: 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json', [widget.dataset.csrfHeader]: widget.dataset.csrfToken},
                body: JSON.stringify({conversationId, message})
            });
            const data = response.headers.get('content-type')?.includes('application/json') ? await response.json() : null;
            if (!response.ok || !data) {
                const fallback = response.status === 403 ? 'Phiên đã thay đổi. Bạn có thể tải lại trang để tiếp tục.'
                    : 'Trợ lý AI tạm thời không trả lời được. Bạn có thể thử lại sau.';
                throw new Error(data?.error || fallback);
            }
            conversationId = data.conversationId;
            bubble(data.reply, false);
            data.products.forEach(product => productCard(product, data.trackingId));
            status.textContent = '';
        } catch (failure) {
            status.classList.add('text-danger');
            status.textContent = failure instanceof TypeError
                ? 'Không kết nối được với trợ lý. Bạn thử lại nhé.' : failure.message;
            if (!input.value) input.value = message;
        } finally {
            pending = send.disabled = restart.disabled = false;
            scrollToLatest();
        }
    });
})();
