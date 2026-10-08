package com.example.domain.ai.analyst

import com.example.data.local.entity.ComparablePropertyEntity
import com.example.data.local.entity.FinancialAnalysisEntity
import com.example.data.local.entity.MarketDataEntity
import com.example.data.local.entity.PropertyEntity
import com.example.data.local.entity.RentEstimateEntity
import com.example.data.local.entity.TaxRecordEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps one property's whitelisted fields to the model boundary. This is intentionally not a generic
 * entity serializer: descriptions, images, coordinates, deal flags, other properties, and unrelated
 * database tables are never placed in the Gemini request. All untrusted free text passes through
 * [AnalystTextSanitizer] so hostile listing imports cannot smuggle control, invisible, or code-fence
 * characters into the prompt.
 */
class RealEstateAnalystInputFactory(
    private val maxComparables: Int = RealEstateAnalystInputSchema.MAX_COMPARABLES
) {
    init {
        require(maxComparables in 0..RealEstateAnalystInputSchema.MAX_COMPARABLES)
    }

    fun create(
        property: PropertyEntity,
        market: MarketDataEntity? = null,
        rent: RentEstimateEntity? = null,
        tax: TaxRecordEntity? = null,
        financial: FinancialAnalysisEntity? = null,
        comparables: List<ComparablePropertyEntity> = emptyList()
    ): RealEstateAnalystInput {
        val evidence = linkedMapOf<String, AnalystEvidence>()
        val propertyTimestamp = property.scannedAt.takeIf { it > 0L }

        fun <T : Any> field(
            id: String,
            name: String,
            value: T?,
            source: AnalystEvidenceSource,
            asOfEpochMillis: Long? = null
        ): AnalystInputValue<T> {
            if (value == null) return AnalystInputValue()
            evidence[id] = AnalystEvidence(id, source, name, asOfEpochMillis)
            return AnalystInputValue(value = value, evidenceRef = id)
        }

        // Untrusted listing/record text is neutralized before it crosses the model boundary:
        // control, invisible, and code-fence characters are stripped, whitespace is collapsed, and
        // the value is capped. A field that is entirely injection payload sanitizes to null, so the
        // analyst sees an explicit gap (UNKNOWN) instead of attacker text.
        fun text(
            id: String,
            name: String,
            value: String?,
            source: AnalystEvidenceSource,
            asOfEpochMillis: Long? = null
        ) = field(
            id,
            name,
            AnalystTextSanitizer.sanitize(value, MAX_TEXT_FIELD_LENGTH),
            source,
            asOfEpochMillis
        )

        fun number(
            id: String,
            name: String,
            value: Double?,
            source: AnalystEvidenceSource,
            asOfEpochMillis: Long? = null
        ) = field(id, name, value?.takeIf { it.isFinite() }, source, asOfEpochMillis)

        fun integer(
            id: String,
            name: String,
            value: Int?,
            source: AnalystEvidenceSource,
            asOfEpochMillis: Long? = null
        ) = field(id, name, value, source, asOfEpochMillis)

        val subject = AnalystPropertyInput(
            city = text("property.city", "city", property.city, AnalystEvidenceSource.PROPERTY_RECORD, propertyTimestamp),
            state = text("property.state", "state", property.state, AnalystEvidenceSource.PROPERTY_RECORD, propertyTimestamp),
            zipCode = text("property.zip_code", "zipCode", property.zipCode, AnalystEvidenceSource.PROPERTY_RECORD, propertyTimestamp),
            sourceType = text("property.source_type", "sourceType", property.sourceType, AnalystEvidenceSource.PROPERTY_RECORD, propertyTimestamp),
            listingStatus = text("property.listing_status", "status", property.status, AnalystEvidenceSource.PROPERTY_RECORD, propertyTimestamp),
            askingPriceUsd = number("property.asking_price_usd", "askingPriceUsd", property.price, AnalystEvidenceSource.PROPERTY_RECORD, propertyTimestamp),
            propertyType = text("property.property_type", "propertyType", property.propertyType, AnalystEvidenceSource.PROPERTY_RECORD, propertyTimestamp),
            bedrooms = integer("property.bedrooms", "bedrooms", property.bedrooms, AnalystEvidenceSource.PROPERTY_RECORD, propertyTimestamp),
            bathrooms = number("property.bathrooms", "bathrooms", property.bathrooms, AnalystEvidenceSource.PROPERTY_RECORD, propertyTimestamp),
            squareFeet = integer("property.square_feet", "squareFeet", property.squareFeet, AnalystEvidenceSource.PROPERTY_RECORD, propertyTimestamp),
            yearBuilt = integer("property.year_built", "yearBuilt", property.yearBuilt, AnalystEvidenceSource.PROPERTY_RECORD, propertyTimestamp),
            lotSizeSquareFeet = integer("property.lot_size_square_feet", "lotSizeSquareFeet", property.lotSizeSqFt, AnalystEvidenceSource.PROPERTY_RECORD, propertyTimestamp)
        )

        val marketInput = AnalystMarketInput(
            estimatedValueUsd = number("market.estimated_value_usd", "estimatedValueUsd", market?.estimatedValue, AnalystEvidenceSource.MARKET_ESTIMATE),
            medianAreaPriceUsd = number("market.median_area_price_usd", "medianAreaPriceUsd", market?.medianAreaPrice, AnalystEvidenceSource.MARKET_ESTIMATE),
            pricePerSquareFootUsd = number("market.price_per_square_foot_usd", "pricePerSquareFootUsd", market?.pricePerSqFt, AnalystEvidenceSource.MARKET_ESTIMATE),
            averageDaysOnMarket = integer("market.average_days_on_market", "averageDaysOnMarket", market?.averageDaysOnMarket, AnalystEvidenceSource.MARKET_DATA_RECORD),
            neighborhoodAppreciationRatePct = number("market.neighborhood_appreciation_rate_pct", "neighborhoodAppreciationRatePct", market?.neighborhoodAppreciationRate, AnalystEvidenceSource.MARKET_ESTIMATE),
            marketDemand = text("market.market_demand", "marketDemand", market?.marketDemand, AnalystEvidenceSource.MARKET_DATA_RECORD)
        )

        val rentInput = AnalystRentInput(
            estimatedMonthlyRentUsd = number("rent.estimated_monthly_rent_usd", "estimatedMonthlyRentUsd", rent?.estimatedRent, AnalystEvidenceSource.RENT_ESTIMATE),
            rangeLowUsd = number("rent.range_low_usd", "rangeLowUsd", rent?.rentRangeLow, AnalystEvidenceSource.RENT_ESTIMATE),
            rangeHighUsd = number("rent.range_high_usd", "rangeHighUsd", rent?.rentRangeHigh, AnalystEvidenceSource.RENT_ESTIMATE),
            sourceConfidence = number("rent.source_confidence", "rentConfidenceScore", rent?.rentConfidenceScore, AnalystEvidenceSource.RENT_ESTIMATE)
        )

        val taxInput = AnalystTaxInput(
            annualTaxUsd = number("tax.annual_tax_usd", "annualTaxUsd", tax?.annualTaxAmount, AnalystEvidenceSource.TAX_RECORD),
            assessmentYear = integer("tax.assessment_year", "assessmentYear", tax?.assessmentYear, AnalystEvidenceSource.TAX_RECORD),
            assessedValueUsd = number("tax.assessed_value_usd", "assessedValueUsd", tax?.assessedValue, AnalystEvidenceSource.TAX_RECORD),
            delinquent = tax?.let { field("tax.delinquent", "taxDelinquent", it.taxDelinquent, AnalystEvidenceSource.TAX_RECORD) } ?: AnalystInputValue()
        )

        val financialInput = AnalystFinancialMetricsInput(
            annualNoiUsd = number("financial.annual_noi_usd", "noiAnnual", financial?.noiAnnual, AnalystEvidenceSource.DETERMINISTIC_FINANCIAL_ENGINE, financial?.calculatedAt),
            monthlyCashFlowUsd = number("financial.monthly_cash_flow_usd", "monthlyCashFlow", financial?.monthlyCashFlow, AnalystEvidenceSource.DETERMINISTIC_FINANCIAL_ENGINE, financial?.calculatedAt),
            annualCashFlowUsd = number("financial.annual_cash_flow_usd", "annualCashFlow", financial?.annualCashFlow, AnalystEvidenceSource.DETERMINISTIC_FINANCIAL_ENGINE, financial?.calculatedAt),
            capRatePct = number("financial.cap_rate_pct", "capRate", financial?.capRate, AnalystEvidenceSource.DETERMINISTIC_FINANCIAL_ENGINE, financial?.calculatedAt),
            cashOnCashReturnPct = number("financial.cash_on_cash_return_pct", "cashOnCashReturn", financial?.cashOnCashReturn, AnalystEvidenceSource.DETERMINISTIC_FINANCIAL_ENGINE, financial?.calculatedAt),
            dscr = number("financial.dscr", "dscr", financial?.dscr, AnalystEvidenceSource.DETERMINISTIC_FINANCIAL_ENGINE, financial?.calculatedAt),
            breakEvenOccupancyPct = number("financial.break_even_occupancy_pct", "breakEvenOccupancyPct", financial?.breakEvenOccupancyPct, AnalystEvidenceSource.DETERMINISTIC_FINANCIAL_ENGINE, financial?.calculatedAt),
            totalCashRequiredUsd = number("financial.total_cash_required_usd", "totalCashRequired", financial?.totalCashRequired, AnalystEvidenceSource.DETERMINISTIC_FINANCIAL_ENGINE, financial?.calculatedAt)
        )

        val comparableInputs = comparables.take(maxComparables).mapIndexed { index, comparable ->
            val prefix = "comparables.${index + 1}"
            AnalystComparableInput(
                address = text("$prefix.address", "comparableAddress", comparable.compAddress, AnalystEvidenceSource.COMPARABLE_RECORD),
                salePriceUsd = number("$prefix.sale_price_usd", "comparablePriceUsd", comparable.compPrice, AnalystEvidenceSource.COMPARABLE_RECORD),
                bedrooms = integer("$prefix.bedrooms", "comparableBedrooms", comparable.compBeds, AnalystEvidenceSource.COMPARABLE_RECORD),
                bathrooms = number("$prefix.bathrooms", "comparableBathrooms", comparable.compBaths, AnalystEvidenceSource.COMPARABLE_RECORD),
                squareFeet = integer("$prefix.square_feet", "comparableSquareFeet", comparable.compSqFt, AnalystEvidenceSource.COMPARABLE_RECORD),
                distanceMiles = number("$prefix.distance_miles", "comparableDistanceMiles", comparable.distanceMiles, AnalystEvidenceSource.COMPARABLE_RECORD),
                saleDate = text("$prefix.sale_date", "comparableSaleDate", comparable.saleDate, AnalystEvidenceSource.COMPARABLE_RECORD)
            )
        }

        return RealEstateAnalystInput(
            subject = subject,
            market = marketInput,
            rent = rentInput,
            taxes = taxInput,
            deterministicFinancialMetrics = financialInput,
            comparables = comparableInputs,
            evidence = evidence.values.toList()
        )
    }

    private companion object {
        const val MAX_TEXT_FIELD_LENGTH = 160
    }
}

/** Serializes only the compact analyst DTO, never Room entities or DAO results. */
fun RealEstateAnalystInput.toJsonObject(): JSONObject = JSONObject().apply {
    put("schemaVersion", schemaVersion)
    put("jurisdiction", "United States")
    put("currency", "USD")
    put("areaUnit", "square_feet")

    put("subject", JSONObject().apply {
        putInput("city", subject.city)
        putInput("state", subject.state)
        putInput("zipCode", subject.zipCode)
        putInput("sourceType", subject.sourceType)
        putInput("listingStatus", subject.listingStatus)
        putInput("askingPriceUsd", subject.askingPriceUsd)
        putInput("propertyType", subject.propertyType)
        putInput("bedrooms", subject.bedrooms)
        putInput("bathrooms", subject.bathrooms)
        putInput("squareFeet", subject.squareFeet)
        putInput("yearBuilt", subject.yearBuilt)
        putInput("lotSizeSquareFeet", subject.lotSizeSquareFeet)
    })

    put("market", JSONObject().apply {
        putInput("estimatedValueUsd", market.estimatedValueUsd)
        putInput("medianAreaPriceUsd", market.medianAreaPriceUsd)
        putInput("pricePerSquareFootUsd", market.pricePerSquareFootUsd)
        putInput("averageDaysOnMarket", market.averageDaysOnMarket)
        putInput("neighborhoodAppreciationRatePct", market.neighborhoodAppreciationRatePct)
        putInput("marketDemand", market.marketDemand)
    })

    put("rent", JSONObject().apply {
        putInput("estimatedMonthlyRentUsd", rent.estimatedMonthlyRentUsd)
        putInput("rangeLowUsd", rent.rangeLowUsd)
        putInput("rangeHighUsd", rent.rangeHighUsd)
        putInput("sourceConfidence", rent.sourceConfidence)
    })

    put("taxes", JSONObject().apply {
        putInput("annualTaxUsd", taxes.annualTaxUsd)
        putInput("assessmentYear", taxes.assessmentYear)
        putInput("assessedValueUsd", taxes.assessedValueUsd)
        putInput("delinquent", taxes.delinquent)
    })

    put("deterministicFinancialMetrics", JSONObject().apply {
        putInput("annualNoiUsd", deterministicFinancialMetrics.annualNoiUsd)
        putInput("monthlyCashFlowUsd", deterministicFinancialMetrics.monthlyCashFlowUsd)
        putInput("annualCashFlowUsd", deterministicFinancialMetrics.annualCashFlowUsd)
        putInput("capRatePct", deterministicFinancialMetrics.capRatePct)
        putInput("cashOnCashReturnPct", deterministicFinancialMetrics.cashOnCashReturnPct)
        putInput("dscr", deterministicFinancialMetrics.dscr)
        putInput("breakEvenOccupancyPct", deterministicFinancialMetrics.breakEvenOccupancyPct)
        putInput("totalCashRequiredUsd", deterministicFinancialMetrics.totalCashRequiredUsd)
        put("calculationOwner", "deterministic_local_financial_engine")
    })

    put("comparables", JSONArray().apply {
        comparables.forEach { comparable ->
            put(JSONObject().apply {
                putInput("address", comparable.address)
                putInput("salePriceUsd", comparable.salePriceUsd)
                putInput("bedrooms", comparable.bedrooms)
                putInput("bathrooms", comparable.bathrooms)
                putInput("squareFeet", comparable.squareFeet)
                putInput("distanceMiles", comparable.distanceMiles)
                putInput("saleDate", comparable.saleDate)
            })
        }
    })

    put("evidence", JSONArray().apply {
        evidence.forEach { ref ->
            put(JSONObject().apply {
                put("id", ref.id)
                put("source", ref.source.name)
                put("field", ref.field)
                put("asOfEpochMillis", ref.asOfEpochMillis ?: JSONObject.NULL)
            })
        }
    })
}

private fun JSONObject.putInput(name: String, input: AnalystInputValue<*>) {
    put(name, JSONObject().apply {
        put("value", input.value ?: JSONObject.NULL)
        put("evidenceRef", input.evidenceRef ?: JSONObject.NULL)
    })
}
