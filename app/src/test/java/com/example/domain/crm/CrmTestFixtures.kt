package com.example.domain.crm

/**
 * Deterministic fixtures for the CRM test suite.
 *
 * Everything is anchored to fixed instants ([T0], [T1]) and fixed ids, so a failing assertion always
 * points at behaviour rather than at the clock or at a random id.
 *
 * The "qualified deal" built by [crmLead] is intentional: `asking 180k / ARV 300k / repairs 45k` with a
 * STRONG documented tax-delinquency signal, a 30-day timeline, a MINOR_REHAB condition, a reachable
 * seller and complete evidence scores exactly **76/100 → QUALIFIED** under the default policy. Tests
 * that need a qualified lead therefore start from real, checkable facts instead of a stub.
 */
object CrmFixtures {

    /** 2023-11-14T22:13:20Z — an arbitrary but fixed instant. */
    const val T0 = 1_700_000_000_000L

    /** 30 days after [T0]. */
    val T1 = T0 + 30L * CrmTime.MILLIS_PER_DAY

    /** 90 days after [T0]. */
    val T2 = T0 + 90L * CrmTime.MILLIS_PER_DAY

    const val LEAD_ID = "LEAD-TEST00000001"
    const val SELLER_ID = "SELLER-TEST0001"
    const val PROPERTY_ID = "PROP-TEST-1"
    const val OFFER_ID = "OFFER-TEST-1"
    const val CONTRACT_ID = "CTR-TEST-1"

    const val ACTOR = "rep.avery"
    const val PHONE = "+15125550123"

    fun source(
        kind: LeadSourceKind = LeadSourceKind.DRIVING_FOR_DOLLARS,
        at: Long = T0,
        detail: String = "2450 Oak St door knock, absentee owner",
        listName: String? = null,
        referrerName: String? = null,
        costMicros: Long? = null
    ): LeadSourceAttribution = LeadSourceAttribution(
        source = kind,
        detail = detail,
        listName = listName,
        referrerName = referrerName,
        capturedAtEpochMillis = at,
        costMicros = costMicros
    )

    fun phonePoint(
        value: String = PHONE,
        at: Long = T0,
        primary: Boolean = true,
        doNotContact: Boolean = false,
        channel: ContactChannel = ContactChannel.PHONE
    ): SellerContactPoint = SellerContactPoint(
        channel = channel,
        value = value,
        label = "mobile",
        isPrimary = primary,
        doNotContact = doNotContact,
        addedAtEpochMillis = at
    )

    fun seller(
        id: String = SELLER_ID,
        at: Long = T0,
        name: String = "Dana Whitfield",
        restrictions: SellerContactRestrictions = SellerContactRestrictions.NONE,
        contactPoints: List<SellerContactPoint> = listOf(phonePoint(at = at)),
        primary: Boolean = true,
        occupancy: OccupancyStatus = OccupancyStatus.ABSENTEE,
        skipTrace: SkipTraceReference? = null
    ): Seller = Seller(
        id = id,
        fullName = name,
        role = SellerRole.OWNER,
        entityType = SellerEntityType.INDIVIDUAL,
        contactPoints = contactPoints,
        mailingAddress = PostalAddress("88 Cedar Lane", null, "Dallas", "TX", "75201"),
        occupancy = occupancy,
        isPrimaryContact = primary,
        restrictions = restrictions,
        skipTrace = skipTrace,
        firstSeenAtEpochMillis = at,
        updatedAtEpochMillis = at
    )

    fun address(): PostalAddress = PostalAddress("2450 Oak St", null, "Austin", "TX", "78704")

    fun addressRef(): String = address().singleLine()

    fun propertyLink(
        at: Long = T0,
        propertyId: String = PROPERTY_ID,
        askingPriceUsd: Double? = 180_000.0,
        afterRepairValueUsd: Double? = 300_000.0,
        estimatedRepairCostUsd: Double? = 45_000.0,
        estimatedAsIsValueUsd: Double? = 175_000.0,
        role: PropertyLinkRole = PropertyLinkRole.SUBJECT
    ): LeadPropertyLink = LeadPropertyLink(
        propertyId = propertyId,
        role = role,
        address = address(),
        apn = "01-2345-6789",
        askingPriceUsd = askingPriceUsd,
        afterRepairValueUsd = afterRepairValueUsd,
        estimatedRepairCostUsd = estimatedRepairCostUsd,
        estimatedAsIsValueUsd = estimatedAsIsValueUsd,
        estimatedRentUsdMonthly = 1_950.0,
        linkedAtEpochMillis = at,
        linkedBy = ACTOR
    )

    fun motivationSignal(
        id: String = "SIG-TAX-1",
        at: Long = T0,
        kind: MotivationSignalKind = MotivationSignalKind.TAX_DELINQUENT,
        strength: MotivationStrength = MotivationStrength.STRONG,
        sourceKind: MotivationSourceKind = MotivationSourceKind.PUBLIC_RECORD,
        evidenceRef: String? = "travis-tax-2026-000123",
        detail: String? = null
    ): MotivationSignal = MotivationSignal(
        id = id,
        kind = kind,
        strength = strength,
        sourceKind = sourceKind,
        observedAtEpochMillis = at,
        recordedAtEpochMillis = at,
        recordedBy = ACTOR,
        evidenceRef = evidenceRef,
        detail = detail
    )

    /** Roof failure ⇒ MINOR_REHAB by the default classification policy. */
    fun condition(
        at: Long = T0,
        indicators: Set<ConditionIndicator> = setOf(ConditionIndicator.ROOF_FAILURE),
        squareFeet: Int? = 1_500,
        source: ConditionEvidenceSource = ConditionEvidenceSource.DRIVE_BY
    ): PropertyConditionAssessment = PropertyConditionClassifier.assess(
        indicators = indicators,
        source = source,
        assessedAtEpochMillis = at,
        assessedBy = ACTOR,
        squareFeet = squareFeet
    )

    fun timeline(daysToClose: Int? = 30, at: Long = T0): SellerTimelineFact =
        SellerTimelineFact.sellerStated(daysToClose, at, ACTOR)

    fun communication(
        id: String = "COMM-1",
        at: Long = T0,
        leadId: String = LEAD_ID,
        direction: CommunicationDirection = CommunicationDirection.OUTBOUND,
        outcome: CommunicationOutcome = CommunicationOutcome.CONNECTED,
        channel: CommunicationChannel = CommunicationChannel.CALL,
        summary: String = "Seller answered, wants to close this quarter",
        durationSeconds: Int? = 265,
        sellerId: String? = SELLER_ID
    ): LeadCommunication = LeadCommunication(
        id = id,
        leadId = leadId,
        channel = channel,
        direction = direction,
        outcome = outcome,
        occurredAtEpochMillis = at,
        loggedAtEpochMillis = at,
        loggedBy = ACTOR,
        sellerId = sellerId,
        summary = summary,
        durationSeconds = durationSeconds
    )

    fun offerReference(
        offerId: String = OFFER_ID,
        at: Long = T0,
        amountUsd: Double = 168_000.0,
        status: String = LeadOfferBridge.STATUS_SENT
    ): LeadOfferReference = LeadOfferReference(
        offerId = offerId,
        amountUsd = amountUsd,
        status = status,
        referencedAtEpochMillis = at,
        referencedBy = ACTOR
    )

    fun contract(
        id: String = CONTRACT_ID,
        signedAt: Long = T0,
        dueDiligenceEndsAtEpochMillis: Long? = T0 + 10L * CrmTime.MILLIS_PER_DAY,
        closingAtEpochMillis: Long? = T0 + 21L * CrmTime.MILLIS_PER_DAY,
        assignmentFeeUsd: Double? = 22_000.0
    ): ContractReference = ContractReference(
        id = id,
        contractPriceUsd = 168_000.0,
        earnestMoneyUsd = 5_000.0,
        signedAtEpochMillis = signedAt,
        dueDiligenceEndsAtEpochMillis = dueDiligenceEndsAtEpochMillis,
        closingAtEpochMillis = closingAtEpochMillis,
        assignmentFeeUsd = assignmentFeeUsd,
        documentRef = "files/contracts/$id.pdf"
    )

    /**
     * The default fixture: a fully evidenced lead sitting at [status], audited at [at].
     *
     * Sellers, property link, motivation, condition, timeline and one connected call are all present, so
     * the lead can legally reach NEGOTIATING and (once an offer is referenced) OFFER_SENT.
     */
    fun crmLead(
        status: LeadPipelineStatus = LeadPipelineStatus.NEW,
        at: Long = T0,
        leadId: String = LEAD_ID,
        source: LeadSourceAttribution = source(at = at),
        sellers: List<Seller> = listOf(seller(at = at)),
        propertyLinks: List<LeadPropertyLink> = listOf(propertyLink(at = at)),
        motivationSignals: List<MotivationSignal> = listOf(motivationSignal(at = at)),
        condition: PropertyConditionAssessment? = condition(at = at),
        timeline: SellerTimelineFact = timeline(at = at),
        tags: Set<String> = setOf("tax-delinquent", "absentee-owner"),
        communications: List<LeadCommunication> = listOf(communication(at = at, leadId = leadId)),
        offerReferences: List<LeadOfferReference> = emptyList(),
        contract: ContractReference? = null,
        nextFollowUp: LeadFollowUp? = null,
        priority: LeadPriority = LeadPriority.NORMAL,
        assignedTo: String? = null
    ): Lead = Lead(
        id = leadId,
        displayName = "Whitfield — 2450 Oak St",
        pipelineStatus = status,
        priority = priority,
        source = source,
        sellers = sellers,
        propertyLinks = propertyLinks,
        motivationSignals = motivationSignals,
        condition = condition,
        timeline = timeline,
        tags = tags,
        communications = communications,
        offerReferences = offerReferences,
        contract = contract,
        nextFollowUp = nextFollowUp,
        assignedTo = assignedTo,
        audit = CrmAuditMetadata.created(at, ACTOR)
    )

    /** [crmLead] plus the qualification assessment the engine derives from it. */
    fun qualifiedLead(
        status: LeadPipelineStatus = LeadPipelineStatus.NEGOTIATING,
        at: Long = T0,
        leadId: String = LEAD_ID,
        communications: List<LeadCommunication> = listOf(communication(at = at, leadId = leadId))
    ): Lead = assess(
        crmLead(status = status, at = at, leadId = leadId, communications = communications),
        at
    )

    /**
     * A lead the way an operator would actually produce one: evidence in, qualification computed,
     * then audited moves NEW → CONTACTED → RESPONDED → NEGOTIATING with a next touch scheduled.
     * This is the fixture validation and service tests use when they need a *coherent* record.
     */
    fun workedLead(
        at: Long = T0,
        leadId: String = LEAD_ID,
        status: LeadPipelineStatus = LeadPipelineStatus.NEGOTIATING
    ): Lead {
        var lead = crmLead(status = LeadPipelineStatus.NEW, at = at, leadId = leadId)
            .withFollowUp(LeadFollowUpPlanner.schedule(LeadPipelineStatus.NEW, at, ACTOR)!!, ACTOR, at)
        lead = assess(lead, at)
        if (status == LeadPipelineStatus.NEW) return lead
        lead = move(lead, LeadPipelineStatus.CONTACTED, LeadTransitionReason.OUTREACH_LOGGED, at + 1)
        if (status == LeadPipelineStatus.CONTACTED) return lead
        lead = move(lead, LeadPipelineStatus.RESPONDED, LeadTransitionReason.SELLER_RESPONDED, at + 2)
        if (status == LeadPipelineStatus.RESPONDED) return lead
        lead = move(lead, LeadPipelineStatus.NEGOTIATING, LeadTransitionReason.NEGOTIATION_OPENED, at + 3)
        return lead
    }

    /** Runs the deterministic real pipeline reassessment (never a hand-built assessment). */
    fun assess(lead: Lead, at: Long = T0, actor: String = "system"): Lead = when (
        val result = LeadQualificationEngine.reassess(lead, atEpochMillis = at, actor = actor)
    ) {
        is LeadQualificationResult.Applied -> result.lead
        is LeadQualificationResult.NoOp -> result.lead
        is LeadQualificationResult.Illegal -> error("fixture reassessment failed: ${result.message}")
    }

    fun move(
        lead: Lead,
        target: LeadPipelineStatus,
        reason: LeadTransitionReason,
        at: Long,
        actor: String = ACTOR,
        policy: LeadTransitionPolicy = LeadTransitionPolicy.DEFAULT
    ): Lead = when (val result = LeadPipelineStateMachine.transition(lead, target, reason, at, actor, policy)) {
        is LeadTransitionResult.Applied -> result.lead
        is LeadTransitionResult.NoOp -> result.lead
        is LeadTransitionResult.Blocked -> error("fixture move to $target was blocked: ${result.blockers.map { it.code }}")
        is LeadTransitionResult.Illegal -> error("fixture move to $target was illegal: ${result.message}")
    }
}
