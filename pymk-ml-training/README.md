# pymk-ml-training

Offline Python project for training the L1 (logistic regression / XGBoost) and
L2 (small feed-forward NN) models, exported to PMML/ONNX for serving from
`pymk-light-ranker` and `pymk-heavy-ranker`.

Not a Maven module — kept separate so training stays in the best ecosystem
for it (scikit-learn / XGBoost / PyTorch) while serving stays in Java.

**Status:** placeholder. First real content lands Week 3, Day 19
("Train logistic regression offline") — see `docs/PYMK_ROADMAP.md`.

## Planned layout
```
pymk-ml-training/
├── requirements.txt
├── train_light_ranker.py     # Day 19
├── train_heavy_ranker.py     # Day 25
├── export_onnx.py
└── notebooks/
```
