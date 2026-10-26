"""Make employer context for the synthetic smoke only; not a historical L0 replay exporter."""
import argparse
import json
from pathlib import Path

from pymk_training.dataset import load_multitarget_datasets
from pymk_training.profile_embeddings import read_profiles, timestamp
from pymk_training.evaluate_heavy_ranker import require, sha
from pymk_training.train_light_ranker import _atomic_write


def create(inputs, profiles, output):
    output = Path(output)
    if output.exists():
        raise FileExistsError('context is immutable')
    dataset = load_multitarget_datasets(inputs)
    rows = read_profiles(Path(profiles),min(timestamp(t) for t in dataset.feature_as_of))
    companies = {int(row['member_id']):row['company'] for row in rows}
    require(all(int(i) in companies for i in dataset.candidate_ids), 'synthetic profile snapshot lacks candidates')
    context = {'schemaVersion':1,'scope':'exported-pool-proxy',
               'datasetSha256':sorted(item['sha256'] for item in dataset.input_files),
               'syntheticFixture':True,'profilesSha256':sha(profiles),
               'assumption':'synthetic profiles are held constant across all exported snapshots; not production history',
               'rows':[{'memberId':int(m),'candidateId':int(c),'featureAsOf':str(t),'employer':companies[int(c)]}
                       for m,c,t in zip(dataset.member_ids,dataset.candidate_ids,dataset.feature_as_of)]}
    output.parent.mkdir(parents=True,exist_ok=True)
    _atomic_write(output,(json.dumps(context,sort_keys=True,indent=2)+'\n').encode())


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',action='append',required=True,type=Path)
    for name in ('profiles','output'):
        parser.add_argument('--'+name,required=True,type=Path)
    args=parser.parse_args()
    create(args.input,args.profiles,args.output)


if __name__ == '__main__':
    main()
