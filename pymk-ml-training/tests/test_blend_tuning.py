import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import numpy as np
import test_heavy_ranker_training
from pymk_training.dataset import DatasetValidationError, load_multitarget_datasets
from pymk_training.blend_context_fixture import create
from pymk_training.tune_blend import (bayesian_search, metrics, select, employer_group, tune, load_employers, load_policy, normalized_weights)


class BlendPolicyTest(unittest.TestCase):
    def test_shared_java_policy_fixture(self):
        path=Path(__file__).resolve().parents[2]/'pymk-reranker/src/test/resources/blend-parity.json'
        for case in json.loads(path.read_text(encoding='utf-8')):
            sent,accepted=normalized_weights(case['sentWeight'],case['acceptedWeight'])
            rows=case['rows']
            chosen,exposure=select([r['id'] for r in rows],[min(1,sent*r['sent']+accepted*r['accepted']) for r in rows],
                                   [r['employer'] for r in rows],case['limit'],case['capFraction'])
            self.assertEqual([rows[i]['id'] for i in chosen],case['expectedIds'],case['name'])
            self.assertEqual(exposure['knownGroupCap'],case['expectedCap'])

    def test_cap_limits_unknown_groups_and_decimal_boundary_match_online(self):
        for limit in (1,2,20,100):
            ids=list(range(1,201))
            chosen, report=select(ids,[.5]*200,[' AcMe ']*200,limit,.4)
            cap=max(1,int(.4*limit))
            self.assertEqual(chosen,list(range(cap)))
            self.assertEqual(report['knownGroupCounts'],{'acme':cap})
            self.assertEqual(report['maxKnownGroupFraction'],1)
            chosen, report=select(ids,[.5]*200,[None]*200,limit,.4)
            self.assertEqual(len(chosen),limit)
            self.assertEqual(report['unknownGroupCount'],limit)
            self.assertEqual(report['knownGroupCounts'],{})
        self.assertEqual(select([],[],[],100,.29)[1]['knownGroupCap'],29)
        self.assertEqual(employer_group('\u2003Acme\u2003'),'acme')
        self.assertEqual(employer_group('\u00a0Acme\u00a0'),'\u00a0acme\u00a0')

    def test_ties_and_backfill_beyond_100(self):
        ids=list(range(1,241))
        chosen,report=select(ids,[1-i/1000 for i in ids],['a']*120+['b']*60+['c']*60,100,.4)
        self.assertEqual(len(chosen),100)
        self.assertEqual(report['knownGroupCounts'],{'a':40,'b':40,'c':20})
        self.assertIn(180,chosen)
        self.assertEqual(select([2,1],[.5,.5],['a','b'],2,.4)[0],[1,0])

    def test_invalid_weights_scores_caps_and_duplicate_ids_rejected(self):
        for weights in ((0,0),(-1,1),(float('nan'),1),(1,float('inf'))):
            with self.assertRaises(DatasetValidationError): normalized_weights(*weights)
        self.assertEqual(normalized_weights(float.fromhex('0x1.fffffffffffffp+1023'),float.fromhex('0x1.fffffffffffffp+1023')),(.5,.5))
        for cap in (0,-1,1.1,float('nan'),float('inf')):
            with self.assertRaises(DatasetValidationError): select([1],[.5],['a'],1,cap)
        for scores in ([float('nan')],[1.1],[-.1]):
            with self.assertRaises(DatasetValidationError): select([1],scores,['a'],1,.4)
        with self.assertRaises(DatasetValidationError): select([1,1],[.5,.5],['a','b'],2,.4)

    def test_underfill_is_penalized_by_fixed_requested_slots(self):
        policy={'k':2,'employerCapFraction':.4}
        result=metrics(np.array([[0,1],[0,1]]),np.array([[.1,.9],[.1,.8]]),np.array([2,3]),
                       np.array([1,1]),np.array(['a','a']),['acme','acme'],.5,policy)
        self.assertEqual(result['objective'],.5)
        self.assertEqual(result['meanReturnedSize'],1)

    def test_gp_is_seeded_budgeted_and_retains_baseline_on_flat_objective(self):
        first=bayesian_search(lambda w:1-(w-.2)**2,12,42)
        self.assertEqual(first,bayesian_search(lambda w:1-(w-.2)**2,12,42))
        self.assertEqual(len(first[1]),12)
        self.assertEqual(len({row['sentWeight'] for row in first[1]}),12)
        self.assertGreaterEqual(first[0]['objective'],.96)
        self.assertEqual(bayesian_search(lambda w:.5,8,42)[0]['sentWeight'],.5)
        for budget in (0,2,65,True):
            with self.assertRaises(DatasetValidationError): bayesian_search(lambda w:.5,budget,42)
        with self.assertRaises(DatasetValidationError): bayesian_search(lambda w:float('nan'),3,42)


class BlendTuningIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.training=test_heavy_ranker_training.HeavyRankerTrainingTest()
        self.training.setUp()
        self.addCleanup(self.training.doCleanups)
        self.root=self.training.root
        self.model,self.metadata,_=self.training.train()
        self.policy=self.root/'blend-policy.json'
        self.policy.write_text(json.dumps({'schemaVersion':1,'objective':'accepted-hits-per-requested-slot',
            'seed':42,'searchBudget':6,'k':1,'l1Survivors':3,'employerCapFraction':.4}))
        self.context=self.root/'context.json'
        create(self.training.paths,Path(__file__).parent/'fixtures/profiles.csv',self.context)

    def run_tuning(self,name):
        return tune(self.training.paths,self.model,self.metadata,self.training.l1,self.training.l1_metadata,
                    self.policy,self.context,self.root/name)

    def test_real_model_search_reproducibility_and_immutable_config(self):
        first=self.run_tuning('one')
        repeated=self.run_tuning('two')
        self.assertEqual(first,repeated)
        self.assertFalse(first['automaticPromotion'])
        self.assertEqual(len(first['optimizer']['trials']),6)
        for baseline in ('equalWeights','acceptedOnly','sentOnly'):
            self.assertGreaterEqual(first['validation']['selected']['objective'],first['validation'][baseline]['objective'])
        config=(self.root/'one/application-blend.properties').read_text()
        self.assertIn('pymk.reranker.sent-weight=',config)
        self.assertNotIn('pymk.serving.ranker',config)  # Opt-in remains a separate deployment decision.
        with self.assertRaises(FileExistsError): self.run_tuning('one')

    def test_test_predictions_and_labels_cannot_select_weights(self):
        original=self.run_tuning('original')
        from pymk_training.tune_blend import frozen_models as real_models
        def changed(*args):
            data,split,meta,l1,identity,session=real_models(*args)
            labels=data.labels.copy()
            labels[split.test]=1-labels[split.test]
            features=data.features.copy()
            features[split.test]=0
            from dataclasses import replace
            return replace(data,labels=labels,features=features),split,meta,l1,identity,session
        with patch('pymk_training.tune_blend.frozen_models',side_effect=changed):
            changed_report=self.run_tuning('changed')
        self.assertEqual(original['selected'],changed_report['selected'])
        self.assertEqual(original['optimizer'],changed_report['optimizer'])
        self.assertEqual(original['validation'],changed_report['validation'])
        self.assertNotEqual(original['test'],changed_report['test'])

    def test_tampered_context_invalid_policy_and_model_rejected(self):
        data=load_multitarget_datasets(self.training.paths)
        context=json.loads(self.context.read_text())
        for change in ('hash','duplicate','missing','employer'):
            bad=copy.deepcopy(context)
            if change=='hash': bad['datasetSha256']=['x']
            elif change=='duplicate': bad['rows'].append(bad['rows'][0])
            elif change=='missing': bad['rows'].pop()
            else: del bad['rows'][0]['employer']
            self.context.write_text(json.dumps(bad))
            with self.assertRaises(DatasetValidationError): load_employers(self.context,data)
        self.context.write_text(json.dumps(context))
        policy=json.loads(self.policy.read_text())
        for field,value in [('schemaVersion',True),('employerCapFraction',0),('searchBudget',2),('objective','causal-lift'),('seed',-1)]:
            bad={**policy,field:value}
            self.policy.write_text(json.dumps(bad))
            with self.assertRaises(DatasetValidationError): load_policy(self.policy)
        self.policy.write_text(json.dumps(policy))
        self.model.write_bytes(b'invalid')
        with self.assertRaises(DatasetValidationError): self.run_tuning('bad-model')
        self.assertFalse((self.root/'bad-model').exists())

    def test_mutating_context_during_search_cannot_publish_a_release(self):
        def search(*args):
            result=bayesian_search(*args)
            self.context.write_text(self.context.read_text()+'\n')
            return result
        with patch('pymk_training.tune_blend.bayesian_search',side_effect=search):
            with self.assertRaisesRegex(DatasetValidationError,'changed during tuning'):
                self.run_tuning('mutated')
        self.assertFalse((self.root/'mutated').exists())

    def test_uninformative_validation_cannot_select_weights(self):
        from pymk_training.tune_blend import frozen_models as real_models
        def zero_validation(*args):
            data,split,*rest=real_models(*args)
            data.labels[split.validation]=0
            return data,split,*rest
        with patch('pymk_training.tune_blend.frozen_models',side_effect=zero_validation):
            with self.assertRaisesRegex(DatasetValidationError,'both accepted label classes'):
                self.run_tuning('uninformative')
