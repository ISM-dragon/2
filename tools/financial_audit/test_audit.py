"""Run: python3 -m unittest discover -s tools/financial_audit -v.
Expected failures are OPEN defects, not acceptance of their current outputs.
"""
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/underwriting_oracle'))
from oracle import analyze, amortizing_payment, F
from generate_golden_vectors import flatten
from scenarios import scenarios

FIXTURES = ROOT / 'app/src/test/resources/underwriting/independent_audit.json'

class IndependentAudit(unittest.TestCase):
    def fixtures(self):
        return json.loads(FIXTURES.read_text())['scenarios']

    def scenario(self, name):
        return next(s['input'] for s in self.fixtures() if s['id'] == name)

    def test_independent_fixture_file_is_current(self):
        self.assertEqual(self.fixtures(), json.loads(json.dumps(scenarios(), default=float)))

    def test_independent_matrix(self):
        for scenario in self.fixtures():
            actual = flatten(analyze(scenario['input']))
            for key, value in scenario['expected'].items():
                with self.subTest(scenario=scenario['id'], metric=key):
                    self.assertIn(key, actual)
                    if value is None:
                        self.assertIsNone(actual[key])
                    else:
                        self.assertAlmostEqual(value, actual[key], delta=max(1e-6, abs(value)*1e-9))

    def test_mao_reprices_default_holding_and_honors_explicit_zero(self):
        for holding in [None, 0, 300]:
            inp = self.scenario('flip_mao_price_based_holding')
            if holding is not None: inp['holdingCostsMonthly'] = holding
            offer = analyze(inp)['flip']['maxOfferForTargetProfit']
            rerun = analyze(dict(inp, purchasePrice=offer))['flip']
            self.assertAlmostEqual(25000, rerun['profit'], places=6)
            self.assertLess(analyze(dict(inp, purchasePrice=offer+1))['flip']['profit'], 25000)

    def test_missing_arv_blocks_flip_and_brrrr(self):
        for strategy, block in [('FIX_AND_FLIP','flip'), ('BRRRR','brrrr')]:
            r=analyze(dict(strategy=strategy,purchasePrice=150000,monthlyRent=2000))
            self.assertIsNone(r.get(block)); self.assertIsNone(r['returns'])
            self.assertTrue(any(i['severity']=='ERROR' for i in r['validation']))

    def test_invalid_numbers_report_errors(self):
        for field in ['purchasePrice', 'rehabCost', 'monthlyRent', 'arv']:
            for value in [float('nan'), float('inf'), -float('inf')]:
                inp=self.scenario('flip_cash'); inp[field]=value
                r=analyze(inp)
                self.assertTrue(any(i['severity']=='ERROR' for i in r['validation']))
                json.dumps(r, allow_nan=False)

    def test_negative_price_and_zero_rate_boundary(self):
        r=analyze(dict(purchasePrice=-1,monthlyRent=0))
        self.assertIn('NON_POSITIVE_PURCHASE_PRICE', [i['code'] for i in r['validation']])
        self.assertEqual(F(1000), amortizing_payment(F(12000), F(0), 12))

    def test_conflicting_financing_and_arv_ceiling(self):
        inp=self.scenario('flip_hard_money')
        r=analyze(dict(inp,loanAmount=200000,downPaymentAmount=1,arv=200000))
        self.assertEqual(140000, r['financing']['loanAmount'])
        self.assertIn('LOAN_AMOUNT_DOWN_PAYMENT_CONFLICT', [i['code'] for i in r['validation']])

    def test_sensitivity(self):
        inp=self.scenario('flip_cash'); base=analyze(inp)['flip']['profit']
        self.assertAlmostEqual(base-23000,analyze(dict(inp,arv=225000))['flip']['profit'])
        self.assertAlmostEqual(base-2200,analyze(dict(inp,rehabCost=22000))['flip']['profit'])
        self.assertAlmostEqual(base-900,analyze(dict(inp,flipHoldMonths=9))['flip']['profit'])
        inp=self.scenario('hold_conventional'); base=analyze(inp)['core']
        self.assertLess(analyze(dict(inp,interestRatePct=8))['core']['annualCashFlow'],base['annualCashFlow'])
        self.assertLess(analyze(dict(inp,vacancyRatePct=10))['core']['dscr'],base['dscr'])

    @unittest.expectedFailure
    def test_open_f02_wholesale_must_not_invent_arv(self):
        inp=self.scenario('wholesale_assignment'); del inp['arv']
        self.assertIsNone(analyze(inp)['wholesale'])

    @unittest.expectedFailure
    def test_open_f03_below_line_reserve_is_still_cash_outflow(self):
        inp=self.scenario('hold_all_cash')
        above=analyze(dict(inp,capexReservePctOfGsi=5))
        below=analyze(dict(inp,capexReservePctOfGsi=5,capexTreatment='BELOW_LINE_RESERVE'))
        self.assertAlmostEqual(above['core']['annualCashFlow'], below['core']['annualCashFlow'])

    @unittest.expectedFailure
    def test_open_f04_hard_money_mao_must_reprice_same_financed_rehab(self):
        inp=self.scenario('flip_hard_money')
        offer=analyze(inp)['flip']['maxOfferForTargetProfit']
        self.assertAlmostEqual(25000,analyze(dict(inp,purchasePrice=offer))['flip']['profit'],places=6)

    @unittest.expectedFailure
    def test_open_f05_amortizing_mao_must_use_scheduled_interest(self):
        inp=dict(self.scenario('flip_cash'),downPaymentPct=20,interestRatePct=6.75)
        offer=analyze(inp)['flip']['maxOfferForTargetProfit']
        self.assertAlmostEqual(25000,analyze(dict(inp,purchasePrice=offer))['flip']['profit'],places=6)

if __name__ == '__main__': unittest.main()
