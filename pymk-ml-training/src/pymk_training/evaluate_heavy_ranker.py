"""Frozen chronological evaluation; no fitting, tuning, or automatic model promotion."""
from __future__ import annotations

import argparse
import hashlib
import json
import platform
from pathlib import Path

import numpy as np
import onnxruntime as ort
import sklearn
from sklearn.metrics import average_precision_score, roc_auc_score

from pymk_training.dataset import DatasetValidationError, FEATURE_NAMES, load_multitarget_datasets, temporal_split
from pymk_training.train_heavy_ranker import TARGETS, OUTPUTS, _frozen_l1, _probabilities
from pymk_training.train_light_ranker import _atomic_write, _split_metadata
from pymk_training.profile_embeddings import timestamp


def require(condition, message):
    if not condition:
        raise DatasetValidationError(message)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def classification(labels, scores, bins=10, probability=True):
    y, p = np.asarray(labels), np.asarray(scores, dtype=float)
    require(y.ndim == p.ndim == 1 and len(y) == len(p), 'metric vector shape mismatch')
    require(np.isin(y, [0, 1]).all() and np.isfinite(p).all(), 'invalid metric values')
    require(isinstance(bins, int) and bins > 0, 'invalid ECE bin count')
    if probability:
        require(((p >= 0) & (p <= 1)).all(), 'probabilities outside [0,1]')
    both = len(np.unique(y)) == 2
    result = {'rows': len(y), 'positives': int(y.sum()), 'prevalence': float(y.mean()) if len(y) else None,
              'rocAuc': float(roc_auc_score(y, p)) if both else None,
              'averagePrecision': float(average_precision_score(y, p)) if both else None,
              'aucUnavailableReason': None if both else 'empty or single-class cohort'}
    if probability:
        reliability, ece = [], 0.0
        assignments = np.minimum((p * bins).astype(int), bins - 1)
        for index in range(bins):
            mask = assignments == index
            count = int(mask.sum())
            predicted = float(p[mask].mean()) if count else None
            observed = float(y[mask].mean()) if count else None
            if count:
                ece += count * abs(predicted - observed) / len(y)
            reliability.append({'lower': index / bins, 'upper': (index + 1) / bins,
                                'rows': count, 'meanPrediction': predicted, 'observedRate': observed})
        result.update(ece=ece if len(y) else None, brier=float(np.mean((p-y)**2)) if len(y) else None,
                      reliability=reliability)
    return result


def ranking(labels, scores, members, snapshots, candidates, k):
    """Macro over requester/snapshot queries. Precision denominator=min(k,pool size)."""
    require(type(k) is int and k > 0, 'ranking k must be positive')
    require(all(len(values) == len(labels) for values in (scores,members,snapshots,candidates)), 'ranking vector lengths differ')
    groups = {}
    for i, key in enumerate(zip(members.tolist(), snapshots.tolist())):
        groups.setdefault(key, []).append(i)
    precision, recall, hits, filled = [], [], 0, 0
    for indices in groups.values():
        order = sorted(indices, key=lambda i: (-float(scores[i]), int(candidates[i])))[:k]
        positives = int(labels[indices].sum())
        found = int(labels[order].sum())
        precision.append(found / len(order))
        if positives:
            recall.append(found / positives)
        hits += found
        filled += len(order) == k
    return {'queries': len(groups), 'precisionAtK': float(np.mean(precision)) if precision else None,
            'recallAtKWithinPool': float(np.mean(recall)) if recall else None,
            'positiveQueries': len(recall), 'positivesAtK': hits,
            'fullKCoverage': filled / len(groups) if groups else None,
            'precisionDenominator': 'min(k, available candidates) per nonempty requester/snapshot',
            'recallDenominator': 'positives in this scored pool; zero-positive queries excluded'}


def bootstrap(labels, scores, baseline, members, snapshots, candidates, k, samples, seed, probability=False, bins=10):
    """Paired cluster bootstrap: all snapshots of a requester travel together."""
    unique = np.unique(members)
    if len(unique) < 2:
        return {'method': 'paired-requester-percentile-95', 'intervals': None,
                'reason': 'fewer than two independent requesters', 'samples': samples}
    rng = np.random.default_rng(seed)
    values = {'precisionAtK': [], 'recallAtKWithinPool': [], 'precisionDeltaVsMutual': [], 'rocAuc': []}
    if probability:
        values['ece'] = []
    for _ in range(samples):
        draws = rng.choice(unique, len(unique), replace=True)
        chunks = [np.flatnonzero(members == member) for member in draws]
        indices = np.concatenate(chunks)
        # Distinct synthetic query identities preserve duplicate sampled clusters.
        identities = np.concatenate([np.full(len(chunk), i) for i, chunk in enumerate(chunks)])
        rank = ranking(labels[indices], scores[indices], identities, snapshots[indices], candidates[indices], k)
        base = ranking(labels[indices], baseline[indices], identities, snapshots[indices], candidates[indices], k)
        values['precisionAtK'].append(rank['precisionAtK'])
        if rank['recallAtKWithinPool'] is not None:
            values['recallAtKWithinPool'].append(rank['recallAtKWithinPool'])
        values['precisionDeltaVsMutual'].append(rank['precisionAtK'] - base['precisionAtK'])
        if len(np.unique(labels[indices])) == 2:
            values['rocAuc'].append(float(roc_auc_score(labels[indices], scores[indices])))
        if probability:
            # Match classification's float64 bin boundaries for float32 ONNX scores.
            probabilities = np.asarray(scores[indices],dtype=float)
            assignments = np.minimum((probabilities * bins).astype(int),bins-1)
            residuals = np.bincount(assignments,weights=probabilities-labels[indices],minlength=bins)
            values['ece'].append(float(np.abs(residuals).sum()/len(indices)))
    return {'method': 'paired-requester-percentile-95', 'samples': samples, 'seed': seed,
            'intervals': {name: {'low': float(np.quantile(items, .025)), 'high': float(np.quantile(items, .975)),
                                 'validReplicates': len(items)} if items else None for name, items in values.items()}}


def load_policy(path, content=None):
    policy = json.loads(content if content is not None else Path(path).read_bytes())
    require(policy.get('schemaVersion') == 1, 'invalid evaluation policy schema')
    for key, lo, hi in [('k',1,1000), ('l1Survivors',1,10000), ('eceBins',2,100),
                        ('bootstrapSamples',100,10000), ('seed',0,2**32-1), ('minRequesters',2,1000000), ('minPositives',1,1000000)]:
        require(type(policy.get(key)) is int and lo <= policy[key] <= hi, f'invalid policy {key}')
    for key in ['minAuc', 'maxEce', 'maxPrecisionRegression']:
        require(type(policy.get(key)) in (int,float) and np.isfinite(policy[key]) and 0 <= policy[key] <= 1, f'invalid policy {key}')
    budgets = policy.get('candidateBudgets', {})
    require(set(budgets) == {'GRAPH_WALK','HEURISTIC','EMBEDDING'}, 'candidate budgets required')
    require(all(type(v) is int and 1 <= v <= 100000 for v in budgets.values()), 'invalid candidate budgets')
    require(policy['k'] <= policy['l1Survivors'], 'k exceeds L1 survivor budget')
    return policy


def load_context(path, dataset, policy):
    if path is None:
        return None
    context = json.loads(Path(path).read_text(encoding='utf-8'))
    require(context.get('schemaVersion') == 1 and context.get('candidateBudgets') == policy['candidateBudgets'], 'context schema/budgets differ')
    require(context.get('datasetSha256') == sorted(item['sha256'] for item in dataset.input_files), 'context dataset hashes differ')
    rows = context.get('rows', [])
    require(len(rows) == dataset.row_count, 'context must cover every exported pair exactly once')
    lookup, query_context, counts = {}, {}, {}
    for row in rows:
        require(type(row.get('memberId')) is int and row['memberId'] > 0
                and type(row.get('candidateId')) is int and row['candidateId'] > 0, 'invalid context member IDs')
        key = (row['featureAsOf'], row['memberId'], row['candidateId'])
        require(key not in lookup, 'duplicate context pair')
        require(type(row.get('blankProfile')) is bool and type(row.get('newMember')) is bool
                and type(row.get('degree')) is int and row['degree'] >= 0, 'invalid historical requester context')
        sources = row.get('sources')
        require(isinstance(sources,list) and len(sources) == len(set(sources)) and set(sources) <= set(policy['candidateBudgets']), 'invalid candidate sources')
        query = key[:2]
        attributes = (row['blankProfile'],row['newMember'],row['degree'])
        require(query_context.setdefault(query,attributes) == attributes, 'inconsistent requester context')
        for source in sources:
            count_key = (*query,source)
            counts[count_key] = counts.get(count_key,0) + 1
            require(counts[count_key] <= policy['candidateBudgets'][source], 'context exceeds candidate source budget')
        lookup[key] = row
    keys = list(zip(dataset.feature_as_of.tolist(), dataset.member_ids.tolist(), dataset.candidate_ids.tolist()))
    require(set(keys) == set(lookup), 'context pairs/snapshots differ from dataset')
    return [lookup[key] for key in keys]


def frozen_models(inputs, model_path, metadata_path, l1_model, l1_metadata):
    """Shared immutable artifact validation; does not score the heavy model or fit anything."""
    dataset = load_multitarget_datasets(inputs)
    split = temporal_split(dataset)
    metadata = json.loads(Path(metadata_path).read_text(encoding='utf-8'))
    artifact = Path(model_path).read_bytes()
    require(metadata.get('schemaVersion') == 1 and metadata.get('modelType') == 'standard-scaler+two-head-mlp'
            and metadata.get('features') == list(FEATURE_NAMES)
            and metadata.get('onnx',{}).get('sha256') == hashlib.sha256(artifact).hexdigest(), 'heavy model contract/checksum differs')
    require(metadata.get('split') == _split_metadata(split,dataset), 'heavy chronological split differs')
    require(metadata.get('inputTensor') == {'name':'features','dtype':'float32','shape':[None,6]}, 'heavy input descriptor differs')
    # Paths may move, immutable source bytes and provenance may not.
    identities = lambda items: sorted((i['sha256'], json.dumps(i.get('provenance'),sort_keys=True)) for i in items)
    require(identities(metadata.get('inputs',[])) == identities(dataset.input_files), 'heavy training datasets/provenance differ')
    compatibility = metadata.get('embeddingCompatibility',{})
    require(compatibility.get('featureSchema') == 'pair-v1', 'heavy feature schema differs')
    for item in dataset.input_files:
        require(all(compatibility.get(key) == item['provenance'].get(key) for key in ('embeddingVersion','transformSha256'))
                and timestamp(compatibility.get('fitCutoff')) == timestamp(item['provenance'].get('fitCutoff')), 'heavy embedding contract differs')
    descriptors = metadata.get('outputTensors',[])
    require(len(descriptors) == 2 and all(d.get('name') == name and d.get('target') == target
            and d.get('dtype') == 'float32' and d.get('shape') == [None,1]
            and d.get('semantics') == 'directional-unconditional-(featureAsOf,labelWindowEnd]'
            for d,name,target in zip(descriptors,OUTPUTS,TARGETS)), 'heavy output semantics differ')
    l1, l1_identity = _frozen_l1(l1_model,l1_metadata,dataset,split,compatibility['transformSha256'])
    require(l1_identity == metadata.get('comparison',{}).get('l1'), 'comparison L1 identity differs from trained model')
    session = ort.InferenceSession(artifact,providers=['CPUExecutionProvider'])
    native_inputs, native_outputs = session.get_inputs(), session.get_outputs()
    require(len(native_inputs) == 1 and native_inputs[0].name == 'features' and native_inputs[0].type == 'tensor(float)'
            and len(native_inputs[0].shape) == 2 and not isinstance(native_inputs[0].shape[0],int)
            and native_inputs[0].shape[1] == 6, 'native heavy input contract differs')
    require({v.name for v in native_outputs} == set(OUTPUTS) and all(v.type == 'tensor(float)' and len(v.shape)==2
            and not isinstance(v.shape[0],int) and v.shape[1]==1 for v in native_outputs), 'native heavy output contract differs')
    return dataset, split, metadata, l1, l1_identity, session


def evaluate(inputs, model_path, metadata_path, l1_model, l1_metadata, policy_path, output, context_path=None):
    output = Path(output)
    if output.exists():
        raise FileExistsError('evaluation reports are immutable; choose a new output path')
    # Resolve and hash the predeclared policy before loading evaluation labels.
    policy_bytes = Path(policy_path).read_bytes()
    policy = load_policy(policy_path,policy_bytes)
    policy_hash = hashlib.sha256(policy_bytes).hexdigest()
    dataset, split, metadata, l1, l1_identity, session = frozen_models(
        inputs, model_path, metadata_path, l1_model, l1_metadata)
    predictions = _probabilities(session,dataset.features)
    context = load_context(context_path,dataset,policy)
    report = {'schemaVersion':1, 'policy':policy, 'policySha256':policy_hash,
              'evaluatorSha256':sha(__file__), 'modelVersion':metadata.get('modelVersion'),
              'runtime':{'python':platform.python_version(),'numpy':np.__version__,
                         'onnxRuntime':ort.__version__,'scikitLearn':sklearn.__version__},
              'modelSha256':sha(model_path), 'metadataSha256':sha(metadata_path), 'l1':l1_identity,
              'inputs':list(dataset.input_files), 'split':metadata['split'],
              'contextSha256':sha(context_path) if context_path else None,
              'calibration': 'raw scores only; no calibration fitted; validation was used for checkpoint selection',
              'poolScope':'immutable exported pair pool; full eligible population recall is unavailable',
              'automaticPromotion':False, 'partitions':{}}
    for partition in ('validation','test'):
        indices = getattr(split,partition)
        variants = {'heavySent':(0,predictions[:,0],True), 'heavyAccepted':(1,predictions[:,1],True),
                    'l1Accepted':(1,l1,True), 'mutualSent':(0,dataset.features[:,0],False),
                    'mutualAccepted':(1,dataset.features[:,0],False)}
        def metrics(selected, target, scores, probability, uncertainty=False):
            result = classification(dataset.labels[selected,target],scores[selected],policy['eceBins'],probability)
            result.update(ranking(dataset.labels[selected,target],scores[selected],dataset.member_ids[selected],
                                  dataset.feature_as_of[selected],dataset.candidate_ids[selected],policy['k']))
            if uncertainty:
                result['bootstrap'] = bootstrap(dataset.labels[selected,target],scores[selected],dataset.features[selected,0],
                    dataset.member_ids[selected],dataset.feature_as_of[selected],dataset.candidate_ids[selected],policy['k'],policy['bootstrapSamples'],policy['seed'],probability,policy['eceBins'])
            return result
        entry = {'exportedPool':{name:metrics(indices,*spec,True) for name,spec in variants.items()},
                 'historicalCohorts':None, 'stages':None,
                 'contextUnavailableReason':None if context else 'provide hash-bound historical replay context; never infer historical cohorts from current profiles'}
        if context:
            masks = {'blankProfile':lambda r:r['blankProfile'], 'nonblankProfile':lambda r:not r['blankProfile'],
                     'newMember':lambda r:r['newMember'], 'establishedMember':lambda r:not r['newMember'],
                     'degree0':lambda r:r['degree']==0, 'degree1To9':lambda r:1<=r['degree']<=9,
                     'degree10Plus':lambda r:r['degree']>=10,
                     'graphOnly':lambda r:r['sources']==['GRAPH_WALK']}
            masks.update({source:lambda r,s=source:s in r['sources'] for source in policy['candidateBudgets']})
            entry['historicalCohorts'] = {name:{variant:metrics(np.array([i for i in indices if predicate(context[i])],dtype=int),*spec)
                    for variant,spec in variants.items()} for name,predicate in masks.items()}
            l0 = np.array([i for i in indices if context[i]['sources']],dtype=int)
            queries = {}
            for i in l0:
                queries.setdefault((dataset.member_ids[i],dataset.feature_as_of[i]),[]).append(i)
            survivors = np.array([i for group in queries.values() for i in sorted(group,key=lambda j:(-float(l1[j]),int(dataset.candidate_ids[j])))[:policy['l1Survivors']]],dtype=int)
            stages = {}
            for target_index,target in enumerate(TARGETS):
                exported, after_l0, after_l1 = [int(dataset.labels[selection,target_index].sum()) for selection in (indices,l0,survivors)]
                stages[target] = {'exportedPoolPositives':exported,'afterL0':after_l0,'lostAtL0':exported-after_l0,
                                  'afterL1':after_l1,'lostAtL1':after_l0-after_l1,'lostAtL2Scoring':0,
                                  'fullEligiblePoolRecall':None}
            entry['stages'] = {'positiveCounts':stages,'l0Rows':len(l0),'l1Rows':len(survivors),
                               'l0':{name:metrics(l0,*spec) for name,spec in variants.items()},
                               'l1Survivors':{name:metrics(survivors,*spec) for name,spec in variants.items()},
                               'note':'L1 replay uses fixed raw-L1 top-N with candidate-ID ties; L2 scores all survivors, no public cutover'}
        report['partitions'][partition] = entry
    test = report['partitions']['test']
    reasons = []
    if context is None:
        reasons.append('historical cohort/L0 replay context unavailable')
    elif test['stages']['l1Rows'] == 0:
        reasons.append('no L1 replay survivors')
    if len(np.unique(dataset.member_ids[split.test])) < policy['minRequesters']:
        reasons.append('insufficient independent test requesters')
    for name in ('heavySent','heavyAccepted'):
        metric = test['exportedPool'][name]
        baseline = test['exportedPool']['mutualSent' if name=='heavySent' else 'mutualAccepted']
        if metric['positives'] < policy['minPositives']:
            reasons.append(name+': insufficient positives')
        if metric['rocAuc'] is None or metric['rocAuc'] < policy['minAuc']:
            reasons.append(name+': AUC unavailable/below threshold')
        if metric['ece'] is None or metric['ece'] > policy['maxEce']:
            reasons.append(name+': ECE unavailable/above threshold')
        if metric['precisionAtK'] < baseline['precisionAtK'] - policy['maxPrecisionRegression']:
            reasons.append(name+': precision regressed against mutual baseline')
        intervals = metric['bootstrap']['intervals']
        delta = intervals.get('precisionDeltaVsMutual') if intervals else None
        if delta is None or delta['low'] < -policy['maxPrecisionRegression']:
            reasons.append(name+': precision noninferiority interval unavailable/below threshold')
    report['qualityGate'] = {'status':'not_met' if reasons else 'met','reasons':reasons,
                             'scope':'exported-pool offline evidence only; not production approval'}
    output.parent.mkdir(parents=True,exist_ok=True)
    _atomic_write(output,(json.dumps(report,indent=2,sort_keys=True,allow_nan=False)+'\n').encode())
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',action='append',required=True,type=Path)
    for name in ('model','metadata','l1-model','l1-metadata','policy','output'):
        parser.add_argument('--'+name,required=True,type=Path)
    parser.add_argument('--context',type=Path)
    parser.add_argument('--require-quality-gate',action='store_true')
    args = parser.parse_args()
    report = evaluate(args.input,args.model,args.metadata,args.l1_model,args.l1_metadata,args.policy,args.output,args.context)
    print(json.dumps({'report':str(args.output),'qualityGate':report['qualityGate']},indent=2))
    if args.require_quality_gate and report['qualityGate']['status'] != 'met':
        raise SystemExit(2)


if __name__ == '__main__':
    main()
