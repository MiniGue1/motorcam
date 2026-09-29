"""Testy fáze 6 (bez PyTorch)."""

import numpy as np
import pandas as pd

from src.experiments.errors import accuracy_by_condition, attach_conditions
from src.experiments.speed_plot import warning_intervals


def test_accuracy_by_condition():
    pred = pd.DataFrame({
        "soubor": ["a/j1_0.jpg", "a/j1_500.jpg", "a/j1_1000.jpg", "a/j1_1500.jpg"],
        "skutecnost": ["asfalt", "sterk", "mokro", "asfalt"],
        "predikce": ["asfalt", "asfalt", "mokro", "asfalt"],
        "jistota": [0.9, 0.6, 0.8, 0.7],
    })
    meta = pd.DataFrame({"soubor": ["j1_0.jpg", "j1_500.jpg", "j1_1000.jpg", "j1_1500.jpg"],
                         "podminky": [None, "stin|noc", "dest", None]})
    t = accuracy_by_condition(attach_conditions(pred, meta)).set_index("podminka")
    assert t.loc["stín", "presnost"] == 0 and t.loc["noc", "pocet"] == 1
    assert t.loc["bez zvláštních podmínek", "presnost"] == 1 and t.loc["bez zvláštních podmínek", "pocet"] == 2
    assert t.loc["mokro (povrch)", "pocet"] == 1 and t.loc["vše", "presnost"] == 0.75


def test_warning_intervals():
    t = np.arange(10.0)
    lv = np.array([0, 1, 1, 2, 2, 0, 0, 1, 1, 1])
    assert warning_intervals(t, lv) == [(1.0, 3.0, 1), (3.0, 5.0, 2), (7.0, 9.0, 1)]
