package com.example.domain.finance.underwriting

/**
 * Turns an untyped bag of values (JSON, a Room row, a restored backup, the
 * golden-vector fixtures) into a validated [UnderwritingInput].
 *
 * Two rules:
 *
 *  * **Nothing is guessed.** A value that cannot be read is left null so the
 *    documented default applies, and a name that is not a known enum constant
 *    falls back to the documented default *and* raises a validation issue that
 *    the engine reports. The caller (and the user) always learns that something
 *    was dropped.
 *  * **Determinism survives serialisation.** Rehydrating the same document
 *    always produces the same input, so a saved analysis can be recomputed
 *    exactly rather than approximately.
 */
object UnderwritingInputCodec {

    data class DecodedInput(
        val input: UnderwritingInput,
        val issues: List<ValidationIssue>,
        /** Keys that are not part of the input schema; reported, never applied. */
        val unknownKeys: List<String>
    )

    fun decode(values: Map<String, Any?>): DecodedInput {
        val issues = ArrayList<ValidationIssue>()
        val unknown = ArrayList<String>()

        fun number(key: String): Double? {
            val raw = values[key] ?: return null
            return when (raw) {
                is Double -> raw
                is Float -> raw.toDouble()
                is Int -> raw.toDouble()
                is Long -> raw.toDouble()
                is Number -> raw.toDouble()
                is String -> raw.trim().toDoubleOrNull()
                else -> {
                    unknown.add(key)
                    null
                }
            }
        }

        fun integer(key: String): Int? {
            val value = number(key) ?: return null
            if (!value.isFinite()) return null
            return Math.round(value).toInt()
        }

        /**
         * A named override table, e.g. `"loanOverrides": {"interestRatePct": 8.5}`.
         * Entries that are not numbers are dropped *and* reported, never coerced.
         */
        fun numberMap(key: String): Map<String, Double> {
            val raw = values[key] ?: return emptyMap()
            val map = raw as? Map<*, *> ?: return emptyMap()
            val out = LinkedHashMap<String, Double>()
            for ((name, value) in map) {
                val field = name?.toString() ?: continue
                val parsed = when (value) {
                    is Double -> value
                    is Float -> value.toDouble()
                    is Int -> value.toDouble()
                    is Long -> value.toDouble()
                    is Number -> value.toDouble()
                    is String -> value.trim().toDoubleOrNull()
                    else -> null
                }
                if (parsed == null) {
                    issues.add(
                        ValidationIssue(
                            "UNKNOWN_OVERRIDE_VALUE", IssueSeverity.ERROR,
                            "Override '$key.$field' was not a number and was ignored."
                        )
                    )
                } else {
                    out[field] = parsed
                }
            }
            return out
        }

        fun text(key: String): String? = when (val raw = values[key]) {
            is String -> raw
            null -> null
            else -> raw.toString()
        }

        fun <T : Enum<T>> enumValue(key: String, constants: Array<T>, unknownCode: String, fallback: T): T {
            val raw = text(key) ?: return fallback
            val match = constants.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            if (match == null) {
                issues.add(
                    ValidationIssue(
                        unknownCode, IssueSeverity.ERROR,
                        "Unknown value for '$key' was ignored and the documented default applied."
                    )
                )
                return fallback
            }
            return match
        }

        val strategy = enumValue(
            "strategy", InvestmentStrategy.entries.toTypedArray(), "UNKNOWN_STRATEGY",
            InvestmentStrategy.BUY_AND_HOLD
        )
        val financingModel = text("financingModel")?.let { raw ->
            val match = FinancingModel.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            if (match == null) {
                issues.add(
                    ValidationIssue(
                        "UNKNOWN_FINANCING_MODEL", IssueSeverity.ERROR,
                        "Unknown financing model was ignored and the documented default applied."
                    )
                )
                null
            } else {
                match
            }
        }

        val capexTreatment = enumValue(
            "capexTreatment", CapexTreatment.entries.toTypedArray(), "UNKNOWN_CAPEX_TREATMENT",
            CapexTreatment.ABOVE_LINE_IN_NOI
        )
        val maintenanceBasis = values["maintenanceBasis"]?.let {
            enumValue(
                "maintenanceBasis", ExpenseBasis.entries.toTypedArray(), "UNKNOWN_EXPENSE_BASIS",
                ExpenseBasis.PERCENT_OF_GSI
            )
        }
        val managementBasis = values["managementBasis"]?.let {
            enumValue(
                "managementBasis", ExpenseBasis.entries.toTypedArray(), "UNKNOWN_EXPENSE_BASIS",
                ExpenseBasis.PERCENT_OF_EGI
            )
        }
        val downPaymentBasis = values["downPaymentBasis"]?.let {
            enumValue(
                "downPaymentBasis", DownPaymentBasis.entries.toTypedArray(), "UNKNOWN_DOWN_PAYMENT_BASIS",
                DownPaymentBasis.PRICE
            )
        }
        val wholesaleMode = values["wholesaleMode"]?.let {
            enumValue(
                "wholesaleMode", WholesaleMode.entries.toTypedArray(), "UNKNOWN_WHOLESALE_MODE",
                WholesaleMode.ASSIGNMENT
            )
        }

        val input = UnderwritingInput(
            strategy = strategy,
            purchasePrice = number("purchasePrice") ?: 0.0,
            closingCosts = number("closingCosts"),
            rehabCost = number("rehabCost") ?: 0.0,
            arv = number("arv"),
            sellerCredits = number("sellerCredits") ?: 0.0,
            monthlyRent = number("monthlyRent") ?: 0.0,
            otherMonthlyIncome = number("otherMonthlyIncome") ?: 0.0,
            vacancyRatePct = number("vacancyRatePct"),
            creditLossRatePct = number("creditLossRatePct"),
            propertyTaxAnnual = number("propertyTaxAnnual"),
            propertyTaxPctOfPrice = number("propertyTaxPctOfPrice"),
            insuranceAnnual = number("insuranceAnnual"),
            insurancePctOfPrice = number("insurancePctOfPrice"),
            hoaMonthly = number("hoaMonthly"),
            maintenanceAnnual = number("maintenanceAnnual"),
            maintenancePctOfGsi = number("maintenancePctOfGsi"),
            maintenanceBasis = maintenanceBasis,
            managementAnnual = number("managementAnnual"),
            managementPctOfEgi = number("managementPctOfEgi"),
            managementBasis = managementBasis,
            capexReservePctOfGsi = number("capexReservePctOfGsi"),
            capexTreatment = if (values["capexTreatment"] != null) capexTreatment else null,
            utilitiesMonthly = number("utilitiesMonthly"),
            landscapingMonthly = number("landscapingMonthly"),
            otherOperatingAnnual = number("otherOperatingAnnual"),
            financingModel = financingModel,
            downPaymentPct = number("downPaymentPct"),
            downPaymentAmount = number("downPaymentAmount"),
            loanAmount = number("loanAmount"),
            downPaymentBasis = downPaymentBasis,
            interestRatePct = number("interestRatePct"),
            loanTermMonths = integer("loanTermMonths"),
            amortizationMonths = integer("amortizationMonths"),
            interestOnlyMonths = integer("interestOnlyMonths"),
            originationPointsPct = number("originationPointsPct"),
            lenderFees = number("lenderFees"),
            lenderReserveMonths = integer("lenderReserveMonths"),
            maxLtvPct = number("maxLtvPct"),
            maxLtcPct = number("maxLtcPct"),
            maxLtvArvPct = number("maxLtvArvPct"),
            loanOverrides = numberMap("loanOverrides"),
            holdYears = integer("holdYears"),
            rentGrowthPct = number("rentGrowthPct"),
            expenseGrowthPct = number("expenseGrowthPct"),
            appreciationPct = number("appreciationPct"),
            saleCostPct = number("saleCostPct"),
            flipHoldMonths = integer("flipHoldMonths") ?: integer("holdMonths"),
            rehabContingencyPct = number("rehabContingencyPct"),
            holdingCostsMonthly = number("holdingCostsMonthly"),
            buyClosingCostPct = number("buyClosingCostPct"),
            sellClosingCostPct = number("sellClosingCostPct"),
            sellerConcessions = number("sellerConcessions"),
            flipTargetProfitPctOfArv = number("flipTargetProfitPctOfArv"),
            brrrrRehabMonths = integer("brrrrRehabMonths"),
            bridgeOverrides = numberMap("bridgeOverrides"),
            brrrrAcquisitionModel = text("brrrrAcquisitionModel")?.let { raw ->
                FinancingModel.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            },
            refiLtvPctOfArv = number("refiLtvPctOfArv"),
            refiInterestRatePct = number("refiInterestRatePct"),
            refiLoanTermMonths = integer("refiLoanTermMonths"),
            refiAmortizationMonths = integer("refiAmortizationMonths"),
            refiOriginationPointsPct = number("refiOriginationPointsPct"),
            refiLenderFees = number("refiLenderFees"),
            refiClosingCostPct = number("refiClosingCostPct"),
            wholesaleMode = wholesaleMode,
            assignmentFee = number("assignmentFee"),
            assignmentFeePctOfArv = number("assignmentFeePctOfArv"),
            earnestMoney = number("earnestMoney"),
            marketingCost = number("marketingCost"),
            daysToClose = integer("daysToClose"),
            buyerRehabEstimate = number("buyerRehabEstimate"),
            buyerSellCostPct = number("buyerSellCostPct"),
            buyerMinProfitPctOfArv = number("buyerMinProfitPctOfArv"),
            minDscrForLender = number("minDscrForLender"),
            minCashOnCashPct = number("minCashOnCashPct"),
            minCapRatePct = number("minCapRatePct"),
            flipMinProfitPctOfArv = number("flipMinProfitPctOfArv"),
            flipMinRoiPctOfCash = number("flipMinRoiPctOfCash"),
            wholesaleMinimumProfit = number("wholesaleMinimumProfit")
        )

        for (key in values.keys.sorted()) {
            if (key !in KNOWN_KEYS) unknown.add(key)
        }
        return DecodedInput(input, issues, unknown.distinct())
    }

    private val KNOWN_KEYS: Set<String> = setOf(
        "strategy", "purchasePrice", "closingCosts", "rehabCost", "arv", "sellerCredits",
        "monthlyRent", "otherMonthlyIncome", "vacancyRatePct", "creditLossRatePct",
        "propertyTaxAnnual", "propertyTaxPctOfPrice", "insuranceAnnual", "insurancePctOfPrice",
        "hoaMonthly", "maintenanceAnnual", "maintenancePctOfGsi", "maintenanceBasis",
        "managementAnnual", "managementPctOfEgi", "managementBasis", "capexReservePctOfGsi",
        "capexTreatment", "utilitiesMonthly", "landscapingMonthly", "otherOperatingAnnual",
        "financingModel", "downPaymentPct", "downPaymentAmount", "loanAmount", "downPaymentBasis",
        "interestRatePct", "loanTermMonths", "amortizationMonths", "interestOnlyMonths",
        "originationPointsPct", "lenderFees", "lenderReserveMonths",
        "maxLtvPct", "maxLtcPct", "maxLtvArvPct", "loanOverrides", "bridgeOverrides",
        "holdYears", "holdMonths", "rentGrowthPct", "expenseGrowthPct", "appreciationPct", "saleCostPct",
        "flipHoldMonths", "rehabContingencyPct", "holdingCostsMonthly", "buyClosingCostPct",
        "sellClosingCostPct", "sellerConcessions", "flipTargetProfitPctOfArv",
        "brrrrRehabMonths", "brrrrAcquisitionModel", "refiLtvPctOfArv", "refiInterestRatePct",
        "refiLoanTermMonths", "refiAmortizationMonths", "refiOriginationPointsPct",
        "refiLenderFees", "refiClosingCostPct",
        "wholesaleMode", "assignmentFee", "assignmentFeePctOfArv", "earnestMoney", "marketingCost",
        "daysToClose", "buyerRehabEstimate", "buyerSellCostPct", "buyerMinProfitPctOfArv",
        "minDscrForLender", "minCashOnCashPct", "minCapRatePct", "flipMinProfitPctOfArv",
        "flipMinRoiPctOfCash", "wholesaleMinimumProfit"
    )
}
