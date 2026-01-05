// GuildSettingsStorePatch.js
(function () {
    const storeName = "GuildSettingsStore";
    const dispatcher = window.fluxDispatcher || window.Dispatcher;
    const origStore = window[storeName];

    if (!origStore || !dispatcher) return;

    // Patch the reducer to merge all fields, not just known ones
    const origReduce = origStore.__proto__.reduce;
    origStore.__proto__.reduce = function (state, action) {
        if (action.type === "USER_GUILD_SETTINGS_UPDATE" && action.guildId && action.settings) {
            // Deep merge all fields, including unknown ones
            state = { ...state };
            state[action.guildId] = {
                ...(state[action.guildId] || {}),
                ...action.settings,
                // Deep merge channel_overrides
                channel_overrides: {
                    ...(state[action.guildId]?.channel_overrides || {}),
                    ...(action.settings.channel_overrides || {})
                }
            };
            return state;
        }
        return origReduce.call(this, state, action);
    };

    // Optionally, expose the store for debugging
    window.PatchedGuildSettingsStore = origStore;
})();
