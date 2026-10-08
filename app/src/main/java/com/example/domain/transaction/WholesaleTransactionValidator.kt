package com.example.domain.transaction

/** Stable machine-readable validation categories for adapters and tests. */
enum class TransactionViolationCode {
    REQUIRED,
    BLANK,
    NEGATIVE_AMOUNT,
    NEGATIVE_DURATION,
    INVALID_DATE_ORDER,
    CONTRADICTORY_TERMS,
    INCOMPLETE_TERMS,
    INVALID_CONTRACT_STATUS,
    INVALID_ASSIGNMENT_STATUS,
    NEGATIVE_ASSIGNMENT_SPREAD,
    INVALID_STATE_TRANSITION,
    IDEMPOTENCY_KEY_REQUIRED,
    CANCELLATION_REASON_REQUIRED,
    VERSION_INVALID
}

data class TransactionViolation(
    val field: String,
    val code: TransactionViolationCode,
    val message: String
)

data class TransactionValidationResult(
    val violations: List<TransactionViolation>
) {
    val isValid: Boolean
        get() = violations.isEmpty()

    fun has(code: TransactionViolationCode): Boolean = violations.any { it.code == code }
}

/**
 * Pure validation rules for the wholesale transaction aggregate. These are data-integrity rules,
 * not legal advice or a determination that any agreement is enforceable.
 */
object WholesaleTransactionValidator {
    private fun violation(
        field: String,
        code: TransactionViolationCode,
        message: String
    ) = TransactionViolation(field, code, message)

    fun validate(draft: WholesaleAssignmentTransactionDraft): TransactionValidationResult {
        val violations = mutableListOf<TransactionViolation>()
        if (draft.transactionId.isNullOrBlank()) {
            violations += violation(
                "transactionId",
                TransactionViolationCode.REQUIRED,
                "A transaction identifier is required."
            )
        }
        val seller = draft.seller
        if (seller == null) {
            violations += violation(
                "seller",
                TransactionViolationCode.REQUIRED,
                "A seller is required."
            )
        } else {
            validateParty(seller, "seller", violations)
        }

        val sellerContract = draft.sellerContract
        if (sellerContract == null) {
            violations += violation(
                "sellerContract",
                TransactionViolationCode.REQUIRED,
                "Seller contract terms are required."
            )
        } else {
            validateSellerContract(sellerContract, violations)
        }

        validateOptionalBuyerAndOffer(
            lifecycle = draft.lifecycle,
            sellerContract = sellerContract,
            buyer = draft.buyer,
            offerTerms = draft.offerTerms,
            assignmentAgreement = draft.assignmentAgreement,
            violations = violations
        )
        return TransactionValidationResult(violations.toList())
    }

    fun validate(transaction: WholesaleAssignmentTransaction): TransactionValidationResult {
        val violations = mutableListOf<TransactionViolation>()
        if (transaction.transactionId.isBlank()) {
            violations += violation(
                "transactionId",
                TransactionViolationCode.BLANK,
                "A transaction identifier cannot be blank."
            )
        }
        validateParty(transaction.seller, "seller", violations)
        validateSellerContract(transaction.sellerContract, violations)
        validateOptionalBuyerAndOffer(
            lifecycle = transaction.lifecycle,
            sellerContract = transaction.sellerContract,
            buyer = transaction.buyer,
            offerTerms = transaction.offerTerms,
            assignmentAgreement = transaction.assignmentAgreement,
            violations = violations
        )
        if (transaction.version < 0L) {
            violations += violation(
                "version",
                TransactionViolationCode.VERSION_INVALID,
                "Aggregate version cannot be negative."
            )
        }
        if (transaction.lifecycle == TransactionLifecycle.CANCELLED &&
            transaction.cancellationReason.isNullOrBlank()
        ) {
            violations += violation(
                "cancellationReason",
                TransactionViolationCode.CANCELLATION_REASON_REQUIRED,
                "A cancellation reason is required for an audit record."
            )
        }
        val expectedAssignmentState = assignmentStateFor(transaction.lifecycle)
        if (transaction.assignmentState != expectedAssignmentState) {
            violations += violation(
                "assignmentState",
                TransactionViolationCode.CONTRADICTORY_TERMS,
                "${transaction.assignmentState} does not match lifecycle ${transaction.lifecycle}."
            )
        }
        return TransactionValidationResult(violations.toList())
    }

    fun validateTransition(
        transaction: WholesaleAssignmentTransaction,
        request: TransactionTransitionRequest
    ): List<TransactionViolation> {
        val violations = mutableListOf<TransactionViolation>()
        if (request.idempotencyKey.isBlank()) {
            violations += violation(
                "idempotencyKey",
                TransactionViolationCode.IDEMPOTENCY_KEY_REQUIRED,
                "Every transition must have a stable, non-blank idempotency key."
            )
        }
        if (request.target == transaction.lifecycle) {
            violations += violation(
                "target",
                TransactionViolationCode.INVALID_STATE_TRANSITION,
                "The transaction is already in ${request.target}."
            )
        } else if (!canTransition(transaction.lifecycle, request.target)) {
            violations += violation(
                "target",
                TransactionViolationCode.INVALID_STATE_TRANSITION,
                "${transaction.lifecycle} cannot transition to ${request.target}."
            )
        }

        if (request.target == TransactionLifecycle.CANCELLED && request.cancellationReason.isNullOrBlank()) {
            violations += violation(
                "cancellationReason",
                TransactionViolationCode.CANCELLATION_REASON_REQUIRED,
                "A cancellation reason is required for an audit record."
            )
        }

        val candidateBuyer = request.buyer ?: transaction.buyer
        val candidateOffer = request.offerTerms ?: transaction.offerTerms
        val candidateAssignment = request.assignmentAgreement ?: transaction.assignmentAgreement
        val candidateTimeline = if (request.target == TransactionLifecycle.CLOSED) {
            transaction.sellerContract.timeline.copy(closedOn = request.closedOn)
        } else {
            transaction.sellerContract.timeline
        }
        val candidate = transaction.copy(
            buyer = candidateBuyer,
            offerTerms = candidateOffer,
            assignmentAgreement = candidateAssignment,
            sellerContract = transaction.sellerContract.copy(timeline = candidateTimeline),
            lifecycle = request.target,
            assignmentState = assignmentStateFor(request.target),
            cancellationReason = if (request.target == TransactionLifecycle.CANCELLED) {
                request.cancellationReason?.trim()
            } else {
                transaction.cancellationReason
            }
        )
        // Keep the transition result useful: return the state-machine error as well as all
        // stage-specific term errors, while not masking an illegal edge.
        val candidateValidation = validate(candidate)
        violations += candidateValidation.violations

        if (request.target == TransactionLifecycle.BUYER_MARKETING &&
            transaction.sellerContract.status != SellerContractStatus.SIGNED
        ) {
            violations += violation(
                "sellerContract.status",
                TransactionViolationCode.INVALID_CONTRACT_STATUS,
                "Seller contract must be SIGNED before buyer marketing begins."
            )
        }

        if (request.target == TransactionLifecycle.CLOSING &&
            candidateAssignment?.status != AssignmentAgreementStatus.EXECUTED
        ) {
            violations += violation(
                "assignmentAgreement.status",
                TransactionViolationCode.INVALID_ASSIGNMENT_STATUS,
                "An EXECUTED assignment agreement is required before closing."
            )
        }
        return violations.distinct()
    }

    fun canTransition(from: TransactionLifecycle, to: TransactionLifecycle): Boolean = when (from) {
        TransactionLifecycle.SELLER_CONTRACT -> to == TransactionLifecycle.BUYER_MARKETING ||
            to == TransactionLifecycle.CANCELLED
        TransactionLifecycle.BUYER_MARKETING -> to == TransactionLifecycle.ASSIGNMENT ||
            to == TransactionLifecycle.CANCELLED
        TransactionLifecycle.ASSIGNMENT -> to == TransactionLifecycle.CLOSING ||
            to == TransactionLifecycle.CANCELLED
        TransactionLifecycle.CLOSING -> to == TransactionLifecycle.CLOSED ||
            to == TransactionLifecycle.CANCELLED
        TransactionLifecycle.CLOSED,
        TransactionLifecycle.CANCELLED -> false
    }

    private fun validateParty(
        party: TransactionParty,
        path: String,
        violations: MutableList<TransactionViolation>
    ) {
        if (party.name.isBlank()) {
            violations += violation(
                "$path.name",
                TransactionViolationCode.BLANK,
                "Party name cannot be blank."
            )
        }
    }

    private fun validateSellerContract(
        contract: SellerContractTerms,
        violations: MutableList<TransactionViolation>
    ) {
        if (contract.purchasePrice == null) {
            violations += violation(
                "sellerContract.purchasePrice",
                TransactionViolationCode.INCOMPLETE_TERMS,
                "Seller contract purchase price must be supplied."
            )
        }
        validateAmount("sellerContract.purchasePrice", contract.purchasePrice, violations)
        validateEarnestMoney("sellerContract.earnestMoney", contract.earnestMoney, violations)
        validateInspection("sellerContract.inspectionPeriod", contract.inspectionPeriod, violations)
        validateTimeline(
            path = "sellerContract.timeline",
            timeline = contract.timeline,
            inspectionPeriod = contract.inspectionPeriod,
            violations = violations
        )
        if (contract.status == SellerContractStatus.TERMINATED ||
            contract.status == SellerContractStatus.EXPIRED
        ) {
            violations += violation(
                "sellerContract.status",
                TransactionViolationCode.INVALID_CONTRACT_STATUS,
                "A terminated or expired seller contract cannot advance in this workflow."
            )
        }
    }

    private fun validateOptionalBuyerAndOffer(
        lifecycle: TransactionLifecycle,
        sellerContract: SellerContractTerms?,
        buyer: TransactionParty?,
        offerTerms: OfferTerms?,
        assignmentAgreement: AssignmentAgreement?,
        violations: MutableList<TransactionViolation>
    ) {
        if (buyer != null) validateParty(buyer, "buyer", violations)
        if (offerTerms != null) validateOfferTerms(offerTerms, sellerContract, violations)
        if (assignmentAgreement != null) validateAssignmentAgreement(assignmentAgreement, violations)

        when (lifecycle) {
            TransactionLifecycle.SELLER_CONTRACT,
            TransactionLifecycle.BUYER_MARKETING -> Unit
            TransactionLifecycle.ASSIGNMENT -> {
                requireBuyerAndCompleteOffer(buyer, offerTerms, violations)
                if (assignmentAgreement == null) {
                    violations += violation(
                        "assignmentAgreement",
                        TransactionViolationCode.INCOMPLETE_TERMS,
                        "Assignment terms are required in the assignment stage."
                    )
                } else if (assignmentAgreement.status == AssignmentAgreementStatus.TERMINATED) {
                    violations += violation(
                        "assignmentAgreement.status",
                        TransactionViolationCode.INVALID_ASSIGNMENT_STATUS,
                        "A terminated assignment cannot be in progress."
                    )
                }
                validateAssignmentEconomics(
                    sellerContract = sellerContract,
                    offerTerms = offerTerms,
                    assignmentAgreement = assignmentAgreement,
                    violations = violations
                )
            }
            TransactionLifecycle.CLOSING -> {
                requireBuyerAndCompleteOffer(buyer, offerTerms, violations)
                if (assignmentAgreement?.status != AssignmentAgreementStatus.EXECUTED) {
                    violations += violation(
                        "assignmentAgreement.status",
                        TransactionViolationCode.INVALID_ASSIGNMENT_STATUS,
                        "Closing requires an EXECUTED assignment agreement."
                    )
                }
                validateAssignmentEconomics(
                    sellerContract = sellerContract,
                    offerTerms = offerTerms,
                    assignmentAgreement = assignmentAgreement,
                    violations = violations
                )
            }
            TransactionLifecycle.CLOSED -> {
                requireBuyerAndCompleteOffer(buyer, offerTerms, violations)
                if (assignmentAgreement?.status != AssignmentAgreementStatus.EXECUTED) {
                    violations += violation(
                        "assignmentAgreement.status",
                        TransactionViolationCode.INVALID_ASSIGNMENT_STATUS,
                        "Closing completion requires an EXECUTED assignment agreement."
                    )
                }
                if (sellerContract?.timeline?.closedOn == null) {
                    violations += violation(
                        "sellerContract.timeline.closedOn",
                        TransactionViolationCode.INCOMPLETE_TERMS,
                        "A supplied closing completion date is required."
                    )
                }
                validateAssignmentEconomics(
                    sellerContract = sellerContract,
                    offerTerms = offerTerms,
                    assignmentAgreement = assignmentAgreement,
                    violations = violations
                )
            }
            TransactionLifecycle.CANCELLED -> Unit
        }
    }

    private fun requireBuyerAndCompleteOffer(
        buyer: TransactionParty?,
        offerTerms: OfferTerms?,
        violations: MutableList<TransactionViolation>
    ) {
        if (buyer == null) {
            violations += violation(
                "buyer",
                TransactionViolationCode.REQUIRED,
                "A buyer is required before assignment."
            )
        }
        if (offerTerms == null) {
            violations += violation(
                "offerTerms",
                TransactionViolationCode.INCOMPLETE_TERMS,
                "Buyer offer terms are required before assignment."
            )
            return
        }
        if (offerTerms.purchasePrice == null) {
            violations += violation(
                "offerTerms.purchasePrice",
                TransactionViolationCode.INCOMPLETE_TERMS,
                "Buyer purchase price must be supplied before assignment."
            )
        }
        if (offerTerms.earnestMoney.amount == null) {
            violations += violation(
                "offerTerms.earnestMoney.amount",
                TransactionViolationCode.INCOMPLETE_TERMS,
                "Buyer earnest-money amount must be supplied before assignment."
            )
        }
        if (offerTerms.inspectionPeriod.days == null) {
            violations += violation(
                "offerTerms.inspectionPeriod.days",
                TransactionViolationCode.INCOMPLETE_TERMS,
                "Buyer inspection period must be supplied before assignment."
            )
        }
        if (offerTerms.timeline.closingDate == null) {
            violations += violation(
                "offerTerms.timeline.closingDate",
                TransactionViolationCode.INCOMPLETE_TERMS,
                "Buyer closing date must be supplied before assignment."
            )
        }
    }

    private fun validateOfferTerms(
        offer: OfferTerms,
        sellerContract: SellerContractTerms?,
        violations: MutableList<TransactionViolation>
    ) {
        validateAmount("offerTerms.purchasePrice", offer.purchasePrice, violations)
        validateEarnestMoney("offerTerms.earnestMoney", offer.earnestMoney, violations)
        validateInspection("offerTerms.inspectionPeriod", offer.inspectionPeriod, violations)
        validateTimeline(
            path = "offerTerms.timeline",
            timeline = offer.timeline,
            inspectionPeriod = offer.inspectionPeriod,
            violations = violations
        )
        val buyerClosing = offer.timeline.closingDate
        val sellerClosing = sellerContract?.timeline?.closingDate
        if (buyerClosing != null && sellerClosing != null && buyerClosing.isAfter(sellerClosing)) {
            violations += violation(
                "offerTerms.timeline.closingDate",
                TransactionViolationCode.CONTRADICTORY_TERMS,
                "Buyer closing date cannot be after the seller contract closing date."
            )
        }
        val offerPrice = offer.purchasePrice
        val offerEarnestMoney = offer.earnestMoney.amount
        if (offerPrice != null && offerEarnestMoney != null && offerEarnestMoney > offerPrice) {
            violations += violation(
                "offerTerms.earnestMoney.amount",
                TransactionViolationCode.CONTRADICTORY_TERMS,
                "Buyer earnest money cannot exceed the buyer purchase price."
            )
        }
    }

    private fun validateAssignmentAgreement(
        assignment: AssignmentAgreement,
        violations: MutableList<TransactionViolation>
    ) {
        validateAmount("assignmentAgreement.assignmentFee", assignment.assignmentFee, violations)
        if (assignment.status == AssignmentAgreementStatus.EXECUTED && assignment.executedOn == null) {
            violations += violation(
                "assignmentAgreement.executedOn",
                TransactionViolationCode.INCOMPLETE_TERMS,
                "An executed assignment requires an execution date."
            )
        }
        if (assignment.status != AssignmentAgreementStatus.EXECUTED && assignment.executedOn != null) {
            violations += violation(
                "assignmentAgreement.executedOn",
                TransactionViolationCode.CONTRADICTORY_TERMS,
                "An execution date cannot be supplied unless the assignment is EXECUTED."
            )
        }
    }

    private fun validateAssignmentEconomics(
        sellerContract: SellerContractTerms?,
        offerTerms: OfferTerms?,
        assignmentAgreement: AssignmentAgreement?,
        violations: MutableList<TransactionViolation>
    ) {
        if (sellerContract == null || offerTerms == null) return
        val economics = AssignmentEconomicsCalculator.calculate(
            sellerContract = sellerContract,
            offerTerms = offerTerms,
            assignmentAgreement = assignmentAgreement
        )
        val explicitFee = economics.explicitAssignmentFee
        val grossSpread = economics.grossSpread
        if (explicitFee != null && grossSpread != null && explicitFee != grossSpread) {
            violations += violation(
                "assignmentAgreement.assignmentFee",
                TransactionViolationCode.CONTRADICTORY_TERMS,
                "Explicit assignment fee must equal buyer price minus seller contract price."
            )
        }
        if (grossSpread != null && grossSpread.isNegative()) {
            violations += violation(
                "assignmentEconomics.grossSpread",
                TransactionViolationCode.NEGATIVE_ASSIGNMENT_SPREAD,
                "Buyer purchase price is below the seller contract price."
            )
        }
    }

    private fun validateAmount(
        field: String,
        amount: UsdAmount?,
        violations: MutableList<TransactionViolation>
    ) {
        if (amount?.isNegative() == true) {
            violations += violation(
                field,
                TransactionViolationCode.NEGATIVE_AMOUNT,
                "USD amounts cannot be negative."
            )
        }
    }

    private fun validateEarnestMoney(
        field: String,
        earnestMoney: EarnestMoney,
        violations: MutableList<TransactionViolation>
    ) {
        validateAmount("$field.amount", earnestMoney.amount, violations)
        if (earnestMoney.amount == null && earnestMoney.dueDate != null) {
            violations += violation(
                "$field.dueDate",
                TransactionViolationCode.INCOMPLETE_TERMS,
                "Earnest-money due date requires an explicit amount."
            )
        }
        if (earnestMoney.amount?.isPositive() == true && earnestMoney.dueDate == null) {
            violations += violation(
                "$field.dueDate",
                TransactionViolationCode.INCOMPLETE_TERMS,
                "A positive earnest-money amount requires a due date."
            )
        }
    }

    private fun validateInspection(
        field: String,
        inspection: InspectionPeriod,
        violations: MutableList<TransactionViolation>
    ) {
        if (inspection.days != null && inspection.days < 0) {
            violations += violation(
                "$field.days",
                TransactionViolationCode.NEGATIVE_DURATION,
                "Inspection period cannot be negative."
            )
        }
    }

    private fun validateTimeline(
        path: String,
        timeline: ClosingTimeline,
        inspectionPeriod: InspectionPeriod,
        violations: MutableList<TransactionViolation>
    ) {
        val effective = timeline.effectiveDate
        val closing = timeline.closingDate
        val closed = timeline.closedOn
        if (effective != null && closing != null && closing.isBefore(effective)) {
            violations += violation(
                "$path.closingDate",
                TransactionViolationCode.INVALID_DATE_ORDER,
                "Closing date cannot be before the effective date."
            )
        }
        if (closing == null && closed != null) {
            violations += violation(
                "$path.closedOn",
                TransactionViolationCode.CONTRADICTORY_TERMS,
                "A completion date requires a supplied closing date."
            )
        }
        if (closing != null && closed != null && closed.isBefore(closing)) {
            violations += violation(
                "$path.closedOn",
                TransactionViolationCode.INVALID_DATE_ORDER,
                "Completion date cannot be before the closing date."
            )
        }
        val inspectionDays = inspectionPeriod.days
        if (effective != null && closing != null && inspectionDays != null &&
            inspectionDays.toLong() > closing.epochDay - effective.epochDay
        ) {
            violations += violation(
                "${path}.inspectionPeriod",
                TransactionViolationCode.CONTRADICTORY_TERMS,
                "Inspection period cannot extend beyond the supplied closing date."
            )
        }
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
