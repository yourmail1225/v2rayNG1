package com.v2ray.ang.util

/**
 * Pure decision logic for the password that guards adding content, kept JVM-testable.
 *
 * The password is the one the panel shipped with the customer's row. A row with no
 * password leaves importing a config and adding a subscription open, because an app
 * that asks for a password nobody was given cannot be used at all.
 */
object PasswordGate {

    /** Whether a configured password must be asked for before adding content. */
    fun requiresPassword(configured: String): Boolean = configured.isNotBlank()

    /**
     * Whether [input] is the expected password. A blank [configured] never matches, so
     * an empty field cannot unlock anything, and an empty [input] never matches either.
     */
    fun verify(input: String, configured: String): Boolean =
        requiresPassword(configured) && input.isNotEmpty() && input == configured
}