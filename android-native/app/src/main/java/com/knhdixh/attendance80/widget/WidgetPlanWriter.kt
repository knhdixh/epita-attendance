package com.knhdixh.attendance80.widget

import com.knhdixh.attendance80.Clock
import com.knhdixh.attendance80.auth.TokenStore
import com.knhdixh.attendance80.net.AppData
import java.security.MessageDigest

/**
 * Keeps the widget's plan part in step with the data snapshot. "No snapshot" means two things: the cache is not read yet
 * (a cold start, signed in) or nobody is signed in. Only the token store can tell them apart, so this waits for it to load
 * before it acts on a null snapshot.
 */
class WidgetPlanWriter(
    private val tokenStore: TokenStore,
    private val store: WidgetStore,
    private val clock: Clock,
    private val redraw: suspend () -> Unit,
) {
    suspend fun onSnapshot(data: AppData?) {
        val token = tokenStore.awaitToken()
        val owner = token?.let(::ownerKey)
        val now = clock.now()
        val plan = when {
            data != null -> buildWidgetPlan(data, now, clock.zone()).copy(owner = owner)
            token == null -> buildWidgetPlan(null, now, clock.zone()) // signed out: nothing of the account stays
            store.read().owner == owner -> return // signed in, and the cache for this account is still being read
            else -> WidgetSnapshot(signedIn = true, builtAt = now, owner = owner) // another account's items never show
        }
        val old = store.read()
        if (old.classes != plan.classes || old.todo != plan.todo || old.signedIn != plan.signedIn || old.owner != plan.owner) {
            store.updatePlan(plan)
            redraw()
        }
    }

    private fun ownerKey(token: String) =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}
