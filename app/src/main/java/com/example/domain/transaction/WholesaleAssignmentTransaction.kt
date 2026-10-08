package com.example.domain.transaction

import java.math.BigDecimal
import java.security.MessageDigest

/**
 * A decimal USD amount. Values are deliberately not rounded or defaulted by the domain. Callers
 * decide the precision of a supplied amount; equality and arithmetic use the numeric value rather
 * than BigDecimal's scale.
 *
 * Negative values can be represented so that [WholesaleTransactionValidator] can report them as
 * input errors instead of silently changing them to zero.
 */
class UsdAmount(value: BigDecimal) : Comparable<UsdAmount> {
    val value: BigDecimal = value.stripTrailingZeros()

    operator fun plus(other: UsdAmount): UsdAmount = UsdAmount(value.add(other.value))

    operator fun minus(other: UsdAmount): UsdAmount = UsdAmount(value.subtract(other.value))

    fun isNegative(): Boolean = value.signum() < 0

    fun isPositive(): Boolean = value.signum() > 0

    fun isZero(): Boolean = value.signum() == 0

    override fun compareTo(other: UsdAmount): Int = value.compareTo(other.value)

    override fun equals(other: Any?): Boolean = other is UsdAmount && value.compareTo(other.value) == 0

    override fun hashCode(): Int = value.stripTrailingZeros().hashCode()

    override fun toString(): String = "USD ${value.toPlainString()}"

    companion object {
        fun of(value: String): UsdAmount = UsdAmount(value.toBigDecimal())

        fun of(value: Long): UsdAmount = UsdAmount(value.toBigDecimal())

        fun of(value: BigDecimal): UsdAmount = UsdAmount(value)
    }
}

data class TransactionParty(
    val name: String,
    val id: String? = null,
    val email: String? = null,
    val phone: String? = null
)

enum class SellerContractStatus {
    DRAFT,
    SIGNED,
    TERMINATED,
    EXPIRED
}

enum class AssignmentAgreementStatus {
    PROPOSED,
    EXECUTED,
    TERMINATED
}

/** Explicit earnest-money terms. A zero amount is different from an omitted amount. */
data class EarnestMoney(
    val amount: UsdAmount? = null,
    val dueDate: TransactionDate? = null
)

data class InspectionPeriod(
    /** Number of days stated in the terms; null means the period was not supplied. */
    val days: Int? = null
)

/**
 * Dates supplied by the parties for a contract. The model does not infer a closing date from a
 * period. If both are supplied, the validator checks that they are internally consistent.
 */
data class ClosingTimeline(
    val effectiveDate: TransactionDate? = null,
    val closingDate: TransactionDate? = null,
    val closedOn: TransactionDate? = null
)

data class SellerContractTerms(
    val contractId: String? = null,
    val status: SellerContractStatus = SellerContractStatus.DRAFT,
    val purchasePrice: UsdAmount? = null,
    val earnestMoney: EarnestMoney = EarnestMoney(),
    val inspectionPeriod: InspectionPeriod = InspectionPeriod(),
    val timeline: ClosingTimeline = ClosingTimeline()
)

data class OfferTerms(
    val purchasePrice: UsdAmount? = null,
    val earnestMoney: EarnestMoney = EarnestMoney(),
    val inspectionPeriod: InspectionPeriod = InspectionPeriod(),
    val timeline: ClosingTimeline = ClosingTimeline()
)

data class AssignmentAgreement(
    val agreementId: String? = null,
    val status: AssignmentAgreementStatus = AssignmentAgreementStatus.PROPOSED,
    /** Explicit fee if one was agreed. It is never inferred into this field. */
    val assignmentFee: UsdAmount? = null,
    val executedOn: TransactionDate? = null
)

enum class TransactionLifecycle {
    SELLER_CONTRACT,
    BUYER_MARKETING,
    ASSIGNMENT,
    CLOSING,
    CLOSED,
    CANCELLED
}

enum class AssignmentState {
    NOT_STARTED,
    MARKETING,
    PENDING,
    ASSIGNED,
    COMPLETED,
    CANCELLED
}

data class LifecycleTransition(
    val from: TransactionLifecycle,
    val to: TransactionLifecycle,
    val idempotencyKey: String,
    val occurredOn: TransactionDate,
    val version: Long
)

data class ProcessedTransactionCommand(
    val idempotencyKey: String,
    val requestFingerprint: String,
    val resultingVersion: Long
)

/**
 * The domain aggregate. Persistence adapters should store [version], [transitions], and
 * [processedCommands] as part of the same atomic write as the aggregate.
 */
data class WholesaleAssignmentTransaction(
    val transactionId: String,
    val seller: TransactionParty,
    val sellerContract: SellerContractTerms,
    val buyer: TransactionParty? = null,
    val offerTerms: OfferTerms? = null,
    val assignmentAgreement: AssignmentAgreement? = null,
    val lifecycle: TransactionLifecycle = TransactionLifecycle.SELLER_CONTRACT,
    val assignmentState: AssignmentState = AssignmentState.NOT_STARTED,
    val cancellationReason: String? = null,
    val version: Long = 0L,
    val transitions: List<LifecycleTransition> = emptyList(),
    val processedCommands: List<ProcessedTransactionCommand> = emptyList()
)

/** Nullable input boundary used to report incomplete data without inventing values. */
data class WholesaleAssignmentTransactionDraft(
    val transactionId: String? = null,
    val seller: TransactionParty? = null,
    val sellerContract: SellerContractTerms? = null,
    val buyer: TransactionParty? = null,
    val offerTerms: OfferTerms? = null,
    val assignmentAgreement: AssignmentAgreement? = null,
    val lifecycle: TransactionLifecycle = TransactionLifecycle.SELLER_CONTRACT
)

data class AssignmentEconomics(
    val sellerContractPrice: UsdAmount?,
    val buyerAssignmentPrice: UsdAmount?,
    val explicitAssignmentFee: UsdAmount?,
    /** Buyer price minus seller contract price, only when both values were supplied. */
    val grossSpread: UsdAmount?,
    /** Explicit fee when supplied, otherwise the calculable gross spread. */
    val assignmentFee: UsdAmount?,
    val sellerEarnestMoney: UsdAmount?,
    val buyerEarnestMoney: UsdAmount?
) {
    val hasCompletePriceInputs: Boolean
        get() = sellerContractPrice != null && buyerAssignmentPrice != null
}

/**
 * Calculates only values supported by explicit inputs. In particular, missing prices are not
 * treated as zero and earnest money is not subtracted from the fee: it is a deposit, not an
 * assumed cost. Costs, taxes, financing, and legal/accounting outcomes are intentionally outside
 * this domain model.
 */
object AssignmentEconomicsCalculator {
    fun calculate(transaction: WholesaleAssignmentTransaction): AssignmentEconomics =
        calculate(
            sellerContract = transaction.sellerContract,
            offerTerms = transaction.offerTerms,
            assignmentAgreement = transaction.assignmentAgreement
        )

    fun calculate(
        sellerContract: SellerContractTerms,
        offerTerms: OfferTerms?,
        assignmentAgreement: AssignmentAgreement?
    ): AssignmentEconomics {
        val sellerPrice = sellerContract.purchasePrice
        val buyerPrice = offerTerms?.purchasePrice
        val grossSpread = if (sellerPrice != null && buyerPrice != null) {
            buyerPrice - sellerPrice
        } else {
            null
        }
        val explicitFee = assignmentAgreement?.assignmentFee
        return AssignmentEconomics(
            sellerContractPrice = sellerPrice,
            buyerAssignmentPrice = buyerPrice,
            explicitAssignmentFee = explicitFee,
            grossSpread = grossSpread,
            assignmentFee = explicitFee ?: grossSpread,
            sellerEarnestMoney = sellerContract.earnestMoney.amount,
            buyerEarnestMoney = offerTerms?.earnestMoney?.amount
        )
    }
}

data class TransactionTransitionRequest(
    val target: TransactionLifecycle,
    val idempotencyKey: String,
    val occurredOn: TransactionDate,
    /** Required when moving to [TransactionLifecycle.ASSIGNMENT]. */
    val buyer: TransactionParty? = null,
    val offerTerms: OfferTerms? = null,
    /** PROPOSED is sufficient for entering ASSIGNMENT; EXECUTED is required for CLOSING. */
    val assignmentAgreement: AssignmentAgreement? = null,
    /** Used for the closing completion date. */
    val closedOn: TransactionDate? = null,
    val cancellationReason: String? = null
)

sealed interface TransactionTransitionResult {
    data class Applied(
        val transaction: WholesaleAssignmentTransaction,
        val replayed: Boolean = false
    ) : TransactionTransitionResult

    data class Rejected(
        val transaction: WholesaleAssignmentTransaction,
        val violations: List<TransactionViolation>
    ) : TransactionTransitionResult

    data class IdempotencyConflict(
        val transaction: WholesaleAssignmentTransaction,
        val idempotencyKey: String,
        val expectedFingerprint: String,
        val actualFingerprint: String
    ) : TransactionTransitionResult
}

sealed interface TransactionCreationResult {
    data class Created(val transaction: WholesaleAssignmentTransaction) : TransactionCreationResult
    data class Rejected(val validation: TransactionValidationResult) : TransactionCreationResult
}

/** Pure workflow and idempotency boundary for the transaction aggregate. */
object WholesaleAssignmentTransactionWorkflow {
    fun create(draft: WholesaleAssignmentTransactionDraft): TransactionCreationResult {
        val validation = WholesaleTransactionValidator.validate(draft)
        if (!validation.isValid) return TransactionCreationResult.Rejected(validation)

        val transaction = WholesaleAssignmentTransaction(
            transactionId = draft.transactionId!!.trim(),
            seller = draft.seller!!,
            sellerContract = draft.sellerContract!!,
            buyer = draft.buyer,
            offerTerms = draft.offerTerms,
            assignmentAgreement = draft.assignmentAgreement,
            lifecycle = draft.lifecycle,
            assignmentState = assignmentStateFor(draft.lifecycle),
            version = 0L
        )
        return TransactionCreationResult.Created(transaction)
    }

    fun transition(
        transaction: WholesaleAssignmentTransaction,
        request: TransactionTransitionRequest
    ): TransactionTransitionResult {
        val fingerprint = TransactionRequestFingerprint.of(request)
        val existing = transaction.processedCommands.firstOrNull { it.idempotencyKey == request.idempotencyKey }
        if (existing != null) {
            return if (existing.requestFingerprint == fingerprint) {
                TransactionTransitionResult.Applied(transaction, replayed = true)
            } else {
                TransactionTransitionResult.IdempotencyConflict(
                    transaction = transaction,
                    idempotencyKey = request.idempotencyKey,
                    expectedFingerprint = existing.requestFingerprint,
                    actualFingerprint = fingerprint
                )
            }
        }

        val violations = WholesaleTransactionValidator.validateTransition(transaction, request)
        if (violations.isNotEmpty()) {
            return TransactionTransitionResult.Rejected(transaction, violations)
        }

        val updatedBuyer = request.buyer ?: transaction.buyer
        val updatedOfferTerms = request.offerTerms ?: transaction.offerTerms
        val updatedAssignment = request.assignmentAgreement ?: transaction.assignmentAgreement
        val updatedTimeline = if (request.target == TransactionLifecycle.CLOSED) {
            transaction.sellerContract.timeline.copy(closedOn = request.closedOn)
        } else {
            transaction.sellerContract.timeline
        }
        val updatedSellerContract = transaction.sellerContract.copy(timeline = updatedTimeline)
        val nextVersion = transaction.version + 1L
        val transition = LifecycleTransition(
            from = transaction.lifecycle,
            to = request.target,
            idempotencyKey = request.idempotencyKey,
            occurredOn = request.occurredOn,
            version = nextVersion
        )
        val updated = transaction.copy(
            sellerContract = updatedSellerContract,
            buyer = updatedBuyer,
            offerTerms = updatedOfferTerms,
            assignmentAgreement = updatedAssignment,
            lifecycle = request.target,
            assignmentState = assignmentStateFor(request.target),
            cancellationReason = if (request.target == TransactionLifecycle.CANCELLED) {
                request.cancellationReason?.trim()
            } else {
                transaction.cancellationReason
            },
            version = nextVersion,
            transitions = transaction.transitions + transition,
            processedCommands = transaction.processedCommands + ProcessedTransactionCommand(
                idempotencyKey = request.idempotencyKey,
                requestFingerprint = fingerprint,
                resultingVersion = nextVersion
            )
        )
        return TransactionTransitionResult.Applied(updated)
    }

    private fun assignmentStateFor(lifecycle: TransactionLifecycle): AssignmentState = when (lifecycle) {
        TransactionLifecycle.SELLER_CONTRACT -> AssignmentState.NOT_STARTED
        TransactionLifecycle.BUYER_MARKETING -> AssignmentState.MARKETING
        TransactionLifecycle.ASSIGNMENT -> AssignmentState.PENDING
        TransactionLifecycle.CLOSING -> AssignmentState.ASSIGNED
        TransactionLifecycle.CLOSED -> AssignmentState.COMPLETED
        TransactionLifecycle.CANCELLED -> AssignmentState.CANCELLED
    }
}

private object TransactionRequestFingerprint {
    fun of(request: TransactionTransitionRequest): String {
        val canonical = buildString {
            append(request.target.name).append('|')
            append(request.occurredOn).append('|')
            append(party(request.buyer)).append('|')
            append(offer(request.offerTerms)).append('|')
            append(assignment(request.assignmentAgreement)).append('|')
            append(request.closedOn ?: "").append('|')
            append(request.cancellationReason.orEmpty())
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun party(value: TransactionParty?): String = value?.let {
        listOf(it.id.orEmpty(), it.name, it.email.orEmpty(), it.phone.orEmpty()).joinToString("~")
    }.orEmpty()

    private fun amount(value: UsdAmount?): String = value?.value?.toPlainString().orEmpty()

    private fun earnestMoney(value: EarnestMoney): String =
        listOf(amount(value.amount), value.dueDate?.toString().orEmpty()).joinToString("~")

    private fun inspection(value: InspectionPeriod): String = value.days?.toString().orEmpty()

    private fun timeline(value: ClosingTimeline): String = listOf(
        value.effectiveDate?.toString().orEmpty(),
        value.closingDate?.toString().orEmpty(),
        value.closedOn?.toString().orEmpty()
    ).joinToString("~")

    private fun offer(value: OfferTerms?): String = value?.let {
        listOf(
            amount(it.purchasePrice),
            earnestMoney(it.earnestMoney),
            inspection(it.inspectionPeriod),
            timeline(it.timeline)
        ).joinToString("^")
    }.orEmpty()

    private fun assignment(value: AssignmentAgreement?): String = value?.let {
        listOf(
            it.agreementId.orEmpty(),
            it.status.name,
            amount(it.assignmentFee),
            it.executedOn?.toString().orEmpty()
        ).joinToString("^")
    }.orEmpty()
}

/**
 * The adapter-facing persistence contract. Implementations must atomically compare [expectedVersion]
 * and write the aggregate, including its processed command keys. No database or UI type is required.
 */
interface WholesaleTransactionStore {
    suspend fun find(transactionId: String): WholesaleAssignmentTransaction?

    suspend fun insert(transaction: WholesaleAssignmentTransaction): Boolean

    suspend fun compareAndSet(
        transactionId: String,
        expectedVersion: Long,
        replacement: WholesaleAssignmentTransaction
    ): Boolean
}

/**
 * Application integration contract. The store implementation owns the atomic compare-and-set; the
 * workflow remains deterministic and can be used without Room, CRM, OfferRepository, or UI code.
 */
interface WholesaleTransactionWorkflowPort {
    suspend fun apply(
        transactionId: String,
        request: TransactionTransitionRequest
    ): TransactionTransitionResult
}
