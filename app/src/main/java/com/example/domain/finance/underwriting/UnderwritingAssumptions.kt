package com.example.domain.finance.underwriting

/**
 * Every number the engine uses that is NOT supplied by the caller lives here.
 *
 * The point of this file is auditability: an underwriter must be able to read
 * the model, see exactly which defaults produced a result, and replace any of
 * them. Nothing in this package calls a network service, a clock, a random
 * number generator or an AI model - the same input always produces the same
 * output, and every result carries the list of assumptions that produced it
 * ([AssumptionRecord] / [UnderwritingResult.assumptionsUsed]).
 *
 * These are stated, overridable defaults for US residential investment deals
 * (2026 vintage), not market promises. Sources are named so they can be
 * challenged and replaced with the user's own underwriting standards.
 */
object UnderwritingAssumptions {

    /** Bumping this string is a breaking change to the reported assumptions. */
    const val SPEC_VERSION: String = "US_INVESTOR_DEFAULTS_2026_01"

    // ---- operating expenses -------------------------------------------------

    /** Loss to lease / turnover vacancy. US SFR underwriting convention: 5%. */
    const val VACANCY_RATE_PCT: Double = 5.0

    /** Collection loss and non-payment, on top of vacancy. 0% unless modelled. */
    const val CREDIT_LOSS_RATE_PCT: Double = 0.0

    /** Maintenance & repairs as a share of gross scheduled income. */
    const val MAINTENANCE_PCT_OF_GSI: Double = 5.0

    /** Property management as a share of collected (effective) income. */
    const val MANAGEMENT_PCT_OF_EGI: Double = 8.0

    /** Replacement reserves / CapEx, agency-style, inside NOI. */
    const val CAPEX_RESERVE_PCT_OF_GSI: Double = 5.0

    /** Default property tax, annual, as a share of purchase price. */
    const val PROPERTY_TAX_PCT_OF_PRICE: Double = 1.20

    /** Default landlord insurance, annual, as a share of purchase price. */
    const val INSURANCE_PCT_OF_PRICE: Double = 0.60

    // ---- growth and exit ---------------------------------------------------

    /** Default hold period for a buy-and-hold projection. */
    const val HOLD_YEARS: Int = 5

    const val RENT_GROWTH_PCT: Double = 3.0
    const val EXPENSE_GROWTH_PCT: Double = 3.0

    /** Long-run appreciation used for the exit value. */
    const val APPRECIATION_PCT: Double = 3.5

    /** Commission + title + transfer + concessions when selling. */
    const val SALE_COST_PCT: Double = 8.0

    /** Default flip hold period in months. */
    const val FLIP_HOLD_MONTHS: Int = 6

    /** Rehab overrun buffer applied to the rehab estimate. */
    const val REHAB_CONTINGENCY_PCT: Double = 10.0

    /** Seller-side closing costs on a flip. */
    const val FLIP_SELL_COST_PCT: Double = 8.0

    /** Carrying costs during a flip/BRRRR, per month, as a share of price. */
    const val HOLDING_COSTS_MONTHLY_PCT_OF_PRICE: Double = 0.15

    // ---- lender / qualification thresholds ---------------------------------

    /** Minimum DSCR an investor lender will usually accept. */
    const val MIN_DSCR_FOR_LENDER: Double = 1.25

    /** Minimum cash-on-cash return for a deal to be "worth the risk". */
    const val MIN_CASH_ON_CASH_PCT: Double = 6.0

    /** Minimum going-in cap rate. */
    const val MIN_CAP_RATE_PCT: Double = 5.0

    /** A flip should clear this share of ARV as profit (the 70%-rule world). */
    const val FLIP_MIN_PROFIT_PCT_OF_ARV: Double = 10.0

    /** ...and return this much on the cash actually at risk in the deal. */
    const val FLIP_MIN_ROI_PCT_OF_CASH: Double = 15.0

    /** Linear annualisation threshold used only for reference reporting. */
    const val FLIP_MIN_ANNUALIZED_ROI_PCT: Double = 20.0

    /** A BRRRR refinance is sized at this share of ARV. */
    const val BRRRR_TARGET_LTV_OF_ARV_PCT: Double = 75.0

    /** The end buyer of an assignment must still make this much of ARV. */
    const val WHOLESALE_BUYER_MIN_PROFIT_PCT_OF_ARV: Double = 10.0

    /** Default assignment fee when the wholesaler does not state one. */
    const val WHOLESALE_ASSIGNMENT_FEE_PCT_OF_ARV: Double = 2.0

    /** Minimum acceptable assignment profit, in dollars. */
    const val WHOLESALE_MINIMUM_PROFIT_AMOUNT: Double = 5000.0

    /** Marketing / list cost of a wholesale deal. */
    const val WHOLESALER_MARKETING_COST: Double = 500.0

    /** Days from contract to close used to annualise wholesale returns. */
    const val DAYS_TO_CLOSE_WHOLESALE: Int = 30

    // ---- BRRRR refinance ---------------------------------------------------

    const val BRRRR_REFI_INTEREST_RATE_PCT: Double = 7.50
    const val BRRRR_REFI_TERM_MONTHS: Int = 360
    const val BRRRR_REFI_POINTS_PCT: Double = 1.5
    const val BRRRR_REFI_LENDER_FEES: Double = 1500.0
    const val BRRRR_REFI_CLOSING_COST_PCT: Double = 2.0

    /** Refi lenders test DSCR on the stabilised property. */
    const val BRRRR_REFI_MIN_DSCR: Double = 1.20

    /** Ceiling used when a loan's own term is extended for an IRR stream. */
    const val REFINANCE_EXTENSION_AMORTIZATION_MONTHS: Int = 360

    /** Bisection settings for the internal rate of return. Fixed, so it repeats. */
    const val IRR_ITERATIONS: Int = 200
    const val IRR_LOW_MONTHLY: Double = -0.9999
    const val IRR_HIGH_MONTHLY: Double = 10.0
    const val IRR_BRACKET_ITERATIONS: Int = 200
}

/**
 * Stated defaults for each financing model. `maxLtvPct` is a share of the
 * purchase price, `maxLtcPct` a share of cost (purchase + financed rehab) and
 * `maxLtvArvPct` a share of after-repair value; only the constraints a model
 * actually declares are applied, and the tightest one wins.
 *
 * `downPaymentPct` is interpreted against [downPaymentBasis]: agency and DSCR
 * products put a share of the *price* down, bridge lenders put a share of the
 * *cost* down (which is the LTC constraint restated).
 */
data class FinancingModelDefaults(
    val model: FinancingModel,
    val displayName: String,
    val downPaymentPct: Double,
    val downPaymentBasis: DownPaymentBasis,
    val interestRatePct: Double,
    val loanTermMonths: Int,
    val amortizationMonths: Int,
    val interestOnlyMonths: Int,
    val originationPointsPct: Double,
    val lenderFees: Double,
    val closingCostPctOfPrice: Double,
    val lenderReserveMonths: Int,
    val financesRehab: Boolean,
    val financesClosingCosts: Boolean = false,
    val maxLtvPct: Double? = null,
    val maxLtcPct: Double? = null,
    val maxLtvArvPct: Double? = null,
    val minDscr: Double? = null,
    val lenderMinimumInterestMonths: Int = 0,
    /** Only declared by models that actually share profit (private money). */
    val lenderProfitSharePct: Double? = null
)

object FinancingModelDefaultsRegistry {

    val ALL: Map<FinancingModel, FinancingModelDefaults> = mapOf(

        FinancingModel.CONVENTIONAL to FinancingModelDefaults(
            model = FinancingModel.CONVENTIONAL,
            displayName = "Conventional (Fannie/Freddie investor)",
            downPaymentPct = 20.0,
            downPaymentBasis = DownPaymentBasis.PRICE,
            interestRatePct = 6.75,
            loanTermMonths = 360,
            amortizationMonths = 360,
            interestOnlyMonths = 0,
            originationPointsPct = 0.0,
            lenderFees = 1200.0,
            closingCostPctOfPrice = 2.5,
            lenderReserveMonths = 0,
            financesRehab = false,
            maxLtvPct = 80.0,
            minDscr = null
        ),

        FinancingModel.DSCR to FinancingModelDefaults(
            model = FinancingModel.DSCR,
            displayName = "DSCR investor loan",
            downPaymentPct = 25.0,
            downPaymentBasis = DownPaymentBasis.PRICE,
            interestRatePct = 7.50,
            loanTermMonths = 360,
            amortizationMonths = 360,
            interestOnlyMonths = 0,
            originationPointsPct = 1.5,
            lenderFees = 1500.0,
            closingCostPctOfPrice = 2.0,
            lenderReserveMonths = 6,
            financesRehab = false,
            maxLtvPct = 75.0,
            minDscr = 1.25
        ),

        FinancingModel.HARD_MONEY to FinancingModelDefaults(
            model = FinancingModel.HARD_MONEY,
            displayName = "Hard money / bridge",
            downPaymentPct = 15.0,
            downPaymentBasis = DownPaymentBasis.COST,
            interestRatePct = 11.50,
            loanTermMonths = 12,
            amortizationMonths = 0,
            interestOnlyMonths = 12,
            originationPointsPct = 3.0,
            lenderFees = 2500.0,
            closingCostPctOfPrice = 1.5,
            lenderReserveMonths = 0,
            financesRehab = true,
            maxLtcPct = 85.0,
            maxLtvArvPct = 70.0,
            lenderMinimumInterestMonths = 3
        ),

        FinancingModel.PRIVATE_MONEY to FinancingModelDefaults(
            model = FinancingModel.PRIVATE_MONEY,
            displayName = "Private money",
            downPaymentPct = 10.0,
            downPaymentBasis = DownPaymentBasis.COST,
            interestRatePct = 9.00,
            loanTermMonths = 24,
            amortizationMonths = 0,
            interestOnlyMonths = 24,
            originationPointsPct = 1.5,
            lenderFees = 500.0,
            closingCostPctOfPrice = 1.0,
            lenderReserveMonths = 0,
            financesRehab = true,
            maxLtcPct = 90.0,
            maxLtvArvPct = 75.0,
            lenderProfitSharePct = 0.0
        ),

        FinancingModel.SELLER_FINANCING to FinancingModelDefaults(
            model = FinancingModel.SELLER_FINANCING,
            displayName = "Seller financing",
            downPaymentPct = 10.0,
            downPaymentBasis = DownPaymentBasis.PRICE,
            interestRatePct = 6.00,
            loanTermMonths = 60,
            amortizationMonths = 360,
            interestOnlyMonths = 0,
            originationPointsPct = 0.0,
            lenderFees = 500.0,
            closingCostPctOfPrice = 1.0,
            lenderReserveMonths = 0,
            financesRehab = false,
            maxLtvPct = 90.0
        )
    )

    fun of(model: FinancingModel): FinancingModelDefaults =
        ALL[model] ?: ALL.getValue(FinancingModel.CONVENTIONAL)

    /** All models rendered as scenarios for side-by-side comparison. */
    fun allModels(): List<FinancingModelDefaults> = FinancingModel.entries.map { of(it) }
}
