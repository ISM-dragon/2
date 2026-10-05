package com.example.domain.finance.underwriting

/**
 * Manual for the deterministic underwriting engine
 * ================================================
 *
 * The engine answers one question: *given these inputs and these stated
 * assumptions, what are the numbers?* It never guesses, never calls a network
 * service, and never asks a language model to do arithmetic.
 *
 * Three rules hold everywhere in this package:
 *
 *  1. **Deterministic.** Same input -> same output, byte for byte. There is no
 *     clock, no randomness, no I/O and no AI. The only iterative numeric step
 *     is the internal rate of return, and its bisection bracket and iteration
 *     count are fixed constants.
 *  2. **Explicit.** Every value the caller did not supply is a named default in
 *     [UnderwritingAssumptions] / [FinancingModelDefaultsRegistry], and the
 *     result reports where each field's value came from ([ValueSource]).
 *  3. **Honest about the undefined.** Dividing by zero is not a return: an
 *     all-cash deal reports `dscr = null` instead of a sentinel, and a BRRRR
 *     that returns all of the investor's capital flags
 *     [BrrrrAnalysis.infiniteCashOnCash] instead of printing an infinite ratio.
 */

// ---------------------------------------------------------------------------
// enumerations
// ---------------------------------------------------------------------------

enum class InvestmentStrategy { BUY_AND_HOLD, BRRRR, FIX_AND_FLIP, WHOLESALE }

enum class FinancingModel { CONVENTIONAL, DSCR, HARD_MONEY, PRIVATE_MONEY, SELLER_FINANCING }

/** Where a down payment percentage is measured, so 20% is never ambiguous. */
enum class DownPaymentBasis { PRICE, COST }

/** Whether replacement reserves sit inside NOI or below the line. */
enum class CapexTreatment { ABOVE_LINE_IN_NOI, BELOW_LINE_RESERVE }

/** How an operating-expense line is computed. Reported for every line. */
enum class ExpenseBasis {
    FIXED_ANNUAL,
    FIXED_MONTHLY,
    PERCENT_OF_PRICE,
    PERCENT_OF_GSI,
    PERCENT_OF_EGI,
    NONE
}

/** Provenance of a resolved value. */
enum class ValueSource { EXPLICIT, MODEL_DEFAULT, DERIVED }

enum class IssueSeverity { ERROR, WARNING, INFO }

enum class MetricApplicability { APPLICABLE, INFORMATIONAL_ONLY, NOT_APPLICABLE }

enum class DealRating { STRONG, ACCEPTABLE, MARGINAL, FAILS_CRITERIA }

enum class WholesaleMode { ASSIGNMENT, DOUBLE_CLOSE }

/**
 * Which lender ceiling limited the loan, if any. Reported so an "the bank said
 * 85% LTC" conversation can be settled from the output.
 */
enum class LoanCeiling { MAX_LTV_OF_PRICE, MAX_LTC_OF_COST, MAX_LTV_OF_ARV }

// ---------------------------------------------------------------------------
// input
// ---------------------------------------------------------------------------

/**
 * Everything an underwriter can state about a deal. Optional fields are
 * genuinely optional: `null` means "use the documented default", never "zero",
 * and the result records which of the two happened.
 *
 * Loan sizing precedence, highest first:
 *   1. [loanAmount]              (explicit dollars)
 *   2. [downPaymentAmount]       (explicit dollars)
 *   3. [downPaymentPct]          (percentage of price or cost, see basis)
 *   4. the financing model's default down payment
 *
 * An explicit [loanAmount] is a *request*: lender ceilings
 * (`maxLtvPct` / `maxLtcPct` / `maxLtvArvPct`) still apply, and when they bite
 * the result says so (`LOAN_CAPPED_BY_...`) and reports the requested amount.
 */
data class UnderwritingInput(
    val strategy: InvestmentStrategy = InvestmentStrategy.BUY_AND_HOLD,

    // ---- acquisition ------------------------------------------------------
    val purchasePrice: Double,
    val closingCosts: Double? = null,
    val rehabCost: Double = 0.0,
    /** After-repair value. Required for FIX_AND_FLIP and BRRRR. */
    val arv: Double? = null,
    val sellerCredits: Double = 0.0,

    // ---- income -----------------------------------------------------------
    val monthlyRent: Double = 0.0,
    val otherMonthlyIncome: Double = 0.0,
    val vacancyRatePct: Double? = null,
    val creditLossRatePct: Double? = null,

    // ---- operating expenses (each overrides the matching default) ---------
    val propertyTaxAnnual: Double? = null,
    val propertyTaxPctOfPrice: Double? = null,
    val insuranceAnnual: Double? = null,
    val insurancePctOfPrice: Double? = null,
    val hoaMonthly: Double? = null,
    val maintenanceAnnual: Double? = null,
    val maintenancePctOfGsi: Double? = null,
    val maintenanceBasis: ExpenseBasis? = null,
    val managementAnnual: Double? = null,
    val managementPctOfEgi: Double? = null,
    val managementBasis: ExpenseBasis? = null,
    val capexReservePctOfGsi: Double? = null,
    val capexTreatment: CapexTreatment? = null,
    val utilitiesMonthly: Double? = null,
    val landscapingMonthly: Double? = null,
    val otherOperatingAnnual: Double? = null,

    // ---- financing --------------------------------------------------------
    val financingModel: FinancingModel? = null,
    val downPaymentPct: Double? = null,
    val downPaymentAmount: Double? = null,
    val loanAmount: Double? = null,
    val downPaymentBasis: DownPaymentBasis? = null,
    val interestRatePct: Double? = null,
    val loanTermMonths: Int? = null,
    val amortizationMonths: Int? = null,
    val interestOnlyMonths: Int? = null,
    val originationPointsPct: Double? = null,
    val lenderFees: Double? = null,
    val lenderReserveMonths: Int? = null,
    val maxLtvPct: Double? = null,
    val maxLtcPct: Double? = null,
    val maxLtvArvPct: Double? = null,
    val loanOverrides: Map<String, Double> = emptyMap(),

    // ---- hold / exit ------------------------------------------------------
    val holdYears: Int? = null,
    val rentGrowthPct: Double? = null,
    val expenseGrowthPct: Double? = null,
    val appreciationPct: Double? = null,
    val saleCostPct: Double? = null,

    // ---- project (flip) ---------------------------------------------------
    val flipHoldMonths: Int? = null,
    val rehabContingencyPct: Double? = null,
    val holdingCostsMonthly: Double? = null,
    val buyClosingCostPct: Double? = null,
    val sellClosingCostPct: Double? = null,
    val sellerConcessions: Double? = null,
    val flipTargetProfitPctOfArv: Double? = null,

    // ---- BRRRR ------------------------------------------------------------
    val brrrrRehabMonths: Int? = null,
    val brrrrAcquisitionModel: FinancingModel? = null,
    val bridgeOverrides: Map<String, Double> = emptyMap(),
    val refiLtvPctOfArv: Double? = null,
    val refiInterestRatePct: Double? = null,
    val refiLoanTermMonths: Int? = null,
    val refiAmortizationMonths: Int? = null,
    val refiOriginationPointsPct: Double? = null,
    val refiLenderFees: Double? = null,
    val refiClosingCostPct: Double? = null,

    // ---- wholesale --------------------------------------------------------
    val wholesaleMode: WholesaleMode? = null,
    val assignmentFee: Double? = null,
    val assignmentFeePctOfArv: Double? = null,
    val earnestMoney: Double? = null,
    val marketingCost: Double? = null,
    val daysToClose: Int? = null,
    val buyerRehabEstimate: Double? = null,
    val buyerSellCostPct: Double? = null,
    val buyerMinProfitPctOfArv: Double? = null,

    // ---- verdict thresholds (override the defaults) -----------------------
    val minDscrForLender: Double? = null,
    val minCashOnCashPct: Double? = null,
    val minCapRatePct: Double? = null,
    val flipMinProfitPctOfArv: Double? = null,
    val flipMinRoiPctOfCash: Double? = null,
    val wholesaleMinimumProfit: Double? = null
)

// ---------------------------------------------------------------------------
// resolved financing
// ---------------------------------------------------------------------------

/** A financing model resolved against a specific property. */
data class FinancingTerms(
    val model: FinancingModel,
    val displayName: String,
    val loanAmount: Double,
    val downPaymentAmount: Double,
    val costBasis: Double,
    val financedRehab: Double,
    val financedClosingCosts: Double,
    val interestRatePct: Double,
    val loanTermMonths: Int,
    val amortizationMonths: Int,
    val interestOnlyMonths: Int,
    val originationPointsPct: Double,
    val lenderFees: Double,
    val lenderReserveMonths: Int,
    val lenderMinimumInterestMonths: Int,
    val minDscr: Double?,
    val maxLtvPct: Double?,
    val maxLtcPct: Double?,
    val maxLtvArvPct: Double?,
    val requestedLoanAmount: Double,
    val bindingLoanCeiling: LoanCeiling?,
    val downPaymentBasis: DownPaymentBasis,
    val monthlyPayment: Double,
    val interestOnlyPayment: Double,
    val newLoanFeesAndPoints: Double,
    val isInterestOnly: Boolean,
    val lenderProfitSharePct: Double
)

// ---------------------------------------------------------------------------
// result
// ---------------------------------------------------------------------------

data class ValidationIssue(
    val code: String,
    val severity: IssueSeverity,
    val message: String
)

/** One field's provenance: EXACTLY where its value came from. */
data class AssumptionRecord(
    val field: String,
    val source: ValueSource,
    val value: String
)

data class ExpenseLineResult(
    val key: String,
    val annualAmount: Double,
    val basis: ExpenseBasis
)

data class OperatingStatementResult(
    val grossScheduledIncomeAnnual: Double,
    val vacancyAndCreditLossAnnual: Double,
    val effectiveGrossIncomeAnnual: Double,
    val expenseLines: List<ExpenseLineResult>,
    val capitalReservesAnnual: Double,
    val capexTreatment: CapexTreatment,
    val totalOperatingExpensesAnnual: Double,
    val netOperatingIncomeAnnual: Double,
    val annualDebtService: Double,
    val monthlyDebtService: Double,
    val monthlyCashFlow: Double,
    val annualCashFlow: Double,
    val dscr: Double?,
    val capRateOnPricePct: Double?,
    val capRateOnCostPct: Double?,
    val grossRentMultiplier: Double?,
    val breakEvenOccupancyPct: Double?,
    val operatingExpenseRatioPct: Double?,
    val vacancyRatePct: Double,
    val creditLossRatePct: Double
)

data class FinancingSummary(
    val model: FinancingModel,
    val displayName: String,
    val loanAmount: Double,
    val downPaymentAmount: Double,
    val downPaymentPctOfPrice: Double?,
    val interestRatePct: Double,
    val loanTermMonths: Int,
    val amortizationMonths: Int,
    val interestOnlyMonths: Int,
    val monthlyPayment: Double,
    val newLoanFeesAndPoints: Double,
    val originationPointsPct: Double,
    val lenderFees: Double,
    val lenderReserveMonths: Int,
    val isInterestOnly: Boolean,
    val requestedLoanAmount: Double,
    val loanCappedByLender: Boolean,
    val bindingLoanCeiling: LoanCeiling?,
    val maxLtvPct: Double?,
    val maxLtcPct: Double?,
    val downPaymentBasis: DownPaymentBasis
)

data class CapitalStack(
    val downPaymentAmount: Double,
    val cashRehab: Double,
    val closingCosts: Double,
    val loanFeesAndPoints: Double,
    val lenderReserves: Double,
    val sellerCredits: Double,
    val totalCashRequired: Double,
    val ltvOfPricePct: Double? = null
)

data class CoreMetrics(
    val purchasePrice: Double,
    val closingCosts: Double,
    val rehabCost: Double,
    val loanAmount: Double,
    val downPaymentAmount: Double,
    val ltvOfPricePct: Double?,
    val totalCashRequired: Double,
    val monthlyDebtService: Double,
    val annualDebtService: Double,
    val noiAnnual: Double,
    val monthlyCashFlow: Double,
    val annualCashFlow: Double,
    val capRateOnPricePct: Double?,
    val capRateOnCostPct: Double?,
    val cashOnCashPct: Double?,
    val dscr: Double?,
    val breakEvenOccupancyPct: Double?,
    val grossRentMultiplier: Double?,
    val operatingExpenseRatioPct: Double?,
    val effectiveGrossIncomeAnnual: Double,
    // The three fields below exist so the BRRRR block can publish a complete
    // stabilised core; the top-level core block reports the primary twenty.
    val grossScheduledIncomeAnnual: Double = 0.0,
    val totalOperatingExpensesAnnual: Double = 0.0,
    val infiniteCashOnCash: Boolean = false
)

data class ProjectionYear(
    val year: Int,
    val effectiveGrossIncome: Double,
    val operatingExpenses: Double,
    val netOperatingIncome: Double,
    val debtService: Double,
    val cashFlow: Double,
    val dscr: Double?,
    val propertyValue: Double,
    val loanBalance: Double
)

data class HoldAnalysis(
    val simpleAnnualizedRoiPct: Double?,
    val years: List<ProjectionYear>,
    val exitValue: Double,
    val saleCosts: Double,
    val loanBalanceAtExit: Double,
    val netSaleProceeds: Double,
    val cumulativeCashFlow: Double,
    val totalProfit: Double,
    val roiPct: Double?,
    val annualizedRoiPct: Double?,
    val equityMultiple: Double?,
    val irrMonthly: Double?,
    val irrAnnualPct: Double?
)

data class ReturnMetrics(
    val cashInvested: Double,
    val holdPeriodMonths: Double,
    val totalProfit: Double,
    val roiPct: Double?,
    val simpleAnnualizedRoiPct: Double?,
    val annualizedRoiPct: Double?,
    val equityMultiple: Double?,
    val irrAnnualPct: Double?
)

data class RefinanceSummary(
    val loanAmount: Double,
    val ltvOfArvPct: Double,
    val interestRatePct: Double,
    val loanTermMonths: Int,
    val amortizationMonths: Int,
    val monthlyPayment: Double,
    val closingCostsAndPoints: Double
)

/**
 * Buy -> Rehab -> Rent -> Refinance -> Repeat.
 *
 * The decision metrics are the post-refinance ones ([postRefiCore]): the deal an
 * investor actually keeps is the one after the bridge loan is retired. When the
 * refinance returns all of the cash invested, [infiniteCashOnCash] is set and
 * [postRefiCashOnCashPct] is null - because dividing by zero is not a return.
 */
data class BrrrrAnalysis(
    val arv: Double,
    val acquisitionModel: FinancingModel,
    val bridgeLoanAmount: Double,
    val bridgeInterestPaid: Double,
    val bridgeLoanFeesAndPoints: Double,
    val bridgePayoffAmount: Double,
    val rehabMonths: Int,
    val holdingCostsDuringRehab: Double,
    val phase1CashInvested: Double,
    val refinance: RefinanceSummary,
    val cashOutAtRefinance: Double,
    val cashLeftInDeal: Double,
    val capitalRecoveredPct: Double?,
    val infiniteCashOnCash: Boolean,
    val postRefiCashOnCashPct: Double?,
    val postRefiMonthlyCashFlow: Double,
    val postRefiDscr: Double?,
    val postRefiCapRateOnArvPct: Double?,
    val equityCreatedAtRefi: Double,
    val postRefiCore: CoreMetrics,
    val postRefiCapitalStack: CapitalStack,
    val postRefiOperating: OperatingStatementResult,
    val postRefiHold: HoldAnalysis
)

data class FlipAnalysis(
    val arv: Double,
    val rehabBase: Double,
    val rehabContingencyPct: Double,
    val totalRehabCost: Double,
    val holdMonths: Int,
    val buyClosingCosts: Double,
    val holdingCostsTotal: Double,
    val holdingCostsMonthly: Double,
    val loanAmount: Double,
    val loanInterestPaid: Double,
    val loanFeesAndPoints: Double,
    val lenderMinimumInterestMonths: Int,
    val allInCost: Double,
    val grossSalePrice: Double,
    val sellingCosts: Double,
    val netSaleProceeds: Double,
    val profit: Double,
    val profitMarginOnArvPct: Double,
    val profitMarginOnCostPct: Double?,
    val returnMetrics: ReturnMetrics,
    val maoSeventyRule: Double,
    val maxOfferForTargetProfit: Double?,
    val targetProfitPctOfArv: Double,
    val targetProfitAmount: Double,
    val breakEvenArv: Double?,
    val breakEvenArvCushionPct: Double?
)

data class WholesaleAnalysis(
    val mode: WholesaleMode,
    val contractPrice: Double,
    val arv: Double,
    val assignmentFee: Double,
    val assignmentFeeSource: String,
    val earnestMoney: Double,
    val marketingCost: Double,
    val buySideClosingCosts: Double,
    val sellSideClosingCosts: Double,
    val grossRevenue: Double,
    val totalCosts: Double,
    val profit: Double,
    val daysToClose: Int,
    val buyerAllInCost: Double,
    val buyerProfit: Double,
    val buyerProfitPctOfArv: Double?,
    val maxAssignableFee: Double,
    val maoSeventyRule: Double,
    val returnMetrics: ReturnMetrics
)

data class DealVerdict(
    val rating: DealRating,
    val passedCriteria: List<String>,
    val failedCriteria: List<String>
)

data class UnderwritingResult(
    val specVersion: String,
    val strategy: InvestmentStrategy,
    val financingModel: FinancingModel,
    val purchasePrice: Double,
    val rehabCost: Double,
    val financing: FinancingSummary,
    val operating: OperatingStatementResult,
    val core: CoreMetrics,
    val capitalStack: CapitalStack,
    val hold: HoldAnalysis? = null,
    val brrrr: BrrrrAnalysis? = null,
    val flip: FlipAnalysis? = null,
    val wholesale: WholesaleAnalysis? = null,
    val returns: ReturnMetrics? = null,
    val verdict: DealVerdict,
    val validation: List<ValidationIssue>,
    val assumptionsUsed: Map<String, AssumptionRecord>,
    val metricApplicability: Map<String, MetricApplicability>
)

/** One strategy's headline answer, for side-by-side comparison screens. */
data class StrategyComparisonRow(
    val strategy: InvestmentStrategy,
    val rating: DealRating,
    val metricLabel: String,
    val metricValue: Double?,
    val monthlyCashFlow: Double?,
    val dscr: Double?,
    val cashOnCashPct: Double?,
    val totalCashRequired: Double,
    val summary: String
)

/** One financing model's answer for the same deal. */
data class FinancingComparisonRow(
    val model: FinancingModel,
    val displayName: String,
    val loanAmount: Double,
    val downPaymentAmount: Double,
    val monthlyPayment: Double,
    val totalCashRequired: Double,
    val monthlyCashFlow: Double,
    val dscr: Double?,
    val cashOnCashPct: Double?,
    val bindingLoanCeiling: LoanCeiling?,
    val ltvOfPricePct: Double?
)
