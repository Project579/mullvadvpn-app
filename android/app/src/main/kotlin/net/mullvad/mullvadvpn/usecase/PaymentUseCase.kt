package net.mullvad.mullvadvpn.usecase

import android.app.Activity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transform
import net.mullvad.mullvadvpn.constant.VERIFICATION_BACK_OFF_FACTOR
import net.mullvad.mullvadvpn.constant.VERIFICATION_INITIAL_BACK_OFF_MILLISECONDS
import net.mullvad.mullvadvpn.constant.VERIFICATION_MAX_ATTEMPTS
import net.mullvad.mullvadvpn.lib.payment.PaymentRepository
import net.mullvad.mullvadvpn.lib.payment.model.PaymentAvailability
import net.mullvad.mullvadvpn.lib.payment.model.ProductId
import net.mullvad.mullvadvpn.lib.payment.model.PurchaseResult
import net.mullvad.mullvadvpn.lib.payment.model.VerificationResult
import net.mullvad.mullvadvpn.util.retryWithExponentialBackOff

interface PaymentUseCase {
    val paymentAvailability: Flow<PaymentAvailability?>
    val purchaseResult: Flow<PurchaseResult?>

    suspend fun purchaseProduct(productId: ProductId, activityProvider: () -> Activity)

    suspend fun queryPaymentAvailability()

    suspend fun resetPurchaseResult()

    suspend fun verifyPurchases(onSuccessfulVerification: () -> Unit = {})
}

class PlayPaymentUseCase(private val paymentRepository: PaymentRepository) : PaymentUseCase {
    private val _paymentAvailability = MutableStateFlow<PaymentAvailability?>(null)
    private val _purchaseResult = MutableStateFlow<PurchaseResult?>(null)

    override val paymentAvailability = _paymentAvailability.asStateFlow()
    override val purchaseResult = _purchaseResult.asStateFlow()

    override suspend fun purchaseProduct(productId: ProductId, activityProvider: () -> Activity) {
        paymentRepository
            .purchaseProduct(productId, activityProvider)
            .transform {
                emit(it)
                if (it.shouldDelayLoading()) {
                    delay(EXTRA_LOADING_DELAY_MS)
                }
            }
            .collect(_purchaseResult)
    }

    override suspend fun queryPaymentAvailability() {
        paymentRepository.queryPaymentAvailability().collect(_paymentAvailability)
    }

    override suspend fun resetPurchaseResult() {
        _purchaseResult.emit(null)
    }

    override suspend fun verifyPurchases(onSuccessfulVerification: () -> Unit) {
        paymentRepository
            .verifyPurchases()
            .retryWithExponentialBackOff(
                maxAttempts = VERIFICATION_MAX_ATTEMPTS,
                initialBackOffDelay = VERIFICATION_INITIAL_BACK_OFF_MILLISECONDS,
                backOffDelayFactor = VERIFICATION_BACK_OFF_FACTOR
            ) {
                it is VerificationResult.Error
            }
            .collect {
                if (it == VerificationResult.Success) {
                    // Update the payment availability after a successful verification.
                    queryPaymentAvailability()
                    onSuccessfulVerification()
                }
            }
    }

    private fun PurchaseResult?.shouldDelayLoading() =
        this is PurchaseResult.FetchingProducts || this is PurchaseResult.VerificationStarted

    companion object {
        const val EXTRA_LOADING_DELAY_MS = 300L
    }
}

class EmptyPaymentUseCase : PaymentUseCase {
    override val paymentAvailability = MutableStateFlow(PaymentAvailability.ProductsUnavailable)
    override val purchaseResult = MutableStateFlow<PurchaseResult?>(null)

    override suspend fun purchaseProduct(productId: ProductId, activityProvider: () -> Activity) {
        // No op
    }

    override suspend fun queryPaymentAvailability() {
        // No op
    }

    override suspend fun resetPurchaseResult() {
        // No op
    }

    override suspend fun verifyPurchases(onSuccessfulVerification: () -> Unit) {
        // No op
    }
}
