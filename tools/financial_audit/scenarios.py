"""Independent synthetic expectations. No production/oracle imports.
Regenerate: python3 tools/financial_audit/scenarios.py
Dollar calculations use Decimal; annuity balance uses a separate cash ledger.
"""
from decimal import Decimal as D
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'app/src/test/resources/underwriting/independent_audit.json'

def payment(principal, rate, months=360):
    p, r = D(str(principal)), D(str(rate)) / 1200
    return p / months if not r else p * r / (1 - (1+r) ** -months)

def scenarios():
    rows = []
    def add(name, inp, expected, equations):
        if inp.get('financingModel') == 'ALL_CASH':
            inp = dict(inp, financingModel='CONVENTIONAL', downPaymentPct=100,
                       interestRatePct=0, originationPointsPct=0, lenderFees=0)
        rows.append(dict(id=name, input=inp, expected=expected, equations=equations))
    rental = dict(strategy='BUY_AND_HOLD', purchasePrice=200000, rehabCost=0,
                  closingCosts=4000, monthlyRent=2400, vacancyRatePct=5,
                  creditLossRatePct=0, propertyTaxAnnual=2400, insuranceAnnual=1200,
                  maintenancePctOfGsi=0, managementPctOfEgi=0, capexReservePctOfGsi=0,
                  holdYears=1, rentGrowthPct=0, expenseGrowthPct=0, appreciationPct=0, saleCostPct=8)
    # Model, principal, annual rate, points+fees, reserve months, IO
    models = [('ALL_CASH',0,0,0,0,False), ('CONVENTIONAL',160000,6.75,1200,0,False),
              ('DSCR',150000,7.5,3750,6,False), ('HARD_MONEY',170000,11.5,7600,0,True),
              ('PRIVATE_MONEY',180000,9,3200,0,True), ('SELLER_FINANCING',180000,6,500,0,False)]
    for model, principal, rate, fees, reserves, io in models:
        p = D(principal)
        monthly = p * D(str(rate))/1200 if io else payment(principal, rate)
        cash = D(204000-principal+fees) + reserves*(monthly+D(300))
        debt = monthly*12
        cf = D(23760)-debt
        balance = p
        for _ in range(12):
            balance -= monthly - balance*D(str(rate))/1200
        if io: balance = p
        add('hold_'+model.lower(), dict(rental, financingModel=model), {
            'financing.loanAmount':p, 'financing.monthlyPayment':monthly,
            'financing.newLoanFeesAndPoints':fees, 'core.closingCosts':4000,
            'core.totalCashRequired':cash, 'operating.grossScheduledIncomeAnnual':28800,
            'operating.effectiveGrossIncomeAnnual':27360, 'core.noiAnnual':23760,
            'core.annualDebtService':debt, 'core.annualCashFlow':cf,
            'core.capRateOnPricePct':D('11.88'), 'core.dscr':D(23760)/debt if debt else None,
            'core.cashOnCashPct':cf/cash*100,
            'hold.netSaleProceeds':D(184000)-balance,
            'hold.totalProfit':cf+D(184000)-balance-cash,
        }, 'GSI=2400*12; EGI=GSI*.95; NOI=EGI-2400-1200=23760. '
           'M=P*r/(1-(1+r)^-360), IO=P*r. Cash=200000-P+4000+fees+reserves*(M+tax/12+insurance/12). '
           'CF=NOI-12*M; cap=NOI/200000; exit=200000*.92-balance12; profit=CF+exit-cash.')
    wholesale = dict(strategy='WHOLESALE', financingModel='ALL_CASH', purchasePrice=150000,
                     arv=250000, buyerRehabEstimate=30000, assignmentFee=10000,
                     earnestMoney=1000, marketingCost=500, daysToClose=30, buyerSellCostPct=8,
                     buyerMinProfitPctOfArv=10)
    for mode, closing in [('ASSIGNMENT',0), ('DOUBLE_CLOSE',3100)]:
        profit = D(9500-closing)
        add('wholesale_'+mode.lower(), dict(wholesale, wholesaleMode=mode), {
            'wholesale.arv':250000, 'wholesale.profit':profit,
            'wholesale.totalCosts':500+closing, 'wholesale.buyerAllInCost':190000,
            'wholesale.buyerProfit':40000, 'wholesale.maxAssignableFee':25000,
            'wholesale.maoSeventyRule':145000, 'returns.cashInvested':1500,
            'returns.roiPct':profit/1500*100,
        }, 'Buyer profit=250000*.92-150000-10000-30000=40000; max fee=250000*.92-30000-150000-250000*.10=25000. '
           '70% heuristic=250000*.7-30000. Assignment profit=10000-500. '
           'Double-close adds 1%*150000+1%*160000=3100. Reported ROI denominator=earnest+marketing (funding limitation).')
    flip = dict(strategy='FIX_AND_FLIP', financingModel='ALL_CASH', purchasePrice=150000,
                arv=250000, rehabCost=20000, rehabContingencyPct=10, closingCosts=3000,
                flipHoldMonths=6, holdingCostsMonthly=300, sellClosingCostPct=8,
                flipTargetProfitPctOfArv=10)
    add('flip_cash', flip, {'flip.totalRehabCost':22000, 'flip.holdingCostsTotal':1800,
        'flip.sellingCosts':20000, 'flip.allInCost':176800, 'flip.profit':53200,
        'flip.maoSeventyRule':153000, 'flip.maxOfferForTargetProfit':178200,
        'returns.roiPct':D(53200)/176800*100,
        'returns.annualizedRoiPct':((1+D(53200)/176800)**2-1)*100},
        'Rehab=20000*1.10; cost=150000+22000+3000+300*6=176800; sale net=230000; '
        'profit=53200; MAO=230000-25000-22000-3000-1800=178200; CAGR=(1+profit/cash)^2-1.')
    default_holding = {k:v for k,v in flip.items() if k != 'holdingCostsMonthly'}
    add('flip_mao_price_based_holding', default_holding,
        {'flip.maxOfferForTargetProfit':D(180000)/D('1.009')},
        'At candidate price x: x+(.0015*x*6)+22000+3000=230000-25000; x=180000/1.009.')
    hard = dict(flip, financingModel='HARD_MONEY')
    # Acquisition finances base rehab only; contingency remains investor-funded.
    loan = D(170000)*D('.85'); fees=loan*D('.03')+2500
    interest=loan*D('.115')/12*6; cost=D(176800)+fees+interest
    add('flip_hard_money', hard, {'financing.loanAmount':loan,
        'flip.loanInterestPaid':interest, 'flip.loanFeesAndPoints':fees,
        'flip.allInCost':cost, 'flip.profit':230000-cost,
        'returns.cashInvested':cost-loan, 'returns.roiPct':(230000-cost)/(cost-loan)*100},
        'L=min(.85*(150000+20000), .70*250000)=144500; fees=.03*L+2500=6835; '
        'interest=L*.115*6/12=8308.75; cost=176800+6835+8308.75=191943.75.')
    brrrr=dict(rental, strategy='BRRRR', purchasePrice=150000, rehabCost=30000,
               arv=300000, closingCosts=3000, monthlyRent=2500, vacancyRatePct=0,
               propertyTaxAnnual=3000, holdingCostsMonthly=300, brrrrRehabMonths=6,
               refiLtvPctOfArv=60, refiInterestRatePct=0, refiLoanTermMonths=360,
               refiAmortizationMonths=360, refiOriginationPointsPct=0,
               refiLenderFees=0, refiClosingCostPct=0)
    for model, loan, phase in [('ALL_CASH',0,D(184800)), ('HARD_MONEY',153000,D('47687.5'))]:
        out=D(180000-loan); left=phase-out
        add('brrrr_'+model.lower(),dict(brrrr, financingModel=model), {
            'brrrr.bridgePayoffAmount':loan, 'brrrr.phase1CashInvested':phase,
            'brrrr.refinance.loanAmount':180000, 'brrrr.cashOutAtRefinance':out,
            'brrrr.cashLeftInDeal':left, 'brrrr.postRefiCore.annualCashFlow':19800,
            'brrrr.postRefiCore.dscr':D('4.3'),
        }, 'Cash phase=150000+30000+3000+1800=184800. Hard L=.85*180000=153000; '
           'phase=27000+3000+7090+8797.50+1800=47687.50. Refi=.60*300000=180000; '
           'cashout=180000-L; retained=phase-cashout; NOI=30000-4200=25800; debt=180000/360*12=6000.')
    return rows

if __name__ == '__main__':
    OUTPUT.write_text(json.dumps({'notice':'Synthetic QA assumptions, not observed property facts or market-validated estimates.',
                                 'scenarios':scenarios()}, indent=2, default=float)+'\n')
    print(OUTPUT)
