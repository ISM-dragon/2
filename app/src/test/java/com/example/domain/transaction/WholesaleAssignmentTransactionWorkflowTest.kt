package com.example.domain.transaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WholesaleAssignmentTransactionWorkflowTest {
    private val seller = TransactionParty(
        name = "Seller One",
        id = "seller-1",
        email = "seller@example.com"
    )
    private val buyer = TransactionParty(
        name = "Buyer One",
        id = "buyer-1",
        email = "buyer@example.com"
    )

    private val sellerContract = SellerContractTerms(
        contractId = "seller-contract-1",
        status = SellerContractStatus.SIGNED,
        purchasePrice = UsdAmount.of("100000"),
        earnestMoney = EarnestMoney(
            amount = UsdAmount.of("1000"),
            dueDate = TransactionDate.of(2026, 1, 2)
        ),
        inspectionPeriod = InspectionPeriod(days = 10),
        timeline = ClosingTimeline(
            effectiveDate = TransactionDate.of(2026, 1, 1),
            closingDate = TransactionDate.of(2026, 2, 1)
        )
    )

    private val offerTerms = OfferTerms(
        purchasePrice = UsdAmount.of("120000"),
        earnestMoney = EarnestMoney(
            amount = UsdAmount.of("2000"),
            dueDate = TransactionDate.of(2026, 1, 12)
        ),
        inspectionPeriod = InspectionPeriod(days = 5),
        timeline = ClosingTimeline(
            effectiveDate = TransactionDate.of(2026, 1, 10),
            closingDate = TransactionDate.of(2026, 1, 31)
        )
    )

    private fun initial(): WholesaleAssignmentTransaction {
        val result = WholesaleAssignmentTransactionWorkflow.create(
            WholesaleAssignmentTransactionDraft(
                transactionId = "txn-1",
                seller = seller,
                sellerContract = sellerContract
            )
        )
        assertTrue("fixture should be valid: $result", result is TransactionCreationResult.Created)
        return (result as TransactionCreationResult.Created).transaction
    }

    private fun transition(
        transaction: WholesaleAssignmentTransaction,
        target: TransactionLifecycle,
        key: String,
        buyer: TransactionParty? = null,
        offer: OfferTerms? = null,
        assignment: AssignmentAgreement? = null,
        closedOn: TransactionDate? = null,
        cancellationReason: String? = null
    ): WholesaleAssignmentTransaction {
        val result = WholesaleAssignmentTransactionWorkflow.transition(
            transaction,
            TransactionTransitionRequest(
                target = target,
                idempotencyKey = key,
                occurredOn = TransactionDate.of(2026, 1, 1),
                buyer = buyer,
                offerTerms = offer,
                assignmentAgreement = assignment,
                closedOn = closedOn,
                cancellationReason = cancellationReason
            )
        )
        assertTrue("expected applied transition: $result", result is TransactionTransitionResult.Applied)
        return (result as TransactionTransitionResult.Applied).transaction
    }

    @Test
    fun `lifecycle supports seller contract through closing in order`() {
        var transaction = initial()

        transaction = transition(
            transaction = transaction,
            target = TransactionLifecycle.BUYER_MARKETING,
            key = "command-marketing"
        )
        assertEquals(AssignmentState.MARKETING, transaction.assignmentState)

        transaction = transition(
            transaction = transaction,
            target = TransactionLifecycle.ASSIGNMENT,
            key = "command-assignment",
            buyer = buyer,
            offer = offerTerms,
            assignment = AssignmentAgreement(
                agreementId = "assignment-1",
                status = AssignmentAgreementStatus.PROPOSED,
                assignmentFee = UsdAmount.of("20000")
            )
        )
        assertEquals(TransactionLifecycle.ASSIGNMENT, transaction.lifecycle)
        assertEquals(AssignmentState.PENDING, transaction.assignmentState)

        transaction = transition(
            transaction = transaction,
            target = TransactionLifecycle.CLOSING,
            key = "command-closing",
            assignment = AssignmentAgreement(
                agreementId = "assignment-1",
                status = AssignmentAgreementStatus.EXECUTED,
                assignmentFee = UsdAmount.of("20000"),
                executedOn = TransactionDate.of(2026, 1, 15)
            )
        )
        assertEquals(AssignmentState.ASSIGNED, transaction.assignmentState)

        transaction = transition(
            transaction = transaction,
            target = TransactionLifecycle.CLOSED,
            key = "command-closed",
            closedOn = TransactionDate.of(2026, 2, 1)
        )
        assertEquals(AssignmentState.COMPLETED, transaction.assignmentState)
        assertEquals(4L, transaction.version)
        assertEquals(
            listOf(
                TransactionLifecycle.SELLER_CONTRACT,
                TransactionLifecycle.BUYER_MARKETING,
                TransactionLifecycle.ASSIGNMENT,
                TransactionLifecycle.CLOSING
            ),
            transaction.transitions.map { it.from }
        )
    }

    @Test
    fun `invalid lifecycle jumps and terminal transitions are rejected`() {
        val transaction = initial()
        val shortcut = WholesaleAssignmentTransactionWorkflow.transition(
            transaction,
            TransactionTransitionRequest(
                target = TransactionLifecycle.CLOSING,
                idempotencyKey = "shortcut",
                occurredOn = TransactionDate.of(2026, 1, 1)
            )
        )
        assertTrue(shortcut is TransactionTransitionResult.Rejected)
        assertTrue(
            (shortcut as TransactionTransitionResult.Rejected).violations.any {
                it.code == TransactionViolationCode.INVALID_STATE_TRANSITION
            }
        )

        val closed = transition(
            transaction,
            TransactionLifecycle.CANCELLED,
            "cancel",
            cancellationReason = "seller withdrew"
        )
        val afterTerminal = WholesaleAssignmentTransactionWorkflow.transition(
            closed,
            TransactionTransitionRequest(
                target = TransactionLifecycle.BUYER_MARKETING,
                idempotencyKey = "after-cancel",
                occurredOn = TransactionDate.of(2026, 1, 2)
            )
        )
        assertTrue(afterTerminal is TransactionTransitionResult.Rejected)
        assertEquals(TransactionLifecycle.CANCELLED, closed.lifecycle)
        assertEquals("seller withdrew", closed.cancellationReason)
    }

    @Test
    fun `transition is idempotent and a key cannot be reused for another request`() {
        val transaction = initial()
        val request = TransactionTransitionRequest(
            target = TransactionLifecycle.BUYER_MARKETING,
            idempotencyKey = "same-command",
            occurredOn = TransactionDate.of(2026, 1, 1)
        )
        val first = WholesaleAssignmentTransactionWorkflow.transition(transaction, request)
        assertTrue(first is TransactionTransitionResult.Applied)
        val applied = (first as TransactionTransitionResult.Applied).transaction

        val replay = WholesaleAssignmentTransactionWorkflow.transition(applied, request)
        assertTrue(replay is TransactionTransitionResult.Applied)
        val replayed = replay as TransactionTransitionResult.Applied
        assertTrue(replayed.replayed)
        assertEquals(applied, replayed.transaction)
        assertEquals(1L, applied.version)
        assertEquals(1, applied.transitions.size)

        val conflicting = WholesaleAssignmentTransactionWorkflow.transition(
            applied,
            request.copy(target = TransactionLifecycle.CANCELLED, cancellationReason = "changed")
        )
        assertTrue(conflicting is TransactionTransitionResult.IdempotencyConflict)
        assertEquals(TransactionLifecycle.BUYER_MARKETING, applied.lifecycle)
    }

    @Test
    fun `repeated application of a command does not create another history entry`() {
        val initial = initial()
        val request = TransactionTransitionRequest(
            target = TransactionLifecycle.BUYER_MARKETING,
            idempotencyKey = "deterministic-transition",
            occurredOn = TransactionDate.of(2026, 1, 1)
        )
        val once = WholesaleAssignmentTransactionWorkflow.transition(initial, request)
            as TransactionTransitionResult.Applied
        val twice = WholesaleAssignmentTransactionWorkflow.transition(once.transaction, request)
            as TransactionTransitionResult.Applied

        assertEquals(once.transaction.version, twice.transaction.version)
        assertEquals(once.transaction.transitions, twice.transaction.transitions)
        assertEquals(once.transaction.processedCommands, twice.transaction.processedCommands)
    }

    @Test
    fun `assignment economics use only supplied values`() {
        val beforeBuyer = AssignmentEconomicsCalculator.calculate(
            sellerContract = sellerContract,
            offerTerms = null,
            assignmentAgreement = null
        )
        assertNull(beforeBuyer.grossSpread)
        assertNull(beforeBuyer.assignmentFee)
        assertFalse(beforeBuyer.hasCompletePriceInputs)

        val economics = AssignmentEconomicsCalculator.calculate(
            sellerContract = sellerContract,
            offerTerms = offerTerms,
            assignmentAgreement = AssignmentAgreement(
                status = AssignmentAgreementStatus.PROPOSED,
                assignmentFee = UsdAmount.of("20000")
            )
        )
        assertEquals(UsdAmount.of("20000"), economics.grossSpread)
        assertEquals(UsdAmount.of("20000"), economics.assignmentFee)
        assertEquals(UsdAmount.of("2000"), economics.buyerEarnestMoney)
    }

    @Test
    fun `negative and contradictory terms are rejected without normalization`() {
        val negative = WholesaleTransactionValidator.validate(
            WholesaleAssignmentTransactionDraft(
                transactionId = "negative",
                seller = seller,
                sellerContract = sellerContract.copy(purchasePrice = UsdAmount.of("-1"))
            )
        )
        assertFalse(negative.isValid)
        assertTrue(negative.has(TransactionViolationCode.NEGATIVE_AMOUNT))
        assertEquals(UsdAmount.of("-1"), negativeInputPrice())

        val contradictory = WholesaleTransactionValidator.validate(
            WholesaleAssignmentTransactionDraft(
                transactionId = "contradictory",
                seller = seller,
                sellerContract = sellerContract,
                lifecycle = TransactionLifecycle.ASSIGNMENT,
                buyer = buyer,
                offerTerms = offerTerms.copy(
                    purchasePrice = UsdAmount.of("120000"),
                    timeline = ClosingTimeline(
                        effectiveDate = TransactionDate.of(2026, 1, 10),
                        closingDate = TransactionDate.of(2026, 2, 2)
                    )
                ),
                assignmentAgreement = AssignmentAgreement(
                    agreementId = "assignment-1",
                    assignmentFee = UsdAmount.of("19000")
                )
            )
        )
        assertFalse(contradictory.isValid)
        assertTrue(contradictory.has(TransactionViolationCode.CONTRADICTORY_TERMS))
    }

    @Test
    fun `assignment cannot proceed with incomplete terms`() {
        val transaction = transition(initial(), TransactionLifecycle.BUYER_MARKETING, "marketing")
        val rejected = WholesaleAssignmentTransactionWorkflow.transition(
            transaction,
            TransactionTransitionRequest(
                target = TransactionLifecycle.ASSIGNMENT,
                idempotencyKey = "incomplete-assignment",
                occurredOn = TransactionDate.of(2026, 1, 2),
                buyer = buyer,
                offerTerms = OfferTerms(purchasePrice = UsdAmount.of("120000")),
                assignmentAgreement = AssignmentAgreement(agreementId = "assignment-1")
            )
        )
        assertTrue(rejected is TransactionTransitionResult.Rejected)
        val rejectedResult = rejected as TransactionTransitionResult.Rejected
        val violations = rejectedResult.violations
        assertTrue(violations.any { it.field == "offerTerms.earnestMoney.amount" })
        assertTrue(violations.any { it.field == "offerTerms.inspectionPeriod.days" })
        assertTrue(violations.any { it.field == "offerTerms.timeline.closingDate" })
        assertEquals(TransactionLifecycle.BUYER_MARKETING, rejectedResult.transaction.lifecycle)
        assertNotNull(rejectedResult.transaction)
    }

    private fun negativeInputPrice(): UsdAmount = UsdAmount.of("-1")
}
