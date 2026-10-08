package com.example.data.repository

import com.example.data.local.entity.PropertyEntity
import com.example.data.local.entity.RentEstimateEntity
import com.example.data.local.entity.TaxRecordEntity
import com.example.domain.finance.underwriting.FinancingModel
import com.example.domain.finance.underwriting.InvestmentStrategy
import com.example.domain.finance.underwriting.IssueSeverity
import com.example.domain.finance.underwriting.UnderwritingAssumptions
import com.example.domain.finance.underwriting.UnderwritingInput
import com.example.domain.finance.underwriting.ValidationIssue

/**
 * Builds the canonical [UnderwritingInput] for a stored property.
 *
 * Single-source-of-truth policy: this factory decides **which** numbers the
 * [com.example.domain.finance.underwriting.UnderwritingEngine] sees for an automated run, and it
 * follows three rules:
 *
 *  1. **Observed data is used exactly.** A rent estimate or a tax record that exists and is
 *     finite and positive becomes an explicit input, unmodified.
 *  2. **Nothing is guessed.** The old repository fabricated economics when data was missing -
 *     rent as 0.8% of price, taxes as 1.2% of price or of the market-data value estimate,
 *     insurance as 0.6% of price, renovation as $35,000 for off-market deals and $5,000
 *     otherwise. Those silent fallbacks are gone: a missing observation stays missing, and a
 *     [ValidationIssue] reports each absence so it can never masquerade as observed data.
 *  3. **Assumptions are named.** Whatever the caller did not supply is resolved inside the
 *     engine against the named constants in [UnderwritingAssumptions] and
 *     [com.example.domain.finance.underwriting.FinancingModelDefaultsRegistry], and the result
 *     records the provenance of every one of them.
 */
object PropertyUnderwritingFactory {

    /** The assembled input plus the explicit findings about what was missing. */
    data class Build(
        val input: UnderwritingInput,
        val issues: List<ValidationIssue>
    )

    fun build(
        property: PropertyEntity,
        rentEstimate: RentEstimateEntity?,
        taxRecord: TaxRecordEntity?,
        strategy: InvestmentStrategy = InvestmentStrategy.BUY_AND_HOLD,
        financingModel: FinancingModel? = null
    ): Build {
        val issues = ArrayList<ValidationIssue>()

        val observedRent = rentEstimate?.estimatedRent?.takeIf { it.isFinite() && it > 0.0 }
        if (observedRent == null) {
            issues.add(
                ValidationIssue(
                    "MISSING_RENT_ESTIMATE",
                    IssueSeverity.WARNING,
                    "No usable rent estimate exists for this property; monthly rent stays at the " +
                        "stated value of 0 instead of a fabricated price-to-rent guess. Supply an " +
                        "explicit monthlyRent before treating rental metrics as observed."
                )
            )
        }

        val observedTax = taxRecord?.annualTaxAmount?.takeIf { it.isFinite() && it > 0.0 }
        if (observedTax == null) {
            issues.add(
                ValidationIssue(
                    "MISSING_PROPERTY_TAX_RECORD",
                    IssueSeverity.WARNING,
                    "No property tax record exists; the named assumption " +
                        "UnderwritingAssumptions.PROPERTY_TAX_PCT_OF_PRICE " +
                        "(${UnderwritingAssumptions.PROPERTY_TAX_PCT_OF_PRICE}% of price) applies."
                )
            )
        }

        issues.add(
            ValidationIssue(
                "INSURANCE_NAMED_DEFAULT",
                IssueSeverity.INFO,
                "Landlord insurance is not observed data for this property; the named assumption " +
                    "UnderwritingAssumptions.INSURANCE_PCT_OF_PRICE " +
                    "(${UnderwritingAssumptions.INSURANCE_PCT_OF_PRICE}% of price) applies."
            )
        )

        issues.add(
            ValidationIssue(
                "NO_RENOVATION_ESTIMATE",
                IssueSeverity.INFO,
                "No renovation estimate exists for this property; rehab cost is the stated value " +
                    "0, not a guess derived from the source type. State rehabCost explicitly for " +
                    "distressed or off-market deals."
            )
        )

        val observedHoa = property.hoaMonthly.takeIf { it.isFinite() && it > 0.0 }

        val input = UnderwritingInput(
            strategy = strategy,
            purchasePrice = property.price,
            rehabCost = 0.0,
            monthlyRent = observedRent ?: 0.0,
            propertyTaxAnnual = observedTax,
            hoaMonthly = observedHoa,
            financingModel = financingModel
        )
        return Build(input, issues)
    }
}
