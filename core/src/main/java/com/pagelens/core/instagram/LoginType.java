package com.pagelens.core.instagram;

/** Which Meta API setup an account is connected through (stored in {@code ig_account.login_type}). */
public enum LoginType {
    /** Business Login for Instagram: graph.instagram.com, no Facebook Page needed. */
    INSTAGRAM_LOGIN,
    /** Facebook Login: graph.facebook.com, account linked to a Facebook Page. */
    FACEBOOK_LOGIN
}
