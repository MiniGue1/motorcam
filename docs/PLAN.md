# Plán projektu MotorCam

Prototyp vyhodnocovaný na **nahraných jízdách** – není to bezpečnostní systém pro reálný provoz.

## Architektura

```
video ──► snímek ──┬─► model povrchu (MobileNetV3, výřez spodní části) ─► P(povrch)
                   └─► detektor děr (YOLO nano) ──────────────────────► boxy
CSV senzorů ──► GPS rychlost, poloha ──┬─► map matching (OSM) ─► zatáčky před motorkou
               akcelerometr ──────────┘   └─► v_max = √(g·R·tanθ), brzdná dráha d
                                    │
             fusion (klouzavé okno, hystereze) ─► kontrolka ZELENÁ / ORANŽOVÁ / ČERVENÁ + pípání
                                    │
                         demo ─► výstupní video s overlayem, minimapou a zvukem
```

## Fáze

| # | Fáze | Výstupy | Kde |
|---|---|---|---|
| ✅ **1** | **Data** | stažení RDD2022/RSCD, převod do YOLO / ImageFolder, snímky z videí, postup anotace, grafy složení dat | `src/dataset/`, `docs/`, `notebooks/01_data.ipynb` |
| ✅ 2 | Model povrchu | MobileNetV3-Small / EfficientNet-B0, augmentace (jas, stíny, motion blur, déšť), accuracy, F1 po třídách, confusion matrix | `src/models/surface.py`, `train_surface.py`, `notebooks/02_surface.ipynb` |
| ✅ 3 | Model děr | YOLO11n (ultralytics) na RDD2022 + vlastní data; mAP50, mAP50-95, P, R | `src/models/detector.py`, `notebooks/03_detector.ipynb` |
| ✅ 4 | Rozhodovací logika | fúze modelů + GPS rychlosti, vyhlazení, hystereze; volitelně povrch z vibrací akcelerometru; **zatáčky z OSM** (map matching, poloměr R, v_max, brzdná dráha) | `src/fusion/` |
| ✅ 5 | Demo | video s overlayem (boxy „díra 87 %“, povrch „štěrk 92 %“, panel rychlostí, kontrolka, minimapa, FPS), zvuková stopa s pípáním; export TFLite + test FPS | `src/demo/` |
| ✅ 6 | Experimenty | veřejná data vs. doladěno na českých záběrech; analýza chyb (stíny, mokro, noc, rozmazání); graf rychlost / doporučená rychlost / varování | `src/experiments/`, `notebooks/06_experiments.ipynb` |

Navíc: **Android aplikace** (`android/`, `releases/MotorCam-0.1.apk`) – stejná logika běží
živě v telefonu, modely se do ní nahrávají jako `.tflite`. Kód je hotový pro všechny fáze;
zbývá natrénovat modely v Colabu a nasbírat/anotovat vlastní jízdy (✅ = kód hotový a otestovaný).

## Klíčová rozhodnutí

* **Povrch se klasifikuje z výřezu spodní části snímku** (vozovka těsně před motorkou) –
  odpovídá to snímkům RSCD, které jsou jen výřezy povrchu.
* **Detektor má 2 třídy: díra, trhlina.** Díra přímo před motorkou → červený rámeček, díra jinde → oranžový, trhlina → žlutý.
* **Dělení dat po celých videích/jízdách**, aby testovací výsledky nebyly nadhodnocené.
* **Model YOLO nano + MobileNetV3** – obojí zvládne mobil (TFLite) a trénink na free GPU.
* **Kontrolka** (návrh pro fázi 4):
  * ČERVENÁ: díra v „koridoru“ před motorkou (spodní středová část obrazu) s jistotou > práh
    po dobu ≥ 2 snímků; štěrk / bláto nad `high_speed_kmh`; zatáčka a rychlost výrazně nad v_max.
  * ORANŽOVÁ: změna povrchu, štěrk / mokro / rozbitý asfalt, mírné překročení v_max před zatáčkou.
  * ZELENÁ: jinak. Přechod na „klidnější“ barvu až po uplynutí okna (hystereze) → neblikat.

## Vzorce pro zatáčky (fáze 4)

* Poloměr z kružnice přes 3 body A, B, C (po převodu do metrů, vyhlazená geometrie):
  `R = |AB|·|BC|·|CA| / (4·S)`, kde S je obsah trojúhelníku ABC.
* Doporučená rychlost: `v_max = √(g · R · tan θ)`, θ = 25° (15° při štěrku/mokru).
* Potřebná vzdálenost: `d = v·t_r + (v² − v_max²) / (2·a)`, t_r = 1 s, a = 4 m/s².
* Varování, když vzdálenost k zatáčce ≤ d + rezerva. Intenzita pípání podle v − v_max.
