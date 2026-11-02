(function initPromptCopy() {
    const promptSelector = '[data-ai-prompt]';

    async function copyText(text) {
        await navigator.clipboard.writeText(text);
    }

    document.addEventListener('click', async event => {
        if (!(event.target instanceof Element)) {
            return;
        }

        const button = event.target.closest('[data-copy-ai-prompt]');
        const prompt = button?.closest(promptSelector)?.querySelector('[data-ai-prompt-text]');
        if (!(button instanceof HTMLButtonElement) || !prompt) {
            return;
        }

        button.disabled = true;

        try {
            const text = prompt.textContent.replace(/\s+/g, ' ').trim();
            await copyText(text);
            button.textContent = '已复制';
        }
        catch {
            button.textContent = '复制失败';
        }

        window.setTimeout(() => {
            button.textContent = '复制';
            button.disabled = false;
        }, 1600);
    });

})();
