

package com.rfidsoftwares.integration.config

/**
 * Custom Node endpoint paths (examples centralized for Phase 3).
 *
 * Note: actual backend paths should remain aligned with these constants.
 */
object ApiPathCustom {
    const val AUTH_LOGIN = "/v1/auth/login"
    const val AUTH_REFRESH = "/v1/auth/refresh"
    const val AUTH_REVOKE = "/v1/auth/revoke"

    const val HEALTH_STATUS = "/v1/system/health"

    const val PRODUCTS_FULL = "/v1/products"
    const val PRODUCTS_DELTA = "/v1/products/delta"
    const val PRODUCT_BY_QUERY = "/v1/products/search"

    const val REGISTER_PRODUCT_EPC = "/v1/products/epc/register"

    const val INVENTORY_PUSH_SESSION = "/v1/inventory/sessions/push"
    const val INVENTORY_RECONCILE = "/v1/inventory/reconcile"

    const val CUSTOMER_LOOKUP = "/v1/customers/lookup"
    const val CUSTOMER_CREATE = "/v1/customers"

    const val CART_FROM_EPCS = "/v1/checkout/cart/from-epcs"
    const val CART_MODIFY = "/v1/checkout/cart/modify"
    const val CHECKOUT_GENERATE_BILL = "/v1/checkout/bill"

    const val ANTI_THEFT_UPDATE_TAGS = "/v1/antitheft/tags/update"
    const val RFID_DIAGNOSTICS_UPLOAD = "/v1/rfid/diagnostics"
}

