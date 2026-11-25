// Registers the optional DeepSeek writer model in the Generate Episode Quiz card.
(function () {
    'use strict';

    function registerDeepSeek() {
        if (window.GeneratorCardUi && typeof window.GeneratorCardUi.registerWriterOption === 'function') {
            window.GeneratorCardUi.registerWriterOption({
                value: 'deepseek-flash',
                baseLabel: 'DeepSeek Flash (Optional)',
                providerKey: 'deepseek'
            });
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        setTimeout(registerDeepSeek, 20);
    });
})();
