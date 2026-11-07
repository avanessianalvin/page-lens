package com.pagelens.core.instagram;

/**
 * Where an account's data comes from (stored in {@code ig_account.login_type}). The first two talk to Meta
 * directly; the last two are read-only fallbacks for when the Meta App Dashboard is unreachable
 * (docs/META_ACCESS.md).
 */
public enum LoginType {
    /** Business Login for Instagram: graph.instagram.com, no Facebook Page needed. */
    INSTAGRAM_LOGIN,
    /** Facebook Login: graph.facebook.com, account linked to a Facebook Page. */
    FACEBOOK_LOGIN,
    /** Synced through the Windsor.ai connector API (its Meta app, our API key). Read-only. */
    WINDSOR,
    /** Filled only by CSV uploads (Meta Business Suite export). Read-only, never synced. */
    IMPORT;

    /** True when PageLens holds a Meta token for the account and can call the Graph API itself. */
    public boolean direct() {
        return this == INSTAGRAM_LOGIN || this == FACEBOOK_LOGIN;
    }
}
