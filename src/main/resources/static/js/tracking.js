(() => {
    const settings = document.getElementById('tracking-settings');
    if (!settings) return;
    const banner = document.getElementById('tracking-banner');
    const preferences = document.getElementById('tracking-preferences');
    const status = document.getElementById('tracking-status');
    const choices = settings.querySelectorAll('[data-tracking-choice]');
    const headers = {'Content-Type': 'application/json', [settings.dataset.csrfHeader]: settings.dataset.csrfToken};
    const channel = typeof BroadcastChannel === 'function' ? new BroadcastChannel('eshop-analytics-consent') : null;
    let accepted = settings.dataset.consent === 'ACCEPTED';
    let writingChoice = false;
    let count = 0;
    let windowStart = Date.now();

    function show(open) {
        banner.hidden = !open;
        preferences.setAttribute('aria-expanded', String(open));
    }

    async function refresh() {
        if (writingChoice) return;
        accepted = false;
        try {
            const response = await fetch(settings.dataset.consentUrl, {cache: 'no-store', credentials: 'same-origin'});
            if (!response.ok || writingChoice) return;
            const {consent} = await response.json();
            if (writingChoice) return;
            accepted = consent === 'ACCEPTED';
            show(consent === 'UNSET');
        } catch {
            // Storefront navigation works independently of analytics.
        }
    }

    function eventId() {
        const bytes = crypto.getRandomValues(new Uint8Array(16));
        bytes[6] = (bytes[6] & 15) | 64;
        bytes[8] = (bytes[8] & 63) | 128;
        const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
        return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-');
    }

    function send(eventType, fields = {}) {
        if (!accepted) return;
        const now = Date.now();
        if (now - windowStart >= 60_000) {
            windowStart = now;
            count = 0;
        }
        if (count >= 60) return;
        count++;
        fetch(settings.dataset.eventsUrl, {
            method: 'POST', credentials: 'same-origin', keepalive: true, headers,
            body: JSON.stringify({eventId: eventId(), eventType, ...fields})
        }).then(response => {
            if (response.status === 403) refresh();
        }).catch(() => {});
    }

    preferences.addEventListener('click', () => show(banner.hidden));
    choices.forEach(button => button.addEventListener('click', async () => {
        if (writingChoice) return;
        const allowed = button.dataset.trackingChoice === 'yes';
        accepted = false;
        writingChoice = true;
        choices.forEach(choice => { choice.disabled = true; });
        status.textContent = '';
        if (!allowed) channel?.postMessage('pause');
        try {
            const response = await fetch(settings.dataset.consentUrl, {
                method: 'POST', credentials: 'same-origin', headers, body: JSON.stringify({allowed})
            });
            if (!response.ok) throw new Error();
            const {consent} = await response.json();
            accepted = consent === 'ACCEPTED';
            channel?.postMessage('refresh');
            show(false);
            preferences.focus();
        } catch {
            status.textContent = 'Chưa lưu được lựa chọn. Bạn vẫn có thể sử dụng cửa hàng và thử lại sau.';
        } finally {
            writingChoice = false;
            choices.forEach(choice => { choice.disabled = false; });
        }
    }));

    function click(event) {
        if (!event.isTrusted || event.type === 'auxclick' && event.button !== 1) return;
        const link = event.target.closest('a[data-tracking-offer][data-tracking-product]');
        if (link) {
            send('PRODUCT_CLICK', {interactionId: link.dataset.trackingOffer, productId: Number(link.dataset.trackingProduct)});
        }
    }
    document.addEventListener('click', click);
    document.addEventListener('auxclick', click);
    document.addEventListener('eshop:chat-open', () => send('CHATBOT_OPEN'));
    window.addEventListener('pageshow', event => {
        if (event.persisted) refresh();
    });
    if (channel) {
        channel.onmessage = event => {
            if (event.data === 'pause') accepted = false;
            if (event.data === 'refresh') refresh();
        };
    }
    show(settings.dataset.consent === 'UNSET');
})();
