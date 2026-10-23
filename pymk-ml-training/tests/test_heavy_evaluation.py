import copy
import hashlib
import json
from pathlib import Path
import unittest
from unittest.mock import patch
from contextlib import redirect_stdout
import io

import numpy as np
import test_heavy_ranker_training
from pymk_training.dataset import DatasetValidationError, load_multitarget_datasets
from pymk_training.evaluate_heavy_ranker import classification, ranking, bootstrap, evaluate, load_context, load_policy
from pymk_training.evaluate_heavy_ranker import main


class EvaluationMetricsTest(unittest.TestCase):
    def test_auc_ece_boundaries_and_brier_are_exact(self):
        report = classification(np.array([0,0,1,1]),np.array([0,.2,.8,1]),10)
        self.assertEqual(report['rocAuc'],1)
        self.assertAlmostEqual(report['ece'],.1)
        self.assertAlmostEqual(report['brier'],.02)
        self.assertEqual(sum(row['rows'] for row in report['reliability']),4)
        self.assertEqual(report['reliability'][-1]['rows'],1)

    def test_empty_single_class_and_nonprobability_baseline(self):
        for y in (np.array([]),np.array([1,1])):
            result = classification(y,np.zeros(len(y)))
            self.assertIsNone(result['rocAuc'])
            self.assertIsNone(result['averagePrecision'])
        result = classification(np.array([0,1]),np.array([4,20]),probability=False)
        self.assertNotIn('ece',result)
        self.assertNotIn('brier',result)
        for scores in ([0,float('nan')],[0,1.1],[0,-.1]):
            with self.assertRaises(DatasetValidationError):
                classification(np.array([0,1]),np.array(scores))

    def test_query_grouping_ties_small_pools_and_zero_positive_queries(self):
        y = np.array([1,0,1,0])
        members = np.array([1,1,1,2])
        times = np.array(['a','a','b','a'])
        candidates = np.array([3,2,2,1])
        result = ranking(y,np.ones(4),members,times,candidates,1)
        self.assertEqual(result['queries'],3)
        self.assertEqual(result['positiveQueries'],2)
        self.assertAlmostEqual(result['precisionAtK'],1/3)
        self.assertAlmostEqual(result['recallAtKWithinPool'],.5)
        result = ranking(y,np.ones(4),members,times,candidates,10)
        self.assertAlmostEqual(result['precisionAtK'],.5)
        self.assertEqual(result['fullKCoverage'],0)

    def test_bootstrap_is_paired_reproducible_and_clusters_all_snapshots(self):
        y = np.array([1,0,0,1,1,0,1,0])
        p = np.array([.8,.1,.2,.9,.8,.3,.7,.1])
        members = np.array([1,1,1,1,2,2,2,2])
        times = np.array(['a','a','b','b']*2)
        ids = np.array([2,3,2,3,1,3,1,3])
        first = bootstrap(y,p,p,members,times,ids,1,100,42)
        self.assertEqual(first,bootstrap(y,p,p,members,times,ids,1,100,42))
        self.assertEqual(first['intervals']['precisionDeltaVsMutual']['low'],0)
        self.assertEqual(first['intervals']['precisionDeltaVsMutual']['high'],0)
        self.assertIsNone(bootstrap(y,p,p,np.ones(8),times,ids,1,100,42)['intervals'])
        uncertainty = bootstrap(y,p,p,members,times,ids,1,100,42,True)['intervals']['ece']
        self.assertGreaterEqual(uncertainty['low'],0)
        self.assertLessEqual(uncertainty['high'],1)
        self.assertEqual(uncertainty['validReplicates'],100)

    def test_float32_ece_bootstrap_uses_the_same_bin_boundaries(self):
        labels = np.array([1,0,1,0])
        scores = np.array([.7,.65,.7,.65],dtype=np.float32)
        result = bootstrap(labels,scores,scores,np.array([1,1,2,2]),np.array(['a']*4),np.array([3,4,3,4]),1,100,42,True)
        expected = classification(labels,scores)['ece']
        self.assertAlmostEqual(result['intervals']['ece']['low'],expected)
        self.assertAlmostEqual(result['intervals']['ece']['high'],expected)


class HeavyEvaluationIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.training = test_heavy_ranker_training.HeavyRankerTrainingTest()
        self.training.setUp()
        self.addCleanup(self.training.doCleanups)
        self.root = self.training.root
        self.model,self.metadata,_ = self.training.train()
        self.policy = self.root/'policy.json'
        source = Path(__file__).resolve().parents[1]/'evaluation-policy.json'
        policy = json.loads(source.read_text())
        policy.update(k=1,l1Survivors=1,bootstrapSamples=100)
        self.policy.write_text(json.dumps(policy))
        self.data = load_multitarget_datasets(self.training.paths)
        rows = []
        for time,member,candidate in zip(self.data.feature_as_of,self.data.member_ids,self.data.candidate_ids):
            rows.append({'featureAsOf':str(time),'memberId':int(member),'candidateId':int(candidate),
                         'blankProfile':True,'newMember':True,'degree':0,
                         'sources':['HEURISTIC'] if candidate != 4 else []})
        self.context = self.root/'context.json'
        self.context.write_text(json.dumps({'schemaVersion':1,'candidateBudgets':policy['candidateBudgets'],
            'datasetSha256':sorted(item['sha256'] for item in self.data.input_files),'rows':rows}))

    def evaluate(self, name='report.json', context=True):
        return evaluate(self.training.paths,self.model,self.metadata,self.training.l1,self.training.l1_metadata,
                        self.policy,self.root/name,self.context if context else None)

    def test_real_models_identical_cohorts_stages_and_immutable_report(self):
        before = self.model.read_bytes(),self.metadata.read_bytes(),self.training.l1.read_bytes()
        report = self.evaluate()
        self.assertEqual(report,json.loads((self.root/'report.json').read_text()))
        self.assertEqual(before,(self.model.read_bytes(),self.metadata.read_bytes(),self.training.l1.read_bytes()))
        test = report['partitions']['test']
        self.assertEqual(test['exportedPool']['heavySent']['rows'],3)
        self.assertEqual(test['historicalCohorts']['blankProfile']['heavySent']['rows'],3)
        self.assertIsNone(test['historicalCohorts']['degree10Plus']['heavyAccepted']['rocAuc'])
        self.assertEqual(test['stages']['l0Rows'],2)
        self.assertEqual(test['stages']['l1Rows'],1)
        self.assertEqual(test['stages']['positiveCounts']['invite_sent']['lostAtL1'],1)
        for counts in test['stages']['positiveCounts'].values():
            self.assertEqual(counts['exportedPoolPositives'],counts['lostAtL0']+counts['lostAtL1']+counts['afterL1'])
            self.assertEqual(counts['lostAtL2Scoring'],0)
            self.assertIsNone(counts['fullEligiblePoolRecall'])
        self.assertEqual(report['qualityGate']['status'],'not_met')
        self.assertFalse(report['automaticPromotion'])
        self.assertEqual(report,self.evaluate('repeat.json'))
        with self.assertRaises(FileExistsError):
            self.evaluate()

    def test_missing_context_is_explicit_and_blocks_quality_gate(self):
        report = self.evaluate(context=False)
        self.assertIsNone(report['partitions']['test']['historicalCohorts'])
        self.assertIn('historical cohort/L0 replay context unavailable',report['qualityGate']['reasons'])

    def test_equivalent_utc_cutoff_encodings_are_compatible(self):
        metadata = json.loads(self.metadata.read_text())
        value = metadata['embeddingCompatibility']['fitCutoff']
        metadata['embeddingCompatibility']['fitCutoff'] = value.replace('Z','+00:00') if value.endswith('Z') else value.replace('+00:00','Z')
        self.metadata.write_text(json.dumps(metadata))
        self.assertIn('qualityGate',self.evaluate())

    def test_empty_l0_context_has_explicit_zero_coverage_and_no_fake_metrics(self):
        context = json.loads(self.context.read_text())
        for row in context['rows']:
            row['sources'] = []
        self.context.write_text(json.dumps(context))
        stage = self.evaluate()['partitions']['test']['stages']
        self.assertEqual(stage['l0Rows'],0)
        self.assertEqual(stage['l1Rows'],0)
        self.assertIsNone(stage['l1Survivors']['heavyAccepted']['precisionAtK'])
        self.assertEqual(stage['positiveCounts']['invite_accepted']['lostAtL0'],1)

    def test_cli_quality_gate_failure_publishes_evidence_and_exits_two(self):
        args = ['evaluate']
        for path in self.training.paths:
            args += ['--input',str(path)]
        for name,path in [('model',self.model),('metadata',self.metadata),('l1-model',self.training.l1),
                          ('l1-metadata',self.training.l1_metadata),('policy',self.policy),('output',self.root/'gate.json')]:
            args += ['--'+name,str(path)]
        args.append('--require-quality-gate')
        with patch('sys.argv',args), redirect_stdout(io.StringIO()), self.assertRaises(SystemExit) as error:
            main()
        self.assertEqual(error.exception.code,2)
        self.assertEqual(json.loads((self.root/'gate.json').read_text())['qualityGate']['status'],'not_met')

    def test_corrupted_models_metadata_and_policies_fail_before_publication(self):
        original = self.model.read_bytes()
        self.model.write_bytes(original+b'corrupt')
        with self.assertRaisesRegex(DatasetValidationError,'checksum'):
            self.evaluate()
        self.model.write_bytes(original)
        metadata = json.loads(self.metadata.read_text())
        metadata['outputTensors'][0]['target'] = 'wrong'
        self.metadata.write_text(json.dumps(metadata))
        with self.assertRaisesRegex(DatasetValidationError,'semantics'):
            self.evaluate()
        self.assertFalse((self.root/'report.json').exists())
        policy = json.loads(self.policy.read_text())
        policy['maxEce'] = float('nan')
        self.policy.write_text(json.dumps(policy))
        with self.assertRaisesRegex(DatasetValidationError,'maxEce'):
            self.evaluate()

    def test_trace_hashes_duplicates_attributes_and_budgets_are_checked(self):
        original = json.loads(self.context.read_text())
        variants = []
        bad = copy.deepcopy(original); bad['datasetSha256'][0] = '0'*64; variants.append(bad)
        bad = copy.deepcopy(original); bad['rows'][1] = bad['rows'][0]; variants.append(bad)
        bad = copy.deepcopy(original); bad['rows'][1]['degree'] = 2; variants.append(bad)
        bad = copy.deepcopy(original); bad['rows'][0]['sources'] = ['FAKE']; variants.append(bad)
        bad = copy.deepcopy(original); bad['rows'][0]['sources'] = ['HEURISTIC','HEURISTIC']; variants.append(bad)
        for bad in variants:
            self.context.write_text(json.dumps(bad))
            with self.assertRaises(DatasetValidationError):
                load_context(self.context,self.data,load_policy(self.policy))
        self.assertFalse((self.root/'report.json').exists())


if __name__ == '__main__':
    unittest.main()
