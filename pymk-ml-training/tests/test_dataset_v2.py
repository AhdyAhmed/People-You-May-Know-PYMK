import csv
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from pymk_training.dataset import (DatasetValidationError, V2_COLUMNS, load_datasets,
                                   load_multitarget_datasets, temporal_split)


class MultiTargetDatasetTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def snapshot(self, day=3, end=10):
        path = self.root / f'{day}.csv'
        with path.open('w', newline='', encoding='utf-8') as handle:
            writer = csv.writer(handle)
            writer.writerow(V2_COLUMNS)
            for candidate, targets in enumerate(((0, 1), (1, 0), (0, 0)), 2):
                writer.writerow([2, 1, candidate, 0, True, False, True, 0.5, 1,
                                 (datetime(2026, 1, day, tzinfo=timezone.utc) - timedelta(days=30)).isoformat(), f'2026-01-{day:02}T00:00:00Z',
                                 f'2026-01-{end:02}T00:00:00Z', *targets])
        metadata = {
            'schemaVersion': 2, 'datasetId': str(day), 'releaseId': f'release-{day}',
            'featureSchema': 'pair-v1', 'embeddingVersion': 'frozen-v1',
            'transformSha256': 'a' * 64, 'releaseManifestSha256': 'b' * 64,
            'featureSourceRevision': '10', 'labelSourceRevision': '12',
            'featureAsOf': f'2026-01-{day:02}T00:00:00Z', 'labelWindowEnd': f'2026-01-{end:02}T00:00:00Z',
            'fitCutoff': '2026-01-01T00:00:00Z', 'exportedAt': '2026-02-01T00:00:00Z',
            'targets': ['invite_sent', 'invite_accepted'],
            'labelSemantics': 'directional-unconditional-(featureAsOf,labelWindowEnd]',
            'rowCount': 3, 'csvSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'cohortSha256': hashlib.sha256(b'1,2\n1,3\n1,4\n').hexdigest(),
        }
        self.sidecar(path).write_text(json.dumps(metadata), encoding='utf-8')
        return path

    def sidecar(self, path):
        return Path(str(path) + '.provenance.json')

    def change_metadata(self, path, **changes):
        metadata = json.loads(self.sidecar(path).read_text(encoding='utf-8'))
        metadata.update(changes)
        self.sidecar(path).write_text(json.dumps(metadata), encoding='utf-8')

    def change_rows(self, path, change):
        with path.open(newline='', encoding='utf-8') as handle:
            rows = list(csv.reader(handle))
        change(rows)
        with path.open('w', newline='', encoding='utf-8') as handle:
            csv.writer(handle).writerows(rows)
        self.change_metadata(path, csvSha256=hashlib.sha256(path.read_bytes()).hexdigest())

    def test_unconditional_targets_and_whole_snapshot_chronological_partitions(self):
        paths = [self.snapshot(), self.snapshot(10, 17), self.snapshot(17, 24)]
        dataset = load_multitarget_datasets(reversed(paths))
        self.assertEqual(dataset.row_count, 9)
        self.assertEqual(dataset.labels.shape, (9, 2))
        self.assertEqual(dataset.labels[:3].tolist(), [[0, 1], [1, 0], [0, 0]])
        split = temporal_split(dataset)
        self.assertEqual(split.train.tolist(), [0, 1, 2])
        self.assertEqual(split.validation.tolist(), [3, 4, 5])
        self.assertEqual(split.test.tolist(), [6, 7, 8])
        self.assertEqual(dataset.input_files[0]['provenance']['featureSchema'], 'pair-v1')
        with self.assertRaisesRegex(DatasetValidationError, 'unexpected CSV schema'):
            load_datasets(paths)

    def test_missing_sidecar_and_tampered_csv_are_rejected(self):
        path = self.snapshot()
        self.sidecar(path).unlink()
        with self.assertRaisesRegex(DatasetValidationError, 'provenance'):
            load_multitarget_datasets([path])
        path = self.snapshot()
        with path.open('a', encoding='utf-8') as handle:
            handle.write('\n')
        with self.assertRaisesRegex(DatasetValidationError, 'checksum'):
            load_multitarget_datasets([path])

    def test_contract_cohort_maturity_and_feature_provenance_are_checked(self):
        cases = [dict(schemaVersion=1), dict(targets=['invite_accepted', 'invite_sent']),
                 dict(rowCount=4), dict(cohortSha256='0' * 64), dict(featureSchema='pair-v2'),
                 dict(exportedAt='2026-01-09T00:00:00Z'), dict(fitCutoff='2026-01-04T00:00:00Z'),
                 dict(labelSourceRevision='unknown'), dict(labelSourceRevision='9'), dict(releaseManifestSha256='invalid'),
                 dict(featureAsOf='2026-01-04T00:00:00Z'), dict(datasetId='')]
        for changes in cases:
            with self.subTest(changes=changes):
                path = self.snapshot()
                self.change_metadata(path, **changes)
                with self.assertRaises(DatasetValidationError):
                    load_multitarget_datasets([path])

    def test_bad_rows_duplicate_pairs_and_schema_are_rejected(self):
        changes = [lambda rows: rows[1].__setitem__(-1, '2'),
                   lambda rows: rows[1].__setitem__(0, '1'),
                   lambda rows: rows[1].__setitem__(2, '1'),
                   lambda rows: rows.append(rows[1]),
                   lambda rows: rows[1].append('extra'),
                   lambda rows: rows[1].pop(),
                   lambda rows: rows[1].__setitem__(7, 'nan'),
                   lambda rows: rows[1].__setitem__(1, str(2**63)),
                   lambda rows: rows[1].__setitem__(3, str(2**31)),
                   lambda rows: rows[1].__setitem__(9, '2025-12-01T00:00:00Z'),
                   lambda rows: rows[0].reverse()]
        for change in changes:
            path = self.snapshot()
            self.change_rows(path, change)
            with self.assertRaises(DatasetValidationError):
                load_multitarget_datasets([path])

    def test_mixed_transforms_and_reused_dataset_ids_are_rejected(self):
        first, second = self.snapshot(), self.snapshot(10, 17)
        self.change_metadata(second, transformSha256='f' * 64)
        with self.assertRaisesRegex(DatasetValidationError, 'incompatible'):
            load_multitarget_datasets([first, second])
        second = self.snapshot(10, 17)
        self.change_metadata(second, datasetId='3')
        with self.assertRaisesRegex(DatasetValidationError, 'duplicate dataset identity'):
            load_multitarget_datasets([first, second])

    def test_label_horizons_cannot_cross_temporal_partitions(self):
        for first_end, second_end in ((11, 17), (10, 18)):
            dataset = load_multitarget_datasets([self.snapshot(3, first_end),
                                               self.snapshot(10, second_end), self.snapshot(17, 24)])
            with self.assertRaisesRegex(DatasetValidationError, 'after the next partition'):
                temporal_split(dataset)


if __name__ == '__main__':
    unittest.main()
