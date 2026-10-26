"""Validation-only Bayesian blend search over an immutable exported-pool ranking proxy."""
from __future__ import annotations

import argparse
from collections import Counter
from decimal import Decimal
import hashlib
import json
import math
from pathlib import Path
import platform

import numpy as np
import onnxruntime as ort

from pymk_training.evaluate_heavy_ranker import frozen_models, require, sha
from pymk_training.train_heavy_ranker import _probabilities
from pymk_training.train_light_ranker import _atomic_write

# Character.isWhitespace / String.strip semantics used by the Java online policy.
JAVA_WHITESPACE = '\t\n\v\f\r\x1c\x1d\x1e\x1f \u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2008\u2009\u200a\u2028\u2029\u205f\u3000'


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()


def employer_group(value):
    require(value is None or isinstance(value, str), 'employer must be text or null')
    return '' if value is None else value.strip(JAVA_WHITESPACE).lower()


def normalized_weights(sent, accepted):
    require(all(type(w) in (int,float) and math.isfinite(w) and w >= 0 for w in (sent,accepted))
            and max(sent,accepted) > 0, 'invalid blend weights')
    maximum=max(sent,accepted)
    left,right=sent/maximum,accepted/maximum
    weight=left/(left+right)
    return weight,1-weight


def load_policy(path):
    policy = json.loads(Path(path).read_bytes())
    require(isinstance(policy,dict) and set(policy) == {'schemaVersion','objective','seed','searchBudget','k','l1Survivors','employerCapFraction'}, 'unknown or missing blend policy fields')
    require(type(policy.get('schemaVersion')) is int and policy['schemaVersion'] == 1 and policy.get('objective') == 'accepted-hits-per-requested-slot', 'unknown blend policy/objective')
    for key, lo, hi in [('seed',0,2**32-1),('searchBudget',3,64),('k',1,100),('l1Survivors',1,10000)]:
        require(type(policy.get(key)) is int and lo <= policy[key] <= hi, 'invalid blend policy '+key)
    cap = policy.get('employerCapFraction')
    require(type(cap) in (float,int) and math.isfinite(cap) and 0 < cap <= 1, 'invalid employer cap')
    require(policy['k'] <= policy['l1Survivors'], 'k exceeds survivor budget')
    return policy


def load_employers(path, dataset):
    content = json.loads(Path(path).read_bytes())
    require(type(content.get('schemaVersion')) is int and content['schemaVersion'] == 1 and content.get('scope') == 'exported-pool-proxy', 'invalid employer context scope/schema')
    require(content.get('datasetSha256') == sorted(item['sha256'] for item in dataset.input_files), 'employer context dataset hashes differ')
    lookup, candidate_groups = {}, {}
    for row in content.get('rows', []):
        require(type(row.get('memberId')) is int and type(row.get('candidateId')) is int, 'invalid employer context IDs')
        key = (row.get('featureAsOf'), row['memberId'], row['candidateId'])
        require(key not in lookup and 'employer' in row, 'duplicate pair or missing employer field')
        group=employer_group(row['employer'])
        require(candidate_groups.setdefault((key[0],key[2]),group) == group, 'inconsistent employer for candidate/snapshot')
        lookup[key] = group
    keys = list(zip(dataset.feature_as_of.tolist(), dataset.member_ids.tolist(), dataset.candidate_ids.tolist()))
    require(len(keys) == len(lookup) and set(keys) == set(lookup), 'employer context must cover every pair/snapshot exactly once')
    return [lookup[key] for key in keys]


def select(ids, scores, employers, limit, cap_fraction):
    require(type(limit) is int and 1 <= limit <= 100, 'invalid limit')
    require(type(cap_fraction) in (int,float) and math.isfinite(cap_fraction) and 0 < cap_fraction <= 1, 'invalid cap')
    require(len(ids) == len(scores) == len(employers) and len(set(ids)) == len(ids), 'invalid pool lengths/duplicates')
    require(all(int(i) > 0 for i in ids) and np.isfinite(scores).all()
            and all(0 <= p <= 1 for p in scores), 'invalid pool IDs/scores')
    cap = max(1, int(Decimal(str(cap_fraction)) * limit))
    counts, chosen, unknown = Counter(), [], 0
    for i in sorted(range(len(ids)), key=lambda i: (-float(scores[i]), int(ids[i]))):
        group = employer_group(employers[i])
        if group and counts[group] >= cap:
            continue
        chosen.append(i)
        if group:
            counts[group] += 1
        else:
            unknown += 1
        if len(chosen) == limit:
            break
    require(all(count <= cap for count in counts.values()), 'diversity-breaking selection')
    return chosen, {'requestedLimit':limit, 'returnedSize':len(chosen), 'knownGroupCap':cap,
                    'knownGroupCounts':dict(sorted(counts.items())), 'unknownGroupCount':unknown,
                    'maxKnownGroupFraction':max(counts.values(),default=0)/len(chosen) if chosen else 0}


def metrics(labels, predictions, ids, members, snapshots, employers, weight, policy):
    require(math.isfinite(weight) and 0 <= weight <= 1, 'invalid blend weight')
    require(predictions.shape == labels.shape == (len(ids),2), 'invalid two-head shape')
    require(np.isin(labels,[0,1]).all() and np.isfinite(predictions).all()
            and ((predictions >= 0) & (predictions <= 1)).all(), 'invalid labels/probabilities')
    sent,accepted=normalized_weights(weight,1-weight)
    scores = np.minimum(1, sent*predictions[:,0].astype(float)+accepted*predictions[:,1].astype(float))
    queries = {}
    for i,key in enumerate(zip(members.tolist(), snapshots.tolist())):
        queries.setdefault(key,[]).append(i)
    reports = []
    for (member,snapshot), indices in sorted(queries.items()):
        chosen, exposure = select([int(ids[i]) for i in indices], scores[indices],
                                  [employers[i] for i in indices], policy['k'], policy['employerCapFraction'])
        selected = [indices[i] for i in chosen]
        hits = labels[selected].sum(axis=0) if selected else np.zeros(2,dtype=int)
        reports.append({'memberId':int(member), 'featureAsOf':str(snapshot),
                        'candidateIds':[int(ids[i]) for i in selected], 'exposure':exposure,
                        'sentHits':int(hits[0]), 'acceptedHits':int(hits[1])})
    require(bool(reports), 'empty tuning/evaluation cohort')
    return {'objective':float(np.mean([r['acceptedHits']/policy['k'] for r in reports])),
            'sentHitsPerRequestedSlot':float(np.mean([r['sentHits']/policy['k'] for r in reports])),
            'meanReturnedSize':float(np.mean([r['exposure']['returnedSize'] for r in reports])),
            'queries':reports}


def bayesian_search(objective, budget, seed):
    """Fixed RBF Gaussian-process posterior + expected improvement, finite 1001-point domain.

    Initial observations are 0, .5 and 1. No test callback/data enters this function.
    Hyperparameters are fixed, not estimated from a held-out cohort.
    """
    require(type(budget) is int and 3 <= budget <= 64, 'invalid search budget')
    require(type(seed) is int and 0 <= seed <= 2**32-1, 'invalid seed')
    grid = np.random.default_rng(seed).permutation(np.linspace(0,1,1001))
    trials = []
    for step in range(budget):
        if step < 3:
            weight = (0., .5, 1.)[step]
        else:
            x = np.array([r['sentWeight'] for r in trials])
            y = np.array([r['objective'] for r in trials])
            available = grid[~np.isin(grid,x)]
            kernel = np.exp(-.5*((x[:,None]-x[None,:])/.2)**2) + np.eye(len(x))*1e-6
            cross = np.exp(-.5*((available[:,None]-x[None,:])/.2)**2)
            mean = y.mean() + cross @ np.linalg.solve(kernel,y-y.mean())
            variance = np.maximum(1-np.sum(cross*np.linalg.solve(kernel,cross.T).T,axis=1),1e-12)
            std = np.sqrt(variance)
            improvement = mean-y.max()-.001
            z = improvement/std
            cdf = np.array([.5*(1+math.erf(value/math.sqrt(2))) for value in z])
            ei = improvement*cdf + std*np.exp(-.5*z*z)/math.sqrt(2*math.pi)
            weight = float(available[int(np.argmax(ei))])
        score = float(objective(weight))
        require(math.isfinite(score) and 0 <= score <= 1, 'invalid objective')
        trials.append({'sentWeight':weight, 'acceptedWeight':1-weight, 'objective':score})
    # Flat objectives retain equal weights; deterministic tie preference avoids arbitrary changes.
    winner = min(trials,key=lambda r:(-r['objective'],abs(r['sentWeight']-.5),r['sentWeight']))
    return winner, trials


def survivor_indices(dataset, indices, l1, count):
    queries = {}
    for i in indices:
        queries.setdefault((dataset.member_ids[i],dataset.feature_as_of[i]),[]).append(i)
    return np.array([i for group in queries.values() for i in sorted(group,
                    key=lambda i:(-float(l1[i]),int(dataset.candidate_ids[i])))[:count]],dtype=int)


def tune(inputs, model, metadata_path, l1_model, l1_metadata, policy_path, context_path, output_dir):
    output_dir = Path(output_dir)
    if output_dir.exists():
        raise FileExistsError('blend releases are immutable; choose a new output directory')
    inputs=list(inputs)
    bound_paths=[Path(p) for p in [*inputs,model,metadata_path,l1_model,l1_metadata,policy_path,context_path]]
    bound_paths += [Path(str(p)+'.provenance.json') for p in inputs]
    bindings={str(path):sha(path) for path in bound_paths}
    policy = load_policy(policy_path)
    dataset, split, metadata, l1, l1_identity, session = frozen_models(inputs,model,metadata_path,l1_model,l1_metadata)
    employers = load_employers(context_path,dataset)
    def cohort(indices):
        return hashlib.sha256(canonical([[str(dataset.feature_as_of[i]),int(dataset.member_ids[i]),int(dataset.candidate_ids[i])]
                                         for i in indices])).hexdigest()
    def prepare(indices):
        selected = survivor_indices(dataset,indices,l1,policy['l1Survivors'])
        predictions = _probabilities(session,dataset.features[selected])
        def evaluate_weight(weight):
            return metrics(dataset.labels[selected],predictions,dataset.candidate_ids[selected],dataset.member_ids[selected],
                           dataset.feature_as_of[selected],[employers[i] for i in selected],weight,policy)
        return selected, evaluate_weight
    validation_indices, validation = prepare(split.validation)
    require(len(np.unique(dataset.labels[validation_indices,1])) == 2, 'validation survivors need both accepted label classes')
    winner, trials = bayesian_search(lambda weight:validation(weight)['objective'],policy['searchBudget'],policy['seed'])
    selected_weight = winner['sentWeight']  # Frozen before accessing test predictions/metrics.
    validation_reports = {name:validation(weight) for name,weight in
                          [('selected',selected_weight),('equalWeights',.5),('acceptedOnly',0.),('sentOnly',1.)]}
    test_indices, test = prepare(split.test)
    test_reports = {name:test(weight) for name,weight in
                    [('selected',selected_weight),('equalWeights',.5),('acceptedOnly',0.),('sentOnly',1.)]}
    config = (f'pymk.reranker.sent-weight={selected_weight!r}\n'
              f'pymk.reranker.accepted-weight={1-selected_weight!r}\n'
              f'pymk.reranker.employer-cap-fraction={policy["employerCapFraction"]!r}\n'
              f'pymk.light-ranker.max-candidates={policy["l1Survivors"]}\n'
              f'pymk.heavy-ranker.max-candidates={policy["l1Survivors"]}\n').encode()
    report = {'schemaVersion':1, 'automaticPromotion':False, 'policy':policy, 'policySha256':sha(policy_path),
              'optimizer':{'name':'fixed-RBF-GP-expected-improvement', 'gridSize':1001,'lengthScale':.2,
                           'noiseVariance':1e-6,'improvementOffset':.001,'trials':trials},
              'selected':winner, 'configurationSha256':hashlib.sha256(config).hexdigest(),
              'effectiveNormalizedWeights':dict(zip(('sent','accepted'),normalized_weights(selected_weight,1-selected_weight))),
              'modelSha256':sha(model),'metadataSha256':sha(metadata_path),'modelVersion':metadata['modelVersion'],
              'l1':l1_identity, 'inputs':list(dataset.input_files),'split':metadata['split'],
              'contextSha256':sha(context_path), 'codeSha256':sha(__file__),
              'dependencyCodeSha256':{name:sha(Path(__file__).with_name(name+'.py')) for name in
                                     ('evaluate_heavy_ranker','dataset','train_heavy_ranker','train_light_ranker')},
              'runtime':{'python':platform.python_version(),'numpy':np.__version__,'onnxRuntime':ort.__version__},
              'cohorts':{'validation':cohort(validation_indices),'test':cohort(test_indices)},
              'validation':validation_reports,'test':test_reports,
              'scope':'synthetic exported-pool ranking proxy, not causal engagement lift or full online replay',
              'limitations':['L1 proxy uses candidate-ID ties, not online L0 fusion ties; historical L0 eligibility is not replayed',
                             'employer context must be an immutable as-of snapshot, never reconstructed from current profiles',
                             'validation was already used for heavy checkpoint selection; test metrics never select blend weights',
                             'fixed requested-slot denominator penalizes underfill; no calibration, fairness or SLO claim']}
    require(all(sha(path)==digest for path,digest in bindings.items()), 'input artifact changed during tuning')
    output_dir.mkdir(parents=True,exist_ok=False)
    _atomic_write(output_dir/'application-blend.properties',config)
    # Report is the completion marker. A partial directory is inactive and must never be deployed.
    _atomic_write(output_dir/'report.json',json.dumps(report,indent=2,sort_keys=True,allow_nan=False).encode()+b'\n')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',action='append',required=True,type=Path)
    for name in ('model','metadata','l1-model','l1-metadata','policy','context','output-dir'):
        parser.add_argument('--'+name,required=True,type=Path)
    args = parser.parse_args()
    report = tune(args.input,args.model,args.metadata,args.l1_model,args.l1_metadata,args.policy,args.context,args.output_dir)
    print(json.dumps({'output':str(args.output_dir),'selected':report['selected'],'automaticPromotion':False},indent=2))


if __name__ == '__main__':
    main()
